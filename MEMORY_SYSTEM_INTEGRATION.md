# 产品级记忆系统集成指南

## 概述

新的记忆系统参考了 OpenClaw、Claude Code 等先进 Agent 的设计,实现了分层记忆架构、智能检索、任务状态管理和记忆压缩等功能。

## 核心组件

### 1. MemoryManager.kt - 分层记忆管理器

**功能:**
- **工作记忆(Working Memory)**: 容量20条,当前任务的临时上下文
- **短期记忆(Short-term Memory)**: 容量100条,最近的对话和执行
- **长期记忆(Long-term Memory)**: 容量2000条,重要的经验和知识
- **语义记忆(Semantic Memory)**: 提取的通用知识和模式

**记忆类型:**
- `EXECUTION`: 执行记忆(命令执行结果)
- `CONVERSATION`: 对话记忆(用户交互)
- `KNOWLEDGE`: 知识记忆(提取的知识)
- `TASK`: 任务记忆(任务状态)
- `ERROR`: 错误记忆(失败经验)
- `SUCCESS`: 成功记忆(成功经验)

**重要性级别:**
- `CRITICAL`: 关键记忆(必须保留)
- `HIGH`: 高重要性
- `MEDIUM`: 中等重要性
- `LOW`: 低重要性

**核心API:**
```kotlin
// 添加记忆
val memory = memoryManager.addToWorkingMemory(
    sessionId = sessionId,
    type = MemoryType.EXECUTION,
    content = "执行了命令: ls -la",
    context = "查看文件列表",
    importance = MemoryImportance.MEDIUM,
    tags = listOf("file", "list"),
    metadata = mapOf("command" to "ls")
)

// 智能检索记忆
val results = memoryManager.retrieveMemories(
    query = "如何列出文件",
    sessionId = sessionId,
    types = listOf(MemoryType.EXECUTION, MemoryType.SUCCESS),
    maxResults = 10
)

// 获取会话摘要
val summary = memoryManager.getSessionMemorySummary(sessionId, maxChars = 2000)
```

**评分算法:**
综合考虑5个维度:
1. 内容相似度(token overlap) - 权重3.0
2. 时间新鲜度(指数衰减,24小时半衰期) - 权重1.5
3. 访问频率(对数归一化) - 权重1.0
4. 重要性级别 - 权重2.0
5. 成功率(如果有) - 权重1.5

### 2. TaskStateManager.kt - 任务状态管理器

**功能:**
- 任务分解和追踪
- 中断恢复机制
- 依赖关系管理
- 检查点机制
- 进度持久化

**任务状态:**
- `PENDING`: 待执行
- `RUNNING`: 执行中
- `PAUSED`: 已暂停
- `COMPLETED`: 已完成
- `FAILED`: 失败
- `CANCELLED`: 已取消

**核心API:**
```kotlin
// 创建任务
val task = taskManager.createTask(
    sessionId = sessionId,
    title = "构建Android应用",
    description = "编译并打包APK",
    steps = listOf(
        TaskStep(description = "清理构建目录", actionLine = "exec('default', 'rm -rf build')"),
        TaskStep(description = "编译代码", actionLine = "exec('default', './gradlew assembleDebug')")
    )
)

// 开始执行
taskManager.startTask(task.id)

// 执行下一步
val (updatedTask, step) = taskManager.executeNextStep(task.id) ?: return

// 完成步骤(创建检查点)
taskManager.completeStep(task.id, step.id, result = "编译成功", createCheckpoint = true)

// 暂停任务
taskManager.pauseTask(task.id)

// 从检查点恢复
taskManager.resumeTask(task.id, fromCheckpoint = checkpointId)
```

### 3. MemoryCompressor.kt - 记忆压缩服务

**功能:**
- 使用AI自动总结对话历史
- 提取执行模式(成功/失败)
- 生成知识摘要
- 定期清理冗余记忆

**核心API:**
```kotlin
// 压缩对话历史
val summary = compressor.compressDialogHistory(
    sessionId = sessionId,
    dialogHistory = chatItems,
    maxOutputLength = 1000
)

// 提取执行模式
val patterns = compressor.extractExecutionPatterns(
    sessionId = sessionId,
    executionMemories = execMemories
)

// 生成知识摘要
val knowledge = compressor.generateKnowledgeSummary(
    sessionId = sessionId,
    topic = "Android开发"
)

// 定期压缩(后台任务)
compressor.performPeriodicCompression(sessionId)
```

## 集成到 WebRenderFragment.kt

### 修改 buildStepPrompt 函数

**原来的实现:**
```kotlin
fun buildStepPrompt(
    userInput: String,
    dialogHistory: String?,
    executionMemory: String?,
    stepHistory: String? = null
): String = buildString {
    append(ACTION_TOOLS_PROMPT)

    if (!dialogHistory.isNullOrBlank()) {
        append("【历史对话】\n")
        append(dialogHistory.trim())
        append("\n")
    }
    // ...
}
```

**新的实现:**
```kotlin
// 在文件顶部添加
private lateinit var memoryManager: MemoryManager
private lateinit var taskManager: TaskStateManager
private lateinit var compressor: MemoryCompressor

// 初始化(在 IDE.kt 或 TermuxActivity 中)
fun initMemorySystem(context: Context) {
    memoryManager = MemoryManager(context)
    taskManager = TaskStateManager(context)
    compressor = MemoryCompressor(context, memoryManager)
}

// 改进的 buildStepPrompt
suspend fun buildStepPromptEnhanced(
    context: Context,
    sessionId: String,
    userInput: String,
    chatHistory: List<HerChatItem>
): String = withContext(Dispatchers.IO) {
    // 1. 检索相关记忆
    val relevantMemories = memoryManager.retrieveMemories(
        query = userInput,
        sessionId = sessionId,
        types = listOf(
            MemoryType.EXECUTION,
            MemoryType.SUCCESS,
            MemoryType.KNOWLEDGE
        ),
        maxResults = 6
    )

    // 2. 压缩对话历史(如果太长)
    val dialogSummary = if (chatHistory.size > 20) {
        compressor.compressDialogHistory(sessionId, chatHistory, maxOutputLength = 1000)
    } else {
        chatHistory.takeLast(10).joinToString("\n") { item ->
            if (item.isUser) "用户: ${item.content}"
            else "助手: ${item.content}"
        }
    }

    // 3. 获取活跃任务
    val activeTasks = taskManager.getActiveTasks(sessionId)
    val taskContext = if (activeTasks.isNotEmpty()) {
        buildString {
            append("【进行中的任务】\n")
            activeTasks.forEach { task ->
                append("- ${task.title}: ${task.steps.count { it.status == TaskStatus.COMPLETED }}/${task.steps.size} 步骤完成\n")
            }
        }
    } else ""

    // 4. 构建增强的提示词
    buildString {
        append(ACTION_TOOLS_PROMPT)
        append("\n")

        // 相关记忆
        if (relevantMemories.isNotEmpty()) {
            append("【相关经验】\n")
            relevantMemories.take(5).forEach { result ->
                val m = result.memory
                append("- [${m.type.name}] ${m.content.take(150)}\n")
                if (m.context != null) {
                    append("  (${m.context.take(80)})\n")
                }
            }
            append("\n")
        }

        // 对话历史
        if (dialogSummary.isNotBlank()) {
            append("【对话历史】\n")
            append(dialogSummary)
            append("\n")
        }

        // 任务上下文
        if (taskContext.isNotBlank()) {
            append(taskContext)
            append("\n")
        }

        // 当前任务
        append("【当前用户任务】\n")
        append(userInput)
    }
}
```

### 修改 executeParsedStep 函数

**添加记忆记录:**
```kotlin
suspend fun executeParsedStepEnhanced(
    context: Context,
    sessionId: String,
    step: ParsedStep
): RunResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()

    // 执行步骤
    val result = executeParsedStep(context, step)

    // 记录到记忆系统
    val success = !result.result.contains("error", ignoreCase = true) &&
                  !result.result.contains("failed", ignoreCase = true)

    memoryManager.addToWorkingMemory(
        sessionId = sessionId,
        type = MemoryType.EXECUTION,
        content = step.description,
        context = "动作: ${step.actionLine}",
        importance = if (success) MemoryImportance.MEDIUM else MemoryImportance.HIGH,
        tags = listOf(
            if (success) "success" else "error",
            step.actionLine.substringBefore("(")
        ),
        metadata = mapOf(
            "action" to step.actionLine,
            "duration_ms" to (System.currentTimeMillis() - startTime).toString(),
            "success" to success.toString()
        )
    )

    // 同时保存到原有的 ExecutionMemory
    appendExecutionMemory(
        context = context,
        sessionId = sessionId,
        stepDescription = step.description,
        actionLine = step.actionLine,
        result = result.result,
        terminalLog = result.terminalLog
    )

    result
}
```

### 添加长时间任务支持

**在 IDE.kt 中添加任务管理:**
```kotlin
// 处理复杂任务
suspend fun handleComplexTask(
    sessionId: String,
    userInput: String,
    chatHistory: List<HerChatItem>
) {
    // 1. 分析任务复杂度
    val isComplexTask = analyzeTaskComplexity(userInput)

    if (isComplexTask) {
        // 2. 创建任务
        val task = taskManager.createTask(
            sessionId = sessionId,
            title = extractTaskTitle(userInput),
            description = userInput
        )

        // 3. 使用AI分解任务
        val steps = decomposeTask(userInput)
        steps.forEach { stepDesc ->
            taskManager.addStep(
                taskId = task.id,
                description = stepDesc,
                actionLine = "待AI生成"
            )
        }

        // 4. 开始执行
        taskManager.startTask(task.id)

        // 5. 逐步执行
        while (true) {
            val (updatedTask, step) = taskManager.executeNextStep(task.id) ?: break

            // 使用AI生成具体动作
            val action = generateActionForStep(step.description, chatHistory)

            // 执行动作
            val result = executeActionLines(context, listOf(action))

            // 记录结果
            if (result.result.contains("error", ignoreCase = true)) {
                taskManager.failStep(task.id, step.id, result.result, shouldRetry = true)
            } else {
                taskManager.completeStep(task.id, step.id, result.result, createCheckpoint = true)
            }

            // 定期保存进度
            delay(100)
        }
    } else {
        // 简单任务,直接执行
        // ... 原有逻辑
    }
}
```

## 使用示例

### 示例1: 简单对话

```kotlin
// 用户发送消息
val userInput = "帮我列出当前目录的文件"

// 构建提示词(自动检索相关记忆)
val prompt = buildStepPromptEnhanced(context, sessionId, userInput, chatHistory)

// 调用AI
val aiResponse = callAI(context, prompt)

// 解析并执行
val step = parseStepReply(aiResponse)
if (step != null) {
    val result = executeParsedStepEnhanced(context, sessionId, step)
    // 自动记录到记忆系统
}
```

### 示例2: 长时间任务

```kotlin
// 用户请求: "帮我构建一个Android应用"
val userInput = "帮我构建一个简单的Android应用,包含一个按钮和文本框"

// 创建任务
val task = taskManager.createTask(
    sessionId = sessionId,
    title = "构建Android应用",
    description = userInput,
    steps = listOf(
        TaskStep(description = "创建项目结构", actionLine = "待生成"),
        TaskStep(description = "编写MainActivity", actionLine = "待生成"),
        TaskStep(description = "编译APK", actionLine = "待生成"),
        TaskStep(description = "安装测试", actionLine = "待生成")
    )
)

// 开始执行
taskManager.startTask(task.id)

// 执行循环
while (true) {
    val (updatedTask, step) = taskManager.executeNextStep(task.id) ?: break

    // 为每个步骤生成具体动作
    val actionPrompt = buildStepPromptEnhanced(context, sessionId, step.description, chatHistory)
    val aiResponse = callAI(context, actionPrompt)
    val parsedStep = parseStepReply(aiResponse) ?: continue

    // 执行
    val result = executeParsedStepEnhanced(context, sessionId, parsedStep)

    // 更新任务状态
    if (result.result.contains("error", ignoreCase = true)) {
        taskManager.failStep(task.id, step.id, result.result)
        // 可以选择重试或跳过
    } else {
        taskManager.completeStep(task.id, step.id, result.result, createCheckpoint = true)
    }

    // 用户可以随时暂停
    if (userRequestedPause) {
        taskManager.pauseTask(task.id)
        break
    }
}

// 恢复任务
if (userRequestedResume) {
    taskManager.resumeTask(task.id)
    // 继续执行循环
}
```

### 示例3: 定期压缩

```kotlin
// 在后台定期执行(例如每小时)
lifecycleScope.launch {
    while (isActive) {
        delay(3600_000) // 1小时

        // 压缩当前会话的记忆
        compressor.performPeriodicCompression(getCurrentSessionId(context))
    }
}
```

## 性能优化建议

1. **异步加载**: 记忆检索应该在后台线程执行
2. **缓存策略**: 工作记忆和短期记忆保持在内存中
3. **批量保存**: 每10条记忆批量写入磁盘
4. **索引优化**: 使用记忆索引加速检索
5. **定期清理**: 每周清理30天以上的低重要性记忆

## 迁移指南

### 从旧系统迁移

1. **保留兼容性**: 新系统兼容旧的 `ExecutionMemoryRecord`
2. **数据迁移**: 运行一次性迁移脚本,将旧记忆转换为新格式
3. **渐进式集成**: 先在新会话中使用新系统,旧会话继续使用旧系统

```kotlin
// 迁移脚本
fun migrateOldMemories(context: Context, sessionId: String) {
    val oldMemories = loadExecutionMemories(context, sessionId)

    oldMemories.forEach { old ->
        memoryManager.addToWorkingMemory(
            sessionId = sessionId,
            type = MemoryType.EXECUTION,
            content = old.stepDescription,
            context = "动作: ${old.actionLine}",
            importance = MemoryImportance.MEDIUM,
            tags = emptyList(),
            metadata = mapOf(
                "migrated_from" to "old_system",
                "original_id" to old.id
            )
        )
    }
}
```

## 测试建议

1. **单元测试**: 测试记忆检索算法的准确性
2. **集成测试**: 测试任务状态管理的完整流程
3. **性能测试**: 测试大量记忆时的检索速度
4. **压力测试**: 测试长时间运行的任务恢复能力

## 未来改进方向

1. **向量嵌入**: 集成轻量级embedding模型(如MiniLM)实现语义搜索
2. **跨会话学习**: 提取通用模式,在新会话中应用
3. **主动记忆**: AI主动提醒相关经验
4. **记忆可视化**: 在UI中展示记忆图谱
5. **协作记忆**: 多用户共享知识库

## 总结

新的记忆系统提供了:
- ✅ 分层记忆架构(工作/短期/长期/语义)
- ✅ 智能检索(多维度评分)
- ✅ 任务状态管理(中断恢复)
- ✅ 记忆压缩(AI总结)
- ✅ 跨会话学习(模式提取)
- ✅ 产品级性能(缓存+索引)

这使得 Agent 能够像 OpenClaw 一样处理复杂的长时间任务,并从历史经验中学习。
