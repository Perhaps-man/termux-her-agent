package com.termux.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ln

private const val TAG = "SessionPersistence"
private const val SESSIONS_INDEX = "sessions_index.json"
private const val CHAT_PREFIX = "chat_"
private const val CHAT_SUFFIX = ".json"
private const val LEGACY_CHAT_FILE = "chat_history.json"
private const val PREFS = "session_prefs"
private const val KEY_CURRENT_SESSION = "current_session_id"
private const val KEY_AUTO_BUILD_FREQ = "auto_build_freq"
private const val KEY_USER_MSG_COUNT = "user_msg_count"
private const val DEFAULT_AUTO_BUILD_FREQ = 20
private const val SESSION_STATE_PREFIX = "session_state_"
private const val SESSION_STATE_SUFFIX = ".json"
private const val EXEC_MEMORY_PREFIX = "exec_memory_"
private const val EXEC_MEMORY_SUFFIX = ".json"
private const val EXEC_MEMORY_MAX_ITEMS = 300

private val gson = Gson()
private val indexType = object : TypeToken<List<SessionMeta>>() {}.type
private val chatType = object : TypeToken<List<SessionChatItemSave>>() {}.type
private val sessionStateType = object : TypeToken<SessionStateSave>() {}.type
private val execMemoryType = object : TypeToken<List<ExecutionMemoryRecord>>() {}.type

data class SessionMeta(val id: String, val title: String, val updatedAt: Long)

data class SessionStateSave(
    val dialogSummary: String? = null,
    val dialogCompactedCount: Int = 0
)

data class ExecutionMemoryRecord(
    val id: String,
    val createdAt: Long,
    val stepDescription: String,
    val actionLine: String,
    val result: String,
    val terminalLog: String? = null
)

private data class SessionChatItemSave(
    val isUser: Boolean,
    val content: String,
    val progress: String? = null,
    val result: String? = null,
    val webHtml: String? = null,
    val attachmentPath: String? = null,
    val attachmentLabel: String? = null,
    val depStorePackages: List<String>? = null,
    val dependencyPackage: String? = null,
    val pluginDexPath: String? = null,
    val pluginEntryActivity: String? = null,
    val pluginPkgName: String? = null,
    @Suppress("UNUSED_PARAMETER") val isPlan: Boolean? = null,
    @Suppress("UNUSED_PARAMETER") val taskRootsJson: String? = null
)

private fun HerChatItem.toSessionSave() = SessionChatItemSave(
    isUser = isUser,
    content = content,
    progress = progress,
    result = result,
    webHtml = webHtml,
    attachmentPath = attachmentPath,
    attachmentLabel = attachmentLabel,
    depStorePackages = depStorePackages,
    pluginDexPath = pluginDexPath,
    pluginEntryActivity = pluginEntryActivity,
    pluginPkgName = pluginPkgName,
)

private fun SessionChatItemSave.toItem() = HerChatItem(
    isUser = isUser,
    content = content,
    progress = progress,
    result = result,
    webHtml = webHtml,
    running = false,
    webDimmed = false,
    attachmentPath = attachmentPath,
    attachmentLabel = attachmentLabel,
    depStorePackages = depStorePackages,
    pluginDexPath = pluginDexPath,
    pluginEntryActivity = pluginEntryActivity,
    pluginPkgName = pluginPkgName,
)

/** 获取当前选中的会话 ID */
fun getCurrentSessionId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return prefs.getString(KEY_CURRENT_SESSION, null) ?: run {
        val sessions = listSessions(context)
        if (sessions.isEmpty()) {
            val id = UUID.randomUUID().toString()
            saveSessionIndex(context, listOf(SessionMeta(id, "新会话", System.currentTimeMillis())))
            prefs.edit().putString(KEY_CURRENT_SESSION, id).apply()
            id
        } else {
            sessions.maxByOrNull { it.updatedAt }!!.id
        }
    }
}

/** 设置当前会话 ID */
fun setCurrentSessionId(context: Context, id: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_CURRENT_SESSION, id).apply()
}

/** 列出所有会话（按更新时间倒序） */
fun listSessions(context: Context): List<SessionMeta> {
    migrateLegacyIfNeeded(context)
    return try {
        val file = File(context.filesDir, SESSIONS_INDEX)
        if (!file.isFile) return emptyList()
        val list: List<SessionMeta> = gson.fromJson(file.readText(Charsets.UTF_8), indexType)
        list.sortedByDescending { it.updatedAt }
    } catch (t: Throwable) {
        Log.e(TAG, "listSessions failed", t)
        emptyList()
    }
}

private fun migrateLegacyIfNeeded(context: Context) {
    val legacyFile = File(context.filesDir, LEGACY_CHAT_FILE)
    val indexFile = File(context.filesDir, SESSIONS_INDEX)
    if (legacyFile.isFile && !indexFile.isFile) {
        try {
            val list: List<SessionChatItemSave> = gson.fromJson(legacyFile.readText(Charsets.UTF_8), chatType)
            if (list.isNotEmpty()) {
                val id = UUID.randomUUID().toString()
                val title = list.firstOrNull { it.isUser }?.content?.take(30) ?: "迁移的会话"
                saveSessionIndex(context, listOf(SessionMeta(id, title, System.currentTimeMillis())))
                File(context.filesDir, CHAT_PREFIX + id + CHAT_SUFFIX).writeText(legacyFile.readText(Charsets.UTF_8))
                setCurrentSessionId(context, id)
                legacyFile.delete()
                Log.d(TAG, "Migrated legacy chat to session $id")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Migrate legacy failed", e)
        }
    }
}

private fun saveSessionIndex(context: Context, sessions: List<SessionMeta>) {
    try {
        File(context.filesDir, SESSIONS_INDEX).writeTextAtomic(gson.toJson(sessions), Charsets.UTF_8)
    } catch (t: Throwable) {
        Log.e(TAG, "saveSessionIndex failed", t)
    }
}

/** 保存指定会话的聊天内容 */
fun saveSessionChat(context: Context, sessionId: String, items: List<HerChatItem>, title: String? = null) {
    if (items.isEmpty()) return
    try {
        val file = File(context.filesDir, CHAT_PREFIX + sessionId + CHAT_SUFFIX)
        val list = items.map { it.toSessionSave() }
        file.writeTextAtomic(gson.toJson(list), Charsets.UTF_8)
        val sessions = listSessions(context).toMutableList()
        val idx = sessions.indexOfFirst { it.id == sessionId }
        val meta = SessionMeta(
            sessionId,
            title ?: sessions.getOrNull(idx)?.title ?: items.firstOrNull { it.isUser }?.content?.take(30) ?: "新会话",
            System.currentTimeMillis()
        )
        if (idx >= 0) sessions[idx] = meta else sessions.add(0, meta)
        saveSessionIndex(context, sessions)
    } catch (t: Throwable) {
        Log.e(TAG, "saveSessionChat failed", t)
    }
}

/** 加载指定会话的聊天内容 */
fun loadSessionChat(context: Context, sessionId: String): List<HerChatItem>? {
    return try {
        val file = File(context.filesDir, CHAT_PREFIX + sessionId + CHAT_SUFFIX)
        if (!file.isFile) return null
        val list: List<SessionChatItemSave> = gson.fromJson(file.readText(Charsets.UTF_8), chatType)
        if (list.isEmpty()) return null
        list.map { it.toItem() }
    } catch (t: Throwable) {
        Log.e(TAG, "loadSessionChat failed", t)
        null
    }
}

/** 创建新会话并返回 ID */
fun createNewSession(context: Context): String {
    val id = UUID.randomUUID().toString()
    val sessions = listSessions(context).toMutableList()
    sessions.add(0, SessionMeta(id, "新会话", System.currentTimeMillis()))
    saveSessionIndex(context, sessions)
    setCurrentSessionId(context, id)
    return id
}

/** 删除会话（不再删除工作目录，所有会话共用同一 workspace） */
fun deleteSession(context: Context, sessionId: String) {
    try {
        File(context.filesDir, CHAT_PREFIX + sessionId + CHAT_SUFFIX).delete()
        File(context.filesDir, SESSION_STATE_PREFIX + sessionId + SESSION_STATE_SUFFIX).delete()
        File(context.filesDir, EXEC_MEMORY_PREFIX + sessionId + EXEC_MEMORY_SUFFIX).delete()
        val sessions = listSessions(context).filter { it.id != sessionId }
        saveSessionIndex(context, sessions)
        if (getCurrentSessionId(context) == sessionId && sessions.isNotEmpty()) {
            setCurrentSessionId(context, sessions.first().id)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "deleteSession failed", t)
    }
}

/** 自动编译频率（5-100），每 N 条用户消息触发一次 */
fun getAutoBuildFreq(context: Context): Int {
    val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_AUTO_BUILD_FREQ, DEFAULT_AUTO_BUILD_FREQ)
    return v.coerceIn(5, 100)
}

fun setAutoBuildFreq(context: Context, freq: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putInt(KEY_AUTO_BUILD_FREQ, freq.coerceIn(5, 100)).apply()
}

/** 用户消息累计数 */
fun getUserMessageCount(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_USER_MSG_COUNT, 0)

/** 用户发送一条消息后调用，返回是否应触发自动编译 */
fun incrementUserMessageCountAndCheckTrigger(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val count = prefs.getInt(KEY_USER_MSG_COUNT, 0) + 1
    prefs.edit().putInt(KEY_USER_MSG_COUNT, count).apply()
    val freq = getAutoBuildFreq(context)
    return count > 0 && count % freq == 0
}

/** 供 AI 生成 Activity 时作为历史上下文：自动编译设置与触发信息 */
fun getAutoBuildHistoryForPrompt(context: Context): String {
    val freq = getAutoBuildFreq(context)
    val count = getUserMessageCount(context)
    val triggerTimes = if (count <= 0) 0 else count / freq
    return """
【自动编译设置】用户设置每${freq}条消息自动编译。当前已累计发送${count}条，本次为第${triggerTimes}次自动触发。
    """.trimIndent()
}

fun formatSessionTime(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        now - ms < 60_000 -> "刚刚"
        now - ms < 3600_000 -> "${(now - ms) / 60_000}分钟前"
        now - ms < 86400_000 -> "${(now - ms) / 3600_000}小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

/**
 * 读取当前会话中用户最近若干条消息，格式化为供 Activity 生成的 AI 提示词片段。
 * @param maxCount 最多返回条数，默认 10
 */
fun getLastUserMessagesForPrompt(context: Context, maxCount: Int = 10): String {
    val sessionId = getCurrentSessionId(context)
    val items = loadSessionChat(context, sessionId) ?: return ""
    val userMessages = items.filter { it.isUser }.map { it.content.trim() }.filter { it.isNotEmpty() }
    if (userMessages.isEmpty()) return ""
    val recent = if (userMessages.size <= maxCount) userMessages else userMessages.takeLast(maxCount)
    val lines = recent.mapIndexed { i, text -> "${i + 1}. $text" }
    return """
        【用户最近聊天内容】（共 ${recent.size} 条，请根据其需求与情绪通过生成的 Activity 提供帮助或关怀）
        ${lines.joinToString("\n")}
    """.trimIndent()
}

fun saveSessionState(context: Context, sessionId: String, state: SessionStateSave) {
    if (sessionId.isBlank()) return
    try {
        File(context.filesDir, SESSION_STATE_PREFIX + sessionId + SESSION_STATE_SUFFIX)
            .writeTextAtomic(gson.toJson(state), Charsets.UTF_8)
    } catch (t: Throwable) {
        Log.e(TAG, "saveSessionState failed", t)
    }
}

fun loadSessionState(context: Context, sessionId: String): SessionStateSave? {
    if (sessionId.isBlank()) return null
    return try {
        val file = File(context.filesDir, SESSION_STATE_PREFIX + sessionId + SESSION_STATE_SUFFIX)
        if (!file.isFile) return null
        gson.fromJson<SessionStateSave>(file.readText(Charsets.UTF_8), sessionStateType)
    } catch (t: Throwable) {
        Log.e(TAG, "loadSessionState failed", t)
        null
    }
}

fun loadExecutionMemories(context: Context, sessionId: String): List<ExecutionMemoryRecord> {
    if (sessionId.isBlank()) return emptyList()
    return try {
        val file = File(context.filesDir, EXEC_MEMORY_PREFIX + sessionId + EXEC_MEMORY_SUFFIX)
        if (!file.isFile) return emptyList()
        gson.fromJson<List<ExecutionMemoryRecord>>(file.readText(Charsets.UTF_8), execMemoryType) ?: emptyList()
    } catch (t: Throwable) {
        Log.e(TAG, "loadExecutionMemories failed", t)
        emptyList()
    }
}

private fun tokenizeForRetrieval(text: String): Set<String> =
    text.lowercase()
        .split(Regex("""[^a-z0-9_\u4e00-\u9fa5]+"""))
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= 2 }
        .take(80)
        .toSet()

private fun scoreMemory(queryTokens: Set<String>, m: ExecutionMemoryRecord, now: Long): Double {
    val body = buildString {
        append(m.stepDescription)
        append('\n')
        append(m.actionLine)
        append('\n')
        append(m.result)
    }
    val memoryTokens = tokenizeForRetrieval(body)
    val overlap = queryTokens.count { memoryTokens.contains(it) }.toDouble()
    val ageHours = ((now - m.createdAt).coerceAtLeast(0L) / 3_600_000.0)
    val recency = 1.0 / (1.0 + ln(2.0 + ageHours))
    return overlap * 2.0 + recency
}

fun buildExecutionMemoryContextForPrompt(
    context: Context,
    sessionId: String,
    query: String,
    maxItems: Int = 6,
    maxChars: Int = 6000
): String? {
    val all = loadExecutionMemories(context, sessionId)
    if (all.isEmpty()) return null
    val now = System.currentTimeMillis()
    val qTokens = tokenizeForRetrieval(query)
    val ranked = all
        .asReversed()
        .map { it to scoreMemory(qTokens, it, now) }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(maxItems)
    if (ranked.isEmpty()) return null

    val text = buildString {
        append("【历史执行片段】（按与你当前问题的相关度和新鲜度排序）\n")
        ranked.forEachIndexed { idx, m ->
            append(idx + 1)
            append(". [")
            append(formatSessionTime(m.createdAt))
            append("] ")
            append(m.stepDescription.take(180))
            append("\n动作: ")
            append(m.actionLine.take(220))
            append("\n结果: ")
            append(m.result.take(420))
            if (!m.terminalLog.isNullOrBlank()) {
                append("\n终端片段: ")
                append(m.terminalLog.take(320))
            }
            append("\n\n")
        }
    }.trim()

    return text.take(maxChars).ifBlank { null }
}
