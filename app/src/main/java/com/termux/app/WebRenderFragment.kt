package com.termux.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlin.text.Regex
import com.termux.R
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object WebRenderManager {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** loadHtml 鏃跺洖璋冿紝鐢?IDE 鍦ㄨ亰澶╁垪琛ㄤ腑鍔犲叆缃戦〉棰勮姘旀场 */
    var onLoadHtml: ((String) -> Unit)? = null

    fun loadHtml(html: String) {
        mainHandler.post { onLoadHtml?.invoke(html) }
    }
}
fun sha256(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}




// 预编译高频调用路径上的 Regex，避免每次调用重新编译
private val RE_JSON_ARRAY = Regex("""\[[\s\S]*\]""")
private val RE_ASK_USER = Regex("""askUser\s*\(\s*"((?:[^"\\]|\\.)*)"\s*\)""")
private val RE_WAIT = Regex("""wait\s*\(\s*([0-9]+)\s*\)""")

/** 返回最近一次 execCommand 输出的末尾 N 行，直接来自 Process 输出流，无 transcript 限制 */
/**
 * 清洗终端输出：去除 ANSI 转义序列、处理 \r 进度条覆写，让 AI 看到纯文本。
 * 此前是 text.trim() 的空实现，ANSI 控制码会直接污染 AI 上下文。
 */
fun filterTerminalOutput(text: String): String {
    // 1. Strip ANSI/VT escape sequences: ESC [ ... m / ESC [ ... A-Z etc.
    var r = text.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
    // 2. Strip OSC sequences: ESC ] ... BEL  or  ESC ] ... ESC \
    r = r.replace(Regex("\u001B][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
    // 3. Handle carriage-return overwrite (progress bars like wget/pip):
    //    split each line by \r, keep only the last segment (the final overwrite)
    r = r.lines().joinToString("\n") { line -> line.split('\r').last() }
    // 4. Remove remaining non-printable control chars (except \n and \t)
    r = r.replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")
    return r.trim()
}

/**
 * 返回最近一次 exec 输出供 AI 查看：
 * - 先经过 filterTerminalOutput 清洗掉 ANSI 码
 * - 采用 head+tail 策略：展示前 8 行 + 末尾 (maxLines-8) 行，避免丢失编译错误开头
 * - 总长度上限 3000 字符
 */
fun getLastTerminalLinesStr(maxLines: Int = 80): String {
    val cleaned = filterTerminalOutput(herLastExecOutput)
    val allLines = cleaned.lines().filter { it.isNotBlank() }
    if (allLines.isEmpty()) return ""
    if (allLines.size <= maxLines) return allLines.joinToString("\n").take(3000)
    val headCount = 8
    val tailCount = maxLines - headCount
    val head = allLines.take(headCount)
    val tail = allLines.takeLast(tailCount)
    val omitted = allLines.size - maxLines
    return (head + listOf("... ($omitted 行已省略) ...") + tail)
        .joinToString("\n")
        .take(3000)
}

/** runJava 编译出的 Activity 启动信息（由对话 UI 决定何时点击跳转） */
data class PluginLaunchInfo(
    val dexPath: String,
    val entryActivity: String,
    val pkgName: String
)

/** showResultOnBubble=false 鏃朵笉鍦?AI 姘旀场涓嬫樉绀虹伆鑹茬粨鏋滐紙濡?askForHelp 鍚庣敤鎴峰洖澶嶅凡鍗曠嫭鎴愭场锛?*/
data class RunResult(
    val result: String,
    val terminalLog: String,
    val showResultOnBubble: Boolean = true,
    val isSuccess: Boolean = true,
    /** 非空时表示应插入「安装提醒」卡片，点击跳转依赖商店；包名列表可传给商店预填 */
    val depStorePackages: List<String>? = null,
    /** 非空时表示应插入「运行 Activity」卡片，点击后通过 hook 启动 runJava 生成的界面 */
    val pluginLaunchInfo: PluginLaunchInfo? = null
)

/** 渚涙墽琛?askForHelp 鏃舵寕璧峰苟绛夊緟鐢ㄦ埛杈撳叆锛岀敱 Activity 瀹炵幇 */
interface AskForHelpReplyReceiver {
    suspend fun waitForUserReply(): String
}

/**
 * 动作模式说明：
 * - 每一轮你只能输出一个「数组字面量」，不能有额外文字或 Markdown。
 * - 统一格式：["下一步要做什么的简要中文描述", 动作表达式，例如 exec('default', 'pwd')]。
 */
val ACTION_TOOLS_PROMPT = """
你是一个AI助手。正在安卓 Termux 环境的终端上执行任务。你必须严格按以下“动作模式”工作：
1. 【唯一允许的输出格式】：
     ["下一步要做什么的简要描述", 动作表达式（不用引号包裹），例如 exec('default', 'pwd')]

2. 【动作表达式只能是下列几种之一】
   1）exec("sessionId", "shell 命令")
      - 在指定会话中执行 shell 命令。
      - 禁止在 exec 里使用复杂 heredoc（如 cat <<EOF 写入大段 HTML/JS/CSS/JSX 等）；写文件请用 writeFile。
      - 示例：["查看当前目录", exec('default', 'pwd')]

   2）termuxAPI("命令")
      - 调用原生Termux API(已安装)时，必须使用本形式，而不是放进 exec 里。
      - 示例：["发送测试短信", termuxAPI('termux-sms-send 10086 \"内容\"')]

   3）search("查询词")
      - 调用联网搜索能力，实时查网页/文档/资料。
      - 示例：["查今天的科技新闻", search('今天的科技新闻')]

   4）runJava("Java 源码字符串")
      - 将一段Java源码编译成可运行的activity并自动启动。
      - 【代码规范】：
        1）只生成一个 Activity 类，包名固定为 com.example.plugin，类名固定为 MainActivity,必须写完整的 import 语句，确保 javac 可直接编译。
        2）必须 extends android.app.Activity，禁止使用 AppCompatActivity、FragmentActivity 或任何 androidx 类。
        3）只允许使用 java.* 和 android.* 标准库；禁止第三方库。
        4）禁止使用 R 资源，必须用纯代码布局（new TextView / LinearLayout / FrameLayout 等 + setContentView(root)）。
        5）如果需要权限，应自行处理申请回调等逻辑。
      - 示例（结构示意）：["编译并预览一个简单 Activity", runJava('package com.example.plugin; import android.app.Activity; import android.os.Bundle; public class MainActivity extends Activity { @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); /* 在这里用代码搭建 UI */ } }')]

   5）message({...})
      - 必须在任务完成后调用，也可向用户提问补充信息。如果任务要求交付文件，必须将其放进attachments.
      - 示例：["向用户展示说明", message({"text":"说明文字","attachments":["/data/data/.../index.html"]})]
        - text：对话里展示的文字内容。
        - attachments：要交付的文件绝对路径列表，系统将自动展示给用户（如 .html / .pdf / .doc / .txt 等）。

   6）writeFile("path", "内容字符串")
      - 把给定内容写入文件，禁止再用 heredoc。
      - path：绝对或相对路径
      - 对于html文件:只在手机横屏展示，禁止滑动，没有键鼠只能触屏。如果需要Three.js等第三方库，应使用绝对url
      - 示例：["生成 index.html", writeFile('index.html', '<!doctype html>\n<html>...')]

   7）readFile("path")
      - 读取指定文件内容并返回。
      - path：绝对路径或以 ~/ 开头的相对路径。
      - 示例：["查看 main.py", readFile('~/project/main.py')]

   8）editFile("path", "原字符串", "新字符串")
      - 在文件中将第一处匹配的「原字符串」精确替换为「新字符串」，不重写整个文件。
      - 修改已有文件时，优先用此工具而非 writeFile，避免意外破坏其他内容。
      - 若文件不存在或找不到原字符串，会返回错误并提示用 readFile 确认实际内容。
      - 原字符串必须与文件中的内容完全一致，包括空格、换行、缩进。
      - 示例：editFile("~/main.py", "def greet():\n    pass", "def greet():\n    print('hi')")

   9）appendFile("path", "追加内容")
      - 在文件末尾追加内容（若文件不存在则自动创建）。
      - 适合追加日志、配置行等，避免先读文件再全量重写。
      - 示例：appendFile("~/.bashrc", "export MY_VAR=hello")
      
   10）pkg("包名1","包名2")
       -如果任务所需某个Termux软件包未安装，不要用exec函数直接调用pkg，改为使用该函数。 

3. 【其它规则】
   - 每轮只能输出一个 JSON 数组，不能输出多个 JSON 或额外解释文字。
   - 避免多次询问用户非必要问题，尽可能选择简洁高效的方式。禁止无意义的检查或确认，不得在上一步显示执行成功后再做检查。
   - 【步骤历史规则】✓ 表示已成功完成 → 绝对不要重复执行；✗ 表示失败 → 下一步必须先修复这个失败（换不同方式），再继续后续步骤；所有必要步骤均为 ✓ → 立即调用 message() 汇报结果，不要再做额外操作。
""".trimIndent()

private data class PromptDep(
    val displayName: String,
    val pkgName: String,
    val binRelativePath: String,
    val required: Boolean = false
)

private val PROMPT_DEPS = listOf(
    PromptDep("Bash Shell", "bash", "bin/bash", required = true),
    PromptDep("OpenJDK 17", "openjdk-17", "bin/javac", required = true),
    PromptDep("which", "which", "bin/which"),
    PromptDep("wget", "wget", "bin/wget"),
    PromptDep("Git", "git", "bin/git"),
    // Termux 仓库目前只提供滚动的 python 包（对应当前稳定的 Python 3.x）
    // 不能精确锁到 3.11，因此这里仍使用官方的 python 包名
    PromptDep("Python 3", "python", "bin/python"),
    PromptDep("Clang / C++", "clang", "bin/clang"),
    PromptDep("Node.js LTS", "nodejs-lts", "bin/node")
)

private fun termuxPrefix(ctx: Context): File =
    File(ctx.filesDir.parentFile, "files/usr")

private fun buildInstalledDepsPromptBlock(ctx: Context): String {
    val prefix = termuxPrefix(ctx)
    val installed = PROMPT_DEPS.filter { dep ->
        File(prefix, dep.binRelativePath).exists()
    }
    if (installed.isEmpty()) return "【当前已安装软件包】\n- 暂未检测到已安装包"
    return buildString {
        append("【当前已安装软件包】\n")
        installed.forEach { dep ->
            append("- ${dep.displayName} (${dep.pkgName})\n")
        }
    }.trimEnd()
}

data class DepInfo(val displayName: String, val pkgName: String, val required: Boolean) : java.io.Serializable

/** 返回未安装的依赖列表，required=true 表示必须安装 */
fun getMissingRecommendedDeps(context: Context): List<DepInfo> {
    val prefix = termuxPrefix(context)
    return PROMPT_DEPS.filter { dep -> !File(prefix, dep.binRelativePath).exists() }
        .map { dep -> DepInfo(dep.displayName, dep.pkgName, dep.required) }
}


/** 返回所有推荐依赖及是否已安装，供依赖商店页面展示 */
fun getAllRecommendedDepsWithStatus(context: Context): List<Pair<DepInfo, Boolean>> {
    val prefix = termuxPrefix(context)
    return PROMPT_DEPS.map { dep ->
        val info = DepInfo(dep.displayName, dep.pkgName, dep.required)
        val installed = File(prefix, dep.binRelativePath).exists()
        info to installed
    }
}

fun buildActionToolsPrompt(ctx: Context): String {
    return ACTION_TOOLS_PROMPT + "\n\n" + buildInstalledDepsPromptBlock(ctx)
}



data class ParsedStep(val description: String, val actionLine: String, val directContent: String?) {
    /** 鏈疆缁撴潫骞跺悜鐢ㄦ埛鍙戦€佷竴鏉?message锛堜笉鍐嶇户缁墽琛屽伐鍏凤級 */
    val isMessage: Boolean get() = actionLine == "message"
}

fun parseStepReply(reply: String): ParsedStep? {
    // 剥离 markdown 代码围栏：模型有时把数组包在 ```json ... ``` 里
    var t = reply.trim()
    t = Regex("""```(?:json|JSON)?\s*""").replace(t, "").replace("```", "").trim()
    val arrMatch = RE_JSON_ARRAY.find(t) ?: return null
    val jsonStr = arrMatch.value.trim()

    return try {
        // 只接受数组形式：["描述", 动作表达式]
        if (!jsonStr.startsWith("[") || !jsonStr.endsWith("]")) return null
        val inside = jsonStr.substring(1, jsonStr.length - 1)
        val parts = splitUnquotedCommas(inside)
        if (parts.size < 2) return null

        val firstPart = parts[0].trim()
        val secondPart = parts[1].trim()

        // 解析描述字符串
        val desc = run {
            if (firstPart.length < 2) "执行中…"
            else {
                val quote = firstPart.first()
                if ((quote == '"' || quote == '\'') && firstPart.last() == quote) {
                    val raw = firstPart.substring(1, firstPart.length - 1)
                    unescapeStringLiteral(raw).trim().ifBlank { "执行中…" }
                } else {
                    firstPart.trim().ifBlank { "执行中…" }
                }
            }
        }
        val secondRaw = secondPart.trim()

        // runJava("...")：用 extractRunJavaCode 正确提取含内部引号的源码，避免被截断
        if (secondRaw.startsWith("runJava(")) {
            val code = extractRunJavaCode(secondRaw) ?: ""
            val content = code.ifBlank { null }
            return ParsedStep(description = desc, actionLine = "runJava", directContent = content)
        }

        // message({...})：括号内是完整 JSON 对象，不能用 extractStringArg（会截断）
        if (secondRaw.startsWith("message(")) {
            val json = extractParenContent(secondRaw, "message(") ?: ""
            val content = json.ifBlank { null }
            return ParsedStep(description = desc, actionLine = "message", directContent = content)
        }

        // 其它动作（exec / termuxAPI / search / wait 等）直接作为 actionLine
        ParsedStep(description = desc, actionLine = secondRaw, directContent = null)
    } catch (_: Throwable) {
        null
    }
}

/**
 * 需权限的 API：先申请权限（弹窗等待用户），通过后再执行 block；未通过或异常时写入 results 并返回 false。
 * 保证「先做好权限申请再执行」。
 */
private suspend fun ensurePermissionsThen(
    results: MutableList<String>,
    deniedMessage: String,
    vararg perms: String,
    block: suspend () -> Unit
): Boolean {
    val ok = try {
        HerSystemRuntime.ensurePermissions(*perms)
    } catch (e: Throwable) {
        results.add("无法请求权限: ${e.message}")
        return false
    }
    if (!ok) {
        results.add(deniedMessage)
        return false
    }
    block()
    return true
}

/** 处理 Termux:API 风格命令；若命令被识别返回 true 并把结果写入 results（使用官方 termux-api 库）*/
private suspend fun handleTermuxApiCommand(context: Context, raw: String, results: MutableList<String>): Boolean {
    val line = raw.trim()

    // termux-telephony-call 10086（内部会先申请 CALL_PHONE + READ_CONTACTS 再执行）
    Regex("""^termux-telephony-call\s+(\S+)\s*$""").find(line)?.let { m ->
        try {
            val ok = HerSystemRuntime.callTarget(context, m.groupValues[1])
            results.add(if (ok) "已拨打电话" else "拨打电话失败")
        } catch (e: Throwable) {
            results.add("无法请求权限或拨号失败: ${e.message}")
        }
        return true
    }

    // termux-dialog "提示文字"
    Regex("""^termux-dialog\s+(.+)$""").find(line)?.let { m ->
        val message = m.groupValues[1].trim().trim('"')
        HerSystemRuntime.showDialog(context, message)
        results.add("dialog shown")
        return true
    }

    // termux-sms-list / termux-sms-inbox（支持 -l limit、-o offset，先申请读短信权限再执行）
    Regex("""^termux-sms-(?:list|inbox)(?:\s+(.*))?$""").find(line)?.let { m ->
        val args = m.groupValues.getOrNull(1).orEmpty()
        val limit = Regex("""-l\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5000) ?: 25
        val offset = Regex("""-o\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        ensurePermissionsThen(results, "permission denied", Manifest.permission.READ_SMS) {
            results.add(TermuxApiRunner.smsInbox(context, limit = limit, offset = offset))
        }
        return true
    }

    // termux-contact-list（先申请通讯录权限再执行）
    if (line == "termux-contact-list") {
        ensurePermissionsThen(results, "permission denied", Manifest.permission.READ_CONTACTS) {
            results.add(TermuxApiRunner.contactList(context))
        }
        return true
    }

    // termux-microphone-record start|stop（start 时先申请录音权限再执行）
    Regex("""^termux-microphone-record\s+(start|stop)$""").find(line)?.let { m ->
        if (m.groupValues[1] == "start") {
            ensurePermissionsThen(results, "recording failed", Manifest.permission.RECORD_AUDIO) {
                results.add(TermuxApiRunner.micRecordStart(context))
            }
        } else {
            results.add(TermuxApiRunner.micRecordStop())
        }
        return true
    }

    // termux-wake-lock / termux-wake-unlock
    Regex("""^termux-wake-(lock|unlock)$""").find(line)?.let { m ->
        if (m.groupValues[1] == "lock") { results.add(TermuxApiRunner.wakeLock(context)) }
        else { results.add(TermuxApiRunner.wakeUnlock()) }
        return true
    }

    // termux-wallpaper [lock|system] /path/to/file（读文件可能需存储权限，先申请再执行）
    Regex("""^termux-wallpaper(?:\s+(lock|system))?\s+(.+)$""").find(line)?.let { m ->
        val target = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "system"
        val path   = m.groupValues[2].trim().trim('"')
        ensurePermissionsThen(results, "wallpaper failed", Manifest.permission.READ_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.setWallpaper(context, path, target))
        }
        return true
    }

    // termux-battery-status
    if (line == "termux-battery-status") {
        results.add(TermuxApiRunner.batteryStatus(context))
        return true
    }

    // termux-clipboard-get
    if (line == "termux-clipboard-get") {
        results.add(TermuxApiRunner.clipboardGet(context))
        return true
    }

    // termux-tts-speak "内容"
    Regex("""^termux-tts-speak\s+(.+)$""").find(line)?.let { m ->
        val text = m.groupValues[1].trim().trim('"')
        results.add(TermuxApiRunner.ttsSpeak(context, text))
        return true
    }

    // termux-tts-stop
    if (line == "termux-tts-stop") {
        results.add(TermuxApiRunner.ttsStop())
        return true
    }

    // termux-tts-engines
    if (line == "termux-tts-engines") {
        results.add(TermuxApiRunner.ttsEngines(context))
        return true
    }

    // termux-brightness 120（需「修改系统设置」权限，先检查再执行）
    Regex("""^termux-brightness\s+(\d+)$""").find(line)?.let { m ->
        if (!Settings.System.canWrite(context)) {
            results.add("调节亮度需要「修改系统设置」权限")
            HerSystemRuntime.withActivity { activity ->
                activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:${activity.packageName}")))
            }
            results.add("已打开设置页，请开启「修改系统设置」后重新执行 termux-brightness")
        } else {
            results.add(TermuxApiRunner.brightness(context, m.groupValues[1].toInt()))
        }
        return true
    }

    // termux-volume（无参数：查询当前各声道音量）
    if (line == "termux-volume") {
        results.add(TermuxApiRunner.getVolumes(context))
        return true
    }
    // termux-volume stream level（设置音量）
    Regex("""^termux-volume\s+(\S+)\s+(\d+)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.setVolume(context, m.groupValues[1], m.groupValues[2].toInt()))
        return true
    }

    // termux-torch on/off（先申请相机权限再执行）
    Regex("""^termux-torch\s+(on|off)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "camera permission denied", Manifest.permission.CAMERA) {
            results.add(TermuxApiRunner.torch(context, m.groupValues[1] == "on"))
        }
        return true
    }

    // termux-telephony-deviceinfo（先申请电话状态权限再执行）
    if (line == "termux-telephony-deviceinfo") {
        ensurePermissionsThen(results, "permission denied", Manifest.permission.READ_PHONE_STATE) {
            results.add(TermuxApiRunner.telephonyDeviceInfo(context))
        }
        return true
    }

    // termux-telephony-cellinfo（先申请电话+位置权限再执行）
    if (line == "termux-telephony-cellinfo") {
        ensurePermissionsThen(
            results, "permission denied",
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) {
            results.add(TermuxApiRunner.telephonyCellInfo(context))
        }
        return true
    }

    // termux-media-scan /path（扫描外部路径可能需存储权限，先申请再执行）
    Regex("""^termux-media-scan\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "media scan failed", Manifest.permission.READ_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.mediaScan(context, m.groupValues[1]))
        }
        return true
    }

    // termux-clipboard-set "内容"
    Regex("""^termux-clipboard-set\s+(.+)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.clipboardSet(context, m.groupValues[1].trim().trim('"')))
        return true
    }

    // termux-notification title="xxx" text="yyy"（Android 13+ 需先申请通知权限再执行）
    Regex("""^termux-notification\s+(.*)$""").find(line)?.let { m ->
        val args  = m.groupValues[1]
        val title = Regex("""title="([^"]*)"""").find(args)?.groupValues?.get(1) ?: ""
        val text  = Regex("""text="([^"]*)"""").find(args)?.groupValues?.get(1) ?: ""
        val notificationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
        if (notificationPerm.isEmpty()) {
            results.add(TermuxApiRunner.showNotification(context, title, text))
        } else {
            ensurePermissionsThen(results, "notification failed", *notificationPerm) {
                results.add(TermuxApiRunner.showNotification(context, title, text))
            }
        }
        return true
    }

    // termux-file-exists /path（外部路径可能需存储权限，先申请再执行）
    Regex("""^termux-file-exists\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "permission denied", Manifest.permission.READ_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.fileExists(m.groupValues[1]))
        }
        return true
    }

    // termux-file-read /path（先申请读存储权限再执行）
    Regex("""^termux-file-read\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "permission denied", Manifest.permission.READ_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.fileRead(m.groupValues[1]))
        }
        return true
    }

    // termux-file-write /path "内容"（先申请写存储权限再执行）
    Regex("""^termux-file-write\s+(\S+)\s+(.+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "write failed", Manifest.permission.WRITE_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.fileWrite(m.groupValues[1], m.groupValues[2].trim().trim('"')))
        }
        return true
    }

    // termux-file-delete /path（先申请写存储权限再执行）
    Regex("""^termux-file-delete\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "delete failed", Manifest.permission.WRITE_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.fileDelete(m.groupValues[1]))
        }
        return true
    }

    // termux-media-play /path（外部路径可能需存储权限，先申请再执行）
    Regex("""^termux-media-play\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "playing failed", Manifest.permission.READ_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.mediaPlay(m.groupValues[1]))
        }
        return true
    }

    // termux-media-stop
    if (line == "termux-media-stop") {
        results.add(TermuxApiRunner.mediaStop())
        return true
    }

    // termux-toast "内容"
    Regex("""^termux-toast\s+(.+)$""").find(line)?.let { m ->
        val text = m.groupValues[1].trim().trim('"')
        results.add(TermuxApiRunner.toast(context, text))
        return true
    }

    // termux-vibrate [ms]
    Regex("""^termux-vibrate(?:\s+(\d+))?\s*$""").find(line)?.let { m ->
        val ms = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 400
        results.add(TermuxApiRunner.vibrate(context, ms))
        return true
    }

    // termux-open-url https://xxx
    Regex("""^termux-open-url\s+(.+)$""").find(line)?.let { m ->
        val url = m.groupValues[1].trim().trim('"')
        results.add(TermuxApiRunner.openUrl(context, url))
        return true
    }

    // termux-sms-send 10086 "内容"（先申请发短信+通讯录权限再执行）
    Regex("""^termux-sms-send\s+(\S+)\s+(.+)$""").find(line)?.let { m ->
        val phone = m.groupValues[1]
        val text  = m.groupValues[2].trim().trim('"')
        ensurePermissionsThen(
            results, "短信发送失败",
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        ) {
            val target = if (phone.any { it.isDigit() }) phone
                         else HerSystemRuntime.findPhoneByName(context, phone) ?: ""
            results.add(
                if (target.isNotEmpty()) TermuxApiRunner.smsSend(context, target, text)
                else "短信发送失败"
            )
        }
        return true
    }

    // termux-open-app com.xxx
    Regex("""^termux-open-app\s+(\S+)\s*$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.openApp(context, m.groupValues[1]))
        return true
    }

    // termux-location -p gps -r once（先申请位置权限再执行）
    Regex("""^termux-location\s+(.+)$""").find(line)?.let { m ->
        val args     = m.groupValues[1]
        val provider = Regex("""-p\s+(\w+)""").find(args)?.groupValues?.get(1)?.lowercase() ?: "gps"
        val request  = Regex("""-r\s+(\w+)""").find(args)?.groupValues?.get(1)?.lowercase() ?: "once"
        ensurePermissionsThen(
            results, """{"error":"permission denied"}""",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) {
            results.add(TermuxApiRunner.location(context, provider, request))
        }
        return true
    }

    // termux-camera-photo（内部会先申请 CAMERA 再拍照）
    if (line == "termux-camera-photo") {
        try {
            val path = HerSystemRuntime.takePhotoReturnPath(context)
            results.add(if (path.isNotEmpty()) "已拍照，文件: $path" else "拍照失败或已取消")
        } catch (e: Throwable) {
            results.add("无法请求权限或拍照失败: ${e.message}")
        }
        return true
    }

    // termux-set-alarm / termux-alarm-set 8 30 "备注"
    Regex("""^termux-(?:set-alarm|alarm-set)\s+(\d{1,2})\s+(\d{1,2})(?:\s+(.+))?\s*$""").find(line)?.let { m ->
        val hour  = m.groupValues[1].toInt()
        val min   = m.groupValues[2].toInt()
        val label = m.groupValues.getOrNull(3)?.trim()?.trim('"').orEmpty()
        results.add(TermuxApiRunner.setAlarm(context, hour, min, label))
        return true
    }

    // termux-add-calendar "标题" startMillis endMillis "描述"（先申请日历权限再执行）
    Regex("""^termux-add-calendar\s+"([^"]+)"\s+(\d+)\s+(\d+)(?:\s+"([^"]*)")?\s*$""").find(line)?.let { m ->
        ensurePermissionsThen(
            results, "日历事件创建失败",
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALENDAR
        ) {
            results.add(TermuxApiRunner.addCalendarEvent(
                context, m.groupValues[1],
                m.groupValues[2].toLong(), m.groupValues[3].toLong(),
                m.groupValues.getOrNull(4).orEmpty()
            ))
        }
        return true
    }

    // termux-wifi-enable true/false
    Regex("""^termux-wifi-enable\s+(true|false)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.wifiEnable(context, m.groupValues[1] == "true"))
        return true
    }

    // termux-wifi-connectioninfo
    if (line == "termux-wifi-connectioninfo") {
        results.add(TermuxApiRunner.wifiConnectionInfo(context))
        return true
    }

    // termux-wifi-scaninfo（先申请位置权限再执行）
    if (line == "termux-wifi-scaninfo") {
        ensurePermissionsThen(results, "permission denied", Manifest.permission.ACCESS_FINE_LOCATION) {
            results.add(TermuxApiRunner.wifiScanInfo(context))
        }
        return true
    }

    // termux-http-get URL
    Regex("""^termux-http-get\s+(\S+)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.httpGet(m.groupValues[1]))
        return true
    }

    // termux-http-post URL "content"
    Regex("""^termux-http-post\s+(\S+)\s+(.+)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.httpPost(m.groupValues[1], m.groupValues[2].trim().trim('"')))
        return true
    }

    // termux-share /path（读文件分享可能需存储权限，先申请再执行）
    Regex("""^termux-share\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "share failed", Manifest.permission.READ_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.shareFile(context, m.groupValues[1]))
        }
        return true
    }

    // termux-notification-remove 123
    Regex("""^termux-notification-remove\s+(\d+)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.removeNotification(context, m.groupValues[1].toInt()))
        return true
    }

    // termux-download URL（写入存储可能需权限，先申请再执行）
    Regex("""^termux-download\s+(\S+)$""").find(line)?.let { m ->
        ensurePermissionsThen(results, "download failed", Manifest.permission.WRITE_EXTERNAL_STORAGE) {
            results.add(TermuxApiRunner.download(context, m.groupValues[1]))
        }
        return true
    }

    // termux-sensor-list
    if (line == "termux-sensor-list") {
        results.add(TermuxApiRunner.sensorList(context))
        return true
    }

    // termux-sensor-get TYPE
    Regex("""^termux-sensor-get\s+(\S+)$""").find(line)?.let { m ->
        results.add(TermuxApiRunner.sensorGet(context, m.groupValues[1]))
        return true
    }

    // termux-audio-info
    if (line == "termux-audio-info") {
        results.add(TermuxApiRunner.audioInfo(context))
        return true
    }

    // termux-camera-info（先申请相机权限再执行）
    if (line == "termux-camera-info") {
        ensurePermissionsThen(results, "permission denied", Manifest.permission.CAMERA) {
            results.add(TermuxApiRunner.cameraInfo(context))
        }
        return true
    }

    // termux-call-log [-l limit] [-o offset]（先申请通话记录权限再执行）
    Regex("""^termux-call-log(?:\s+(.*))?$""").find(line)?.let { m ->
        val args   = m.groupValues.getOrNull(1).orEmpty()
        val limit  = Regex("""-l\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        val offset = Regex("""-o\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        ensurePermissionsThen(results, "permission denied", Manifest.permission.READ_CALL_LOG) {
            results.add(TermuxApiRunner.callLog(context, limit, offset))
        }
        return true
    }

    // termux-infrared-frequencies
    if (line == "termux-infrared-frequencies") {
        results.add(TermuxApiRunner.infraredFrequencies(context))
        return true
    }

    // termux-infrared-transmit -f frequency -p pattern
    Regex("""^termux-infrared-transmit\s+(.+)$""").find(line)?.let { m ->
        val args      = m.groupValues[1]
        val frequency = Regex("""-f\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull() ?: 38000
        val pattern   = Regex("""-p\s+(\S+)""").find(args)?.groupValues?.get(1) ?: ""
        results.add(TermuxApiRunner.infraredTransmit(context, frequency, pattern))
        return true
    }

    // 兜底：所有以 termux- 开头但尚未实现的命令
    if (line.startsWith("termux-")) {
        results.add("未实现的 Termux API: $line")
        return true
    }

    return false
}

/** 鎵ц鍗曟瑙ｆ瀽缁撴灉锛氳嫢涓?runJava 涓斿甫 directContent 鍒欑洿鎺ョ敤锛涘惁鍒欐寜鍔ㄤ綔琛屾墽琛?*/
suspend fun executeParsedStep(context: Context, step: ParsedStep): RunResult = withContext(Dispatchers.IO) {
    val api = HerApi(context)
    if (step.actionLine == "message") {
        val msg = step.directContent?.takeIf { it.isNotBlank() } ?: step.description
        return@withContext RunResult(msg, "")
    }

    // 特例：runJava 使用 Activity 编译流水线，返回带插件启动信息的 RunResult，供对话页插入卡片
    if (step.directContent != null && step.actionLine == "runJava") {
        return@withContext try {
            val build = api.buildActivityFromJava(step.directContent)
            RunResult(
                result = build.message,
                terminalLog = "",
                isSuccess = true,
                pluginLaunchInfo = PluginLaunchInfo(
                    dexPath = build.dexPath,
                    entryActivity = build.entryActivity,
                    pkgName = build.pkgName
                )
            )
        } catch (e: Throwable) {
            RunResult(
                result = "runJava 编译 Activity 失败: ${e.message}",
                terminalLog = "",
                isSuccess = false
            )
        }
    }

    executeActionLines(context, listOf(step.actionLine))
}

/**
 * 鎵ц鍔ㄤ綔锛? * - exec("sessionId", "鍛戒护")锛氬湪鎸囧畾浼氳瘽鎵ц shell 鍛戒护锛屼笉鎷︽埅 termux-*锛? * - termuxAPI("鍛戒护")锛氱洿鎺ヨ皟鐢ㄥ師鐢?Termux API锛屽 termuxAPI("termux-battery-status")锛? * - search("鏌ヨ璇?)锛氱濉?AI 鎼滅储锛? * - runJava("...")锛氳繍琛?Java 浠ｇ爜锛? * - askUser("...")锛氱瓑寰呯敤鎴峰洖澶嶏紱
 * - wait(绉掓暟)锛氭寕璧蜂竴娈垫椂闂村悗缁х画锛? * - 鍏跺畠琛岃涓烘櫘閫氱粓绔懡浠わ紙鍏煎鏃ф牸寮忥級銆? */
suspend fun executeActionLines(context: Context, lines: List<String>): RunResult = withContext(Dispatchers.IO) {
    val api = HerApi(context)
    val askForHelpReceiver = context as? AskForHelpReplyReceiver
    val results = mutableListOf<String>()
    val terminalLogs = mutableListOf<String>()
    var anyFailure = false
    var depStorePackages: List<String>? = null
    // pluginLaunchInfo 只应在当前这次动作真正调用 runJava 时返回，不能“记住”上一次的值
    var pluginLaunchInfo: PluginLaunchInfo? = null

    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue

        // 每条动作行开始时重置本地状态，防止上一次 runJava 的插件信息泄漏到其它步骤
        pluginLaunchInfo = null

        try {
            when {
                line.startsWith("askUser(") -> {
                    val askMatch = RE_ASK_USER.find(line)
                    if (askMatch != null && askForHelpReceiver != null) {
                        val userReply = askForHelpReceiver.waitForUserReply()
                        return@withContext RunResult(userReply, "", showResultOnBubble = false)
                    }
                    results.add("askUser 需要当前界面支持，未能执行")
                }

                line.startsWith("wait(") -> {
                    // wait(秒数) —— 简单延时，不走终端
                    val m = RE_WAIT.find(line)
                    val seconds = m?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (seconds == null) {
                        results.add("wait 调用格式错误: $line")
                    } else {
                        delay(seconds * 1000L)
                        results.add("wait 完成，已等待 ${seconds}s")
                    }
                }

                line.startsWith("termuxAPI(") -> {
                    // 鎻愮ず璇嶇害瀹氭牸寮忥細termuxAPI("termux-battery-status") 绛夛紝鐩存帴璋冪敤鍘熺敓瀹炵幇
                    val cmd = extractStringArg(line)?.trim()
                    if (cmd.isNullOrBlank()) {
                        results.add("termuxAPI 调用格式错误: $line")
                    } else {
                        try {
                            if (!handleTermuxApiCommand(context, cmd, results)) {
                                results.add("未知的 termux 命令: $cmd")
                            }
                        } catch (e: Throwable) {
                            results.add("termuxAPI 执行异常: ${e.message}")
                        }
                    }
                }

                line.startsWith("writeFile(") -> {
                    val args = parseWriteFileArgs(line)
                    if (args == null) {
                        results.add("writeFile 调用格式错误: $line")
                    } else {
                        val (pathRaw, content) = args
                        try {
                            val file = resolveWriteFilePath(context, pathRaw)
                            file.parentFile?.mkdirs()
                            file.writeText(content, Charsets.UTF_8)
                            // 执行成功时，返回“写入成功: 绝对路径”，方便人和后续步骤查看
                            results.add("写入成功: ${file.absolutePath}")
                        } catch (e: Throwable) {
                            results.add("writeFile 失败: ${e.message}")
                        }
                    }
                }

                line.startsWith("editFile(") -> {
                    // editFile("path", "old_str", "new_str") — 精确替换，不重写整个文件
                    val triple = parseThreeStringArgs(line)
                    if (triple == null) {
                        anyFailure = true
                        results.add("editFile 调用格式错误: $line")
                    } else {
                        val (pathRaw, oldStr, newStr) = triple
                        try {
                            val file = resolveWriteFilePath(context, pathRaw)
                            if (!file.exists()) {
                                anyFailure = true
                                results.add("editFile 失败: 文件不存在 — ${file.absolutePath}")
                            } else {
                                val content = file.readText(Charsets.UTF_8)
                                if (!content.contains(oldStr)) {
                                    anyFailure = true
                                    results.add("editFile 失败: 未找到目标字符串，请先用 readFile 确认实际内容再重试")
                                } else {
                                    file.writeText(content.replaceFirst(oldStr, newStr), Charsets.UTF_8)
                                    results.add("editFile 成功: ${file.absolutePath}")
                                }
                            }
                        } catch (e: Throwable) {
                            anyFailure = true
                            results.add("editFile 失败: ${e.message}")
                        }
                    }
                }

                line.startsWith("appendFile(") -> {
                    // appendFile("path", "content") — 追加到文件末尾
                    val args = parseTwoStringArgs(line)
                    if (args == null) {
                        anyFailure = true
                        results.add("appendFile 调用格式错误: $line")
                    } else {
                        val (pathRaw, content) = args
                        try {
                            val file = resolveWriteFilePath(context, pathRaw)
                            file.parentFile?.mkdirs()
                            file.appendText(content, Charsets.UTF_8)
                            results.add("appendFile 成功: ${file.absolutePath}")
                        } catch (e: Throwable) {
                            anyFailure = true
                            results.add("appendFile 失败: ${e.message}")
                        }
                    }
                }

                line.startsWith("exec(") -> {
                    // exec("sessionId", "command") — 在指定会话的 bash 进程中执行命令
                    var sessionId: String
                    var cmd: String
                    val parsed = parseExecArgs(line)
                    if (parsed != null) {
                        sessionId = parsed.first
                        cmd = parsed.second
                    } else {
                        // 鍏煎鏃ф牸寮忥細exec("command")
                        val single = extractStringArg(line)
                        if (single.isNullOrBlank()) {
                            results.add("exec 调用格式错误: $line")
                            continue
                        }
                        sessionId = "default"
                        cmd = single
                    }

                    // 先插入占位条目，执行过程中实时更新
                    val liveIndex = execLogEntries.size
                    val liveStartMs = System.currentTimeMillis()
                    execLogEntries.add(ExecLogEntry(cmd = cmd, output = "执行中…", isSuccess = false, exitCode = -1, timeMs = liveStartMs))
                    onExecLogUpdated?.invoke()

                    var execError: Throwable? = null
                    var exitCode = -1
                    var progressCount = 0
                    try {
                        exitCode = execCommand(cmd, sessionId) { liveOutput ->
                            if (++progressCount % 3 == 0) {
                                val preview = filterTerminalOutput(liveOutput)
                                    .lines().filter { it.isNotBlank() }.takeLast(30).joinToString("\n")
                                if (liveIndex < execLogEntries.size) {
                                    execLogEntries[liveIndex] = execLogEntries[liveIndex].copy(output = preview)
                                    onExecLogUpdated?.invoke()
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        execError = e
                    }
                    // 获取清洗后的输出（head+tail，最多 3000 字符）
                    val tail = getLastTerminalLinesStr()
                    // 获取执行后的工作目录，注入到返回结果中让 AI 感知 CWD 变化
                    val cwd = herExecSessions[sessionId]?.cwd ?: ""
                    val cwdNote = if (cwd.isNotBlank()) "\n[CWD] $cwd" else ""
                    // 最终更新日志条目
                    val logOutput = buildString {
                        if (tail.isNotBlank()) append(tail)
                        if (execError != null) append(execError.message ?: "执行异常")
                    }
                    if (liveIndex < execLogEntries.size) {
                        execLogEntries[liveIndex] = ExecLogEntry(
                            cmd = cmd,
                            output = logOutput.trim(),
                            isSuccess = execError == null && exitCode == 0,
                            exitCode = exitCode,
                            timeMs = liveStartMs
                        )
                        onExecLogUpdated?.invoke()
                    }
                    if (execError != null) {
                        anyFailure = true
                        results.add("[exec FAIL] ${execError.message}")
                        terminalLogs.add("error: ${execError.message}\n$tail$cwdNote")
                    } else {
                        if (exitCode != 0) anyFailure = true
                        // results 放简洁的 exit 信号，terminalLogs 放完整输出
                        // 两者都会进入 resultStr，AI 能同时看到状态码和终端内容
                        results.add(if (exitCode == 0) "[exit=0 OK]" else "[exit=$exitCode FAIL]")
                        terminalLogs.add(
                            if (tail.isNotBlank()) "$ $cmd\n$tail$cwdNote"
                            else "$ $cmd\n(无输出)$cwdNote"
                        )
                    }
                }


                line.startsWith("search(") -> {
                    val query = extractStringArg(line)?.trim()
                    if (query.isNullOrBlank()) {
                        results.add("search 调用格式错误: $line")
                    } else {
                        try {
                            val ret = api.search(query)
                            results.add(ret)
                        } catch (e: Throwable) {
                            results.add("search 执行异常: ${e.message}")
                        }
                    }
                }

                line.startsWith("readFile(") -> {
                    val pathRaw = extractStringArg(line)
                    if (pathRaw.isNullOrBlank()) {
                        results.add("readFile 调用格式错误: $line")
                    } else {
                        try {
                            val file = resolveWriteFilePath(context, pathRaw)
                            if (!file.isFile) {
                                anyFailure = true
                                results.add("readFile 失败: 文件不存在 ${file.absolutePath}")
                            } else {
                                val content = file.readText(Charsets.UTF_8)
                                val truncated = content.take(8000)
                                val suffix = if (content.length > 8000) "\n…(已截断，共 ${content.length} 字符)" else ""
                                results.add(truncated + suffix)
                            }
                        } catch (e: Throwable) {
                            anyFailure = true
                            results.add("readFile 失败: ${e.message}")
                        }
                    }
                }

                line.startsWith("pkg(") -> {
                    val pkgs = parsePkgArgs(line)
                    if (pkgs.isNullOrEmpty()) {
                        results.add("pkg 调用格式错误，应为 pkg(\"包名1\", \"包名2\", ...)")
                    } else {
                        depStorePackages = pkgs
                        results.add("建议通过依赖商店安装以下包：${pkgs.joinToString(", ")}。请点击对话中的安装提醒卡片前往。")
                    }
                }

                line.startsWith("runJava(") -> {
                    val code = extractRunJavaCode(line) ?: ""
                    if (code.isBlank()) {
                        results.add("runJava 调用格式错误: 无法解析源码字符串")
                    } else {
                        try {
                            val build = api.buildActivityFromJava(code)
                            results.add(build.message)
                            pluginLaunchInfo = PluginLaunchInfo(
                                dexPath = build.dexPath,
                                entryActivity = build.entryActivity,
                                pkgName = build.pkgName
                            )
                        } catch (e: Throwable) {
                            anyFailure = true
                            results.add("runJava 执行失败: ${e.message}")
                        }
                    }
                }

                else -> {
                    try {
                        val exitCode = execCommand(line)
                        if (exitCode != 0) anyFailure = true
                        val tail = getLastTerminalLinesStr()
                        val cwdNote = herExecSessions["default"]?.cwd?.let { "\n[CWD] $it" } ?: ""
                        results.add(if (exitCode == 0) "[exit=0 OK]" else "[exit=$exitCode FAIL]")
                        terminalLogs.add(if (tail.isNotBlank()) "$ $line\n$tail$cwdNote" else "$ $line\n(无输出)$cwdNote")
                    } catch (e: Throwable) {
                        anyFailure = true
                        results.add("[exec FAIL] ${e.message}")
                        terminalLogs.add("error: ${e.message}\n${getLastTerminalLinesStr()}")
                    }
                }
            }
        } catch (e: Throwable) {
            anyFailure = true
            results.add("error: ${e.message}")
        }
    }

    val resultStr = when {
        results.isNotEmpty() && terminalLogs.isNotEmpty() ->
            results.joinToString("; ") + "\n终端:\n" + terminalLogs.joinToString("\n")
        results.isNotEmpty() -> results.joinToString("; ")
        terminalLogs.isNotEmpty() -> terminalLogs.joinToString("\n")
        else -> "OK"
    }
    RunResult(
        resultStr,
        terminalLogs.joinToString("\n---\n").trim(),
        isSuccess = !anyFailure,
        depStorePackages = depStorePackages,
        pluginLaunchInfo = pluginLaunchInfo
    )
}

/** 从形如 funcName(...) 的调用中抽取括号内的完整内容，正确处理字符串里的括号和转义。 */
private fun extractParenContent(call: String, funcName: String): String? {
    val prefix = call.indexOf(funcName)
    if (prefix < 0) return null
    // funcName 之后紧跟的第一个字符必须是 '('
    val openIdx = prefix + funcName.length - 1
    if (call[openIdx] != '(') return null
    var depth = 1
    var i = openIdx + 1
    var inString = false
    var escape = false
    var quoteChar = ' '
    while (i < call.length) {
        val c = call[i]
        if (escape) {
            escape = false
            i++
            continue
        }
        if (inString) {
            if (c == '\\') escape = true
            else if (c == quoteChar) inString = false
            i++
            continue
        }
        when (c) {
            '"', '\'' -> { inString = true; quoteChar = c }
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return call.substring(openIdx + 1, i)
            }
        }
        i++
    }
    return null
}

/** 对字符串字面量内容做简单反转义，仅用于 exec/runJava 里的代码字符串，不用于 message 的 JSON。 */
private fun unescapeStringLiteral(raw: String): String =
    raw
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

/**
 * 从 runJava("...") 或 runJava('...') 中提取完整源码。
 *
 * 关键点：
 * - 只负责「找到成对的外层引号」，不再对内容做转义还原（保持与调用方写入的完全一致）。
 * - 对于内部的反斜杠，仅用于跳过后一个字符以避免错误识别结束引号，但会把 `\` 和后一个字符
 *   原样写回结果，避免破坏 Java 源码里的字符串字面量（例如 "C:\\temp"、"\"quoted\"" 等）。
 */
private fun extractRunJavaCode(call: String): String? {
    val prefix = "runJava("
    val startIdx = call.indexOf(prefix)
    if (startIdx < 0) return null
    var i = startIdx + prefix.length
    while (i < call.length && call[i].isWhitespace()) i++
    if (i >= call.length) return null
    val quoteChar = call[i]
    if (quoteChar != '"' && quoteChar != '\'') return null
    i++
    val sb = StringBuilder()
    while (i < call.length) {
        val c = call[i]
        when {
            c == '\\' -> {
                // 保留反斜杠本身，并把后一个字符原样写入，防止破坏 Java 源码中的转义序列
                if (i + 1 < call.length) {
                    sb.append('\\')
                    sb.append(call[i + 1])
                    i += 2
                } else {
                    sb.append('\\')
                    i++
                }
            }
            c == quoteChar -> return sb.toString()
            else -> { sb.append(c); i++ }
        }
    }
    return null
}

/** 从形如 func("...") 的调用中提取单个字符串参数，返回反转义后的内容。 */
private fun extractStringArg(call: String): String? {
    // 同时支持双引号与单引号：exec("pwd") 或 exec('pwd')
    val firstDouble = call.indexOf('"')
    val firstSingle = call.indexOf('\'')
    val firstQuote = listOf(firstDouble, firstSingle).filter { it >= 0 }.minOrNull() ?: return null
    val quoteChar = call[firstQuote]
    val lastQuote = call.lastIndexOf(quoteChar)
    if (lastQuote <= firstQuote) return null
    val raw = call.substring(firstQuote + 1, lastQuote)
    return unescapeStringLiteral(raw)
}

/** 解析形如 func("a", "b") 的两个字符串参数，返回 pair(a,b)，失败返回 null。 */
private fun parseTwoStringArgs(call: String): Pair<String, String>? {
    val text = call.trim()
    // 形如：exec( "id" , "cmd" )
    val openParen = text.indexOf('(')
    val closeParen = text.lastIndexOf(')')
    if (openParen < 0 || closeParen <= openParen) return null
    val inside = text.substring(openParen + 1, closeParen)
    // 按第一个逗号分成两部分，允许逗号两侧有空格
    val commaIndex = inside.indexOf(',')
    if (commaIndex <= 0) return null
    val firstPart = inside.substring(0, commaIndex).trim()
    val secondPart = inside.substring(commaIndex + 1).trim()

    fun stripQuotes(part: String): String? {
        if (part.length < 2) return null
        val quote = part.first()
        if (quote != '\'' && quote != '"') return null
        if (part.last() != quote) return null
        val raw = part.substring(1, part.length - 1)
        return unescapeStringLiteral(raw)
    }

    val sessionId = stripQuotes(firstPart) ?: return null
    val second = stripQuotes(secondPart) ?: return null
    return sessionId to second
}

/**
 * 在括号内容中按"未被引号包裹的逗号"分割，正确处理转义字符，
 * 用于解析 editFile("path", "old", "new") 等三参数工具调用。
 */
private fun splitUnquotedCommas(inside: String): List<String> {
    val parts = mutableListOf<String>()
    var i = 0
    var inQuote: Char? = null
    var start = 0
    while (i < inside.length) {
        val c = inside[i]
        when {
            inQuote == null && (c == '"' || c == '\'') -> inQuote = c
            inQuote != null && c == '\\' -> i++  // skip escaped char
            inQuote != null && c == inQuote -> inQuote = null
            inQuote == null && c == ',' -> {
                parts.add(inside.substring(start, i).trim())
                start = i + 1
            }
        }
        i++
    }
    parts.add(inside.substring(start).trim())
    return parts
}

/** 解析形如 func("a", "b", "c") 的三个字符串参数，失败返回 null。 */
private fun parseThreeStringArgs(call: String): Triple<String, String, String>? {
    val text = call.trim()
    val openParen = text.indexOf('(')
    val closeParen = text.lastIndexOf(')')
    if (openParen < 0 || closeParen <= openParen) return null
    val inside = text.substring(openParen + 1, closeParen)
    val parts = splitUnquotedCommas(inside)
    if (parts.size != 3) return null
    fun stripQuotes(part: String): String? {
        val p = part.trim()
        if (p.length < 2) return null
        val q = p.first()
        if (q != '\'' && q != '"') return null
        if (p.last() != q) return null
        return unescapeStringLiteral(p.substring(1, p.length - 1))
    }
    return Triple(
        stripQuotes(parts[0]) ?: return null,
        stripQuotes(parts[1]) ?: return null,
        stripQuotes(parts[2]) ?: return null
    )
}

/** 解析 exec("sessionId", "command")，返回 pair(sessionId, command)，失败返回 null。 */
private fun parseExecArgs(call: String): Pair<String, String>? =
    parseTwoStringArgs(call)

/** 解析 writeFile("path", "content")，返回 pair(path, content)，失败返回 null。 */
private fun parseWriteFileArgs(call: String): Pair<String, String>? =
    parseTwoStringArgs(call)

/** 解析 pkg("包名1", "包名2", ...) 的多个字符串参数，返回包名列表，失败返回 null。 */
private fun parsePkgArgs(call: String): List<String>? {
    val inside = extractParenContent(call.trim(), "pkg(") ?: return null
    val parts = splitUnquotedCommas(inside)
    if (parts.isEmpty()) return null
    fun stripQuotes(part: String): String? {
        val p = part.trim()
        if (p.length < 2) return null
        val q = p.first()
        if (q != '\'' && q != '"') return null
        if (p.last() != q) return null
        return unescapeStringLiteral(p.substring(1, p.length - 1))
    }
    val list = parts.mapNotNull { stripQuotes(it) }
    return if (list.isEmpty()) null else list
}


/**
 * 将 writeFile 的 path 解析为实际 File：
 * - 以 "/" 开头：视为绝对路径；
 * - 以 "~/" 开头：展开为 Termux HOME（/data/data/com.termux/files/home）下的路径；
 * - 其他：视为相对于 Termux HOME 的相对路径。
 */
private fun resolveWriteFilePath(context: Context, raw: String): File {
    return when {
        raw.startsWith("/") -> File(raw)
        raw.startsWith("~/") ->
            File("/data/data/com.termux/files/home", raw.removePrefix("~/"))
        else ->
            File("/data/data/com.termux/files/home", raw)
    }
}

