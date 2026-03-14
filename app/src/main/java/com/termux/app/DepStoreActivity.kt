package com.termux.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 依赖商店：推荐安装列表（可勾选）+ 手动输入 pkg 包名，选好后进入安装页。
 * 可通过 Intent 的 EXTRA_PREFILL_PACKAGES（ArrayList<String>）预填待安装包名。
 */
class DepStoreActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL_PACKAGES = "prefill_packages"
        /** 是否只展示必装依赖（用于从对话页自动跳转时的精简模式） */
        const val EXTRA_REQUIRED_ONLY = "required_only"
    }

    private val dp: Float by lazy { resources.displayMetrics.density }
    private val recommendedWithStatus = mutableListOf<Pair<DepInfo, Boolean>>()
    /** 推荐区中“可选依赖”的复选框与对应依赖（不含必装项） */
    private val checkboxes = mutableListOf<Pair<CheckBox, DepInfo>>()
    /** 用户在完整模式下手动添加的额外包名 */
    private val manualPkgs = mutableListOf<String>()
    /** AI 通过 pkg() 预填的依赖包名集合，用于在推荐列表中自动勾选 */
    private val prefillPkgs = mutableSetOf<String>()
    private lateinit var installButton: Button
    private lateinit var manualInput: EditText
    private lateinit var manualListLabel: TextView
    /** 是否为精简模式（仅必装依赖），由 Intent 决定 */
    private var requiredOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requiredOnly = intent.getBooleanExtra(EXTRA_REQUIRED_ONLY, false)

        // 每次进入都重新检测是否已安装。
        // - 精简模式：只展示必装依赖（当前为 Bash / OpenJDK）；
        // - 完整模式：展示所有推荐依赖（bash / openjdk / python / git 等）。
        recommendedWithStatus.clear()
        if (requiredOnly) {
            recommendedWithStatus.addAll(
                getAllRecommendedDepsWithStatus(this).filter { (dep, _) -> dep.required }
            )
        } else {
            recommendedWithStatus.addAll(getAllRecommendedDepsWithStatus(this))
        }

        // 记录已有推荐项的 pkgName，方便后续去重
        val existingPkgNames = recommendedWithStatus.map { it.first.pkgName }.toMutableSet()

        // 处理来自 AI 的 pkg("bash","python",...) 预填依赖：
        // - 若在推荐列表中已存在，则后面自动勾选对应复选框；
        // - 若不存在，则追加一条样式相同的依赖行（displayName 使用包名本身）。
        val prefill = intent.getStringArrayListExtra(EXTRA_PREFILL_PACKAGES)
        if (!prefill.isNullOrEmpty()) {
            prefill.forEach { raw ->
                val pkg = raw.trim()
                if (pkg.isEmpty()) return@forEach
                prefillPkgs.add(pkg)
                if (!existingPkgNames.contains(pkg)) {
                    val info = DepInfo(displayName = pkg, pkgName = pkg, required = false)
                    // 动态添加的包目前无法精确探测是否已安装，这里统一视为“未安装，可选”
                    val installed = false
                    recommendedWithStatus.add(info to installed)
                    existingPkgNames.add(pkg)
                }
            }
        }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        val title = TextView(this).apply {
            text = "依赖商店"
            textSize = 22f
            setTextColor(Color.parseColor("#111827"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = if (requiredOnly) {
                "安装运行 AI 任务所必需的 Termux 依赖（如 Bash、OpenJDK），以及本次任务自动检测到的缺失包。"
            } else {
                "选择要安装的 pkg 包：可从推荐列表勾选，也可以手动输入包名添加。"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 0, 0, (20 * dp).toInt())
        }
        root.addView(sub)

        // 推荐安装
        val recTitle = TextView(this).apply {
            text = "推荐安装"
            textSize = 16f
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        root.addView(recTitle)

        val recList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        checkboxes.clear()
        recommendedWithStatus.forEachIndexed { index, (dep, installed) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }
            val cb = CheckBox(this).apply {
                // 对于必装依赖（如 Bash、OpenJDK），用户不能取消勾选；未安装时强制安装。
                // 普通推荐依赖不默认全选，但对于 AI 通过 pkg() 明确给出的依赖，会自动勾选。
                isChecked = !installed && (dep.required || prefillPkgs.contains(dep.pkgName))
                isEnabled = !installed && !dep.required
                if (dep.required) {
                    // 必装项不作为“可选依赖”，因此不加入 checkboxes 列表
                    // 这里保留勾选图标但禁用点击，让用户感知为必选项
                }
                setPadding(0, 0, (12 * dp).toInt(), 0)
                setOnCheckedChangeListener { _, _ -> refreshInstallButton() }
            }
            val label = TextView(this).apply {
                text = when {
                    dep.required && installed ->
                        "✓ 必需 · ${dep.displayName} (${dep.pkgName})"
                    dep.required && !installed ->
                        "必需 · ${dep.displayName} (${dep.pkgName})"
                    installed ->
                        "✓ ${dep.displayName} (${dep.pkgName})"
                    else ->
                        "${dep.displayName} (${dep.pkgName})"
                }
                textSize = 14f
                setTextColor(if (installed) Color.parseColor("#9CA3AF") else Color.parseColor("#111827"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(cb)
            row.addView(label)
            recList.addView(row)

            // 已安装且非必装依赖：长按行触发卸载流程
            if (installed && !dep.required) {
                row.setOnLongClickListener {
                    startUninstall(listOf(dep))
                    true
                }
                label.setOnLongClickListener {
                    startUninstall(listOf(dep))
                    true
                }
            }

            // 仅“可选依赖”加入复选框列表；必装项始终由逻辑强制安装，不依赖勾选状态
            if (!dep.required) {
                checkboxes.add(cb to dep)
            }
        }
        root.addView(recList)

        // 仅在完整模式下展示「手动添加包名」相关 UI
        if (!requiredOnly) {
            val sep = View(this).apply {
                setBackgroundColor(Color.parseColor("#E5E7EB"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * dp).toInt()
                ).apply {
                    topMargin = (20 * dp).toInt()
                    bottomMargin = (16 * dp).toInt()
                }
            }
            root.addView(sep)

            val manualTitle = TextView(this).apply {
                text = "手动添加包名"
                textSize = 16f
                setTextColor(Color.parseColor("#374151"))
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            root.addView(manualTitle)

            val manualRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            manualInput = EditText(this).apply {
                hint = "输入 pkg 包名，如 python、nodejs-lts、clang"
                textSize = 14f
                setPadding(
                    (12 * dp).toInt(),
                    (10 * dp).toInt(),
                    (12 * dp).toInt(),
                    (10 * dp).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val addBtn = Button(this).apply {
                text = "添加"
                setOnClickListener { addManualPkg() }
            }
            manualRow.addView(
                manualInput,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            manualRow.addView(addBtn)
            root.addView(manualRow)

            manualListLabel = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#4B5563"))
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            root.addView(manualListLabel)
        }

        val scroll = ScrollView(this).apply { addView(root) }
        installButton = Button(this).apply {
            text = "开始安装"
            isEnabled = false
            setOnClickListener { startInstall() }
            setPadding((24 * dp).toInt(), (14 * dp).toInt(), (24 * dp).toInt(), (14 * dp).toInt())
        }
        val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * dp).toInt()
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F9FAFB"))
        }
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(installButton, btnLp)
        setContentView(outer)
        refreshInstallButton()
        if (!requiredOnly) {
            refreshManualList()
        }
    }

    private fun buildSelectedDeps(): List<DepInfo> {
        val list = mutableListOf<DepInfo>()
        // 必装依赖（如 Bash/OpenJDK）：如果未安装，则强制加入待安装列表
        recommendedWithStatus.forEach { (dep, installed) ->
            if (dep.required && !installed) {
                list.add(dep)
            }
        }

        // 普通推荐依赖：仅在用户勾选复选框时才加入
        checkboxes.forEach { (cb, dep) ->
            val installed = recommendedWithStatus.firstOrNull { it.first.pkgName == dep.pkgName }?.second ?: false
            if (!installed && cb.isChecked) {
                list.add(dep)
            }
        }

        // 完整模式下：将手动添加的额外包名加入安装列表
        if (!requiredOnly) {
            manualPkgs.forEach { pkg ->
                list.add(DepInfo(displayName = pkg, pkgName = pkg, required = false))
            }
        }

        return list
    }

    private fun refreshInstallButton() {
        installButton.isEnabled = buildSelectedDeps().isNotEmpty()
    }

    private fun addManualPkg() {
        if (requiredOnly) return
        val name = manualInput.text.toString().trim()
        if (name.isBlank()) return
        if (manualPkgs.contains(name)) return
        manualPkgs.add(name)
        manualInput.text.clear()
        refreshManualList()
        refreshInstallButton()
    }

    private fun refreshManualList() {
        if (requiredOnly) return
        if (!::manualListLabel.isInitialized) return
        if (manualPkgs.isEmpty()) {
            manualListLabel.visibility = View.GONE
        } else {
            manualListLabel.visibility = View.VISIBLE
            manualListLabel.text = "待安装：${manualPkgs.joinToString(", ")}"
        }
    }

    private fun startInstall() {
        val deps = buildSelectedDeps()
        if (deps.isEmpty()) return
        startActivity(DepInstallActivity.createIntent(this, deps))
        finish()
    }

    private fun startUninstall(deps: List<DepInfo>) {
        if (deps.isEmpty()) return
        startActivity(DepUninstallActivity.createIntent(this, deps))
    }
}
