package com.termux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.termux.R

data class HerChatItem(
    val isUser: Boolean,
    var content: String,
    var progress: String? = null,
    var result: String? = null,
    var webHtml: String? = null,
    var running: Boolean = false,
    var webDimmed: Boolean = false,
    var resultExpanded: Boolean = false,
    var attachmentPath: String? = null,
    var attachmentLabel: String? = null,
    var depStorePackages: List<String>? = null,
    var pluginDexPath: String? = null,
    var pluginEntryActivity: String? = null,
    var pluginPkgName: String? = null
) {
    fun isWebBlock(): Boolean = !isUser && webHtml != null
    fun isAttachmentBlock(): Boolean = !isUser && attachmentPath != null
    fun isDepStoreBlock(): Boolean = !isUser && !depStorePackages.isNullOrEmpty()
    fun isPluginBlock(): Boolean = !isUser && !pluginDexPath.isNullOrBlank() && !pluginEntryActivity.isNullOrBlank()
}

class HerChatAdapter(
    private var items: MutableList<HerChatItem> = mutableListOf(),
    private val onWebCardClick: ((String) -> Unit)? = null,
    private val onDepStoreCardClick: ((List<String>) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
        private const val TYPE_WEB = 2
        private const val TYPE_ATTACHMENT = 3
        private const val TYPE_DEP_STORE = 4
        private const val TYPE_PLUGIN = 5
        /** 结果区超过多少个字符时折叠显示 */
        private const val RESULT_MAX_CHARS = 50

        private fun copyToClipboard(context: Context, text: String?) {
            if (text.isNullOrEmpty()) return
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("her_chat", text))
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemViewType(position: Int): Int =
        when {
            items[position].isWebBlock() -> TYPE_WEB
            items[position].isAttachmentBlock() -> TYPE_ATTACHMENT
            items[position].isDepStoreBlock() -> TYPE_DEP_STORE
            items[position].isPluginBlock() -> TYPE_PLUGIN
            items[position].isUser -> TYPE_USER
            else -> TYPE_AI
        }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_USER -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_her_user, parent, false)
                UserHolder(v)
            }
            TYPE_WEB -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_her_web, parent, false)
                WebHolder(v, onWebCardClick)
            }
            TYPE_ATTACHMENT -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_attachment, parent, false)
                AttachmentHolder(v)
            }
            TYPE_DEP_STORE -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_dep_store, parent, false)
                DepStoreHolder(v, onDepStoreCardClick)
            }
            TYPE_PLUGIN -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_plugin_launch, parent, false)
                PluginHolder(v)
            }
            else -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_her_ai, parent, false)
                AIHolder(v)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is UserHolder -> holder.bind(item.content)
            is AIHolder -> holder.bind(
                item.content,
                item.progress,
                item.result,
                item.running,
                item.resultExpanded
            ) { toggleResultExpand(position) }
            is WebHolder -> holder.bind(item.webHtml ?: "")
            is AttachmentHolder -> holder.bind(item.attachmentLabel ?: "", item.attachmentPath ?: "")
            is DepStoreHolder -> holder.bind(item.depStorePackages ?: emptyList())
            is PluginHolder -> holder.bind(item)
            else -> {}
        }
    }

    fun toggleResultExpand(position: Int) {
        if (position !in items.indices || items[position].isUser || items[position].webHtml != null || items[position].depStorePackages != null) return
        items[position].resultExpanded = !items[position].resultExpanded
        notifyItemChanged(position)
    }

    fun addUserMessage(text: String) {
        items.add(HerChatItem(isUser = true, content = text))
        notifyItemInserted(items.size - 1)
    }

    fun addAiMessage(
        content: String,
        progress: String? = null,
        result: String? = null,
        running: Boolean = false
    ): Int {
        val pos = items.size
        items.add(
            HerChatItem(
                isUser = false,
                content = content,
                progress = progress,
                result = result,
                running = running
            )
        )
        notifyItemInserted(items.size - 1)
        return pos
    }

    fun addAiTaskBubble(taskDesc: String): Int {
        val pos = items.size
        items.add(HerChatItem(isUser = false, content = taskDesc, running = true))
        notifyItemInserted(items.size - 1)
        return pos
    }

    fun updateAiAt(
        index: Int,
        content: String? = null,
        progress: String? = null,
        result: String? = null,
        running: Boolean? = null
    ) {
        if (index < 0 || index >= items.size) return
        val item = items[index]
        if (item.isUser || item.webHtml != null || item.depStorePackages != null) return
        if (content != null) item.content = content
        if (progress != null) item.progress = progress
        if (result != null) item.result = result
        if (running != null) item.running = running
        notifyItemChanged(index)
    }

    fun addWebBubble(html: String) {
        items.add(HerChatItem(isUser = false, content = "", webHtml = html, webDimmed = false))
        notifyItemInserted(items.size - 1)
    }

    fun addAttachmentCard(label: String, path: String) {
        items.add(
            HerChatItem(
                isUser = false,
                content = "",
                attachmentPath = path,
                attachmentLabel = label
            )
        )
        notifyItemInserted(items.size - 1)
    }

    /** 插入安装提醒卡片，点击跳转依赖商店；packages 会传给商店预填 */
    fun addDepStoreCard(packages: List<String>) {
        if (packages.isEmpty()) return
        items.add(
            HerChatItem(
                isUser = false,
                content = "",
                depStorePackages = packages.toList()
            )
        )
        notifyItemInserted(items.size - 1)
    }

    /** 插入「运行 Activity」卡片，点击后通过 hook 启动 runJava 生成的界面 */
    fun addPluginCard(title: String, dexPath: String, entryActivity: String, pkgName: String) {
        items.add(
            HerChatItem(
                isUser = false,
                content = title,
                pluginDexPath = dexPath,
                pluginEntryActivity = entryActivity,
                pluginPkgName = pkgName
            )
        )
        notifyItemInserted(items.size - 1)
    }

    fun updateLastAi(content: String? = null, progress: String? = null, result: String? = null, running: Boolean? = null) {
        val last = items.indices.lastOrNull { i -> !items[i].isUser && items[i].webHtml == null } ?: return
        val item = items[last]
        if (content != null) item.content = content
        if (progress != null) item.progress = progress
        if (result != null) item.result = result
        if (running != null) item.running = running
        notifyItemChanged(last)
    }

    fun lastAiIndex(): Int = items.indices.lastOrNull { i -> !items[i].isUser && items[i].webHtml == null } ?: -1

    fun stopAllRunning(messageIfEmptyResult: String? = null) {
        var changed = false
        items.forEach { item ->
            if (!item.isUser && item.running) {
                item.running = false
                if (!messageIfEmptyResult.isNullOrBlank() && item.result.isNullOrBlank()) {
                    item.result = messageIfEmptyResult
                }
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    fun getItems(): List<HerChatItem> = items.toList()

    fun setItems(newItems: List<HerChatItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun dimAllWebViews() {
        var changed = false
        items.forEach { item ->
            if (item.webHtml != null && !item.webDimmed) {
                item.webDimmed = true
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    class UserHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tv_text)
        fun bind(text: String) {
            tvText.text = text
            // 长按用户气泡复制文本
            tvText.setOnLongClickListener {
                copyToClipboard(itemView.context, text)
                true
            }
        }
    }

    class AIHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusLoading: View = itemView.findViewById(R.id.ai_status_loading)
        private val statusDone: View = itemView.findViewById(R.id.ai_status_done)
        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val tvProgress: TextView = itemView.findViewById(R.id.tv_progress)
        private val resultRow: View = itemView.findViewById(R.id.layout_result_row)
        private val tvResult: TextView = itemView.findViewById(R.id.tv_result)
        private val tvResultCopy: TextView = itemView.findViewById(R.id.tv_result_copy)
        private val tvResultExpandHint: TextView = itemView.findViewById(R.id.tv_result_expand_hint)

        fun bind(
            content: String,
            progress: String?,
            result: String?,
            running: Boolean,
            resultExpanded: Boolean = false,
            onToggleResultExpand: (() -> Unit)? = null
        ) {
            tvContent.text = content
            // 长按 AI 主内容复制
            tvContent.setOnLongClickListener {
                copyToClipboard(itemView.context, content)
                true
            }
            statusLoading.visibility = if (running) View.VISIBLE else View.GONE
            statusDone.visibility = if (running) View.GONE else View.VISIBLE

            // 终端进度块：有内容且正在运行时显示
            if (!progress.isNullOrBlank() && running) {
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = progress
            } else {
                tvProgress.visibility = View.GONE
            }

            if (result.isNullOrBlank()) {
                resultRow.visibility = View.GONE
                tvResult.visibility = View.GONE
                tvResultCopy.visibility = View.GONE
                tvResultExpandHint.visibility = View.GONE
                tvResult.setOnClickListener(null)
                tvResultExpandHint.setOnClickListener(null)
                tvResultCopy.setOnClickListener(null)
                return
            }
            resultRow.visibility = View.VISIBLE
            tvResult.visibility = View.VISIBLE
            tvResultCopy.visibility = View.VISIBLE

            val totalChars = result.length
            if (totalChars <= RESULT_MAX_CHARS) {
                tvResult.text = result
                tvResult.setOnClickListener(null)
                tvResultExpandHint.visibility = View.GONE
                tvResultExpandHint.setOnClickListener(null)
            } else {
                val toggle: (View) -> Unit = { onToggleResultExpand?.invoke() }
                tvResult.setOnClickListener(toggle)
                tvResultExpandHint.setOnClickListener(toggle)
                tvResultExpandHint.visibility = View.VISIBLE
                if (resultExpanded) {
                    tvResult.text = result
                    tvResultExpandHint.text = "收起"
                } else {
                    val preview = result.take(RESULT_MAX_CHARS)
                    tvResult.text = preview + "..."
                    tvResultExpandHint.text = "展开剩余 ${totalChars - RESULT_MAX_CHARS} 字"
                }
            }

            // “复制”按钮点击：复制完整 result 文本
            tvResultCopy.setOnClickListener {
                copyToClipboard(itemView.context, result)
            }

            // 长按结果卡片本身也可以复制完整文本
            tvResult.setOnLongClickListener {
                copyToClipboard(itemView.context, result)
                true
            }
        }
    }

    class WebHolder(
        itemView: View,
        private val onWebCardClick: ((String) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.web_card_root)

        fun bind(html: String) {
            cardRoot.setOnClickListener {
                onWebCardClick?.invoke(html)
            }
        }
    }

    class AttachmentHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.attachment_card_root)
        private val tvLabel: TextView = itemView.findViewById(R.id.tv_attachment_label)

        fun bind(label: String, path: String) {
            tvLabel.text = label
            cardRoot.setOnClickListener {
                val ok = HerSystemRuntime.openFile(itemView.context, path)
                if (!ok) {
                    Toast.makeText(
                        itemView.context,
                        "无法打开附件：$label",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    class DepStoreHolder(
        itemView: View,
        private val onDepStoreCardClick: ((List<String>) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.dep_store_card_root)
        private val tvPackages: TextView = itemView.findViewById(R.id.tv_dep_store_packages)

        fun bind(packages: List<String>) {
            tvPackages.text = packages.joinToString(", ")
            cardRoot.setOnClickListener {
                onDepStoreCardClick?.invoke(packages)
            }
        }
    }

    class PluginHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.plugin_card_root)

        fun bind(item: HerChatItem) {
            val entry = item.pluginEntryActivity ?: return
            val dexPath = item.pluginDexPath ?: return
            val pkgName = item.pluginPkgName ?: itemView.context.packageName
            cardRoot.setOnClickListener {
                try {
                    startDynamicActivity(
                        context = itemView.context,
                        dexPath = dexPath,
                        entryActivity = entry,
                        pkgName = pkgName,
                        projectRoot = null
                    )
                } catch (e: Throwable) {
                    Toast.makeText(itemView.context, "启动 Activity 失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}
