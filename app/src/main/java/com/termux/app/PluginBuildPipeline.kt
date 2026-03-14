package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties

object PluginBuildPipeline {

    private const val TAG = "TestPoint"

    private val PLUGIN_BUILD_OUTPUT_FORMAT = """
【输出格式】你必须只输出一个 JSON 对象，不要输出任何解释、路径、包名说明或 markdown。JSON 仅包含一个字段：
{
  "code": "代码"
}。
- code：唯一需要的字段，必须是可被 javac 直接编译的合法 Java 代码。
""".trimIndent()

    private const val TEMPLATE_PKG = "com.example.plugin"
    private const val TEMPLATE_ENTRY_ACTIVITY = "com.example.plugin.MainActivity"
    private val TEMPLATE_MAIN_JAVA = """
package com.example.plugin;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFDFDFD);
        TextView tv = new TextView(this);
        tv.setText("你好啊哈哈");
        tv.setTextSize(24);
        tv.setTextColor(Color.BLACK);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = android.view.Gravity.CENTER;
        root.addView(tv, lp);
        setContentView(root);
    }
}
""".trimIndent()

    private fun copyAssetDir(context: Context, assetDir: String, outDir: File) {
        val am = context.assets
        val list = am.list(assetDir) ?: return
        outDir.mkdirs()
        for (name in list) {
            val childAssetPath = "$assetDir/$name"
            val childOut = File(outDir, name)
            val sub = am.list(childAssetPath)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetDir(context, childAssetPath, childOut)
            } else {
                if (!childOut.exists()) {
                    am.open(childAssetPath).use { input ->
                        childOut.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
    private fun fixMultilineStringLiterals(javaCode: String): String {
        val sb = StringBuilder(javaCode.length + 128)
        var inStr = false
        var inChar = false
        var escaped = false

        for (c in javaCode) {
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }

            when (c) {
                '\\' -> {
                    sb.append(c)
                    if (inStr || inChar) escaped = true
                }
                '"' -> {
                    sb.append(c)
                    if (!inChar) inStr = !inStr
                }
                '\'' -> {
                    sb.append(c)
                    if (!inStr) inChar = !inChar
                }
                '\r' -> {
                    // 忽略 \r，统一交给 \n 处理
                    // 也可以 sb.append("\\n")，但通常下一位就是 \n
                }
                '\n' -> {
                    if (inStr) sb.append("\\n") else sb.append('\n')
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val PLUGIN_BUILD_CODE_RULES = """
【代码规范】
1. **只生成一个 Activity**：包名固定为 com.example.plugin，类名为 MainActivity。
2. **必须继承 android.app.Activity**：禁止 extends AppCompatActivity、FragmentActivity 或 androidx 任何类。
3. **禁止使用任何第三方库**：仅允许 java.* 和 android.*。界面仅使用 Android 自带控件（TextView、LinearLayout、FrameLayout、Button 等）。
4. **禁止使用 R 资源**：必须用纯代码布局（new TextView、setContentView(root)），禁止 setContentView(R.layout.xxx)、R.drawable.xxx 等。
""".trimIndent()

    private val PLUGIN_BUILD_PROMPT_USER_CARE = """
【用户关怀】请根据下方「用户最近聊天内容」，在生成的 Activity 中体现帮助、惊喜或关怀：
- 界面文案、标题或功能与用户话题相关；
- 提供与用户需求相关的快捷入口或引导；
- UI尽量美观,推荐使用卡片,canvas,圆角等风格；
""".trimIndent()


    fun buildActivityGenerationPrompt(context: Context): String {
        val autoBuildBlock = getAutoBuildHistoryForPrompt(context)
        val userBlock = getLastUserMessagesForPrompt(context, 10)
        val parts = listOf(
            PLUGIN_BUILD_PROMPT_USER_CARE,
            PLUGIN_BUILD_CODE_RULES,
            autoBuildBlock,
            if (userBlock.isNotEmpty()) userBlock else "暂无历史消息，按通用场景生成即可。",
            PLUGIN_BUILD_OUTPUT_FORMAT
        )
        return parts.joinToString("\n\n")
    }

    private fun extractJsonFromReply(reply: String): String? {
        val t = reply.trim()
        val jsonBlock = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE).find(t)
        if (jsonBlock != null) {
            val inner = jsonBlock.groupValues.getOrNull(1)?.trim() ?: return null
            if (inner.isNotEmpty()) return inner
        }
        val first = t.indexOf('{')
        val last = t.lastIndexOf('}')
        if (first in 0..last) return t.substring(first, last + 1)
        return null
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        Log.d(TAG, "开始构建插件...")

        val buildRoot = File(context.filesDir, "plugin_build").apply { mkdirs() }
        val srcDir = File(buildRoot, "src").apply { mkdirs() }
        val classDir = File(buildRoot, "classes").apply { mkdirs() }
        val outDir = File(buildRoot, "out").apply { mkdirs() }
        srcDir.deleteRecursively(); srcDir.mkdirs()
        classDir.deleteRecursively(); classDir.mkdirs()
        outDir.deleteRecursively(); outDir.mkdirs()

        File(buildRoot, "tmp.jar").delete()
        File(outDir, "classes.dex").delete()
        var pkgName = TEMPLATE_PKG
        var entryActivity = TEMPLATE_ENTRY_ACTIVITY
        var usedTemplate = true

        val prompt = buildActivityGenerationPrompt(context)
        val replyStr = try {
            callAI(context, prompt)
        } catch (t: Throwable) {
            Log.e(TAG, "AI 调用失败", t)
            null
        }
        if (replyStr != null && replyStr.startsWith("Error:")) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "模型或 API Key 错误，请检查抽屉内 AI 配置",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        if (replyStr != null && !replyStr.startsWith("Error:")) {
            val jsonStr = extractJsonFromReply(replyStr)
            if (jsonStr != null) {
                try {
                    val json = JSONObject(jsonStr)
                    val activityJavaRaw = json.optString("code", "").trim()
                    val activityJava = fixMultilineStringLiterals(activityJavaRaw)
                    Log.e(TAG, activityJava)
                    if (activityJava.isNotBlank()) {
                        srcDir.deleteRecursively(); srcDir.mkdirs()
                        val f = File(srcDir, "com/example/plugin/MainActivity.java")
                        f.parentFile?.mkdirs()
                        f.writeText(activityJava, Charsets.UTF_8)
                        usedTemplate = false
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "解析 AI JSON 失败，使用模板", e)
                }
            } else {
                Log.w(TAG, "未从 AI 回复中解析到 JSON，使用模板")
            }
        } else {
            Log.w(TAG, "AI 未返回有效回复，使用模板")
        }

        if (usedTemplate) {
            pkgName = TEMPLATE_PKG
            entryActivity = TEMPLATE_ENTRY_ACTIVITY
            srcDir.listFiles()?.forEach { it.deleteRecursively() }
            File(srcDir, "com/example/plugin/MainActivity.java").apply {
                parentFile?.mkdirs()
                writeText(TEMPLATE_MAIN_JAVA)
            }
        }

        val androidJar = File(buildRoot, "android.jar")
        if (!androidJar.exists()) {
            context.assets.open("android.jar").use { input ->
                androidJar.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val libDir = File(context.filesDir, "lib")
        copyAssetDir(context, "lib", libDir)

        val javaFiles = srcDir.walkTopDown().filter { it.extension == "java" }.map { it.absolutePath }
        if (javaFiles.none()) {
            Log.w(TAG, "没有 Java 源文件")
            return@withContext
        }

        val JAVAC = "/data/data/com.termux/files/usr/bin/javac"
        val JAR = "/data/data/com.termux/files/usr/bin/jar"
        val JAVA = "/data/data/com.termux/files/usr/bin/java"

        val javacCmd = """
            $JAVAC --release 8 \
            -classpath ${androidJar.absolutePath} \
            -d ${classDir.absolutePath} \
            ${javaFiles.joinToString(" ")}
        """.trimIndent()

        try {
            execCommand(javacCmd)
        } catch (t: Throwable) {
            Log.e(TAG, "javac 失败", t)
            return@withContext
        }

        val jarFile = File(buildRoot, "tmp.jar")
        val jarCmd = "cd ${classDir.absolutePath} && $JAR cf ${jarFile.absolutePath} ."
        try {
            execCommand(jarCmd)
        } catch (t: Throwable) {
            Log.e(TAG, "jar 失败", t)
            return@withContext
        }


        val d8Cmd = """
$JAVA -cp '${libDir.absolutePath}/*' com.android.tools.r8.D8 \
${jarFile.absolutePath} \
--lib ${androidJar.absolutePath} \
--min-api 26 \
--output ${outDir.absolutePath}
""".trimIndent()
        try {
            execCommand(d8Cmd)

        } catch (t: Throwable) {
            Log.e(TAG, "d8 失败", t)
            return@withContext
        }

        val dexFile = File(outDir, "classes.dex")
        if (!dexFile.exists()) {
            Log.w(TAG, "d8 未生成 classes.dex")
            return@withContext
        }

        val pluginDir = File(context.filesDir, DYNAMIC_PLUGIN_DIR).apply { mkdirs() }
        val targetDex = File(pluginDir, PLUGIN_DEX_NAME)
        dexFile.copyTo(targetDex, overwrite = true)

        val props = Properties().apply {
            setProperty("entry_activity", entryActivity)
            setProperty("pkg_name", pkgName)
        }
        val configFile = File(pluginDir, PLUGIN_CONFIG_NAME)
        configFile.outputStream().use { props.store(it, "Generated by PluginBuildPipeline") }

        Log.d(TAG, "插件构建完成: $targetDex, entry=$entryActivity")

        // 构建完成发送通知
        sendBuildCompleteNotification(context, entryActivity)
    }

    private fun sendTerminalNotReadyNotification(context: Context) {
        val channelId = "plugin_build_fail"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "插件构建", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val n = NotificationCompat.Builder(context, channelId)
            .setContentTitle("插件构建失败")
            .setContentText("终端未就绪，请先点击「对话」进入后再试")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        nm.notify(9003, n)
    }

    private fun sendBuildCompleteNotification(context: Context, entryActivity: String) {
        val channelId = "plugin_build_done"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "插件构建完成",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("插件构建完成")
            .setContentText("下次启动将加载 $entryActivity")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(9002, notification)
    }
}
