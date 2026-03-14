package com.termux.app
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ActionMode
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.termux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectEntry(
    val projectId: String,
    val projectRoot: File,
    val apkSrc: File?,
    val apkCached: File?,
    val label: String,
    val icon: android.graphics.drawable.Drawable?
)

private fun projectIdFromDir(dir: File): String = dir.name

private fun findDebugApk(projectRoot: File): File? {
    val apkFile = File(projectRoot, "app/build/outputs/apk/debug/app-debug.apk")
    return if (apkFile.isFile) apkFile else null
}

/** 持久缓存目录：/data/user/0/<pkg>/files/apk_cache/<projectId>/app-debug.apk */
private fun cachedApkPath(context: Context, projectId: String): File {
    val base = File(context.filesDir, "apk_cache/$projectId").apply { mkdirs() }
    return File(base, "app-debug.apk")
}

/** 若源 apk 更新则覆盖缓存（原子替换，避免半写入） */
private fun ensureApkCached(context: Context, projectId: String, srcApk: File): File {
    val dst = cachedApkPath(context, projectId)
    val needsCopy = !dst.exists()
        || dst.length() != srcApk.length()
        || dst.lastModified() != srcApk.lastModified()

    if (!needsCopy) return dst

    val tmp = File(dst.parentFile, dst.name + ".tmp")
    srcApk.inputStream().use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
    }
    // 尽量保持时间戳一致，方便下次判断
    tmp.setLastModified(srcApk.lastModified())

    if (dst.exists()) dst.delete()
    if (!tmp.renameTo(dst)) {
        // rename 失败就 fallback copy
        tmp.copyTo(dst, overwrite = true)
        tmp.delete()
    }
    return dst
}

/** 从 APK 里取应用图标和 label（用于“像手机 app 一样”展示） */
private fun loadApkLabelAndIcon(context: Context, apkFile: File): Pair<String, android.graphics.drawable.Drawable?> {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        val appInfo = info?.applicationInfo
        if (appInfo != null) {
            // 必须设置，否则资源路径不对
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            val label = pm.getApplicationLabel(appInfo)?.toString().orEmpty().ifBlank { apkFile.name }
            val icon = pm.getApplicationIcon(appInfo)
            label to icon
        } else {
            apkFile.name to null
        }
    } catch (_: Throwable) {
        apkFile.name to null
    }
}

/** 递归删除目录 */
private fun deleteRecursivelySafe(dir: File): Boolean {
    if (!dir.exists()) return true
    dir.listFiles()?.forEach { f ->
        if (f.isDirectory) deleteRecursivelySafe(f) else runCatching { f.delete() }
    }
    return runCatching { dir.delete() }.getOrDefault(false)
}
class ProjectAdapter(
    private val onClick: (ProjectEntry) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<ProjectEntry, ProjectAdapter.VH>(DIFF) {

    private val selectedIds = linkedSetOf<String>()

    fun getSelected(): List<ProjectEntry> {
        val set = selectedIds.toSet()
        return currentList.filter { it.projectId in set }
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun attachSelectionToActionMode(
        host: AppCompatActivity,
        onDelete: (List<ProjectEntry>) -> Unit,
        onEdit: (ProjectEntry) -> Unit
    ) {
        actionModeCallback = object : ActionMode.Callback {

            private val MENU_EDIT = 1
            private val MENU_DELETE = 2

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_EDIT, 0, "编辑").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, MENU_DELETE, 1, "删除").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val single = selectedIds.size == 1
                menu.findItem(MENU_EDIT)?.isVisible = single
                menu.findItem(MENU_EDIT)?.isEnabled = single
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    MENU_DELETE -> { onDelete(getSelected()); mode.finish(); true }
                    MENU_EDIT -> { getSelected().firstOrNull()?.let(onEdit); mode.finish(); true }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                clearSelection()
            }
        }
        hostRef = host
    }


    private var hostRef: AppCompatActivity? = null
    private var actionMode: ActionMode? = null
    private var actionModeCallback: ActionMode.Callback? = null


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project_icon, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val selected = item.projectId in selectedIds
        holder.bind(item, selected)

        holder.itemView.setOnClickListener {
            if (actionMode != null) {
                toggleSelect(item)
            } else {
                onClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (actionMode == null) {
                actionMode = hostRef?.startSupportActionMode(actionModeCallback!!)
            }
            toggleSelect(item)
            true
        }
    }

    private fun toggleSelect(item: ProjectEntry) {
        if (selectedIds.contains(item.projectId)) selectedIds.remove(item.projectId)
        else selectedIds.add(item.projectId)

        notifyDataSetChanged()
        val count = selectedIds.size
        onSelectionChanged(count)
        actionMode?.invalidate()
        actionMode?.title = "已选中 $count"
        if (count == 0) actionMode?.finish()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val mask: View = itemView.findViewById(R.id.selected_mask)

        fun bind(item: ProjectEntry, selected: Boolean) {
            title.text = item.label
            if (item.icon != null) icon.setImageResource(R.drawable.ic_androidapp)
            else icon.setImageResource(R.drawable.ic_androidapp2) // 你自己放个占位图标

            mask.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.alpha = if (item.apkCached != null) 1.0f else 0.6f // 没 apk 就灰一点
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProjectEntry>() {
            override fun areItemsTheSame(old: ProjectEntry, new: ProjectEntry) = old.projectId == new.projectId
            override fun areContentsTheSame(old: ProjectEntry, new: ProjectEntry) =
                old.label == new.label &&
                    old.apkSrc?.lastModified() == new.apkSrc?.lastModified() &&
                    old.apkSrc?.length() == new.apkSrc?.length()
        }
    }
}

class ProjectLauncherFragment : Fragment(R.layout.fragment_project_launcher) {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: ProjectAdapter

    // 扫描的根目录：/files/apps/
    private val rootDir: File by lazy {
        File(requireContext().filesDir, "apps").apply { mkdirs() }
    }

    // 你跑动态 Activity 用的入口（先按你现在写死）
    private val targetClassName = "com.example.miniapk.MainActivity"
    private val targetPackageName = "com.example.miniapk"



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.recycler)
        emptyView = view.findViewById(R.id.empty)

        adapter = ProjectAdapter(
            onClick = { entry ->
                // ✅ 点击：优先启动（如果没 APK 就提示）
                val apk = entry.apkCached
                if (apk == null || !apk.exists()) {
                    Toast.makeText(requireContext(), "该项目尚未生成 APK", Toast.LENGTH_SHORT).show()
                    return@ProjectAdapter
                }
            },
            onSelectionChanged = { /* ActionMode 自己显示 title，不需要这里 */ }
        )

        recycler.layoutManager = GridLayoutManager(requireContext(), 4)
        recycler.adapter = adapter


        refresh()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { scanProjects() }
            adapter.submitList(list)

            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** ✅ 扫描 + 更新缓存 APK + 读取 label/icon（全在 IO 线程） */
    private fun scanProjects(): List<ProjectEntry> {
        val ctx = requireContext()
        val dirs = rootDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

        return dirs.map { projectRoot ->
            val projectId = projectIdFromDir(projectRoot)

            val apkSrc = findDebugApk(projectRoot)
            val apkCached = apkSrc?.let { ensureApkCached(ctx, projectId, it) }

            val (label, icon) = if (apkCached != null && apkCached.exists()) {
                loadApkLabelAndIcon(ctx, apkCached)
            } else {
                projectId to null
            }

            ProjectEntry(
                projectId = projectId,
                projectRoot = projectRoot,
                apkSrc = apkSrc,
                apkCached = apkCached,
                label = label,
                icon = icon
            )
        }
    }

    private fun confirmDelete(selected: List<ProjectEntry>) {
        if (selected.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("删除项目")
            .setMessage("确定删除选中的 ${selected.size} 个项目？（会删除整个目录）")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    selected.forEach { entry ->
                        // 删除项目目录
                        deleteRecursivelySafe(entry.projectRoot)
                        // 删除缓存 APK
                        runCatching {
                            val cacheDir = File(requireContext().filesDir, "apk_cache/${entry.projectId}")
                            deleteRecursivelySafe(cacheDir)
                        }
                    }
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
            .show()
    }
}

