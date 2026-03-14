package com.termux.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏依赖安装页：展示每个依赖的下载/安装进度。
 * 由 SimpleExecutorActivity 在用户选择「仅安装必须项」或「全部安装」后启动。
 */
class DepInstallActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEPS = "deps"

        fun createIntent(context: android.content.Context, deps: List<DepInfo>): android.content.Intent {
            return android.content.Intent(context, DepInstallActivity::class.java).apply {
                putExtra(EXTRA_DEPS, ArrayList(deps))
            }
        }
    }

    private val dp: Float by lazy { resources.displayMetrics.density }
    private lateinit var contentLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var doneButton: Button
    private var retryButton: Button? = null
    private val rowViews = mutableListOf<DepRowView>()
    private var currentDeps: List<DepInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("UNCHECKED_CAST")
        val deps = intent.getSerializableExtra(EXTRA_DEPS) as? ArrayList<DepInfo> ?: emptyList<DepInfo>()
        if (deps.isEmpty()) {
            finish()
            return
        }
        currentDeps = deps
        buildUi(deps)
        startInstall(deps)
    }

    private fun buildUi(deps: List<DepInfo>) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding((24 * dp).toInt())
        }

        val title = TextView(this).apply {
            text = "正在安装依赖"
            textSize = 20f
            setTextColor(Color.parseColor("#111827"))
            setPadding(0, 0, 0, (16 * dp).toInt())
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "按顺序安装以下包，请勿退出本页"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 0, 0, (16 * dp).toInt())
        }
        root.addView(sub)

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        deps.forEach { dep ->
            val row = createDepRow(dep)
            contentLayout.addView(row.root)
            rowViews.add(row)
        }

        scrollView = ScrollView(this).apply {
            addView(contentLayout)
        }
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        doneButton = Button(this).apply {
            text = "全部完成后关闭"
            isEnabled = false
            setOnClickListener { finish() }
            setPadding((24 * dp).toInt(), (14 * dp).toInt(), (24 * dp).toInt(), (14 * dp).toInt())
        }
        val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * dp).toInt()
        }
        root.addView(doneButton, btnLp)
        retryButton = Button(this).apply {
            text = "重试失败项"
            visibility = View.GONE
            setOnClickListener { retryFailed() }
            setPadding((24 * dp).toInt(), (14 * dp).toInt(), (24 * dp).toInt(), (14 * dp).toInt())
        }
        root.addView(retryButton, btnLp)
        setContentView(root)
    }

    private data class DepRowView(
        val root: LinearLayout,
        val dot: TextView,
        val name: TextView,
        val progressBar: ProgressBar,
        val status: TextView,
        val output: TextView
    )

    private fun createDepRow(dep: DepInfo): DepRowView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        val cardLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (10 * dp).toInt()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = TextView(this).apply {
            text = "○"
            textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 0, (8 * dp).toInt(), 0)
        }
        val name = TextView(this).apply {
            text = dep.displayName
            textSize = 16f
            setTextColor(Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        val status = TextView(this).apply {
            text = "等待"
            textSize = 13f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        topRow.addView(dot)
        topRow.addView(name)
        topRow.addView(progressBar, LinearLayout.LayoutParams((80 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        topRow.addView(status)
        card.addView(topRow)

        val output = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, (6 * dp).toInt(), 0, 0)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        card.addView(output)
        card.layoutParams = cardLp
        return DepRowView(card, dot, name, progressBar, status, output)
    }

    private fun startInstall(deps: List<DepInfo>) {
        lifecycleScope.launch {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val failedIndices = mutableListOf<Int>()
            deps.forEachIndexed { index, dep ->
                val row = rowViews[index]
                withContext(Dispatchers.Main) {
                    setRowInstalling(row)
                }
                scrollToRow(index)
                val exitCode = runCatching {
                    execCommand("pkg install -y ${dep.pkgName}", sessionId = "dep_install") { out ->
                        val lastLine = out.lines().lastOrNull { it.isNotBlank() } ?: return@execCommand
                        mainHandler.post {
                            row.output.text = lastLine.trim()
                            row.output.visibility = View.VISIBLE
                        }
                    }
                }.getOrDefault(-1)
                withContext(Dispatchers.Main) {
                    row.progressBar.visibility = View.GONE
                    if (exitCode == 0) {
                        row.dot.text = "✓"
                        row.dot.setTextColor(Color.parseColor("#10B981"))
                        row.status.text = "完成"
                        row.status.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        row.dot.text = "✗"
                        row.dot.setTextColor(Color.parseColor("#EF4444"))
                        row.status.text = "失败"
                        row.status.setTextColor(Color.parseColor("#EF4444"))
                        failedIndices.add(index)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                doneButton.isEnabled = true
                doneButton.text = "关闭"
                if (failedIndices.isNotEmpty()) {
                    retryButton?.visibility = View.VISIBLE
                    retryButton?.isEnabled = true
                } else {
                    retryButton?.visibility = View.GONE
                }
            }
        }
    }

    private fun setRowInstalling(row: DepRowView) {
        row.dot.text = "●"
        row.dot.setTextColor(Color.parseColor("#F59E0B"))
        row.status.text = "安装中"
        row.status.setTextColor(Color.parseColor("#F59E0B"))
        row.progressBar.visibility = View.VISIBLE
        row.progressBar.isIndeterminate = true
        row.output.visibility = View.VISIBLE
        row.output.text = ""
    }

    private fun setRowWaiting(row: DepRowView) {
        row.dot.text = "○"
        row.dot.setTextColor(Color.parseColor("#9CA3AF"))
        row.status.text = "等待"
        row.status.setTextColor(Color.parseColor("#9CA3AF"))
        row.progressBar.visibility = View.GONE
        row.output.text = ""
        row.output.visibility = View.GONE
    }

    private fun retryFailed() {
        val failedIndices = rowViews.mapIndexed { index, row -> index to row }
            .filter { (_, row) -> row.status.text == "失败" }
            .map { it.first }
        if (failedIndices.isEmpty()) return
        retryButton?.isEnabled = false
        val toRetry = failedIndices.map { currentDeps[it] }
        failedIndices.forEach { index ->
            setRowWaiting(rowViews[index])
        }
        lifecycleScope.launch {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val stillFailed = mutableListOf<Int>()
            failedIndices.forEachIndexed { relIndex, absIndex ->
                val dep = toRetry[relIndex]
                val row = rowViews[absIndex]
                withContext(Dispatchers.Main) { setRowInstalling(row) }
                scrollToRow(absIndex)
                val exitCode = runCatching {
                    execCommand("pkg install -y ${dep.pkgName}", sessionId = "dep_install") { out ->
                        val lastLine = out.lines().lastOrNull { it.isNotBlank() } ?: return@execCommand
                        mainHandler.post {
                            row.output.text = lastLine.trim()
                            row.output.visibility = View.VISIBLE
                        }
                    }
                }.getOrDefault(-1)
                withContext(Dispatchers.Main) {
                    row.progressBar.visibility = View.GONE
                    if (exitCode == 0) {
                        row.dot.text = "✓"
                        row.dot.setTextColor(Color.parseColor("#10B981"))
                        row.status.text = "完成"
                        row.status.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        row.dot.text = "✗"
                        row.dot.setTextColor(Color.parseColor("#EF4444"))
                        row.status.text = "失败"
                        row.status.setTextColor(Color.parseColor("#EF4444"))
                        stillFailed.add(absIndex)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                retryButton?.isEnabled = true
                retryButton?.visibility = if (stillFailed.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun scrollToRow(index: Int) {
        val row = rowViews.getOrNull(index) ?: return
        scrollView.post {
            scrollView.smoothScrollTo(0, row.root.bottom)
        }
    }
}
