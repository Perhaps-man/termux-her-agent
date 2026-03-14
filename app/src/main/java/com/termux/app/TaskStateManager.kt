package com.termux.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG_TASK = "TaskStateManager"
private const val TASK_STATE_DIR = "task_states"

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,     // 待执行
    RUNNING,     // 执行中
    PAUSED,      // 已暂停
    COMPLETED,   // 已完成
    FAILED,      // 失败
    CANCELLED    // 已取消
}

/**
 * 任务步骤
 */
data class TaskStep(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val actionLine: String,
    val directContent: String? = null,  // runJava/message 等动作的内容体，resume 时重建 ParsedStep 用
    val status: TaskStatus = TaskStatus.PENDING,
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val retryCount: Int = 0,
    val dependencies: List<String> = emptyList()  // 依赖的步骤ID
)

/**
 * 任务状态
 */
data class TaskState(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val title: String,
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val steps: List<TaskStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val context: Map<String, String> = emptyMap(),  // 任务上下文
    val checkpoints: List<TaskCheckpoint> = emptyList(),  // 检查点
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 任务检查点(用于恢复)
 */
data class TaskCheckpoint(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val stepIndex: Int,
    val context: Map<String, String>,
    val description: String
)

/**
 * 任务状态管理器
 *
 * 功能:
 * 1. 任务分解和追踪
 * 2. 中断恢复机制
 * 3. 依赖关系管理
 * 4. 进度持久化
 * 5. 检查点机制
 */
class TaskStateManager(private val context: Context) {

    private val gson = Gson()
    private val taskDir = File(context.filesDir, TASK_STATE_DIR).apply { mkdirs() }

    // 内存缓存
    private val activeTasks = mutableMapOf<String, TaskState>()

    // 已完整扫描过磁盘的 sessionId 集合，避免对同一会话重复全盘扫描
    private val loadedSessions = mutableSetOf<String>()

    // 专用 IO scope，让磁盘写入不阻塞 Agent 主循环
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 创建新任务
     */
    fun createTask(
        sessionId: String,
        title: String,
        description: String,
        steps: List<TaskStep> = emptyList(),
        context: Map<String, String> = emptyMap()
    ): TaskState {
        val task = TaskState(
            sessionId = sessionId,
            title = title,
            description = description,
            steps = steps,
            context = context
        )

        synchronized(activeTasks) {
            activeTasks[task.id] = task
        }

        saveTask(task)
        return task
    }

    /**
     * 添加任务步骤
     */
    fun addStep(
        taskId: String,
        description: String,
        actionLine: String,
        directContent: String? = null,
        dependencies: List<String> = emptyList()
    ): TaskState? {
        val task = getTask(taskId) ?: return null

        val step = TaskStep(
            description = description,
            actionLine = actionLine,
            directContent = directContent,
            dependencies = dependencies
        )

        val updated = task.copy(
            steps = task.steps + step,
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 开始执行任务
     */
    fun startTask(taskId: String): TaskState? {
        val task = getTask(taskId) ?: return null

        if (task.status != TaskStatus.PENDING && task.status != TaskStatus.PAUSED) {
            Log.w(TAG_TASK, "Task $taskId is not in PENDING or PAUSED state")
            return null
        }

        val updated = task.copy(
            status = TaskStatus.RUNNING,
            startedAt = task.startedAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 执行下一步
     */
    fun executeNextStep(taskId: String): Pair<TaskState, TaskStep>? {
        val task = getTask(taskId) ?: return null

        if (task.status != TaskStatus.RUNNING) {
            Log.w(TAG_TASK, "Task $taskId is not running")
            return null
        }

        // 找到下一个可执行的步骤
        val nextStep = findNextExecutableStep(task) ?: run {
            // 没有更多步骤,任务完成
            completeTask(taskId)
            return null
        }

        // 更新步骤状态
        val updatedSteps = task.steps.map { step ->
            if (step.id == nextStep.id) {
                step.copy(
                    status = TaskStatus.RUNNING,
                    startedAt = System.currentTimeMillis()
                )
            } else step
        }

        val updated = task.copy(
            steps = updatedSteps,
            currentStepIndex = task.steps.indexOfFirst { it.id == nextStep.id },
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated to nextStep
    }

    /**
     * 完成步骤
     */
    fun completeStep(
        taskId: String,
        stepId: String,
        result: String,
        createCheckpoint: Boolean = false
    ): TaskState? {
        val task = getTask(taskId) ?: return null

        val updatedSteps = task.steps.map { step ->
            if (step.id == stepId) {
                step.copy(
                    status = TaskStatus.COMPLETED,
                    result = result,
                    completedAt = System.currentTimeMillis()
                )
            } else step
        }

        var updated = task.copy(
            steps = updatedSteps,
            updatedAt = System.currentTimeMillis()
        )

        // 创建检查点，最多保留 3 个（避免冗余堆积，恢复时只用最近一个）
        if (createCheckpoint) {
            val checkpoint = TaskCheckpoint(
                stepIndex = task.currentStepIndex,
                context = task.context,
                description = "完成步骤: ${task.steps.find { it.id == stepId }?.description}"
            )
            val checkpoints = (updated.checkpoints + checkpoint).takeLast(3)
            updated = updated.copy(checkpoints = checkpoints)
        }

        updateTask(updated)
        return updated
    }

    /**
     * 步骤失败
     */
    fun failStep(
        taskId: String,
        stepId: String,
        error: String,
        shouldRetry: Boolean = true
    ): TaskState? {
        val task = getTask(taskId) ?: return null

        val updatedSteps = task.steps.map { step ->
            if (step.id == stepId) {
                val newRetryCount = step.retryCount + 1
                val newStatus = if (shouldRetry && newRetryCount < 3) {
                    TaskStatus.PENDING  // 重试
                } else {
                    TaskStatus.FAILED
                }

                step.copy(
                    status = newStatus,
                    error = error,
                    retryCount = newRetryCount,
                    completedAt = System.currentTimeMillis()
                )
            } else step
        }

        val updated = task.copy(
            steps = updatedSteps,
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 暂停任务
     */
    fun pauseTask(taskId: String): TaskState? {
        val task = getTask(taskId) ?: return null

        val updated = task.copy(
            status = TaskStatus.PAUSED,
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 恢复任务
     */
    fun resumeTask(taskId: String, fromCheckpoint: String? = null): TaskState? {
        val task = getTask(taskId) ?: return null

        if (task.status != TaskStatus.PAUSED) {
            Log.w(TAG_TASK, "Task $taskId is not paused")
            return null
        }

        var updated = task

        // 从检查点恢复
        if (fromCheckpoint != null) {
            val checkpoint = task.checkpoints.find { it.id == fromCheckpoint }
            if (checkpoint != null) {
                updated = updated.copy(
                    currentStepIndex = checkpoint.stepIndex,
                    context = checkpoint.context
                )
            }
        }

        // 重置上次中断时遗留的 RUNNING 步骤为 PENDING，
        // 否则 findNextExecutableStep 会永久跳过这些步骤导致任务执行缺失
        val resetSteps = updated.steps.map { step ->
            if (step.status == TaskStatus.RUNNING)
                step.copy(status = TaskStatus.PENDING, startedAt = null)
            else step
        }
        updated = updated.copy(steps = resetSteps)

        updated = updated.copy(
            status = TaskStatus.RUNNING,
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 完成任务
     */
    fun completeTask(taskId: String): TaskState? {
        val task = getTask(taskId) ?: return null

        val updated = task.copy(
            status = TaskStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 取消任务
     */
    fun cancelTask(taskId: String): TaskState? {
        val task = getTask(taskId) ?: return null

        val updated = task.copy(
            status = TaskStatus.CANCELLED,
            updatedAt = System.currentTimeMillis()
        )

        updateTask(updated)
        return updated
    }

    /**
     * 获取任务
     */
    fun getTask(taskId: String): TaskState? {
        synchronized(activeTasks) {
            activeTasks[taskId]?.let { return it }
        }

        // 从磁盘加载
        return loadTask(taskId)?.also { task ->
            synchronized(activeTasks) {
                activeTasks[task.id] = task
            }
        }
    }

    /**
     * 获取会话的所有任务
     *
     * 对同一 sessionId 只扫一次磁盘，后续直接命中内存缓存。
     */
    fun getSessionTasks(sessionId: String): List<TaskState> {
        val tasks = mutableListOf<TaskState>()

        // 先读内存缓存
        synchronized(activeTasks) {
            tasks.addAll(activeTasks.values.filter { it.sessionId == sessionId })
        }

        // 若该 session 尚未从磁盘加载过，则扫一次
        val alreadyLoaded = synchronized(loadedSessions) { sessionId in loadedSessions }
        if (!alreadyLoaded) {
            taskDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".json")) {
                    try {
                        val task = gson.fromJson(file.readText(), TaskState::class.java)
                        if (task.sessionId == sessionId) {
                            synchronized(activeTasks) {
                                if (tasks.none { it.id == task.id }) {
                                    tasks.add(task)
                                    activeTasks[task.id] = task
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG_TASK, "Failed to load task from ${file.name}", e)
                    }
                }
            }
            synchronized(loadedSessions) { loadedSessions.add(sessionId) }
        }

        return tasks.sortedByDescending { it.updatedAt }
    }

    /**
     * 获取活跃任务
     *
     * sessionId 为 null 时只查内存缓存，避免全盘扫描。
     */
    fun getActiveTasks(sessionId: String? = null): List<TaskState> {
        if (sessionId == null) {
            return synchronized(activeTasks) {
                activeTasks.values.filter {
                    it.status == TaskStatus.RUNNING || it.status == TaskStatus.PAUSED
                }.toList()
            }
        }
        return getSessionTasks(sessionId).filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.PAUSED
        }
    }

    /**
     * 找到下一个可执行的步骤
     */
    private fun findNextExecutableStep(task: TaskState): TaskStep? {
        return task.steps.firstOrNull { step ->
            step.status == TaskStatus.PENDING &&
            areDependenciesMet(step, task.steps)
        }
    }

    /**
     * 检查依赖是否满足
     */
    private fun areDependenciesMet(step: TaskStep, allSteps: List<TaskStep>): Boolean {
        if (step.dependencies.isEmpty()) return true

        return step.dependencies.all { depId ->
            allSteps.find { it.id == depId }?.status == TaskStatus.COMPLETED
        }
    }

    /**
     * 更新任务
     */
    private fun updateTask(task: TaskState) {
        synchronized(activeTasks) {
            activeTasks[task.id] = task
        }
        saveTask(task)
    }

    /**
     * 异步保存任务到磁盘，不阻塞调用方（Agent 主循环每步约触发 2 次写盘）
     */
    private fun saveTask(task: TaskState) {
        val snapshot = gson.toJson(task)
        ioScope.launch {
            try {
                File(taskDir, "${task.id}.json").writeTextAtomic(snapshot)
            } catch (e: Exception) {
                Log.e(TAG_TASK, "Failed to save task ${task.id}", e)
            }
        }
    }

    /**
     * 从磁盘加载任务
     */
    private fun loadTask(taskId: String): TaskState? {
        return try {
            val file = File(taskDir, "$taskId.json")
            if (!file.exists()) return null
            gson.fromJson(file.readText(), TaskState::class.java)
        } catch (e: Exception) {
            Log.e(TAG_TASK, "Failed to load task $taskId", e)
            null
        }
    }

    /**
     * 删除任务
     */
    fun deleteTask(taskId: String) {
        val sessionId = synchronized(activeTasks) { activeTasks.remove(taskId)?.sessionId }
        // 使该 session 的磁盘缓存失效，确保下次 getSessionTasks 重扫后不包含已删除任务
        if (sessionId != null) {
            synchronized(loadedSessions) { loadedSessions.remove(sessionId) }
        }
        try {
            File(taskDir, "$taskId.json").delete()
        } catch (e: Exception) {
            Log.e(TAG_TASK, "Failed to delete task $taskId", e)
        }
    }

    /**
     * 清理已完成的旧任务
     */
    fun cleanupOldTasks(olderThanDays: Int = 7) {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 3600 * 1000L)

        taskDir.listFiles()?.forEach { file ->
            try {
                val task = gson.fromJson(file.readText(), TaskState::class.java)
                if ((task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED) &&
                    task.updatedAt < cutoffTime) {
                    file.delete()
                    synchronized(activeTasks) {
                        activeTasks.remove(task.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG_TASK, "Failed to cleanup task ${file.name}", e)
            }
        }
    }
}
