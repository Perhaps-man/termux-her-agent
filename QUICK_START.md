# 快速开始指南 - 产品级记忆系统

## 5分钟快速集成

### 1. 在 IDE.kt 或 TermuxActivity 中初始化

```kotlin
class IDE : AppCompatActivity() {

    private lateinit var enhancedHelper: EnhancedAgentHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化增强助手
        enhancedHelper = EnhancedAgentHelper(this)

        // 启动后台维护任务(可选)
        lifecycleScope.launch {
            while (isActive) {
                delay(3600_000) // 每小时
                enhancedHelper.performPeriodicMaintenance(getCurrentSessionId(this@IDE))
            }
        }
    }
}
```

### 2. 替换原有的 buildStepPrompt 调用

**原来的代码:**
```kotlin
val prompt = buildStepPrompt(
    userInput = userInput,
    dialogHistory = getDialogHistory(),
    executionMemory = getExecutionMemory()
)
```

**新的代码:**
```kotlin
val prompt = enhancedHelper.buildEnhancedPrompt(
    sessionId = getCurrentSessionId(this),
    userInput = userInput,
    chatHistory = chatAdapter.getItems()
)
```

### 3. 替换原有的 executeParsedStep 调用

**原来的代码:**
```kotlin
val result = executeParsedStep(context, step)
```

**新的代码:**
```kotlin
val result = enhancedHelper.executeAndRemember(
    sessionId = getCurrentSessionId(this),
    step = step
)
```

### 4. 处理复杂任务(可选)

```kotlin
// 检测是否为复杂任务
val isComplex = userInput.contains("构建") ||
                userInput.contains("创建项目") ||
                userInput.length > 100

if (isComplex) {
    // 使用任务管理器
    lifecycleScope.launch {
        val task = enhancedHelper.handleComplexTask(
            sessionId = getCurrentSessionId(this@IDE),
            userInput = userInput,
            onProgress = { progress ->
                // 更新UI显示进度
                runOnUiThread {
                    chatAdapter.updateLastAi(progress = progress)
                }
            }
        )

        // 任务完成
        runOnUiThread {
            chatAdapter.updateLastAi(
                content = "任务完成: ${task.title}",
                running = false
            )
        }
    }
} else {
    // 简单任务,使用原有流程
    // ...
}
```

### 5. 显示会话统计(可选)

```kotlin
// 在抽屉菜单或设置页面显示
val stats = enhancedHelper.getSessionStats(getCurrentSessionId(this))
statsTextView.text = stats
```

## 完整示例

```kotlin
// 在 IDE.kt 的 sendMessage 函数中
private fun sendMessage(userInput: String) {
    if (userInput.isBlank()) return

    // 1. 添加用户消息到UI
    chatAdapter.addUserMessage(userInput)

    // 2. 添加AI气泡
    val aiIndex = chatAdapter.addAiTaskBubble("思考中...")

    // 3. 在协程中处理
    lifecycleScope.launch {
        try {
            // 检测任务复杂度
            val isComplex = analyzeComplexity(userInput)

            if (isComplex) {
                // 复杂任务:使用任务管理器
                chatAdapter.updateAiAt(aiIndex, content = "正在分解任务...")

                val task = enhancedHelper.handleComplexTask(
                    sessionId = getCurrentSessionId(this@IDE),
                    userInput = userInput,
                    onProgress = { progress ->
                        runOnUiThread {
                            chatAdapter.updateAiAt(aiIndex, progress = progress)
                        }
                    }
                )

                runOnUiThread {
                    chatAdapter.updateAiAt(
                        aiIndex,
                        content = "任务完成: ${task.title}",
                        result = "共执行 ${task.steps.size} 个步骤",
                        running = false
                    )
                }
            } else {
                // 简单任务:直接执行
                chatAdapter.updateAiAt(aiIndex, content = "正在执行...")

                // 构建增强提示词
                val prompt = enhancedHelper.buildEnhancedPrompt(
                    sessionId = getCurrentSessionId(this@IDE),
                    userInput = userInput,
                    chatHistory = chatAdapter.getItems()
                )

                // 调用AI
                val aiResponse = callAI(this@IDE, prompt)

                // 解析步骤
                val step = parseStepReply(aiResponse)

                if (step != null) {
                    chatAdapter.updateAiAt(aiIndex, content = step.description)

                    // 执行并记录
                    val result = enhancedHelper.executeAndRemember(
                        sessionId = getCurrentSessionId(this@IDE),
                        step = step
                    )

                    runOnUiThread {
                        chatAdapter.updateAiAt(
                            aiIndex,
                            content = step.description,
                            result = result.result,
                            running = false
                        )
                    }
                } else {
                    runOnUiThread {
                        chatAdapter.updateAiAt(
                            aiIndex,
                            content = aiResponse,
                            running = false
                        )
                    }
                }
            }

            // 保存会话
            saveSessionChat(
                this@IDE,
                getCurrentSessionId(this@IDE),
                chatAdapter.getItems()
            )

        } catch (e: Exception) {
            runOnUiThread {
                chatAdapter.updateAiAt(
                    aiIndex,
                    content = "执行出错",
                    result = e.message ?: "未知错误",
                    running = false
                )
            }
        }
    }
}

// 辅助函数:分析任务复杂度
private fun analyzeComplexity(userInput: String): Boolean {
    val complexKeywords = listOf(
        "构建", "创建项目", "开发", "实现",
        "多步骤", "完整的", "从头开始"
    )

    return complexKeywords.any { userInput.contains(it) } ||
           userInput.length > 100 ||
           userInput.count { it == ',' || it == '，' } >= 2
}
```

## 恢复中断的任务

```kotlin
// 在应用启动时检查未完成的任务
override fun onResume() {
    super.onResume()

    lifecycleScope.launch {
        val sessionId = getCurrentSessionId(this@IDE)
        val activeTasks = enhancedHelper.taskManager.getActiveTasks(sessionId)

        if (activeTasks.isNotEmpty()) {
            // 显示对话框询问是否恢复
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@IDE)
                    .setTitle("发现未完成的任务")
                    .setMessage("是否继续执行任务: ${activeTasks.first().title}?")
                    .setPositiveButton("继续") { _, _ ->
                        lifecycleScope.launch {
                            enhancedHelper.resumeInterruptedTask(
                                taskId = activeTasks.first().id,
                                onProgress = { progress ->
                                    // 更新UI
                                }
                            )
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
```

## 测试新系统

```kotlin
// 简单测试
fun testMemorySystem() {
    lifecycleScope.launch {
        val sessionId = getCurrentSessionId(this@IDE)

        // 1. 测试记忆添加
        enhancedHelper.memoryManager.addToWorkingMemory(
            sessionId = sessionId,
            type = MemoryType.KNOWLEDGE,
            content = "Android开发使用Kotlin语言",
            importance = MemoryImportance.HIGH,
            tags = listOf("android", "kotlin")
        )

        // 2. 测试记忆检索
        val results = enhancedHelper.memoryManager.retrieveMemories(
            query = "如何开发Android应用",
            sessionId = sessionId,
            maxResults = 5
        )

        Log.d("MemoryTest", "找到 ${results.size} 条相关记忆")
        results.forEach { result ->
            Log.d("MemoryTest", "- ${result.memory.content} (分数: ${result.score})")
        }

        // 3. 测试任务管理
        val task = enhancedHelper.taskManager.createTask(
            sessionId = sessionId,
            title = "测试任务",
            description = "这是一个测试任务",
            steps = listOf(
                TaskStep(description = "步骤1", actionLine = "test1"),
                TaskStep(description = "步骤2", actionLine = "test2")
            )
        )

        Log.d("TaskTest", "创建任务: ${task.id}")
    }
}
```

## 常见问题

### Q: 新系统会影响性能吗?
A: 不会。记忆检索在后台线程执行,使用了缓存和索引优化。

### Q: 旧的对话历史会丢失吗?
A: 不会。新系统兼容旧的 `ExecutionMemoryRecord`,会自动迁移。

### Q: 如何禁用某些功能?
A: 可以选择性使用。例如,只使用记忆系统而不使用任务管理器。

### Q: 记忆会占用多少存储空间?
A: 每个会话约1-5MB,会自动清理30天以上的旧记忆。

## 下一步

1. 阅读 `MEMORY_SYSTEM_INTEGRATION.md` 了解详细架构
2. 查看 `EnhancedAgentHelper.kt` 的完整API
3. 根据需求定制记忆评分算法
4. 添加UI展示记忆和任务状态

## 支持

如有问题,请查看:
- `MemoryManager.kt` - 记忆管理核心
- `TaskStateManager.kt` - 任务管理核心
- `MemoryCompressor.kt` - 记忆压缩服务
- `MEMORY_SYSTEM_INTEGRATION.md` - 完整文档
