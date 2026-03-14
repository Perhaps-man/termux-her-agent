package com.termux.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.Properties

/** 插件运行时：ClassLoader */
data class PluginRuntime(val classLoader: ClassLoader)

object DexLoaderRegistry {
    private val runtimeMap = HashMap<String, PluginRuntime>()

    fun registerActivity(
        targetClassName: String,
        classLoader: ClassLoader
    ) {
        runtimeMap[targetClassName] = PluginRuntime(classLoader = classLoader)
    }

    fun getRuntime(targetClassName: String): PluginRuntime? = runtimeMap[targetClassName]
}

/** 从源码目录扫描继承 Activity/AppCompatActivity 的类名 */
fun collectActivityClassNames(srcDir: File, pkgName: String): List<String> {
    if (!srcDir.exists()) return emptyList()
    val result = mutableListOf<String>()
    srcDir.walkTopDown()
        .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
        .forEach { file ->
            val text = file.readText()
            if (
                text.contains("class ") &&
                (
                    text.contains("extends Activity") ||
                    text.contains("extends AppCompatActivity") ||
                    text.contains(": Activity") ||
                    text.contains(": AppCompatActivity")
                )
            ) {
                result += "$pkgName.${file.nameWithoutExtension}"
            }
        }
    return result
}

/** 动态加载并启动插件入口 Activity */
fun startDynamicActivity(
    context: Context,
    dexPath: String,
    entryActivity: String,
    pkgName: String,
    projectRoot: File?
) {
    val optDir = File(context.filesDir, "opt_dex").apply { mkdirs() }
    val loader = DexClassLoader(dexPath, optDir.absolutePath, null, context.classLoader)

    val allActivities = if (projectRoot != null) {
        val pkgPath = pkgName.replace('.', '/')
        val srcDir = File(projectRoot, "app/src/main/java/$pkgPath")
        collectActivityClassNames(srcDir, pkgName)
    } else {
        emptyList()
    }

    val activitiesToRegister = if (allActivities.isEmpty()) {
        listOf(entryActivity)
    } else {
        (listOf(entryActivity) + allActivities).distinct()
    }

    Log.d(TAG, "注册插件 Activity: $activitiesToRegister")

    activitiesToRegister.forEach { cls ->
        DexLoaderRegistry.registerActivity(
            targetClassName = cls,
            classLoader = loader
        )
    }

    val intent = Intent().apply {
        component = ComponentName(context.packageName, StartActivityHook.STUB_CLASS)
        putExtra(StartActivityHook.EXTRA_TARGET_CLASS, entryActivity)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

// --------------- 启动页检测：自定义文件夹下是否有插件依赖 ---------------

/** 插件依赖目录名（位于 context.filesDir 下） */
val DYNAMIC_PLUGIN_DIR = "dynamic_plugin"

/** 所需文件 */
const val PLUGIN_DEX_NAME = "plugin.dex"
const val PLUGIN_CONFIG_NAME = "plugin_config.properties"

private const val TAG = "DynamicPlugin"

/** 配置 key */
private const val KEY_ENTRY_ACTIVITY = "entry_activity"
private const val KEY_PKG_NAME = "pkg_name"
private const val KEY_PROJECT_ROOT = "project_root"

/**
 * 是否已安装插件（存在 plugin.dex 与 plugin_config.properties）。
 */
fun hasPluginInstalled(context: Context): Boolean {
    val dir = File(context.filesDir, DYNAMIC_PLUGIN_DIR)
    val dexFile = File(dir, PLUGIN_DEX_NAME)
    val configFile = File(dir, PLUGIN_CONFIG_NAME)
    return dexFile.isFile && configFile.isFile
}

/**
 * 当前配置的插件入口 Activity 类名；未安装或配置不完整时返回 null。
 * 用于判断当前界面是否为「作为启动界面的插件 Activity」。
 */
fun getPluginEntryActivityClassName(context: Context): String? {
    val dir = File(context.filesDir, DYNAMIC_PLUGIN_DIR)
    val configFile = File(dir, PLUGIN_CONFIG_NAME)
    if (!configFile.isFile) return null
    val props = Properties().apply {
        try {
            configFile.inputStream().use { load(it) }
        } catch (_: Throwable) { return null }
    }
    return props.getProperty(KEY_ENTRY_ACTIVITY)?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * 重置为默认：删除插件 dex 与配置文件，下次启动将走普通启动页。
 */
fun resetPluginToDefault(context: Context) {
    val dir = File(context.filesDir, DYNAMIC_PLUGIN_DIR)
    File(dir, PLUGIN_DEX_NAME).takeIf { it.exists() }?.delete()
    File(dir, PLUGIN_CONFIG_NAME).takeIf { it.exists() }?.delete()
    Log.d(TAG, "已重置插件（删除 dex 与 config）")
}

/**
 * 检测 [DYNAMIC_PLUGIN_DIR] 下是否有所需依赖（plugin.dex、plugin_config.properties）。
 * 若有且配置合法，则加载并启动插件入口 Activity，返回 true；否则返回 false。
 */
fun tryStartDynamicPlugin(context: Context): Boolean {
    val dir = File(context.filesDir, DYNAMIC_PLUGIN_DIR)
    val dexFile = File(dir, PLUGIN_DEX_NAME)
    val configFile = File(dir, PLUGIN_CONFIG_NAME)

    if (!dexFile.isFile || !configFile.isFile()) {
        Log.d(TAG, "插件依赖不完整: dir=${dir.absolutePath}, dex=${dexFile.exists()}, config=${configFile.exists()}")
        return false
    }

    val props = Properties().apply {
        configFile.inputStream().use { load(it) }
    }
    val entryActivity = props.getProperty(KEY_ENTRY_ACTIVITY)?.trim()
    val pkgName = props.getProperty(KEY_PKG_NAME)?.trim()
    if (entryActivity.isNullOrBlank() || pkgName.isNullOrBlank()) {
        Log.w(TAG, "plugin_config.properties 缺少 entry_activity 或 pkg_name")
        return false
    }

    val projectRootStr = props.getProperty(KEY_PROJECT_ROOT)?.trim()
    val projectRoot = if (projectRootStr.isNullOrBlank()) null else File(projectRootStr)

    try {
        startDynamicActivity(
            context = context,
            dexPath = dexFile.absolutePath,
            entryActivity = entryActivity,
            pkgName = pkgName,
            projectRoot = projectRoot
        )
        Log.d(TAG, "已启动插件: $entryActivity")
        return true
    } catch (t: Throwable) {
        Log.e(TAG, "启动插件失败", t)
        return false
    }
}
