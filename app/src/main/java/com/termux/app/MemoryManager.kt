package com.termux.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TAG = "MemoryManager"
private const val MEMORY_DIR = "memory_system"
private const val WORKING_MEMORY_FILE = "working_memory.json"
private const val SHORT_TERM_MEMORY_FILE = "short_term_memory.json"
private const val LONG_TERM_MEMORY_FILE = "long_term_memory.json"

// 记忆类型
enum class MemoryType {
    EXECUTION,      // 执行记忆(命令执行结果)
    CONVERSATION,   // 对话记忆(用户交互)
    KNOWLEDGE,      // 知识记忆(提取的知识)
    TASK,           // 任务记忆(任务状态)
    ERROR,          // 错误记忆(失败经验)
    SUCCESS         // 成功记忆(成功经验)
}

// 记忆重要性级别
enum class MemoryImportance {
    CRITICAL,   // 关键记忆(必须保留)
    HIGH,       // 高重要性
    MEDIUM,     // 中等重要性
    LOW         // 低重要性
}

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val type: MemoryType,
    val importance: MemoryImportance,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val content: String,
    val context: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val embedding: List<Float>? = null,
    val relatedMemoryIds: List<String> = emptyList(),
    val successRate: Double? = null
)

data class MemoryRetrievalResult(
    val memory: MemoryEntry,
    val score: Double,
    val reason: String
)

/**
 * 产品级记忆管理器
 *
 * 分层架构：
 * - 工作记忆(20条)：当前任务临时上下文，纯内存
 * - 短期记忆(100条)：最近对话，内存+定期持久化
 * - 长期记忆(2000条)：重要经验，磁盘+内存缓存（只加载一次）
 */
class MemoryManager(private val context: Context) {

    private val gson = Gson()
    private val memoryDir = File(context.filesDir, MEMORY_DIR).apply { mkdirs() }

    // 内存存储
    private val workingMemory = mutableListOf<MemoryEntry>()
    private val shortTermMemory = mutableListOf<MemoryEntry>()

    // 长期记忆缓存：启动时加载一次，后续增删改直接操作缓存+同步写盘
    private var longTermCache: MutableList<MemoryEntry>? = null

    // 访问计数覆盖层：不持久化（重启清零），避免频繁写盘
    // accessCounts[id] 覆盖 MemoryEntry.accessCount，用于排序和晋升判断
    private val accessCounts = mutableMapOf<String, Int>()

    // 长期记忆专用锁，与 workingMemory/shortTermMemory 锁分离，避免死锁
    // 所有对 longTermCache 的读写必须持有此锁
    private val longTermLock = Any()

    // Embedding 缓存：key = MemoryEntry.id，value = 向量（运行时，不持久化）
    private val embeddingCache = HashMap<String, List<Float>>()
    private val embeddingCacheLock = Any()
    // 后台异步计算 embedding 的专用 scope
    private val embedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 限制并发 embedding API 调用数，防止启动时大量历史条目同时请求导致限流
    private val embeddingSemaphore = Semaphore(4)

    private val WORKING_MEMORY_CAPACITY = 20
    private val SHORT_TERM_MEMORY_CAPACITY = 100
    private val LONG_TERM_MEMORY_CAPACITY = 2000

    init {
        loadMemories()
    }

    // ────────────────────────────────────────────────────────────
    // 写入
    // ────────────────────────────────────────────────────────────

    fun addToWorkingMemory(
        sessionId: String,
        type: MemoryType,
        content: String,
        context: String? = null,
        importance: MemoryImportance = MemoryImportance.MEDIUM,
        tags: List<String> = emptyList(),
        metadata: Map<String, String> = emptyMap()
    ): MemoryEntry {
        val entry = MemoryEntry(
            sessionId = sessionId,
            type = type,
            importance = importance,
            content = content,
            context = context,
            tags = tags,
            metadata = metadata
        )

        synchronized(workingMemory) {
            workingMemory.add(entry)
            if (workingMemory.size > WORKING_MEMORY_CAPACITY) {
                // LRU 淘汰：驱逐最近最少访问且非 CRITICAL 的项
                val now = System.currentTimeMillis()
                val evictIdx = workingMemory.indices.minByOrNull { i ->
                    val m = workingMemory[i]
                    if (m.importance == MemoryImportance.CRITICAL) return@minByOrNull Double.MAX_VALUE
                    val ageHours = (now - m.lastAccessedAt).coerceAtLeast(0L) / 3_600_000.0
                    val recency = exp(-ageHours / 24.0)
                    val access = (accessCounts[m.id] ?: m.accessCount).toDouble()
                    recency * 0.6 + access * 0.4
                } ?: 0
                addToShortTermMemory(workingMemory.removeAt(evictIdx))
            }
        }

        // 后台异步计算 embedding，不阻塞调用方
        scheduleEmbedding(entry.id, content)

        return entry
    }

    /** 后台计算并缓存 entry 的 embedding；embedding key 未配置时静默跳过 */
    private fun scheduleEmbedding(id: String, text: String) {
        val apiKey = getEmbedApiKey(context)
        if (apiKey.isBlank()) return
        embedScope.launch {
            embeddingSemaphore.withPermit {
                val emb = callEmbedding(apiKey, text) ?: return@withPermit
                synchronized(embeddingCacheLock) { embeddingCache[id] = emb }
            }
        }
    }

    private fun addToShortTermMemory(entry: MemoryEntry) {
        synchronized(shortTermMemory) {
            shortTermMemory.add(entry)
            if (shortTermMemory.size > SHORT_TERM_MEMORY_CAPACITY) {
                // LRU 淘汰：按 recency × 0.6 + normalizedAccess × 0.4 打分，驱逐最低分
                // CRITICAL 级别记忆豁免，永不主动淘汰
                val now = System.currentTimeMillis()
                val evictIdx = shortTermMemory.indices.minByOrNull { i ->
                    val m = shortTermMemory[i]
                    if (m.importance == MemoryImportance.CRITICAL) return@minByOrNull Double.MAX_VALUE
                    val ageHours = (now - m.lastAccessedAt).coerceAtLeast(0L) / 3_600_000.0
                    val recency = exp(-ageHours / 24.0)
                    val access = (accessCounts[m.id] ?: m.accessCount).toDouble()
                    recency * 0.6 + access * 0.4
                } ?: 0
                val evicted = shortTermMemory.removeAt(evictIdx)
                if (shouldPromoteToLongTerm(evicted)) {
                    addToLongTermMemory(evicted)
                }
            }
            // 每5条持久化一次（原10），降低崩溃时的数据丢失窗口
            if (shortTermMemory.size % 5 == 0) {
                saveShortTermMemory()
            }
        }
    }

    private fun addToLongTermMemory(entry: MemoryEntry) {
        synchronized(longTermLock) {
            val cache = getLongTermCache()
            cache.add(entry)

            // 超容量时按重要性+访问频率+时间排序，淘汰末尾
            if (cache.size > LONG_TERM_MEMORY_CAPACITY) {
                cache.sortWith(
                    compareByDescending<MemoryEntry> { it.importance.ordinal }
                        .thenByDescending { accessCounts[it.id] ?: it.accessCount }
                        .thenBy { it.createdAt }
                )
                repeat(cache.size - LONG_TERM_MEMORY_CAPACITY) { cache.removeLast() }
            }

            saveLongTermMemory(cache)
        }
    }

    private fun shouldPromoteToLongTerm(entry: MemoryEntry): Boolean {
        val effectiveAccessCount = accessCounts[entry.id] ?: entry.accessCount
        return when {
            entry.importance == MemoryImportance.CRITICAL -> true
            entry.importance == MemoryImportance.HIGH -> true
            effectiveAccessCount >= 3 -> true          // 访问频率高
            entry.type == MemoryType.KNOWLEDGE -> true
            entry.type == MemoryType.SUCCESS && (entry.successRate ?: 0.0) > 0.8 -> true
            else -> false
        }
    }

    // ────────────────────────────────────────────────────────────
    // 检索
    // ────────────────────────────────────────────────────────────

    fun retrieveMemories(
        query: String,
        sessionId: String? = null,
        types: List<MemoryType>? = null,
        maxResults: Int = 10,
        queryEmbedding: List<Float>? = null   // 由 retrieveMemoriesSemantic 传入
    ): List<MemoryRetrievalResult> {
        val allMemories = mutableListOf<MemoryEntry>()
        synchronized(workingMemory)  { allMemories.addAll(workingMemory) }
        synchronized(shortTermMemory) { allMemories.addAll(shortTermMemory) }
        // 持有锁期间复制快照，避免检索过程中并发写入导致 ConcurrentModificationException
        synchronized(longTermLock) { allMemories.addAll(getLongTermCache()) }

        var filtered: List<MemoryEntry> = allMemories
        if (sessionId != null) filtered = filtered.filter { it.sessionId == sessionId }
        if (types != null)     filtered = filtered.filter { it.type in types }

        val queryTokens = tokenize(query)
        val now = System.currentTimeMillis()
        // 快照 embedding 缓存，避免在评分循环中反复加锁
        val embSnap = synchronized(embeddingCacheLock) { HashMap(embeddingCache) }

        return filtered
            .map { memory ->
                val score = calculateRelevanceScore(queryTokens, memory, now, queryEmbedding, embSnap)
                MemoryRetrievalResult(memory, score, buildReasonString(memory, score))
            }
            .sortedByDescending { it.score }
            .take(maxResults)
            .also { results -> results.forEach { updateAccessStats(it.memory) } }
    }

    /**
     * 语义检索：先调用 Embedding API 获取查询向量，再用余弦相似度评分。
     * embedding key 未配置或 API 失败时返回空列表（不降级为 token 匹配）。
     */
    suspend fun retrieveMemoriesSemantic(
        query: String,
        sessionId: String? = null,
        types: List<MemoryType>? = null,
        maxResults: Int = 10
    ): List<MemoryRetrievalResult> = withContext(Dispatchers.IO) {
        val apiKey = getEmbedApiKey(context)
        if (apiKey.isBlank()) {
            Log.w(TAG, "Embedding key not set, memory retrieval skipped")
            return@withContext emptyList()
        }
        val qEmb = callEmbedding(apiKey, query)
        if (qEmb == null) {
            Log.w(TAG, "Embedding API failed for query, memory retrieval skipped")
            return@withContext emptyList()
        }
        retrieveMemories(query, sessionId, types, maxResults, queryEmbedding = qEmb)
    }

    /** 余弦相似度，两向量维度不同或为空时返回 0.0 */
    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    private fun calculateRelevanceScore(
        queryTokens: Set<String>,
        memory: MemoryEntry,
        now: Long,
        queryEmbedding: List<Float>? = null,
        embSnap: Map<String, List<Float>> = emptyMap()
    ): Double {
        val memoryText = buildString {
            append(memory.content)
            if (memory.context != null) append(" ").append(memory.context)
            memory.tags.forEach { append(" ").append(it) }
        }
        // 余弦相似度：entry embedding 尚未计算完成时暂记 0，后台补全后下次检索生效
        val memEmb = embSnap[memory.id]
        val contentScore = if (queryEmbedding != null && memEmb != null)
            cosineSimilarity(queryEmbedding, memEmb)
        else
            0.0

        val ageHours = (now - memory.lastAccessedAt).coerceAtLeast(0L) / 3_600_000.0
        val recencyScore = exp(-ageHours / 24.0)

        // 使用运行时访问计数覆盖层（原字段 accessCount 可能已过期）
        val effectiveAccessCount = accessCounts[memory.id] ?: memory.accessCount
        val accessScore = ln(1.0 + effectiveAccessCount) / ln(11.0)

        val importanceScore = when (memory.importance) {
            MemoryImportance.CRITICAL -> 1.0
            MemoryImportance.HIGH     -> 0.75
            MemoryImportance.MEDIUM   -> 0.5
            MemoryImportance.LOW      -> 0.25
        }

        val successScore = memory.successRate ?: 0.5

        return contentScore * 3.0 +
               recencyScore * 1.5 +
               accessScore  * 1.0 +
               importanceScore * 2.0 +
               successScore * 1.5
    }

    private fun buildReasonString(memory: MemoryEntry, score: Double): String = buildString {
        append("相关性: ${String.format("%.2f", score)}")
        append(", 类型: ${memory.type.name}")
        append(", 重要性: ${memory.importance.name}")
        val ac = accessCounts[memory.id] ?: memory.accessCount
        if (ac > 0) append(", 访问${ac}次")
    }

    // 真正累积访问计数（原版是空实现）
    private fun updateAccessStats(memory: MemoryEntry) {
        accessCounts[memory.id] = (accessCounts[memory.id] ?: memory.accessCount) + 1
    }

    fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("""[^a-z0-9_\u4e00-\u9fa5]+"""))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(100)
            .toSet()

    fun getSessionMemorySummary(sessionId: String, maxChars: Int = 2000): String {
        val memories = retrieveMemories(query = "", sessionId = sessionId, maxResults = 20)
        if (memories.isEmpty()) return ""
        return buildString {
            append("【记忆摘要】\n")
            memories.take(10).forEachIndexed { idx, result ->
                val m = result.memory
                append("${idx + 1}. [${m.type.name}] ")
                append(m.content.take(150))
                if (m.content.length > 150) append("...")
                append("\n")
            }
        }.take(maxChars)
    }

    // ────────────────────────────────────────────────────────────
    // 维护
    // ────────────────────────────────────────────────────────────

    fun cleanupOldMemories(olderThanDays: Int = 30) {
        synchronized(longTermLock) {
            val cutoff = System.currentTimeMillis() - (olderThanDays * 24 * 3600 * 1000L)
            val cache = getLongTermCache()
            val before = cache.size
            cache.removeAll { it.lastAccessedAt <= cutoff && it.importance != MemoryImportance.CRITICAL }
            if (cache.size < before) {
                saveLongTermMemory(cache)
                Log.d(TAG, "Cleaned up ${before - cache.size} old memories")
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // 持久化
    // ────────────────────────────────────────────────────────────

    private fun loadMemories() {
        try {
            loadShortTermMemory()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memories", e)
        }
    }

    // 懒加载长期记忆缓存：首次调用时从磁盘加载，之后直接返回内存对象
    // 调用方必须持有 longTermLock，此处不再重复加锁
    private fun getLongTermCache(): MutableList<MemoryEntry> =
        longTermCache ?: loadLongTermMemory().toMutableList().also {
            longTermCache = it
            restoreEmbeddingsFromEntries(it)
        }

    private fun loadShortTermMemory() {
        val file = File(memoryDir, SHORT_TERM_MEMORY_FILE)
        if (!file.exists()) return
        val type = object : TypeToken<List<MemoryEntry>>() {}.type
        val loaded: List<MemoryEntry> = gson.fromJson(file.readText(), type) ?: emptyList()
        synchronized(shortTermMemory) {
            shortTermMemory.clear()
            shortTermMemory.addAll(loaded)
        }
        restoreEmbeddingsFromEntries(loaded)
    }

    private fun saveShortTermMemory() {
        try {
            val file = File(memoryDir, SHORT_TERM_MEMORY_FILE)
            val embSnap = synchronized(embeddingCacheLock) { HashMap(embeddingCache) }
            synchronized(shortTermMemory) {
                val toSave = shortTermMemory.map { entry ->
                    val ac = accessCounts[entry.id]
                    val emb = embSnap[entry.id]
                    entry.copy(
                        accessCount = if (ac != null && ac > entry.accessCount) ac else entry.accessCount,
                        embedding = emb ?: entry.embedding
                    )
                }
                file.writeTextAtomic(gson.toJson(toSave))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save short-term memory", e)
        }
    }

    private fun loadLongTermMemory(): List<MemoryEntry> {
        val file = File(memoryDir, LONG_TERM_MEMORY_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<MemoryEntry>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load long-term memory", e)
            emptyList()
        }
    }

    private fun saveLongTermMemory(memories: List<MemoryEntry>) {
        try {
            val embSnap = synchronized(embeddingCacheLock) { HashMap(embeddingCache) }
            val toSave = memories.map { entry ->
                val ac = accessCounts[entry.id]
                val emb = embSnap[entry.id]
                entry.copy(
                    accessCount = if (ac != null && ac > entry.accessCount) ac else entry.accessCount,
                    embedding = emb ?: entry.embedding
                )
            }
            File(memoryDir, LONG_TERM_MEMORY_FILE).writeTextAtomic(gson.toJson(toSave))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save long-term memory", e)
        }
    }

    /** 构建一条记忆的可嵌入文本（content + context + tags） */
    private fun buildEntryText(entry: MemoryEntry): String = buildString {
        append(entry.content)
        if (entry.context != null) append(" ").append(entry.context)
        entry.tags.forEach { append(" ").append(it) }
    }

    /**
     * 从已加载的 entries 恢复 embeddingCache：
     * - 有 embedding 字段的直接写入缓存（无需 API 调用）
     * - 没有 embedding 的在后台补算（重启后首次检索前完成）
     */
    private fun restoreEmbeddingsFromEntries(entries: List<MemoryEntry>) {
        val missing = mutableListOf<MemoryEntry>()
        synchronized(embeddingCacheLock) {
            entries.forEach { entry ->
                val emb = entry.embedding
                if (emb != null) embeddingCache[entry.id] = emb
                else missing.add(entry)
            }
        }
        // 后台为缺失 embedding 的历史条目补算，不阻塞加载
        missing.forEach { entry -> scheduleEmbedding(entry.id, buildEntryText(entry)) }
        if (missing.isNotEmpty()) Log.d(TAG, "Scheduling embedding for ${missing.size} entries")
    }
}
