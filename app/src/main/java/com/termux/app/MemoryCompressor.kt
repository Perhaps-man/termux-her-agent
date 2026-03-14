package com.termux.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG_COMPRESSOR = "MemoryCompressor"

/** 对话压缩的 system prompt：固定指令，不随对话内容变化 */
private val COMPRESSION_SYSTEM_PROMPT = """
你是一个记忆压缩助手。将对话历史总结为简洁的摘要，保留关键信息。

要求：
1. 提取用户的主要需求和意图
2. 记录重要的执行结果和发现
3. 保留关键的上下文信息
4. 删除冗余和重复内容
5. 输出纯文本摘要，无需其他格式
""".trimIndent()

/**
 * 记忆压缩服务
 *
 * 功能:
 * 1. 使用AI自动总结对话历史
 * 2. 提取关键信息
 * 3. 定期清理冗余记忆
 * 4. 生成语义记忆
 */
class MemoryCompressor(
    private val context: Context,
    private val memoryManager: MemoryManager
) {

    /**
     * 压缩对话历史
     *
     * 将长对话历史总结为简洁的摘要
     */
    suspend fun compressDialogHistory(
        sessionId: String,
        dialogHistory: List<HerChatItem>,
        maxOutputLength: Int = 1000
    ): String = withContext(Dispatchers.IO) {
        if (dialogHistory.isEmpty()) return@withContext ""

        // 构建压缩提示词
        val prompt = buildCompressionPrompt(dialogHistory, maxOutputLength)

        try {
            // 调用AI进行总结（system/user 分离，低温度保证摘要格式稳定）
            val summary = callAIAgent(context, COMPRESSION_SYSTEM_PROMPT, prompt)

            // 保存为语义记忆
            memoryManager.addToWorkingMemory(
                sessionId = sessionId,
                type = MemoryType.KNOWLEDGE,
                content = summary,
                context = "对话历史总结",
                importance = MemoryImportance.HIGH,
                tags = listOf("summary", "dialog"),
                metadata = mapOf(
                    "original_message_count" to dialogHistory.size.toString(),
                    "compressed_at" to System.currentTimeMillis().toString()
                )
            )

            summary
        } catch (e: Exception) {
            Log.e(TAG_COMPRESSOR, "Failed to compress dialog history", e)
            // 降级方案:简单截取
            fallbackCompression(dialogHistory, maxOutputLength)
        }
    }

    /**
     * 构建压缩的 user 侧 prompt（固定指令已移入 COMPRESSION_SYSTEM_PROMPT）
     */
    private fun buildCompressionPrompt(
        dialogHistory: List<HerChatItem>,
        maxOutputLength: Int
    ): String = buildString {
        append("请将以下对话历史总结为不超过 ${maxOutputLength} 字符的摘要：\n\n")
        dialogHistory.forEach { item ->
            if (item.isUser) {
                append("用户: ${item.content}\n")
            } else {
                append("助手: ${item.content}\n")
                val result = item.result
                if (!result.isNullOrBlank()) {
                    append("结果: ${result.take(200)}\n")
                }
            }
        }
    }

    /**
     * 降级压缩方案
     */
    private fun fallbackCompression(
        dialogHistory: List<HerChatItem>,
        maxOutputLength: Int
    ): String {
        val important = dialogHistory.filter { item ->
            val result = item.result
            item.isUser ||
            (!result.isNullOrBlank() && result.length > 50)
        }

        return buildString {
            append("【对话摘要】\n")
            important.take(10).forEach { item ->
                if (item.isUser) {
                    append("用户: ${item.content.take(100)}\n")
                } else {
                    val result = item.result
                    if (!result.isNullOrBlank()) {
                        append("结果: ${result.take(150)}\n")
                    }
                }
            }
        }.take(maxOutputLength)
    }

    /**
     * 提取执行经验
     *
     * 从执行记忆中提取成功模式和失败教训
     */
    suspend fun extractExecutionPatterns(
        sessionId: String,
        executionMemories: List<ExecutionMemoryRecord>
    ): List<MemoryEntry> = withContext(Dispatchers.IO) {
        if (executionMemories.isEmpty()) return@withContext emptyList()

        val patterns = mutableListOf<MemoryEntry>()

        // 分析成功模式
        val successfulExecutions = executionMemories.filter { memory ->
            !memory.result.contains("error", ignoreCase = true) &&
            !memory.result.contains("failed", ignoreCase = true) &&
            !memory.result.contains("失败", ignoreCase = true)
        }

        if (successfulExecutions.size >= 3) {
            // 找到重复的成功模式
            val actionGroups = successfulExecutions.groupBy { it.actionLine }
            actionGroups.forEach { (action, memories) ->
                if (memories.size >= 2) {
                    val pattern = MemoryEntry(
                        sessionId = sessionId,
                        type = MemoryType.SUCCESS,
                        importance = MemoryImportance.HIGH,
                        content = "成功模式: $action",
                        context = "该操作成功执行${memories.size}次",
                        tags = listOf("pattern", "success"),
                        successRate = 1.0,
                        metadata = mapOf(
                            "execution_count" to memories.size.toString(),
                            "action" to action
                        )
                    )
                    patterns.add(pattern)
                }
            }
        }

        // 分析失败模式
        val failedExecutions = executionMemories.filter { memory ->
            memory.result.contains("error", ignoreCase = true) ||
            memory.result.contains("failed", ignoreCase = true) ||
            memory.result.contains("失败", ignoreCase = true)
        }

        if (failedExecutions.isNotEmpty()) {
            val errorGroups = failedExecutions.groupBy { memory ->
                // 提取错误类型
                when {
                    memory.result.contains("permission", ignoreCase = true) -> "permission"
                    memory.result.contains("not found", ignoreCase = true) -> "not_found"
                    memory.result.contains("timeout", ignoreCase = true) -> "timeout"
                    else -> "unknown"
                }
            }

            errorGroups.forEach { (errorType, memories) ->
                if (memories.size >= 2) {
                    val pattern = MemoryEntry(
                        sessionId = sessionId,
                        type = MemoryType.ERROR,
                        importance = MemoryImportance.HIGH,
                        content = "错误模式: $errorType",
                        context = "该类型错误出现${memories.size}次",
                        tags = listOf("pattern", "error", errorType),
                        successRate = 0.0,
                        metadata = mapOf(
                            "error_count" to memories.size.toString(),
                            "error_type" to errorType
                        )
                    )
                    patterns.add(pattern)
                }
            }
        }

        // 保存到记忆管理器
        patterns.forEach { pattern ->
            memoryManager.addToWorkingMemory(
                sessionId = pattern.sessionId,
                type = pattern.type,
                content = pattern.content,
                context = pattern.context,
                importance = pattern.importance,
                tags = pattern.tags,
                metadata = pattern.metadata
            )
        }

        patterns
    }

    /**
     * 定期压缩任务，由 EnhancedAgentHelper.performPeriodicMaintenance 调用。
     */
    suspend fun performPeriodicCompression(sessionId: String) {
        try {
            val execMemories = loadExecutionMemories(context, sessionId)
            if (execMemories.size >= 10) {
                extractExecutionPatterns(sessionId, execMemories)
            }
            memoryManager.cleanupOldMemories(olderThanDays = 30)
            Log.d(TAG_COMPRESSOR, "Periodic compression completed for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG_COMPRESSOR, "Periodic compression failed", e)
        }
    }
}
