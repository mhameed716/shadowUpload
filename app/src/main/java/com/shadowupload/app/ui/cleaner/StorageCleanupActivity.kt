package com.shadowupload.app.ui.cleaner

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shadowupload.app.R
import com.shadowupload.app.databinding.ActivityStorageCleanupBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.FormatUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * تنظيف التخزين: ملفات مؤقتة، مجلدات فارغة، وملفات مكررة.
 *
 * مبدأ الأمان: لا نحذف شيئاً من ملفات المستخدم الشخصية إلا بعد تأكيد صريح،
 * والبحث المكرر يعتمد على (الاسم + الحجم) لا على المحتوى لتفادي بطء الفحص.
 */
class StorageCleanupActivity : BaseActivity() {

    private var _binding: ActivityStorageCleanupBinding? = null
    private val binding get() = _binding!!

    private var tempBytes = 0L
    private var emptyDirs = 0
    private var duplicateBytes = 0L
    private val duplicateFiles = mutableListOf<File>()
    private val emptyDirFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityStorageCleanupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnCleanNow.setOnClickListener { confirmClean() }
        startScan()
    }

    /** فحص التخزين في الخلفية مع تحديث تدريجي */
    private fun startScan() {
        binding.pbScan.progress = 0
        binding.tvScanProgress.text = getString(R.string.cleaner_scanning)
        binding.tvReclaimable.text = "…"

        lifecycleScope.launch {
            val caches = withContext(Dispatchers.IO) { scanCaches() }
            if (_binding == null) return@launch
            tempBytes = caches
            binding.pbScan.progress = 35
            binding.tvScanProgress.text = getString(R.string.cleaner_analyzing, 35)
            binding.tvTempSize.text = FormatUtils.formatSize(tempBytes)

            val dirs = withContext(Dispatchers.IO) {
                val webViewDir = filesDir.parentFile?.let { File(it, "app_webview") }
                collectEmptyDirs(cacheDir) + collectEmptyDirs(webViewDir)
            }
            if (_binding == null) return@launch
            emptyDirFiles.clear()
            emptyDirFiles.addAll(dirs)
            emptyDirs = dirs.size
            binding.pbScan.progress = 65
            binding.tvScanProgress.text = getString(R.string.cleaner_analyzing, 65)
            binding.tvEmptyCount.text = emptyDirs.toString()

            val duplicates = withContext(Dispatchers.IO) {
                findDuplicates(com.shadowupload.app.data.repo.MediaRepository(this@StorageCleanupActivity).receivedFolder())
            }
            if (_binding == null) return@launch
            duplicateFiles.clear()
            duplicateFiles.addAll(duplicates)
            duplicateBytes = duplicates.sumOf { it.length() }
            binding.pbScan.progress = 100
            binding.tvScanProgress.text = getString(R.string.cleaner_analyzing, 100)
            binding.tvDuplicateSize.text = FormatUtils.formatSize(duplicateBytes)

            val total = tempBytes + duplicateBytes
            binding.tvReclaimable.text = FormatUtils.formatSize(total)
            if (total == 0L && emptyDirs == 0) {
                UiUtils.snack(binding.root, R.string.cleaner_nothing)
            }
        }
    }

    /** مجموع أحجام ملفات الذاكرة المؤقتة للتطبيق فقط (آمنة بالكامل) */
    private fun scanCaches(): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        cacheDir?.let { stack.addLast(it) }
        externalCacheDir?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = runCatching { current.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /** إيجاد المجلدات الفارغة */
    private fun collectEmptyDirs(root: File?): List<File> {
        if (root == null || !root.exists()) return emptyList()
        val empties = mutableListOf<File>()
        fun walk(dir: File) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            if (children.isEmpty() && dir != root) {
                empties.add(dir)
                return
            }
            children.filter { it.isDirectory }.forEach { walk(it) }
        }
        walk(root)
        return empties
    }

    /** ملفات مكررة بالاسم والحجم داخل مجلد الاستلام */
    private fun findDuplicates(root: File): List<File> {
        if (!root.exists()) return emptyList()
        val buckets = HashMap<String, MutableList<File>>()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return emptyList()
        children.filter { it.isFile }.forEach { file ->
            val key = "${file.name}:${file.length()}"
            buckets.getOrPut(key) { mutableListOf() }.add(file)
        }
        return buckets.values.filter { it.size > 1 }.flatMap { it.drop(1) }
    }

    /** تنفيذ التنظيف بعد تأكيد */
    private fun confirmClean() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cleaner_clean_now)
            .setMessage(R.string.cleaner_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ -> performClean() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performClean() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                var total = 0L
                if (binding.cbTemp.isChecked) {
                    total += clearDirectory(cacheDir)
                    total += clearDirectory(externalCacheDir)
                }
                if (binding.cbDuplicates.isChecked) {
                    duplicateFiles.forEach { file ->
                        val size = file.length()
                        if (runCatching { file.delete() }.getOrDefault(false)) total += size
                    }
                }
                if (binding.cbEmpty.isChecked) {
                    emptyDirFiles.sortedByDescending { it.path.length }.forEach { dir ->
                        runCatching { dir.delete() }
                    }
                }
                total
            }
            if (_binding == null) return@launch
            binding.tvReclaimable.text = FormatUtils.formatSize(freed)
            UiUtils.snack(binding.root, getString(R.string.cleaner_done, FormatUtils.formatSize(freed)))
            startScan()
        }
    }

    /** تفريغ محتوى مجلد وإرجاع الحجم */
    private fun clearDirectory(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return 0L
        for (child in children) {
            if (child.isDirectory) {
                total += clearDirectory(child)
                runCatching { child.delete() }
            } else {
                val size = child.length()
                if (runCatching { child.delete() }.getOrDefault(false)) total += size
            }
        }
        return total
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
