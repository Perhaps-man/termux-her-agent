package com.termux.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MemoryExtractor"

/** 跨会话全局记忆的虚拟 sessionId */
const val GLOBAL_SESSION = "__global__"

/** 记忆提取的 system prompt：固定指令，不随执行内容变化 */
private val EXTRACTION_SYSTEM_PROMPT = """
你是一个记忆提取助手，任务是从 AI 执行步骤中提取值得长期记住的事实。

请提取 0-3 条值得记住的结构化事实。只提取真正有价值的内容，宁缺毋滥。

判断标准：
- 文件路径、项目结构 → 值得记录
- 可用的命令/工具 → 值得记录
- 错误及其解决方法 → 值得记录
- 依赖包版本 → 值得记录
- 反映用户偏好的选择 → 值得记录，scope=GLOBAL
- 项目技术栈/框架 → 值得记录，scope=GLOBAL
- 普通的命令输出、进度信息 → 不需要记录

输出格式（JSON 数组，无其他文字）：
[
  {
    "content": "简洁的事实陈述（不超过80字）",
    "type": "FILE_PATH|ERROR_PATTERN|COMMAND|DEPENDENCY|PROJECT|PREFERENCE|INSIGHT",
    "scope": "SESSION|GLOBAL",
    "entity": "核心实体，如文件路径或命令名（可选）"
  }
]

若无值得记录的事实，返回空数组：[]
""".trimIndent()

/** AI 提炼后的结构化事实类型 */
enum class FactType {
    FILE_PATH,      // 发现/创建的文件路径
    ERROR_PATTERN,  // 错误模式和解决方法
    COMMAND,        // 可用的命令/工具
    DEPENDENCY,     // 项目依赖
    PROJECT,        // 项目信息（路径、语言、框架）
    PREFERENCE,     // 用户偏好
    INSIGHT,        // 其他有价值的发现
}

/** 记忆范围：SESSION = 本会话有效；GLOBAL = 跨会话持久 */
enum class FactScope { SESSION, GLOBAL }

data class ExtractedFact(
    val content: String,       // 简洁的事实陈述，≤80字
    val type: FactType,
    val scope: FactScope,
    val entity: String? = null // 核心实体（文件路径/命令名/包名等）
)

/**
 * AI 驱动的记忆提取器
 *
 * 职责：把原始执行结果提炼为结构化事实，存入 MemoryManager。
 *
 * 关键设计：
 * - 异步后台执行，不阻塞主 AI 循环
 * - 提取前做去重：相似度 >70% 的已有记忆直接更新，不新增
 * - 有价值才提取（短结果 / 无关输出跳过）
 * - GLOBAL scope 的事实跨会话持久，用于项目上下文/用户偏好
 */
class MemoryExtractor(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val scope: CoroutineScope
) {
    // 最多同时发起 2 个 AI 提取请求，避免并发爆 token 配额
    private val extractSemaphore = Semaphore(2)

    /**
     * 异步提取并存储记忆，不阻塞调用方。
     *
     * 调用方 fire-and-forget，主流程立即继续。
     */
    fun extractAsync(
        sessionId: String,
        step: ParsedStep,
        result: RunResult
    ) {
        // 快速过滤：结果太短或是纯 OK 没有提取价值
        val combined = "${result.result} ${result.terminalLog}".trim()
        if (combined.length < 30 || combined == "OK") return

        scope.launch(Dispatchers.IO) {
            extractSemaphore.withPermit {
                try {
                    val facts = extractFacts(step, result)
                    if (facts.isEmpty()) return@withPermit

                    // 批量预取去重基准：2次检索覆盖所有事实，避免每条事实单独触发 N 次 IO
                    val combinedQuery = "${step.description} ${facts.joinToString(" ") { it.content }}"
                    val sessionExisting = memoryManager.retrieveMemories(
                        query = combinedQuery, sessionId = sessionId, maxResults = 20
                    )
                    val globalExisting = memoryManager.retrieveMemories(
                        query = combinedQuery, sessionId = GLOBAL_SESSION, maxResults = 20
                    )

                    facts.forEach { fact ->
                        val targetSession = if (fact.scope == FactScope.GLOBAL) GLOBAL_SESSION else sessionId
                        val preloaded = if (fact.scope == FactScope.GLOBAL) globalExisting else sessionExisting
                        storeFact(fact, targetSession, preloaded)
                    }
                    Log.d(TAG, "Extracted ${facts.size} facts for step: ${step.description.take(50)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Extraction failed (non-critical): ${e.message}")
                    // 提取失败不影响主流程，静默忽略
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // AI 提取
    // ────────────────────────────────────────────────────────────

    private suspend fun extractFacts(step: ParsedStep, result: RunResult): List<ExtractedFact> =
        withContext(Dispatchers.IO) {
            val resultText = buildString {
                append(result.result.take(600))
                if (result.terminalLog.isNotBlank()) {
                    append("\n终端: ")
                    append(result.terminalLog.take(300))
                }
            }

            val userPrompt = buildExtractionUserPrompt(step.description, step.actionLine, resultText)
            val reply = callAIAgent(context, EXTRACTION_SYSTEM_PROMPT, userPrompt)
            parseExtractionReply(reply)
        }

    private fun buildExtractionUserPrompt(
        description: String,
        actionLine: String,
        result: String
    ): String = """
【执行描述】$description
【执行动作】$actionLine
【执行结果】$result
""".trimIndent()

    private fun parseExtractionReply(reply: String): List<ExtractedFact> {
        val arrMatch = Regex("""\[[\s\S]*\]""").find(reply.trim()) ?: return emptyList()
        return try {
            val arr = JSONArray(arrMatch.value)
            val facts = mutableListOf<ExtractedFact>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val content = obj.optString("content").trim()
                if (content.isBlank()) continue
                val type = runCatching {
                    FactType.valueOf(obj.optString("type", "INSIGHT"))
                }.getOrDefault(FactType.INSIGHT)
                val scope = if (obj.optString("scope") == "GLOBAL") FactScope.GLOBAL else FactScope.SESSION
                val entity = obj.optString("entity").trim().ifBlank { null }
                facts.add(ExtractedFact(content, type, scope, entity))
            }
            facts
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse extraction reply: ${e.message}")
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────
    // 去重 + 存储
    // ────────────────────────────────────────────────────────────

    /**
     * 存储前先检查相似度，>70% 则视为重复，直接丢��避免记忆库膨胀。
     *
     * [preloadedExisting] 由调用方批量预取传入；为空时降级为单独检索（兼容旧调用路径）。
     */
    private fun storeFact(
        fact: ExtractedFact,
        sessionId: String,
        preloadedExisting: List<MemoryRetrievalResult> = emptyList()
    ) {
        val factTokens = memoryManager.tokenize(fact.content)

        val existing = preloadedExisting.ifEmpty {
            memoryManager.retrieveMemories(query = fact.content, sessionId = sessionId, maxResults = 10)
        }

        val isDuplicate = existing.any { result ->
            val existingTokens = memoryManager.tokenize(result.memory.content)
            val union = (factTokens + existingTokens).size.toDouble()
            if (union == 0.0) false
            else {
                val intersection = factTokens.count { it in existingTokens }.toDouble()
                intersection / union > 0.7  // Jaccard 相似度 > 70%
            }
        }

        if (isDuplicate) {
            Log.d(TAG, "Skipped duplicate fact: ${fact.content.take(50)}")
            return
        }

        val (type, importance) = mapFactToMemory(fact)
        memoryManager.addToWorkingMemory(
            sessionId = sessionId,
            type = type,
            content = fact.content,
            context = fact.entity?.let { "实体: $it" },
            importance = importance,
            tags = buildList {
                add(fact.type.name.lowercase())
                fact.entity?.let { add(it.substringAfterLast("/").take(30)) }
            },
            metadata = buildMap {
                put("fact_type", fact.type.name)
                put("fact_scope", fact.scope.name)
                fact.entity?.let { put("entity", it) }
            }
        )
    }

    private fun mapFactToMemory(fact: ExtractedFact): Pair<MemoryType, MemoryImportance> {
        val type = when (fact.type) {
            FactType.ERROR_PATTERN -> MemoryType.ERROR
            FactType.PREFERENCE    -> MemoryType.KNOWLEDGE
            FactType.PROJECT       -> MemoryType.KNOWLEDGE
            FactType.INSIGHT       -> MemoryType.KNOWLEDGE
            else                   -> MemoryType.EXECUTION
        }
        val importance = when (fact.type) {
            FactType.ERROR_PATTERN -> MemoryImportance.HIGH   // 错误经验很重要
            FactType.PROJECT       -> MemoryImportance.HIGH   // 项目上下文很重要
            FactType.PREFERENCE    -> MemoryImportance.HIGH   // 用户偏好很重要
            FactType.FILE_PATH     -> MemoryImportance.MEDIUM
            else                   -> MemoryImportance.MEDIUM
        }
        return type to importance
    }
}
