package com.termux.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class EnhancedAgentHelper(private val context: Context) {

    private val memoryManager = MemoryManager(context)
    private val taskManager = TaskStateManager(context)
    private val compressor = MemoryCompressor(context, memoryManager)
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractor = MemoryExtractor(context, memoryManager, helperScope)

    suspend fun buildEnhancedPrompt(
        sessionId: String,
        userInput: String,
        dialogHistory: String?,
        stepHistory: String? = null,
        currentStepQuery: String? = null,
        failureHint: String? = null
    ): String = withContext(Dispatchers.IO) {
        val memoryQuery = currentStepQuery ?: userInput
        val sessionMemories = memoryManager.retrieveMemoriesSemantic(
            query = memoryQuery,
            sessionId = sessionId,
            types = listOf(MemoryType.EXECUTION, MemoryType.SUCCESS, MemoryType.KNOWLEDGE),
            maxResults = 4
        )
        val globalMemories = memoryManager.retrieveMemoriesSemantic(
            query = memoryQuery,
            sessionId = GLOBAL_SESSION,
            types = listOf(MemoryType.KNOWLEDGE, MemoryType.ERROR),
            maxResults = 3
        )
        val relevantMemories = (sessionMemories + globalMemories)
            .distinctBy { it.memory.content }
            .sortedByDescending { it.score }
            .take(5)

        val activeTasks = taskManager.getActiveTasks(sessionId)
        val taskContext = if (activeTasks.isNotEmpty()) buildString {
            append("【进行中的任务】\n")
            activeTasks.forEach { task ->
                val completed = task.steps.count { it.status == TaskStatus.COMPLETED }
                append("- ${task.title}: $completed/${task.steps.size} 步骤完成\n")
            }
        } else ""

        buildString {
            append("【当前用户任务】\n")
            append(userInput)
            append("\n\n")
            if (relevantMemories.isNotEmpty()) {
                append("【相关经验】\n")
                relevantMemories.forEach { result ->
                    val m = result.memory
                    append("- [${m.type.name}] ${m.content.take(150)}\n")
                    if (m.context != null) append("  (${m.context.take(80)})\n")
                }
                append("\n")
            }
            if (!dialogHistory.isNullOrBlank()) {
                append("【历史对话】\n")
                append(dialogHistory.trim())
                append("\n\n")
            }
            if (taskContext.isNotBlank()) {
                append(taskContext)
                append("\n")
            }
            if (failureHint != null) {
                append("⚠️ $failureHint\n\n")
            }
            if (!stepHistory.isNullOrBlank()) {
                append("【本轮已执行步骤（✓=成功 ✗=失败，勿重复✓步骤）】\n")
                append(stepHistory.trim())
                append("\n")
            }
        }
    }

    private val STEP_TIMEOUT_MS = 90_000L

    suspend fun executeAndRemember(
        sessionId: String,
        step: ParsedStep
    ): RunResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val result = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            executeParsedStep(context, step)
        } ?: RunResult(
            result = "步骤执行超时(${STEP_TIMEOUT_MS / 1000}s)，请检查命令是否卡住",
            terminalLog = "",
            isSuccess = false
        )

        val success = result.isSuccess

        val resultSnippet = result.result.take(500).ifBlank { result.terminalLog.take(300) }
        val memoryContent = if (resultSnippet.isNotBlank()) {
            "${step.description}\n结果: $resultSnippet"
        } else {
            step.description
        }

        memoryManager.addToWorkingMemory(
            sessionId = sessionId,
            type = if (success) MemoryType.EXECUTION else MemoryType.ERROR,
            content = memoryContent,
            context = "动作: ${step.actionLine}",
            importance = if (success) MemoryImportance.MEDIUM else MemoryImportance.HIGH,
            tags = listOf(
                if (success) "success" else "error",
                step.actionLine.substringBefore("(")
            ),
            metadata = mapOf(
                "action"      to step.actionLine,
                "duration_ms" to (System.currentTimeMillis() - startTime).toString(),
                "success"     to success.toString()
            )
        )

        extractor.extractAsync(sessionId, step, result)

        result
    }
    suspend fun handleComplexTask(
        sessionId: String,
        userInput: String,
        onProgress: (String) -> Unit = {}
    ): TaskState {
        val task = taskManager.createTask(
            sessionId = sessionId,
            title = extractTaskTitle(userInput),
            description = userInput
        )
        taskManager.startTask(task.id)
        onProgress("开始: ${task.title}")

        val stepHistory = StringBuilder()
        var stepIdx = 0
        var parseFailures = 0
        var hitMaxSteps = false
        var terminatedByAiError = false
        var terminatedByParseFailure = false
        var terminatedByStepFailure = false
        val MAX_STEPS = 15
        val MAX_PARSE_FAILURES = 3

        while (stepIdx < MAX_STEPS && parseFailures < MAX_PARSE_FAILURES) {
            val memQuery = if (stepIdx == 0) userInput
                           else stepHistory.lines().takeLast(4).joinToString(" ")
            val memories = buildMemoryContext(sessionId, memQuery)

            val userPrompt = buildReActUserPrompt(userInput, memories, stepHistory.toString())
            onProgress(if (stepIdx == 0) "思考第一步..." else "思考第 ${stepIdx + 1} 步...")

            val reply = callAIAgent(context, buildActionToolsPrompt(context), userPrompt)
            if (reply.startsWith("Error:")) {
                terminatedByAiError = true
                onProgress("AI 调用失败: ${reply.take(100)}")
                break
            }

            val step = parseStepReply(reply)
            if (step == null) {
                parseFailures++
                onProgress("输出格式解析失败($parseFailures/$MAX_PARSE_FAILURES)，重试...")
                continue
            }
            parseFailures = 0

            taskManager.addStep(task.id, step.description, step.actionLine, step.directContent)
            val (_, taskStep) = taskManager.executeNextStep(task.id) ?: break
            onProgress("${stepIdx + 1}. ${step.description}")

            if (step.isMessage) {
                executeAndRemember(sessionId, step)
                taskManager.completeStep(task.id, taskStep.id, "已汇报")
                break
            }

            val result = executeAndRemember(sessionId, step)
            val mark = if (result.isSuccess) "✓" else "✗"
            val snippetLen = if (result.isSuccess) 600 else 1500
            val snippet = result.result.take(snippetLen).ifBlank { result.terminalLog.take(snippetLen / 2) }
            stepHistory.append("${stepIdx + 1}.$mark ${step.description}\n$snippet\n\n")

            if (result.isSuccess) {
                taskManager.completeStep(task.id, taskStep.id, result.result,
                    createCheckpoint = (stepIdx + 1) % 3 == 0)
                onProgress("$mark ${snippet.take(80)}")
            } else {
                terminatedByStepFailure = true
                taskManager.failStep(task.id, taskStep.id, result.result, shouldRetry = false)
                onProgress("$mark ${snippet.take(80)}")
                break
            }
            stepIdx++
        }

        if (stepIdx >= MAX_STEPS) {
            hitMaxSteps = true
            onProgress("已达最大步骤数(${MAX_STEPS})，任务结束")
        }
        if (parseFailures >= MAX_PARSE_FAILURES) {
            terminatedByParseFailure = true
            onProgress("连续输出格式解析失败(${MAX_PARSE_FAILURES}次)，任务结束")
        }

        return if (hitMaxSteps || terminatedByAiError || terminatedByParseFailure || terminatedByStepFailure) {
            taskManager.cancelTask(task.id) ?: task
        } else {
            taskManager.completeTask(task.id) ?: task
        }
    }

    suspend fun performPeriodicMaintenance(sessionId: String) {
        try {
            compressor.performPeriodicCompression(sessionId)
            taskManager.cleanupOldTasks(olderThanDays = 7)
            memoryManager.cleanupOldMemories(olderThanDays = 30)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedAgentHelper", "Maintenance failed", e)
        }
    }

    fun getSessionStats(sessionId: String): String {
        val memorySummary = memoryManager.getSessionMemorySummary(sessionId, maxChars = 500)
        val tasks = taskManager.getSessionTasks(sessionId)
        val activeTasks = tasks.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.PAUSED }
        val completedTasks = tasks.count { it.status == TaskStatus.COMPLETED }
        return buildString {
            append("【会话统计】\n")
            append("活跃任务: ${activeTasks.size}\n")
            append("已完成任务: $completedTasks\n\n")
            if (memorySummary.isNotBlank()) append(memorySummary)
        }
    }

    // ────────────────────────────────────────────────────────────
    // 工具方法
    // ────────────────────────────────────────────────────────────

    /**
     * 检索与当前步骤最相关的记忆，供 ReAct prompt 注入。
     *
     * 会话记忆（最近执行经验）+ 全局记忆（跨会话的项目结构/用户偏好/已知错误）
     * 合并去重后取 top-4，保持 prompt 简洁。
     */
    private suspend fun buildMemoryContext(sessionId: String, query: String): String =
        withContext(Dispatchers.IO) {
            val sessionMems = memoryManager.retrieveMemoriesSemantic(
                query = query, sessionId = sessionId,
                types = listOf(MemoryType.EXECUTION, MemoryType.SUCCESS, MemoryType.KNOWLEDGE),
                maxResults = 3
            )
            val globalMems = memoryManager.retrieveMemoriesSemantic(
                query = query, sessionId = GLOBAL_SESSION,
                types = listOf(MemoryType.KNOWLEDGE, MemoryType.ERROR),
                maxResults = 2
            )
            (sessionMems + globalMems)
                .distinctBy { it.memory.content }
                .sortedByDescending { it.score }
                .take(4)
                .joinToString("\n") { "- [${it.memory.type.name}] ${it.memory.content.take(120)}" }
        }

    /**
     * 构建 ReAct 循环的 user 侧 prompt。
     *
     * ACTION_TOOLS_PROMPT 已通过 [callAIAgent] 进入 system 角色，
     * 此处只包含任务目标、记忆背景和步骤历史，保持用户消息简洁高效。
     */
    private fun buildReActUserPrompt(
        userInput: String,
        memories: String,
        stepHistory: String
    ): String = buildString {
        append("【任务】\n$userInput\n\n")
        if (memories.isNotBlank()) {
            append("【相关经验】\n$memories\n\n")
        }
        if (stepHistory.isNotBlank()) {
            append("【已执行步骤】\n${stepHistory.trim()}\n\n")
            append("根据以上执行结果，输出下一步操作。若任务目标已全部完成，调用 message() 汇报结果。")
        } else {
            append("请输出第一个步骤。")
        }
    }

    private fun extractTaskTitle(userInput: String): String =
        userInput.take(50).trim().let { if (it.length < userInput.length) "$it..." else it }
}
