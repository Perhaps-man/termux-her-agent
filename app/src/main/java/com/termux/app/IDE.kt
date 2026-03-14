package com.termux.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.termux.shared.models.ExecutionCommand
import com.termux.R
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.text.method.KeyListener
import android.text.method.TextKeyListener
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.zip.ZipInputStream
import java.util.regex.Pattern
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

import com.termux.app.filterTerminalOutput
import com.termux.shared.shell.ShellUtils
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

var herTerminalSession: TerminalSession? = null

/** 单条执行日志记录 */
data class ExecLogEntry(
    val cmd: String,
    val output: String,
    val isSuccess: Boolean,
    val exitCode: Int,
    val timeMs: Long = System.currentTimeMillis()
)

/** 全局执行日志列表，由 WebRenderFragment 写入，由 SimpleExecutorActivity 展示 */
val execLogEntries: MutableList<ExecLogEntry> = mutableListOf()

/** 每当 execLogEntries 更新时调用（在 SimpleExecutorActivity.onCreate 中赋值） */
var onExecLogUpdated: (() -> Unit)? = null

/** AI 执行命令用的多会话池：key 为 exec("sessionId", ...) 的 sessionId */
val herExecSessions: MutableMap<String, HerExecSession> = mutableMapOf()

/** 最近一次 execCommand 的完整输出，供 getLastTerminalLinesStr 读取 */
var herLastExecOutput: String = ""

/** 供 LaunchActivity 等调用：在应用启动时提前初始化终端，以便从启动页退出时也能完成插件构建 */
fun ensureEmbeddedTerminal(context: Context) {
    if (herTerminalSession != null) return
    context.startService(Intent(context, TermuxService::class.java))
    val handler = Handler(Looper.getMainLooper())
    fun waitForService() {
        val svc = TermuxService.instance ?: run {
            handler.postDelayed({ waitForService() }, 50)
            return
        }
        val existing = svc.getTermuxSession(0)?.terminalSession
        if (existing != null) {
            herTerminalSession = existing
            existing.updateSize(80, 24, 10, 20)
            Log.i("TestPoint", "Reused existing session (ensureEmbeddedTerminal)")
        } else {
            val ec = ExecutionCommand(
                0,
                "/system/bin/sh",
                arrayOf("-c", "exec bash || exec sh"),
                null,
                context.filesDir.absolutePath,
                false,
                false
            )
            val termuxSession = svc.createTermuxSession(ec, "Her")
            herTerminalSession = termuxSession?.terminalSession
            herTerminalSession?.updateSize(80, 24, 10, 20)
            Log.i("TestPoint", "Session created (ensureEmbeddedTerminal)")
        }
    }
    waitForService()
}

/** 是否已完成 Termux 环境安装（供启动页与对话页共用） */
fun isTermuxBootstrapped(): Boolean = File("/data/data/com.termux/files/usr/bin/sh").exists()

const val REQ_STORAGE = 10001
const val REQ_NOTIFICATION = 10002

/** 通知权限：供启动页与对话页共用，首次进入 app 即在启动页申请 */
fun requestNotificationPermissionIfNeeded(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }
}

/** 存储/所有文件权限：供启动页与对话页共用，首次进入 app 即在启动页申请 */
fun requestStoragePermissionIfNeeded(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        }
    } else {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val need = perms.any {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need) {
            ActivityCompat.requestPermissions(activity, perms, REQ_STORAGE)
        }
    }
}

private val RE_PKG_INSTALL = Regex("""((?:^|[|;&\n]\s*)pkg\s+install)(?!\s+-y)""")
private val RE_APT_INSTALL = Regex("""((?:^|[|;&\n]\s*)apt(?:-get)?\s+install)(?!\s+-y)""")
private val RE_APT_UPGRADE = Regex("""((?:^|[|;&\n]\s*)apt(?:-get)?\s+(?:upgrade|dist-upgrade|full-upgrade))(?!\s+-y)""")
private val RE_APT_PURGE   = Regex("""((?:^|[|;&\n]\s*)apt(?:-get)?\s+(?:remove|purge|autoremove))(?!\s+-y)""")
private val RE_PIP_INSTALL = Regex("""(pip[0-9.]?\s+install)(?!.*--no-input)(?!.*-q)""")
private val RE_NPM_INIT    = Regex("""(npm\s+init)(?!\s+-y)""")
private val RE_CONDA       = Regex("""(conda\s+(?:install|update|remove|create))(?!\s+-y)""")
// 去掉 rm/cp/mv 的 -i/-I，避免「是否删除/覆盖」类提示卡住导致超时
private val RE_RM_CP_MV_ONLY_I = Regex("""\b(rm|cp|mv)\s+-i\s+""")
private val RE_RM_CP_MV_COMBINED_I = Regex("""\b(rm|cp|mv)\s+(-[a-zA-Z]*)[iI]([a-zA-Z]*)""")

private fun normalizeCommand(cmd: String): String {
    if (cmd.trimStart().startsWith("yes ") || cmd.trimStart().startsWith("yes|")) return cmd
    var r = cmd
    r = RE_PKG_INSTALL.replace(r, "$1 -y")
    r = RE_APT_INSTALL.replace(r, "$1 -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold")
    r = RE_APT_UPGRADE.replace(r, "$1 -y")
    r = RE_APT_PURGE.replace(r, "$1 -y")
    r = RE_PIP_INSTALL.replace(r, "$1 --no-input -q")
    r = RE_NPM_INIT.replace(r, "$1 -y")
    r = RE_CONDA.replace(r, "$1 -y")
    r = RE_RM_CP_MV_ONLY_I.replace(r, "$1 ")
    r = RE_RM_CP_MV_COMBINED_I.replace(r, "$1 $2$3")
    return r
}

private const val TERMUX_HOME   = "/data/data/com.termux/files/home"
private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
private const val TERMUX_BASH   = "$TERMUX_PREFIX/bin/bash"

/** 单条命令无输出超时（ms）——仅当终端「无任何新输出」达到此时长才触发，有输出增长（含 \r 进度）会重置计时 */
private const val EXEC_NO_OUTPUT_TIMEOUT_MS = 60_000L

/** 全局最大执行时间（ms）兜底 */
private const val EXEC_MAX_TOTAL_MS = 3_000_000L

class HerExecSession(val sessionId: String) {

    val process: Process = ProcessBuilder(TERMUX_BASH)
        .directory(File(TERMUX_HOME))
        .apply {
            redirectErrorStream(true) // stderr 合并进 stdout，AI 能看到错误信息
            environment().apply {
                put("HOME",            TERMUX_HOME)
                put("TMPDIR",          "$TERMUX_PREFIX/tmp")
                put("PREFIX",          TERMUX_PREFIX)
                put("TERM",            "xterm-256color")
                put("PATH",            "$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:${get("PATH") ?: ""}")
                put("LD_LIBRARY_PATH", "$TERMUX_PREFIX/lib")
                put("LANG",            "en_US.UTF-8")
                // 非交互模式——覆盖 apt/dpkg/pip/npm/git/conda 所有交互来源
                put("DEBIAN_FRONTEND",          "noninteractive")
                put("APT_LISTCHANGES_FRONTEND",  "none")
                put("APT_LISTBUGS_FRONTEND",     "none")
                put("PIP_NO_INPUT",              "1")
                put("NPM_CONFIG_YES",            "true")
                put("GIT_TERMINAL_PROMPT",       "0")
                put("CONDA_ALWAYS_YES",          "true")
            }
        }
        .start()

    val stdin    = process.outputStream.bufferedWriter(Charsets.UTF_8)
    val rawInput = java.io.InputStreamReader(process.inputStream, Charsets.UTF_8)

    @Volatile var cwd: String = TERMUX_HOME

    val isAlive: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            try {
                process.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }

    fun destroy() {
        runCatching { stdin.close() }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.destroyForcibly()
            } else {
                process.destroy()
            }
        }
    }
}

suspend fun execCommand(
    cmd: String,
    sessionId: String = "default",
    onProgress: ((String) -> Unit)? = null
): Int = withContext(Dispatchers.IO) {

    if (!File(TERMUX_BASH).exists()) {
        herLastExecOutput = ""
        throw RuntimeException("exec 不可用：未安装 Bash，请先在「环境初始化」中安装必须项。")
    }

    val session = synchronized(herExecSessions) {
        val existing = herExecSessions[sessionId]
        if (existing != null && existing.isAlive) existing
        else try {
            HerExecSession(sessionId).also { herExecSessions[sessionId] = it }
        } catch (e: Throwable) {
            herLastExecOutput = ""
            throw RuntimeException("exec 启动失败: ${e.message}，请确认 Termux 环境已就绪。", e)
        }
    }

    val normalizedCmd = normalizeCommand(cmd)
    val token = UUID.randomUUID().toString().replace("-", "").lowercase()
    // Sentinel captures exit code AND current working directory in one printf
    val exitRe = Regex("""__HER_EXIT__(\d+)__CWD__(.+?)__TOKEN__$token""")

    session.stdin.write(normalizedCmd)
    session.stdin.newLine()
    session.stdin.write("printf '__HER_EXIT__%s__CWD__%s__TOKEN__%s\\n' \$? \"\$PWD\" $token")
    session.stdin.newLine()
    session.stdin.flush()

    // 用 Thread + CountDownLatch 做阻塞读取
    val output = StringBuilder()
    val exitCodeHolder = intArrayOf(-1)
    val cwdHolder = arrayOf("")
    val errorHolder = arrayOf<Throwable?>(null)
    val latch = java.util.concurrent.CountDownLatch(1)
    val lastOutputTime = AtomicLong(System.currentTimeMillis())
    val staleReplyCount = AtomicInteger(0)
    val staleReplyIntervalMs = 10_000L
    val maxStaleReplies = 3

    val readerThread = Thread {
        try {
            val charIn = session.rawInput
            val lineBuf = StringBuilder()
            val charBuf = CharArray(4096)
            while (true) {
                val nRead = charIn.read(charBuf)
                if (nRead < 0) break
                // Any bytes received (including \r progress bars) count as activity
                lastOutputTime.set(System.currentTimeMillis())
                for (i in 0 until nRead) {
                    when (val ch = charBuf[i]) {
                        '\n' -> {
                            val trimmed = lineBuf.toString().trimEnd('\r')
                            lineBuf.clear()
                            val m = exitRe.find(trimmed)
                            if (m != null) {
                                exitCodeHolder[0] = m.groupValues[1].toInt()
                                cwdHolder[0] = m.groupValues[2]
                                latch.countDown()
                                return@Thread
                            }
                            if (trimmed.isNotEmpty()) {
                                output.append(trimmed).append('\n')
                                onProgress?.invoke(output.toString())
                            }
                        }
                        // \r-only progress overwrite: discard current partial line,
                        // don't add to output (AI doesn't need transient progress bars)
                        '\r' -> lineBuf.clear()
                        else -> lineBuf.append(ch)
                    }
                }
            }
        } catch (e: Throwable) {
            errorHolder[0] = e
        }
        latch.countDown()
    }.also { it.isDaemon = true; it.start() }

    // 无输出时自动回复：若长时间无新输出则向 stdin 发送 y\n，最多 maxStaleReplies 次，避免确认类提示导致超时
    val staleReplyThread = Thread {
        try {
            while (true) {
                val completed = latch.await(staleReplyIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (completed) break
                if (staleReplyCount.get() >= maxStaleReplies) continue
                if (System.currentTimeMillis() - lastOutputTime.get() > staleReplyIntervalMs) {
                    if (staleReplyCount.incrementAndGet() <= maxStaleReplies) {
                        try {
                            session.stdin.write("y")
                            session.stdin.newLine()
                            session.stdin.flush()
                        } catch (_: Throwable) { }
                    }
                }
            }
        } catch (_: InterruptedException) {
            // 主循环已中断，退出即可
        }
    }.also { it.isDaemon = true; it.start() }

    // 滑动窗口超时：仅当「连续 60s 无任何新输出」才超时；绝对上限 EXEC_MAX_TOTAL_MS
    val execStartAt = System.currentTimeMillis()
    var timedOut = false
    try {
        while (!latch.await(500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            val now = System.currentTimeMillis()
            val noOutputMs = now - lastOutputTime.get()
            val totalMs = now - execStartAt
            if (noOutputMs > EXEC_NO_OUTPUT_TIMEOUT_MS) { timedOut = true; break }
            if (totalMs > EXEC_MAX_TOTAL_MS) { timedOut = true; break }
        }
    } catch (ie: InterruptedException) {
        // 协程被取消时线程收到中断信号，做清理后重新抛出
        Thread.currentThread().interrupt()
        readerThread.interrupt()
        staleReplyThread.interrupt()
        session.destroy()
        synchronized(herExecSessions) { herExecSessions.remove(sessionId) }
        throw ie
    }

    herLastExecOutput = output.toString()
    if (cwdHolder[0].isNotBlank()) session.cwd = cwdHolder[0]

    if (timedOut) {
        readerThread.interrupt()
        staleReplyThread.interrupt()
        session.destroy()
        synchronized(herExecSessions) { herExecSessions.remove(sessionId) }
        val noOutSec = (System.currentTimeMillis() - lastOutputTime.get()) / 1000
        throw RuntimeException("Command timeout（${noOutSec}s 无输出）:\n$cmd")
    }
    errorHolder[0]?.let {
        session.destroy()
        synchronized(herExecSessions) { herExecSessions.remove(sessionId) }
        throw RuntimeException("exec 读取异常: ${it.message}", it)
    }
    exitCodeHolder[0]
}

/** 解析附件路径并读取文件：若原路径不存在则尝试 home/ 与根目录互换（Termux 工作目录可能不同） */
private fun resolveAndReadFile(context: Context, path: String): String? {
    val candidates = mutableListOf<String>()
    candidates.add(path)
    val base = "/data/data/com.termux/files"
    if (path.startsWith("$base/home/")) {
        candidates.add(path.replace("$base/home/", "$base/"))
    } else if (path.startsWith("$base/") && !path.startsWith("$base/home/")) {
        candidates.add(path.replace("$base/", "$base/home/"))
    }
    candidates.add(File(context.filesDir, File(path).name).absolutePath)
    for (p in candidates) {
        runCatching {
            val f = File(p)
            if (f.isFile) return f.readText(Charsets.UTF_8)
        }
    }
    return null
}

/** 按 MIME/类型从 Uri 提取可读预览（前 maxChars 字）：纯文本安全解码，图片/二进制返回说明，PDF/DOCX/XLSX 提取文本 */
private fun extractPreviewFromUri(context: Context, uri: Uri, pathOrName: String, maxChars: Int): String {
    val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: ""
    val ext = pathOrName.substringAfterLast('.').lowercase(Locale.ROOT)

    fun isTextMime(): Boolean = mime.startsWith("text/") || mime in setOf(
        "application/json", "application/xml", "application/javascript"
    )
    fun isTextExt(): Boolean = ext in setOf(
        "txt", "md", "json", "xml", "csv", "log", "py", "js", "ts", "kt", "java", "html", "htm", "css", "sh", "yml", "yaml"
    )

    return when {
        isTextMime() || isTextExt() -> readTextPreviewFromStream(
            context.contentResolver.openInputStream(uri), maxChars
        )
        mime.startsWith("image/") -> "（图片文件，未提取文本）"
        mime == "application/pdf" || ext == "pdf" -> "（PDF 文件，未提取文本，可将文字复制后粘贴到输入框）"
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || ext == "docx" ->
            context.contentResolver.openInputStream(uri)?.use { extractDocxText(it, maxChars) } ?: "（DOCX 读取失败）"
        mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || ext == "xlsx" ->
            context.contentResolver.openInputStream(uri)?.use { extractXlsxText(it, maxChars) } ?: "（XLSX 读取失败）"
        mime == "application/msword" || ext == "doc" ->
            "（旧版 .doc 格式，建议另存为 .docx 后重试）"
        mime == "application/vnd.ms-excel" || ext == "xls" ->
            "（旧版 .xls 格式，建议另存为 .xlsx 后重试）"
        else -> "（二进制文件，未提取文本）"
    }
}

/** 纯文本：UTF-8 安全解码，遇非法字符替换，取前 maxChars 字 */
private fun readTextPreviewFromStream(ins: InputStream?, maxChars: Int): String {
    if (ins == null) return ""
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val bytes = ins.readBytes()
    return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().take(maxChars) }
        .getOrElse { "" }
}

/** DOCX 为 ZIP，正文在 word/document.xml，提取 <w:t> 内文本 */
private fun extractDocxText(ins: InputStream, maxChars: Int): String {
    val text = buildString {
        ZipInputStream(ins).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = String(zis.readBytes(), Charsets.UTF_8)
                    val p = Pattern.compile("<w:t[^>]*>([^<]*)</w:t>")
                    val m = p.matcher(xml)
                    while (m.find()) append(m.group(1))
                    break
                }
                entry = zis.nextEntry
            }
        }
    }
    return text.take(maxChars)
}

/** XLSX 为 ZIP，字符串在 xl/sharedStrings.xml，<t> 或 <si><r><t> 内文本 */
private fun extractXlsxText(ins: InputStream, maxChars: Int): String {
    val text = buildString {
        ZipInputStream(ins).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "xl/sharedStrings.xml") {
                    val xml = String(zis.readBytes(), Charsets.UTF_8)
                    val p = Pattern.compile("<t[^>]*>([^<]*)</t>")
                    val m = p.matcher(xml)
                    while (m.find()) append(m.group(1)).append(" ")
                    break
                }
                entry = zis.nextEntry
            }
        }
    }
    return text.take(maxChars)
}

class SimpleExecutorActivity : AppCompatActivity(), AskForHelpReplyReceiver {
    /** askForHelp 等待用户输入时由 waitForUserReply 创建，sendChatMessage 写入后置空 */
    var askForHelpChannel: Channel<String>? = null

    override suspend fun waitForUserReply(): String {
        withContext(Dispatchers.Main) {
            askForHelpChannel = Channel(1)
            setSendIcon()
        }
        val reply = askForHelpChannel!!.receive()
        withContext(Dispatchers.Main) {
            askForHelpChannel = null
            setPauseIcon()
        }
        return reply
    }

    private lateinit var execLogList: RecyclerView
    private lateinit var tvExecLogEmpty: TextView
    private lateinit var execLogAdapter: ExecLogAdapter
    private lateinit var logPanel: View
    private lateinit var editorContainer: View
    private var logVisible = false
    private lateinit var mainContainer: View
    private lateinit var chatList: RecyclerView
    private lateinit var chatAdapter: HerChatAdapter
    private lateinit var inputEdit: EditText
    private lateinit var sendButton: ImageButton
    private var frameOverlay: View? = null
    private var currentTaskJob: Job? = null
    private var hasWebViewInCurrentTask: Boolean = false
    // 对话级摘要：用于压缩很早的用户/AI 历史，对模型作为高层上下文
    private var dialogSummary: String? = null
    // 已经被摘要覆盖的历史聊天泡数量（按过滤后的文本泡计数），避免重复压缩
    private var dialogCompactedCount: Int = 0
    private var currentSessionId: String = ""
    private lateinit var agentHelper: EnhancedAgentHelper
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListAdapter: SessionListAdapter
    /** 已选多个文件：(Uri, 用于展示的路径/名称) */
    private val selectedFiles = mutableListOf<Pair<Uri, String>>()
    private lateinit var fileStrip: View
    private lateinit var fileStripName: TextView
    private lateinit var fileStripMeta: TextView

    companion object {
        private const val REQUEST_PICK_FILE = 1001
        private const val MAX_FILE_PREVIEW_CHARS = 1000
    }

    private fun launchTermuxOnce() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.termux",
                "com.termux.app.TermuxActivity" // 你实际用的那个 Activity
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun initEmbeddedTerminal() {
        if (herTerminalSession != null) {
            Log.i("TestPoint", "Terminal already initialized, skip")
            return
        }
        startService(Intent(this, TermuxService::class.java))
        val handler = Handler(Looper.getMainLooper())

        fun waitForService() {
            val svc = TermuxService.instance ?: run {
                handler.postDelayed({ waitForService() }, 50)
                return
            }
            val existing = svc.getTermuxSession(0)?.terminalSession
            if (existing != null) {
                herTerminalSession = existing
                existing.updateSize(80, 24, 10, 20)
                Log.i("TestPoint", "Reused existing session (initEmbeddedTerminal)")
            } else {
                val ec = ExecutionCommand(
                    0,
                    "/system/bin/sh",
                    arrayOf("-c", "exec bash || exec sh"),
                    null,
                    filesDir.absolutePath,
                    false,
                    false
                )
                val termuxSession = svc.createTermuxSession(ec, "Her")
                herTerminalSession = termuxSession?.terminalSession
                herTerminalSession?.updateSize(80, 24, 10, 20)
                Log.e("TestPoint", "Session created and started (emulator initialized)")
            }
        }

        waitForService()
    }



    fun switchTo(fragment: Fragment) {
        frameOverlay?.let { overlay ->
            supportFragmentManager.beginTransaction()
                .replace(R.id.frameOverlay, fragment)
                .commit()
            overlay.visibility = View.VISIBLE
        }
    }

    fun hideFragmentOverlay() {
        frameOverlay?.visibility = View.GONE
    }


    fun showLogPanel() {
        val editorLp = editorContainer.layoutParams as LinearLayout.LayoutParams
        val logLp = logPanel.layoutParams as LinearLayout.LayoutParams

        editorLp.height = 0
        editorLp.weight = 0.65f

        logLp.height = 0
        logLp.weight = 0.35f

        editorContainer.layoutParams = editorLp
        logPanel.layoutParams = logLp

        logPanel.alpha = 1f
        logVisible = true
    }

    private fun hideLogPanel() {
        val editorLp = editorContainer.layoutParams as LinearLayout.LayoutParams
        val logLp = logPanel.layoutParams as LinearLayout.LayoutParams

        editorLp.height = 0
        editorLp.weight = 1f

        logLp.height = 0
        logLp.weight = 0f

        editorContainer.layoutParams = editorLp
        logPanel.layoutParams = logLp

        logPanel.alpha = 0f
        logVisible = false
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSession()
    }

    override fun onStop() {
        super.onStop()
        // 再次兜底保存，防止仅触发 onStop 而用户认为已退出
        saveCurrentSession()
    }

    override fun onBackPressed() {
        // 用户显式返回时优先落盘当前对话
        saveCurrentSession()
        super.onBackPressed()
    }

    private fun saveCurrentSession() {
        val items = chatAdapter.getItems()
        if (items.isEmpty() || currentSessionId.isEmpty()) return
        val title = items.firstOrNull { it.isUser }?.content?.take(30) ?: "新会话"
        saveSessionChat(this, currentSessionId, items, title)
        saveCurrentSessionState()
    }

    private fun saveCurrentSessionState() {
        if (currentSessionId.isBlank()) return
        saveSessionState(
            this,
            currentSessionId,
            SessionStateSave(
                dialogSummary = dialogSummary,
                dialogCompactedCount = dialogCompactedCount
            )
        )
    }

    private fun loadSession(sessionId: String) {
        currentSessionId = sessionId
        setCurrentSessionId(this, sessionId)
        val list = loadSessionChat(this, sessionId)
        chatAdapter.setItems(list ?: emptyList())
        val state = loadSessionState(this, sessionId)
        dialogSummary = state?.dialogSummary
        dialogCompactedCount = state?.dialogCompactedCount ?: 0
        scrollChatToEnd()
    }

    private fun switchToSession(meta: SessionMeta) {
        if (currentSessionId == meta.id) {
            drawerLayout.closeDrawers()
            return
        }
        saveCurrentSession()
        currentTaskJob?.cancel()
        currentTaskJob = null
        chatAdapter.stopAllRunning()
        loadSession(meta.id)
        drawerLayout.closeDrawers()
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout)
        findViewById<ImageButton>(R.id.btnDrawerMenu).setOnClickListener {
            drawerLayout.openDrawer(android.view.Gravity.START)
        }
        val drawerRoot = findViewById<View>(R.id.drawerSessions)
        val sessionRecycler = drawerRoot.findViewById<RecyclerView>(R.id.sessionList)
        val btnNewSession = drawerRoot.findViewById<ImageButton>(R.id.btnNewSession)
        sessionListAdapter = SessionListAdapter(
            sessions = listSessions(this).toMutableList(),
            onSessionClick = { switchToSession(it) },
                onSessionLongClick = { meta ->
                AlertDialog.Builder(this)
                    .setTitle("删除会话")
                    .setMessage("确定删除「${meta.title}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        val isCurrent = currentSessionId == meta.id
                        deleteSession(this, meta.id)
                        val rest = listSessions(this)
                        sessionListAdapter.setItems(rest)
                        if (isCurrent) {
                            currentTaskJob?.cancel()
                            currentTaskJob = null
                            chatAdapter.stopAllRunning()
                            if (rest.isNotEmpty()) {
                                loadSession(rest.first().id)
                            } else {
                                currentSessionId = createNewSession(this)
                                chatAdapter.setItems(emptyList())
                            }
                        }
                        drawerLayout.closeDrawers()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        )
        sessionRecycler.layoutManager = LinearLayoutManager(this)
        sessionRecycler.adapter = sessionListAdapter
        btnNewSession.setOnClickListener {
            saveCurrentSession()
            currentTaskJob?.cancel()
            currentTaskJob = null
            chatAdapter.stopAllRunning()
            currentSessionId = createNewSession(this)
            chatAdapter.setItems(emptyList())
            dialogSummary = null
            dialogCompactedCount = 0
            saveCurrentSessionState()
            sessionListAdapter.setItems(listSessions(this))
            drawerLayout.closeDrawers()
        }
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerOpened(drawerView: View) { refreshSessionList() }
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
        // 让抽屉底部延伸进导航栏区域，避免新建会话按钮下方出现白条
        ViewCompat.setOnApplyWindowInsetsListener(drawerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            drawerRoot.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        drawerRoot.requestApplyInsets()
    }

    private fun refreshSessionList() {
        if (::sessionListAdapter.isInitialized) {
            sessionListAdapter.setItems(listSessions(this))
        }
    }

    private var resumedOnce = false
    private var waitingTermuxInit = false
    override fun onResume() {
        super.onResume()

        if (waitingTermuxInit && isTermuxBootstrapped()) {
            waitingTermuxInit = false
            if (herTerminalSession == null) {
                initEmbeddedTerminal()
            }
            resumedOnce = true
        }
    }





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ide)
        // 边到边显示：状态栏透明，内容延伸到刘海/水滴屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom + ime.bottom)
            insets
        }
        root.requestApplyInsets()
        // 亮色背景，状态栏图标用深色
        WindowCompat.getInsetsController(window, root)?.isAppearanceLightStatusBars = true
        startService(Intent(this, TermuxService::class.java))
        agentHelper = EnhancedAgentHelper(this)
        logPanel = findViewById(R.id.logPanel)
        mainContainer = findViewById(R.id.mainContainer)
        editorContainer = findViewById(R.id.editorContainer)
        frameOverlay = findViewById(R.id.frameOverlay)
        HerSystemRuntime.bind(this)

        // 执行日志 RecyclerView
        execLogList = findViewById(R.id.execLogList)
        tvExecLogEmpty = findViewById(R.id.tvExecLogEmpty)
        execLogAdapter = ExecLogAdapter(execLogEntries)
        execLogList.layoutManager = LinearLayoutManager(this)
        execLogList.adapter = execLogAdapter
        onExecLogUpdated = {
            runOnUiThread {
                execLogAdapter.notifyDataSetChanged()
                if (execLogEntries.isEmpty()) {
                    execLogList.visibility = View.GONE
                    tvExecLogEmpty.visibility = View.VISIBLE
                } else {
                    execLogList.visibility = View.VISIBLE
                    tvExecLogEmpty.visibility = View.GONE
                    execLogList.scrollToPosition(execLogEntries.size - 1)
                }
            }
        }
        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            execLogEntries.clear()
            onExecLogUpdated?.invoke()
        }

        requestStoragePermissionIfNeeded(this)
        requestNotificationPermissionIfNeeded(this)
        if (!isTermuxBootstrapped()) {
            waitingTermuxInit = true
            launchTermuxOnce()
        } else {
            initEmbeddedTerminal()
            resumedOnce = true
        }

        // 聊天列表
        chatList = findViewById(R.id.chatList)
        chatAdapter = HerChatAdapter(
            onWebCardClick = { html ->
                // 通过 Intent extra 传递 HTML，避免依赖静态字段导致进程重建后点击卡片无效/空白
                startActivity(
                    Intent(this, FullScreenWebActivity::class.java).putExtra(
                        FullScreenWebActivity.EXTRA_HTML,
                        html
                    )
                )
            },
            onDepStoreCardClick = { packages ->
                startActivity(Intent(this, DepStoreActivity::class.java).apply {
                    putStringArrayListExtra(DepStoreActivity.EXTRA_PREFILL_PACKAGES, ArrayList(packages))
                })
            }
        )
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = chatAdapter
        currentSessionId = getCurrentSessionId(this)
        loadSession(currentSessionId)

        // 仅在 Python 内调用 loadHtml 时在聊天列表中加入网页预览气泡
        WebRenderManager.onLoadHtml = { html ->
            hasWebViewInCurrentTask = true
            chatAdapter.addWebBubble(html)
            scrollChatToEnd()
        }

        inputEdit = findViewById(R.id.inputEdit)
        sendButton = findViewById(R.id.sendButton)
        fileStrip = findViewById(R.id.fileStrip)
        fileStripName = findViewById(R.id.fileStripName)
        fileStripMeta = findViewById(R.id.fileStripMeta)
        findViewById<ImageButton>(R.id.fileStripCancel).setOnClickListener { clearAttachment() }
        findViewById<ImageButton>(R.id.btnAddFile).setOnClickListener { openFilePicker() }
        setSendIcon()
        sendButton.setOnClickListener {
            if (askForHelpChannel != null) {
                sendChatMessage()
                return@setOnClickListener
            }
            val job = currentTaskJob
            if (job == null || job.isCompleted || job.isCancelled) {
                sendChatMessage()
            } else {
                // 立即尝试中断当前所有执行：取消协程 + 向 shell 发送 Ctrl-C
                herTerminalSession?.write("\u0003")
                job.cancel()
            }
        }
        inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else false
        }

        setupDrawer()
        findViewById<View>(R.id.btnLog).setOnClickListener { showLogPanel() }
        findViewById<View>(R.id.btnCloseLog).setOnClickListener { hideLogPanel() }
        val dragHandle = findViewById<View>(R.id.logBar)
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            var lastY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { lastY = event.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - lastY
                        lastY = event.rawY
                        val parentHeight = mainContainer.height
                        val editorLp = editorContainer.layoutParams as LinearLayout.LayoutParams
                        val logLp = logPanel.layoutParams as LinearLayout.LayoutParams
                        val deltaWeight = dy / parentHeight
                        editorLp.weight = (editorLp.weight + deltaWeight).coerceIn(0.2f, 0.85f)
                        logLp.weight = 1f - editorLp.weight
                        editorContainer.requestLayout()
                        logPanel.requestLayout()
                        return true
                    }
                }
                return false
            }
        })

        // 一进入对话页立即检测依赖：必须项未装齐则直接跳转依赖商店（精简模式），不再弹窗
        lifecycleScope.launch {
            if (!isTermuxBootstrapped()) return@launch
            val missing = getMissingRecommendedDeps(this@SimpleExecutorActivity)
            val requiredMissing = missing.filter { it.required }
            if (requiredMissing.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                startActivity(
                    android.content.Intent(this@SimpleExecutorActivity, DepStoreActivity::class.java).apply {
                        putExtra(DepStoreActivity.EXTRA_REQUIRED_ONLY, true)
                    }
                )
            }
        }
    }

    private fun setSendIcon() {
        sendButton.setImageResource(R.drawable.ic_send_rounded)
    }

    private fun setPauseIcon() {
        sendButton.setImageResource(R.drawable.ic_stop)
    }

    private fun sendChatMessage() {
        val text = inputEdit.text.toString().trim()
        if (text.isEmpty()) return
        if (askForHelpChannel != null) {
            inputEdit.text.clear()
            clearAttachment()
            chatAdapter.addUserMessage(text)
            scrollChatToEnd()
            if (incrementUserMessageCountAndCheckTrigger(this)) {
                ensureEmbeddedTerminal(this)
                startService(Intent(this, PluginBuildService::class.java))
                android.widget.Toast.makeText(this, "已触发自动编译（每${getAutoBuildFreq(this)}条）", android.widget.Toast.LENGTH_SHORT).show()
            }
            lifecycleScope.launch { askForHelpChannel?.send(text); askForHelpChannel = null }
            return
        }
        if (currentTaskJob != null && !currentTaskJob!!.isCompleted && !currentTaskJob!!.isCancelled) {
            return
        }
        hasWebViewInCurrentTask = false
        val filesForPrompt = selectedFiles.toList()
        clearAttachment()
        inputEdit.text.clear()
        // 对话界面：用户泡只显示输入文本，文件用卡片贴在下方展示
        chatAdapter.addUserMessage(text)
        filesForPrompt.forEach { (uri, pathOrName) ->
            chatAdapter.addAttachmentCard(queryFileName(uri) ?: pathOrName, pathOrName)
        }
        scrollChatToEnd()
        saveCurrentSession() // 立即持久化用户消息 + 文件卡片（SessionChatItemSave 已含 attachmentPath/attachmentLabel）
        // 后台发给 AI 的 prompt 仍带文件路径+内容预览
        val effectiveInput = if (filesForPrompt.isNotEmpty()) {
            buildString {
                filesForPrompt.forEach { (uri, pathOrName) ->
                    append("【文件】")
                    append(pathOrName)
                    append("\n【内容预览（前1000字）】\n")
                    append(extractPreviewFromUri(this@SimpleExecutorActivity, uri, pathOrName, MAX_FILE_PREVIEW_CHARS))
                    append("\n\n")
                }
                append(text)
            }
        } else text
        setPauseIcon()

        if (incrementUserMessageCountAndCheckTrigger(this)) {
            ensureEmbeddedTerminal(this)
            startService(Intent(this, PluginBuildService::class.java))
            android.widget.Toast.makeText(this, "已触发自动编译（每${getAutoBuildFreq(this)}条）", android.widget.Toast.LENGTH_SHORT).show()
        }

        currentTaskJob = lifecycleScope.launch {

            data class StepRecord(
                val round: Int,
                val mark: String,
                val description: String,
                val actionType: String,
                val fullResult: String   // 最多存 500 字，截断由显示层决定
            )

            fun buildStepHistoryText(records: List<StepRecord>, currentRound: Int): String? {
                if (records.isEmpty()) return null
                val sb = StringBuilder()
                records.forEach { step ->
                    val age = currentRound - step.round
                    val budget = when {
                        age <= 2 -> 250
                        age <= 7 -> 80
                        else     -> 0
                    }
                    sb.append("${step.mark} ${step.round + 1}. ${step.description.take(80)}")
                    if (step.actionType.isNotBlank()) sb.append(" [${step.actionType}]")
                    if (budget > 0 && step.fullResult.isNotBlank())
                        sb.append(" → ${step.fullResult.replace('\n', ' ').take(budget)}")
                    sb.append("\n")
                }
                val raw = sb.toString()
                return if (raw.length > 4000) "…(早期步骤已省略)\n" + raw.takeLast(3800) else raw
            }

            fun buildFailureHint(failureLog: List<String>): String? {
                if (failureLog.isEmpty()) return null
                return if (failureLog.size >= 3)
                    "⚠️ 已连续失败 ${failureLog.size} 次，不要重试相同方法，必须换一个完全不同的方向。\n近期失败:\n" +
                        failureLog.takeLast(3).joinToString("\n")
                else
                    "上一步失败：${failureLog.last()}，必须先修复此问题再继续。"
            }

            val stepRecords = mutableListOf<StepRecord>()
            val maxRounds = 30                        // 从 20 提升至 30
            var consecutiveFailures = 0
            val failureLog = mutableListOf<String>()  // 滚动窗口，最多 5 条
            var lastStepDescription: String? = null
            var cachedDialogHistory: String? = null
            try {
                for (round in 0 until maxRounds) {
                    if (round == 0) {
                        val (history, updatedSummary) = buildDialogHistoryWithSummaryForPrompt()
                        cachedDialogHistory = history
                        if (!updatedSummary.isNullOrBlank()) {
                            dialogSummary = updatedSummary
                            saveCurrentSessionState()
                        }
                    }
                    val stepIndex = chatAdapter.addAiMessage("思考中", result = null, running = true)
                    scrollChatToEnd()
                    val prompt = agentHelper.buildEnhancedPrompt(
                        sessionId = currentSessionId,
                        userInput = effectiveInput,
                        dialogHistory = cachedDialogHistory,
                        stepHistory = buildStepHistoryText(stepRecords, round),
                        currentStepQuery = lastStepDescription,
                        failureHint = buildFailureHint(failureLog)
                    )
                    val thinkStart = System.currentTimeMillis()
                    val thinkingTicker = lifecycleScope.launch(Dispatchers.Main) {
                        var sec = 0
                        while (true) {
                            chatAdapter.updateAiAt(stepIndex, content = "思考中 ${sec}s", running = true)
                            delay(1000)
                            sec++
                        }
                    }
                    val reply = try {
                        callAIAgent(this@SimpleExecutorActivity, buildActionToolsPrompt(this@SimpleExecutorActivity), prompt)
                    } finally {
                        thinkingTicker.cancel()
                    }
                    val thinkSecs = ((System.currentTimeMillis() - thinkStart + 999) / 1000).toInt()
                    chatAdapter.updateAiAt(stepIndex, content = "思考中 ${thinkSecs}s", running = true)
                    Log.d("TestPoint", "Round ${round + 1} reply: $reply")
                    if (reply.startsWith("Error:")) {
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@SimpleExecutorActivity,
                                "模型或 API Key 错误，请检查抽屉内 AI 配置",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        chatAdapter.updateAiAt(stepIndex, result = reply, running = false)
                        scrollChatToEnd()
                        break
                    }
                    var parsed = parseStepReply(reply)
                    if (parsed == null) {
                        val retryPrompt = "$prompt\n⚠️ 上次输出格式错误，请只输出数组 [\"步骤描述\", 动作表达式]，不要有任何多余文字。"
                        val retryStart = System.currentTimeMillis()
                        val retryTicker = lifecycleScope.launch(Dispatchers.Main) {
                            var sec = 0
                            while (true) {
                                chatAdapter.updateAiAt(stepIndex, content = "思考中 ${sec}s", running = true)
                                delay(1000)
                                sec++
                            }
                        }
                        val retryReply = try {
                            callAIAgent(this@SimpleExecutorActivity, buildActionToolsPrompt(this@SimpleExecutorActivity), retryPrompt)
                        } finally {
                            retryTicker.cancel()
                        }
                        val retrySecs = ((System.currentTimeMillis() - retryStart + 999) / 1000).toInt()
                        chatAdapter.updateAiAt(stepIndex, content = "思考中 ${retrySecs}s", running = true)
                        parsed = parseStepReply(retryReply)
                    }
                    if (parsed == null) {
                        chatAdapter.updateAiAt(stepIndex, result = "（无法解析，已重试）", running = false)
                        scrollChatToEnd()
                        break
                    }
                    if (parsed.isMessage) {
                        val raw = parsed.directContent ?: parsed.description
                        var text = parsed.description
                        try {
                            val obj = JSONObject(raw)
                            text = obj.optString("text", parsed.description)
                            val attachments = obj.optJSONArray("attachments")
                            if (attachments != null) {
                                for (i in 0 until attachments.length()) {
                                    val path = attachments.optString(i) ?: continue
                                    Log.d("TestPoint", "message attachment path: $path")
                                    when {
                                        path.endsWith(".html", ignoreCase = true) ||
                                            path.endsWith(".htm", ignoreCase = true) -> {
                                            val html = resolveAndReadFile(this@SimpleExecutorActivity, path)
                                            if (html != null) {
                                                Log.d("TestPoint", "loaded html length=${html.length} from attachment")
                                                WebRenderManager.loadHtml(html)
                                            } else {
                                                Log.e("TestPoint", "load html from attachment failed: $path")
                                            }
                                        }
                                        path.endsWith(".pdf", ignoreCase = true) ||
                                            path.endsWith(".doc", ignoreCase = true) ||
                                            path.endsWith(".docx", ignoreCase = true) ||
                                            path.endsWith(".txt", ignoreCase = true) -> {
                                            val name = File(path).name
                                            chatAdapter.addAttachmentCard(name, path)
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("TestPoint", "parse message json failed: $raw", e)
                            text = raw
                        }
                        chatAdapter.updateAiAt(stepIndex, content = text, result = null, running = false)
                        scrollChatToEnd()
                        break
                    }
                    chatAdapter.updateAiAt(stepIndex, content = parsed.description, result = null, running = true)
                    scrollChatToEnd()
                    lastStepDescription = parsed.description
                    val runResult = agentHelper.executeAndRemember(currentSessionId, parsed)
                    val filteredResult = filterTerminalOutput(runResult.result)
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val resStr = "[$ts] $filteredResult"
                    val statusMark = if (runResult.isSuccess) "✓" else "✗"
                    val actionType = parsed.actionLine.substringBefore("(").take(12)

                    stepRecords.add(StepRecord(round, statusMark, parsed.description, actionType,
                        filteredResult.take(500)))

                    if (runResult.isSuccess) {
                        consecutiveFailures = 0
                        failureLog.clear()
                    } else {
                        consecutiveFailures++
                        failureLog.add("步骤${round + 1}「${parsed.description.take(40)}」: ${filteredResult.take(200)}")
                        if (failureLog.size > 5) failureLog.removeAt(0)
                        if (consecutiveFailures >= 6) {
                            chatAdapter.updateAiAt(stepIndex, content = parsed.description,
                                result = "连续失败 6 次，任务中止。最后错误: $filteredResult", running = false)
                            scrollChatToEnd()
                            break
                        }
                    }
                    var shouldStopAfterDeps = false
                    if (runResult.showResultOnBubble) {
                        chatAdapter.updateAiAt(stepIndex, content = parsed.description, result = resStr, running = false)
                    } else {
                        chatAdapter.updateAiAt(stepIndex, content = parsed.description, result = null, running = false)
                    }
                    runResult.depStorePackages?.let { pkgs ->
                        chatAdapter.addDepStoreCard(pkgs)
                        // 弹出依赖卡片后终止本次执行链，交由用户决定是否安装依赖再继续
                        shouldStopAfterDeps = true
                    }
                    if (parsed.actionLine.startsWith("runJava")) {
                        runResult.pluginLaunchInfo?.let { info ->
                            chatAdapter.addPluginCard(
                                title = "运行 ${info.entryActivity}",
                                dexPath = info.dexPath,
                                entryActivity = info.entryActivity,
                                pkgName = info.pkgName
                            )
                        }
                    }
                    scrollChatToEnd()
                    if (shouldStopAfterDeps) break
                }
            } catch (e: CancellationException) {
                chatAdapter.stopAllRunning("（已暂停）")
                throw e
            } catch (e: Exception) {
                chatAdapter.stopAllRunning("错误: ${e.message}")
            } finally {
                chatAdapter.stopAllRunning()
                scrollChatToEnd()
                setSendIcon()
                currentTaskJob = null
                synchronized(herExecSessions) {
                    val toRemove = herExecSessions.entries.filter { it.key != "default" }
                    toRemove.forEach { (_, session) ->
                        try { session.destroy() } catch (_: Throwable) {}
                    }
                    toRemove.forEach { herExecSessions.remove(it.key) }
                }
            }
        }
    }

    private fun scrollChatToEnd() {
        if (chatAdapter.itemCount > 0) {
            chatList.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_PICK_FILE)
    }

    private fun clearAttachment() {
        selectedFiles.clear()
        fileStrip.visibility = View.GONE
    }

    /**
     * 构造对话上下文：
     * - 使用最近 10 条「用户/AI」气泡作为详细历史（过滤网页和附件）；
     * - 若历史超过 10 条，则把更早的对话交给 AI 压缩成摘要，并与已有摘要合并；
     * 返回：
     * - dialogHistory: 要塞进提示词的历史文本（摘要 + 最近 10 条）；
     * - newSummary: 更新后的摘要（可能为空）。
     */
    private suspend fun buildDialogHistoryWithSummaryForPrompt(): Pair<String?, String?> {
        val items = chatAdapter.getItems()
        if (items.size <= 1) return null to dialogSummary

        // 如果最后一条是“当前用户输入”，则只取它之前的历史；
        // 如果最后一条是 AI 泡（例如本轮内部刚刚执行完的步骤），则保留到最后一条，让模型能看到最近一步的描述和结果。
        val historySource = if (items.lastOrNull()?.isUser == true) {
            items.dropLast(1)
        } else {
            items
        }

        val allHistoryItems = historySource
            .filter { !it.isWebBlock() && !it.isAttachmentBlock()}

        if (allHistoryItems.isEmpty()) return null to dialogSummary

        var newSummary = dialogSummary

        // 以 10 条为一个分段，对「还未摘要过」的最早部分做增量压缩：
        // 每多出 10 条（相对于 dialogCompactedCount），就触发一次压缩，并累加计数，避免重复摘要。
        while (allHistoryItems.size - dialogCompactedCount > 10) {
            val end = dialogCompactedCount + 10
            if (end > allHistoryItems.size) break
            val segment = allHistoryItems.subList(dialogCompactedCount, end)
            newSummary = compactDialogHistoryWithAI(newSummary, segment)
            dialogCompactedCount = end
        }

        // 最近 10 条聊天泡，作为详细上下文
        val recentItems = allHistoryItems.takeLast(6)

        val lastAiIndex = recentItems.indexOfLast { !it.isUser }
        val recentText = recentItems.mapIndexed { index, item ->
            if (item.isUser) {
                "用户: ${item.content}"
            } else {
                val base = "AI: ${item.content}"
                val result = item.result?.takeIf { it.isNotBlank() } ?: return@mapIndexed base
                // 最后一个 AI 气泡保留完整结果，较早的截断到 150 字避免 token 膨胀
                val resultText = if (index == lastAiIndex) result else result.take(150).let {
                    if (result.length > 150) "$it…" else it
                }
                "$base\n执行结果: $resultText"
            }
        }.joinToString("\n")

        val dialogHistory = buildString {
            if (!newSummary.isNullOrBlank()) {
                append("【更早历史摘要】\n")
                append(newSummary.trim())
                append("\n\n")
            }
            append(recentText)
        }.trim()

        return dialogHistory.ifBlank { null } to newSummary
    }

    /**
     * 使用同一个大模型对更早的对话历史做一次压缩摘要。
     * - existingSummary：之前已有的摘要（可以为空）
     * - itemsToCompact：本次需要压缩的若干聊天泡（用户/AI）
     */
    private suspend fun compactDialogHistoryWithAI(
        existingSummary: String?,
        itemsToCompact: List<HerChatItem>
    ): String? {
        if (itemsToCompact.isEmpty()) return existingSummary

        val prompt = buildString {
            append("你是一个对话历史压缩器，只负责把下面的用户/AI 对话压缩成简短中文摘要，方便后续步骤快速了解上下文。\n")
            append("要求：\n")
            append("- 保留任务目标、关键决策、重要中间产物和失败教训；\n")
            append("- 必须完整保留所有文件路径、文件名和关键代码片段，不得省略或替换为「某文件」；\n")
            append("- 不要重复细节日志；\n")
            append("- 控制在约 400 字以内；\n")
            append("- 不要使用 markdown，只输出纯文本；\n")
            append("- 可以用条目或短句分行。\n\n")

            if (!existingSummary.isNullOrBlank()) {
                append("【已有摘要】\n")
                append(existingSummary.trim())
                append("\n\n")
            }

            append("【本次需要压缩的对话】\n")
            itemsToCompact.forEach { item ->
                if (item.isUser) {
                    append("用户: ")
                    append(item.content)
                } else {
                    append("AI: ")
                    append(item.content)
                    val resultText = item.result?.takeIf { it.isNotBlank() }
                    if (resultText != null) {
                        append(" （执行结果: ")
                        append(resultText)
                        append("）")
                    }
                }
                append("\n")
            }
            append("\n请给出新的整体摘要：\n")
        }

        return try {
            val reply = callAI(this@SimpleExecutorActivity, prompt)
            reply.trim().take(4000).ifBlank { existingSummary }
        } catch (_: Throwable) {
            existingSummary
        }
    }

    private fun updateFileStrip(files: List<Pair<Uri, String>>) {
        selectedFiles.clear()
        selectedFiles.addAll(files)
        if (files.isEmpty()) {
            fileStrip.visibility = View.GONE
            return
        }
        fileStripName.text = "已选 ${files.size} 个文件"
        fileStripMeta.text = files.take(3).map { (uri, _) -> queryFileName(uri) ?: "文件" }.joinToString("、") +
            if (files.size > 3) " …" else ""
        fileStrip.visibility = View.VISIBLE
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun queryFileMeta(uri: Uri): String {
        var type = "文件"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val typeIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        val mime = contentResolver.getType(uri)
        if (!mime.isNullOrEmpty()) {
            type = when {
                mime.startsWith("text") -> "文本"
                mime.startsWith("image") -> "图片"
                mime.startsWith("audio") -> "音频"
                mime.startsWith("video") -> "视频"
                else -> mime.substringAfterLast('/').take(10).ifEmpty { "文件" }
            }
        }
        val sizeStr = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
        return "$type · $sizeStr"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILE || resultCode != android.app.Activity.RESULT_OK || data == null) return
        val list = mutableListOf<Pair<Uri, String>>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uri ->
                    list.add(uri to (queryFileName(uri) ?: uri.toString()))
                }
            }
        } ?: run {
            data.data?.let { uri ->
                list.add(uri to (queryFileName(uri) ?: uri.toString()))
            }
        }
        updateFileStrip(list)
    }
}

/** RecyclerView adapter for the exec log panel */
class ExecLogAdapter(private val entries: List<ExecLogEntry>) :
    RecyclerView.Adapter<ExecLogAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val statusDot: View = v.findViewById(R.id.statusDot)
        val tvCommand: TextView = v.findViewById(R.id.tv_command)
        val tvTime: TextView = v.findViewById(R.id.tv_time)
        val tvOutput: TextView = v.findViewById(R.id.tv_output)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_terminal_command, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.statusDot.setBackgroundResource(
            if (entry.isSuccess) R.drawable.green_dot else R.drawable.red_dot
        )
        holder.tvCommand.text = "$ ${entry.cmd}"
        holder.tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(entry.timeMs))
        if (entry.output.isNotBlank()) {
            holder.tvOutput.text = entry.output.trim()
            holder.tvOutput.visibility = View.VISIBLE
        } else {
            holder.tvOutput.visibility = View.GONE
        }
    }

    override fun getItemCount() = entries.size
}



