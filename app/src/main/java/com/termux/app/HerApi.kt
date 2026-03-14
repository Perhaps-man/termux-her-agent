package com.termux.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val aiHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
}

private const val HER_API_SYNC_CALL_TIMEOUT_MS = 30_000L

private fun <T> runBlockingWithTimeout(
    timeoutMs: Long = HER_API_SYNC_CALL_TIMEOUT_MS,
    block: suspend () -> T
): T = runBlocking {
    withTimeoutOrNull(timeoutMs) { block() }
        ?: throw IllegalStateException("Operation timed out after ${timeoutMs}ms")
}

/** 使用用户在抽屉内配置的供应商与 API Key 调用大模型 */
suspend fun callAI(context: Context, prompt: String): String = withContext(Dispatchers.IO) {
    val provider = getAiProvider(context)
    val apiKey = getAiApiKey(context)
    val model = getAiModel(context)
    if (apiKey.isBlank()) {
        return@withContext "Error: 请在启动页抽屉内填写并保存 API Key"
    }
    when (provider) {
        PROVIDER_CLOSEAI -> callOpenAICompatible(
            baseUrl = "https://api.openai-proxy.org/v1",
            apiKey = apiKey,
            model = model,
            prompt = prompt
        )
        PROVIDER_OPENAI, PROVIDER_GPT -> callOpenAICompatible(
            baseUrl = "https://api.openai.com/v1",
            apiKey = apiKey,
            model = model,
            prompt = prompt
        )
        PROVIDER_DEEPSEEK -> callOpenAICompatible(
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = apiKey,
            model = model,
            prompt = prompt
        )
        PROVIDER_QWEN -> callOpenAICompatible(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey = apiKey,
            model = model,
            prompt = prompt
        )
        PROVIDER_GEMINI -> callGemini(apiKey = apiKey, model = model, prompt = prompt)
        PROVIDER_CLAUDE -> callClaude(apiKey = apiKey, model = model, prompt = prompt)
        else -> callOpenAICompatible(
            baseUrl = "https://api.openai.com/v1",
            apiKey = apiKey,
            model = model,
            prompt = prompt
        )
    }
}

private suspend fun callOpenAICompatible(
    baseUrl: String,
    apiKey: String,
    model: String,
    prompt: String
): String = withContext(Dispatchers.IO) {
    val requestBody = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        }))
    }
    val request = Request.Builder()
        .url("$baseUrl/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody.toString()))
        .build()
    executeAiRequest(request) { bodyStr ->
        val json = JSONObject(bodyStr)
        if (json.has("error")) {
            "Error: " + (json.optJSONObject("error")?.optString("message") ?: "Unknown error")
        } else {
            json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?: "Error: Response format mismatch"
        }
    }
}

private suspend fun callGemini(apiKey: String, model: String, prompt: String): String = withContext(Dispatchers.IO) {
    val requestBody = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
        }))
    }
    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody.toString()))
        .build()
    executeAiRequest(request) { bodyStr ->
        val json = JSONObject(bodyStr)
        val err = json.optJSONArray("error")?.optJSONObject(0)?.optString("message")
        if (err != null) return@executeAiRequest "Error: $err"
        json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text") ?: "No content"
    }
}

private suspend fun callClaude(apiKey: String, model: String, prompt: String): String = withContext(Dispatchers.IO) {
    val requestBody = JSONObject().apply {
        put("model", model)
        put("max_tokens", 8192)
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        }))
    }
    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody.toString()))
        .build()
    executeAiRequest(request) { bodyStr ->
        val json = JSONObject(bodyStr)
        if (json.has("error")) {
            "Error: " + (json.optJSONObject("error")?.optString("message") ?: "Unknown error")
        } else {
            json.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "Error: No content"
        }
    }
}

// ── Agent 专用 AI 调用 ────────────────────────────────────────────────────────────

/** Agent 执行时的采样温度：低温度保证 JSON 格式输出稳定 */
private const val AGENT_TEMPERATURE = 0.15

/**
 * Claude 必填字段的 max_tokens 上限。
 * 设为 16384 而非更小的值，避免 writeFile 写大型 HTML/JS/CSS 时内容被截断。
 * OpenAI / Gemini 不发送此字段，由各自 API 使用模型默认上限。
 */
private const val CLAUDE_AGENT_MAX_TOKENS = 16384

/**
 * Agent 专用 AI 调用：system/user 消息分离 + 低温度 + 指数退避重试
 *
 * 与 [callAI] 的关键区别：
 * - systemPrompt 承载工具格式说明（ACTION_TOOLS_PROMPT），模型作为角色指令处理
 * - userPrompt   承载任务内容、历史、记忆，模型作为用户输入处理
 * - temperature=0.15 确保 JSON 输出格式稳定，减少解析失败率
 * - 网络/API 错误自动重试最多 [maxRetries] 次，每次等待时间翻倍
 */
suspend fun callAIAgent(
    context: Context,
    systemPrompt: String,
    userPrompt: String,
    maxRetries: Int = 3
): String = withContext(Dispatchers.IO) {
    val provider = getAiProvider(context)
    val apiKey   = getAiApiKey(context)
    val model    = getAiModel(context)
    if (apiKey.isBlank()) return@withContext "Error: 请在启动页抽屉内填写并保存 API Key"

    var lastError = "未知错误"
    for (attempt in 0 until maxRetries) {
        if (attempt > 0) delay(1000L shl attempt)   // 1s → 2s → 4s
        val result = runCatching {
            when (provider) {
                PROVIDER_CLAUDE   -> callClaudeAgent(apiKey, model, systemPrompt, userPrompt)
                PROVIDER_GEMINI   -> callGeminiAgent(apiKey, model, systemPrompt, userPrompt)
                PROVIDER_CLOSEAI  -> callOpenAICompatibleAgent("https://api.openai-proxy.org/v1",                    apiKey, model, systemPrompt, userPrompt)
                PROVIDER_DEEPSEEK -> callOpenAICompatibleAgent("https://api.deepseek.com/v1",                       apiKey, model, systemPrompt, userPrompt)
                PROVIDER_QWEN     -> callOpenAICompatibleAgent("https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, model, systemPrompt, userPrompt)
                else              -> callOpenAICompatibleAgent("https://api.openai.com/v1",                          apiKey, model, systemPrompt, userPrompt)
            }
        }.getOrElse { e -> "Error: ${e.message}" }
        if (!result.startsWith("Error:")) return@withContext result
        lastError = result
    }
    lastError
}

private suspend fun callOpenAICompatibleAgent(
    baseUrl: String, apiKey: String, model: String,
    systemPrompt: String, userPrompt: String
): String = withContext(Dispatchers.IO) {
    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt))
        .put(JSONObject().put("role", "user").put("content", userPrompt))
    val body = JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("temperature", AGENT_TEMPERATURE)
        // 不设 max_tokens：让各 OpenAI-compatible API 使用模型自身上限
        // 避免 writeFile 写大型 HTML/JS 时输出被截断
    val request = Request.Builder()
        .url("$baseUrl/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
        .build()
    executeAiRequest(request) { bodyStr ->
        val json = JSONObject(bodyStr)
        if (json.has("error")) "Error: " + (json.optJSONObject("error")?.optString("message") ?: "Unknown")
        else json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?: "Error: Response format mismatch"
    }
}

private suspend fun callGeminiAgent(
    apiKey: String, model: String,
    systemPrompt: String, userPrompt: String
): String = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("system_instruction", JSONObject().put("parts",
            JSONArray().put(JSONObject().put("text", systemPrompt))))
        put("contents", JSONArray().put(JSONObject().put("parts",
            JSONArray().put(JSONObject().put("text", userPrompt)))))
        put("generationConfig", JSONObject()
            .put("temperature", AGENT_TEMPERATURE))
            // 不设 maxOutputTokens，使用 Gemini 模型默认上限
    }
    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
        .build()
    executeAiRequest(request) { bodyStr ->
        val json = JSONObject(bodyStr)
        val err = json.optJSONObject("error")?.optString("message")
        if (err != null) return@executeAiRequest "Error: $err"
        json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text") ?: "No content"
    }
}

private suspend fun callClaudeAgent(
    apiKey: String, model: String,
    systemPrompt: String, userPrompt: String
): String = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("model", model)
        put("max_tokens", CLAUDE_AGENT_MAX_TOKENS)  // Claude 必填；设大值允许输出完整 HTML/JS
        put("system", systemPrompt)   // Claude 用顶层 system 字段，不放进 messages
        put("messages", JSONArray().put(JSONObject()
            .put("role", "user").put("content", userPrompt)))
    }
    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
        .build()
    executeAiRequest(request) { bodyStr ->
        val json = JSONObject(bodyStr)
        if (json.has("error")) "Error: " + (json.optJSONObject("error")?.optString("message") ?: "Unknown")
        else json.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "Error: No content"
    }
}

// ── Embedding ────────────────────────────────────────────────────────────────

/**
 * 调用 Dashscope text-embedding-v4 获取向量。
 * 失败时返回 null（降级为 token 匹配），不抛异常。
 */
suspend fun callEmbedding(apiKey: String, text: String): List<Float>? = withContext(Dispatchers.IO) {
    if (apiKey.isBlank() || text.isBlank()) return@withContext null
    val body = JSONObject()
        .put("model", "text-embedding-v4")
        .put("input", text.take(2000))   // API 单次输入上限
        .put("encoding_format", "float")
    val request = Request.Builder()
        .url("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
        .build()
    return@withContext try {
        val resp = aiHttpClient.newCall(request).execute()
        val bodyStr = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return@withContext null
        val json = JSONObject(bodyStr)
        if (json.has("error")) return@withContext null
        val arr = json.optJSONArray("data")
            ?.optJSONObject(0)?.optJSONArray("embedding") ?: return@withContext null
        (0 until arr.length()).map { arr.getDouble(it).toFloat() }
    } catch (e: Exception) {
        android.util.Log.w("callEmbedding", "Embedding failed: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private suspend fun executeAiRequest(request: Request, parse: (String) -> String): String =
    suspendCancellableCoroutine { cont ->
        val call = aiHttpClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (!cont.isCancelled) cont.resume("Error: ${e.message}", null)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (!cont.isCancelled) cont.resume("Error: HTTP ${resp.code} $bodyStr", null)
                        return
                    }
                    try {
                        cont.resume(parse(bodyStr), null)
                    } catch (e: Exception) {
                        if (!cont.isCancelled) cont.resume("Error: ${e.message}", null)
                    }
                }
            }
        })
    }
/** 秘塔 AI 搜索 API Key，可在设置中覆盖；请勿将密钥提交到版本控制 */
private const val METASO_BASE_URL = "https://metaso.cn"

/** 搜索每日次数限制与持久化 key */
private const val PREFS_SEARCH_LIMIT = "metaso_search_limit"
private const val KEY_SEARCH_DATE = "search_date"
private const val KEY_SEARCH_COUNT = "search_count"
private const val SEARCH_DAILY_LIMIT = 10
private const val LIMIT_MSG = "今日搜索次数已达上限（10次），请明天再试"

class HerApi(private val context: Context) {

    /** 秘塔 AI 搜索：每日最多 10 次，调用 chat/completions 接口 */
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_SEARCH_LIMIT, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString(KEY_SEARCH_DATE, "")
        val count = if (savedDate != today) {
            prefs.edit().putString(KEY_SEARCH_DATE, today).putInt(KEY_SEARCH_COUNT, 0).apply()
            0
        } else {
            prefs.getInt(KEY_SEARCH_COUNT, 0)
        }
        if (count >= SEARCH_DAILY_LIMIT) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, LIMIT_MSG, Toast.LENGTH_LONG).show()
            }
            return@withContext LIMIT_MSG
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", query.take(1000))
        })
        val body = JSONObject().apply {
            put("model", "fast")
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("$METASO_BASE_URL/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $METASO_API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()

        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (!cont.isCancelled) cont.resume("搜索失败: ${e.message}", null)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { resp ->
                        try {
                            val bodyStr = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                cont.resume("搜索失败 HTTP ${resp.code}: $bodyStr", null)
                                return
                            }
                            val json = JSONObject(bodyStr)
                            if (json.has("error")) {
                                cont.resume("搜索错误: ${json.optString("error")}", null)
                                return
                            }
                            val choices = json.optJSONArray("choices")
                            val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                            var content = msg?.optString("content", "")?.trim().orEmpty()

                            // 如果有 citations，则把 content 中的 [[1]]、[[2]] 等数字标记替换为对应链接，形如 [[https://...]]
                            val citations = msg?.optJSONArray("citations")
                            if (citations != null && citations.length() > 0) {
                                val pattern = Regex("""\[\[(\d+)\]\]""")
                                content = pattern.replace(content) { m ->
                                    val idx = m.groupValues[1].toIntOrNull()?.minus(1) ?: return@replace m.value
                                    val cite = citations.optJSONObject(idx) ?: return@replace m.value
                                    val link = cite.optString("link").takeIf { it.isNotBlank() } ?: return@replace m.value
                                    "[$link]"
                                }
                            }

                            prefs.edit()
                                .putInt(KEY_SEARCH_COUNT, prefs.getInt(KEY_SEARCH_COUNT, 0) + 1)
                                .putString(KEY_SEARCH_DATE, today)
                                .apply()
                            cont.resume(content.ifBlank { "无结果" }, null)
                        } catch (e: Exception) {
                            cont.resume("解析失败: ${e.message}", null)
                        }
                    }
                }
            })
        }
    }
    /** 优先查找 run(android.content.Context) 或 run(Context)，否则回退到无参 run() */
    fun extractMethodName(javaCode: String): String {
        val withContextRe = Regex(
            """public\s+[A-Za-z0-9_<>\[\]]+\s+([A-Za-z0-9_]+)\s*\(\s*(?:android\.content\.)?Context\s+[A-Za-z0-9_]+\s*\)""",
            RegexOption.MULTILINE
        )
        withContextRe.find(javaCode)?.let { return it.groupValues[1] }
        val noArgRe = Regex(
            """public\s+[A-Za-z0-9_<>\[\]]+\s+([A-Za-z0-9_]+)\s*\(\s*\)""",
            RegexOption.MULTILINE
        )
        val match = noArgRe.find(javaCode)
            ?: throw IllegalStateException("未找到 public 方法：需要 run(Context context) 或 run()")
        return match.groupValues[1]
    }

    /** 是否声明了带 Context 参数的方法（用于 invoke 时传参） */
    private fun hasContextMethod(javaCode: String): Boolean {
        return Regex(
            """public\s+[A-Za-z0-9_<>\[\]]+\s+[A-Za-z0-9_]+\s*\(\s*(?:android\.content\.)?Context\s+[A-Za-z0-9_]+\s*\)""",
            RegexOption.MULTILINE
        ).containsMatchIn(javaCode)
    }

    private fun extractFullClassName(javaCode: String): String {
        val pkg = Regex("""(?m)^\s*package\s+([a-zA-Z0-9_.]+)\s*;""")
            .find(javaCode)?.groupValues?.get(1)?.trim()
        val className = Regex("""public\s+class\s+([A-Za-z0-9_]+)""")
            .find(javaCode)?.groupValues?.get(1) ?: throw IllegalStateException("未找到 public class")
        return if (pkg.isNullOrBlank()) className else "$pkg.$className"
    }


    fun addCalendarEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String = ""
    ): Boolean = runBlockingWithTimeout {

        val ok = HerSystemRuntime.addCalendarEvent(
            context,
            title,
            startMillis,
            endMillis,
            description
        )

        val act = context as? ComponentActivity

        act?.runOnUiThread {

            val msg = if (ok) {
                "日历事件已创建"
            } else {
                "创建失败"
            }

            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        ok
    }

    fun call(target: String): Boolean = runBlockingWithTimeout {
        HerSystemRuntime.callTarget(context, target)
    }

    fun loadHtml(code: String) {
        val act = context as? ComponentActivity ?: return

        act.runOnUiThread {
            WebRenderManager.loadHtml(code)
        }
    }

    fun openApp(target: String): Boolean {

        val ok = HerSystemRuntime.openApp(context, target)

        val act = context as? ComponentActivity

        act?.runOnUiThread {

            val msg = if (ok) {
                "已打开应用"
            } else {
                "未找到应用"
            }

            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        return ok
    }

    /** 编译到 dex 一条龙：写源码 → javac → jar → d8，返回含 classes.dex 的目录（同 code 会复用） */
    private suspend fun compileJavaToDex(javaCode: String): File = withContext(Dispatchers.IO) {
        val key = sha256(javaCode)
        val buildRoot = File(context.filesDir, "java_build/$key")
        val srcDir = File(buildRoot, "src").apply { mkdirs() }
        val classDir = File(buildRoot, "classes").apply { mkdirs() }
        val outDir = File(buildRoot, "out").apply { mkdirs() }
        val dexFile = File(outDir, "classes.dex")
        if (dexFile.exists()) return@withContext outDir

        val pkg = Regex("""(?m)^\s*package\s+([a-zA-Z0-9_.]+)\s*;""")
            .find(javaCode)?.groupValues?.get(1)?.trim()
        val pkgDir = if (pkg.isNullOrBlank()) srcDir
        else File(srcDir, pkg.replace('.', '/')).apply { mkdirs() }
        val className = Regex("""public\s+class\s+([A-Za-z0-9_]+)""")
            .find(javaCode)?.groupValues?.get(1) ?: "Tool"
        val javaFile = File(pkgDir, "$className.java")
        javaFile.writeText(javaCode)

        // android.jar 放公共目录，所有构建键共享，避免每次重复占用几十 MB
        val androidJar = File(context.filesDir, "java_build/android.jar")
        if (!androidJar.exists()) {
            androidJar.parentFile?.mkdirs()
            context.assets.open("android.jar").use { input ->
                androidJar.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val libDir = File(context.filesDir, "lib").apply { mkdirs() }
        context.assets.list("lib")?.forEach { name ->
            val outF = File(libDir, name)
            if (!outF.exists()) {
                context.assets.open("lib/$name").use { input ->
                    outF.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val JAVAC = "/data/data/com.termux/files/usr/bin/javac"
        val JAR = "/data/data/com.termux/files/usr/bin/jar"
        val JAVA = "/data/data/com.termux/files/usr/bin/java"

        fun summarizeToolError(raw: String, exit: Int, prefix: String): String {
            val text = filterTerminalOutput(raw)
            if (text.isBlank()) return "$prefix (exit $exit)"
            val lines = text.lines()
            // 优先保留包含 error/Exception 的行
            val errorLines = lines.filter { it.contains("error", true) || it.contains("exception", true) }
            val picked = if (errorLines.isNotEmpty()) errorLines else lines
            return buildString {
                append(prefix).append(" (exit ").append(exit).append("):\n")
                append(picked.joinToString("\n").take(800))
            }
        }

        val javacCmd = """
            $JAVAC --release 8 \
            -classpath ${androidJar.absolutePath} \
            -d ${classDir.absolutePath} \
            ${javaFile.absolutePath}
        """.trimIndent()
        herLastExecOutput = ""
        val javacExit = execCommand("cd /data/data/com.termux/files && $javacCmd")
        if (javacExit != 0) {
            throw IllegalStateException(summarizeToolError(herLastExecOutput, javacExit, "javac 编译失败"))
        }

        val jarFile = File(buildRoot, "tmp.jar")
        herLastExecOutput = ""
        val jarExit = execCommand("cd ${classDir.absolutePath} && $JAR cf ${jarFile.absolutePath} .")
        if (jarExit != 0) {
            throw IllegalStateException(summarizeToolError(herLastExecOutput, jarExit, "jar 打包失败"))
        }

        val d8Cmd = """
            $JAVA -cp '${libDir.absolutePath}/*' com.android.tools.r8.D8 \
            ${jarFile.absolutePath} \
            --lib ${androidJar.absolutePath} \
            --min-api 26 \
            --output ${outDir.absolutePath}
        """.trimIndent()
        herLastExecOutput = ""
        val d8Exit = execCommand(d8Cmd)
        if (d8Exit != 0) {
            throw IllegalStateException(summarizeToolError(herLastExecOutput, d8Exit, "d8 转换失败"))
        }
        return@withContext outDir
    }

    /**
     * runJava：改为复用 Activity 编译逻辑。
     *
     * - 不再要求代码里存在 run()/run(Context) 入口方法。
     * - 将源码按 Activity 规范编译为 dex，并返回一段说明文字；真正的启动由对话气泡中的卡片负责。
     * - 为兼容 HerBridge 等调用方，仍保留返回值类型 Any?，但语义是「描述字符串」。
     */
    suspend fun runJava(context: Context, code: String): Any? = withContext(Dispatchers.IO) {
        val build = buildActivityFromJava(code)
        build.message
    }
    /**
     * 将一段 Java Activity 源码编译为 dex，供动态 Activity 启动使用。
     *
     * 不直接启动 Activity，而是返回 dex 路径与入口 Activity 类名，方便在对话气泡中插入卡片，
     * 由用户点击后再通过 StartActivityHook/startDynamicActivity 启动。
     */
    data class JavaActivityBuildResult(
        val message: String,
        val dexPath: String,
        val entryActivity: String,
        val pkgName: String
    )

    suspend fun buildActivityFromJava(code: String): JavaActivityBuildResult = withContext(Dispatchers.IO) {
        val outDir = compileJavaToDex(code)
        val dexPath = File(outDir, "classes.dex").absolutePath
        val fullClassName = extractFullClassName(code)
        val pkgName = fullClassName.substringBeforeLast('.', context.packageName)
        val msg = "已编译 Activity：$fullClassName（点击下方卡片运行界面）"
        JavaActivityBuildResult(
            message = msg,
            dexPath = dexPath,
            entryActivity = fullClassName,
            pkgName = pkgName
        )
    }
    fun sendSms(target: String, text: String): Boolean = runBlockingWithTimeout {

        val ok = HerSystemRuntime.sendSms(
            context,
            target,
            text
        )

        val act = context as? ComponentActivity

        act?.runOnUiThread {

            val msg = if (ok) {
                "短信已发送"
            } else {
                "短信发送失败"
            }

            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        ok
    }

    fun setAlarm(
        hour: Int,
        minute: Int,
        label: String = ""
    ): Boolean {

        val ok = HerSystemRuntime.setAlarm(
            hour,
            minute,
            label
        )

        val act = context as? ComponentActivity

        act?.runOnUiThread {

            val msg = if (ok) {
                "闹钟设置成功"
            } else {
                "闹钟设置失败"
            }

            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        return ok
    }


    fun takePhoto(): String = runBlockingWithTimeout {
        HerSystemRuntime.takePhotoReturnPath(context)
    }
    fun toast(msg: String) {
        // UI 必须主线程
        (context as? ComponentActivity)?.runOnUiThread {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }


}
