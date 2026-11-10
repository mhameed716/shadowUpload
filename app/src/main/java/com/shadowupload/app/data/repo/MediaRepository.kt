package com.shadowupload.app.data.repo

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.shadowupload.app.data.model.FileCategory
import com.shadowupload.app.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * مستودع الملفات: يجسر الفجوة بين التراثي (API 21-28) و
 * Scoped Storage (API 29+) دون أن تعرف الواجهة أي شيء عن الفروق.
 *
 * المسارات:
 *  - الصور/الفيديو/الصوت → MediaStore (سريع جداً، مدعوم من API 21).
 *  - المستندات → تخزين خارجي مباشر إن توفرت صلاحية، وإلا MediaStore.Files.
 *  - التطبيقات → PackageManager (APK paths).
 *  - الذاكرة → متصفح ملفات مباشر (Environment) أو DocumentFile احتياطاً.
 */
class MediaRepository(private val context: Context) {

    // =========================================================================
    //  التطبيقات
    // =========================================================================

    /** قائمة التطبيقات المثبتة التي يمكن مشاركة APK الخاص بها */
    suspend fun loadApplications(): List<FileItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val items = mutableListOf<FileItem>()
        runCatching {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            packages.forEach { packageInfo ->
                val appInfo: ApplicationInfo = packageInfo.applicationInfo ?: return@forEach
                val apkPath = appInfo.sourceDir ?: return@forEach
                val apkFile = File(apkPath)
                if (!apkFile.exists()) return@forEach
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // نتجاهل تطبيقات النظام: لا فائدة عملية من مشاركتها وتضخم القائمة
                if (isSystem) return@forEach
                items.add(
                    FileItem(
                        id = packageInfo.packageName,
                        uri = null,
                        path = apkPath,
                        name = runCatching {
                            pm.getApplicationLabel(appInfo).toString()
                        }.getOrDefault(packageInfo.packageName),
                        size = apkFile.length(),
                        mimeType = "application/vnd.android.package-archive",
                        lastModified = apkFile.lastModified(),
                        category = FileCategory.APPS,
                        packageName = packageInfo.packageName
                    )
                )
            }
        }
        items.sortedByDescending { it.lastModified }
    }

    // =========================================================================
    //  الوسائط عبر MediaStore
    // =========================================================================

    /** تحميل عناصر تصنيف معين */
    suspend fun loadCategory(category: FileCategory): List<FileItem> = withContext(Dispatchers.IO) {
        when (category) {
            FileCategory.APPS -> loadApplications()
            FileCategory.PHOTOS -> queryMedia(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                category = FileCategory.PHOTOS,
                mimeFilter = null
            )
            FileCategory.VIDEOS -> queryMedia(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                category = FileCategory.VIDEOS,
                mimeFilter = null
            )
            FileCategory.AUDIO -> queryMedia(
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                category = FileCategory.AUDIO,
                mimeFilter = null
            )
            FileCategory.DOCUMENTS -> queryDocuments()
            FileCategory.STORAGE -> browseDirectory(internalRoot())
        }
    }

    /** استعلام MediaStore بـ Cursor واحد (أقل تكلفة ممكنة) */
    private fun queryMedia(
        collection: Uri,
        category: FileCategory,
        mimeFilter: String?
    ): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = mimeFilter?.let { "${MediaStore.MediaColumns.MIME_TYPE}=?" }
        val selectionArgs = mimeFilter?.let { arrayOf(it) }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val size = cursor.getLong(sizeIndex)
                    items.add(
                        FileItem(
                            id = uri.toString(),
                            uri = uri,
                            path = null,
                            name = cursor.getString(nameIndex) ?: "ملف $id",
                            size = if (size > 0) size else querySize(uri),
                            mimeType = cursor.getString(mimeIndex),
                            lastModified = cursor.getLong(dateIndex) * 1000L,
                            category = category
                        )
                    )
                }
            }
        }
        return items
    }

    /** المستندات: فحص ذكي لعدة أنواع مع استعلام MediaStore.Files */
    private fun queryDocuments(): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = buildString {
            append("(${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ")
            append("${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ")
            append("${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ")
            append("${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ")
            append("${MediaStore.MediaColumns.MIME_TYPE} LIKE ?)")
        }
        val args = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats%",
            "text/%",
            "application/vnd.ms-excel"
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    items.add(
                        FileItem(
                            id = uri.toString(),
                            uri = uri,
                            path = null,
                            name = cursor.getString(nameIndex) ?: "مستند",
                            size = cursor.getLong(sizeIndex),
                            mimeType = cursor.getString(mimeIndex),
                            lastModified = cursor.getLong(dateIndex) * 1000L,
                            category = FileCategory.DOCUMENTS
                        )
                    )
                }
            }
        }
        return items
    }

    /** حجم ملف عبر ContentResolver عند غياب العمود */
    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
    }.getOrDefault(0L)

    // =========================================================================
    //  متصفح الذاكرة
    // =========================================================================

    /** جذر التخزين الداخلي */
    fun internalRoot(): File {
        val external = context.getExternalFilesDir(null)
        // نعود لمجلد التخزين الخارجي العام إن أمكن، وإلا للتخزين الداخلي
        val publicDir = Environment.getExternalStorageDirectory()
        return if (publicDir != null && publicDir.exists()) publicDir else (external ?: File("/"))
    }

    /** جذر التخزين المخصص للتطبيق (لا يحتاج صلاحيات على كل الإصدارات) */
    fun appStorageRoot(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return base
    }

    /** مجلد الاستلام الافتراضي */
    fun receivedFolder(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(base, "shadowUpload")
        if (!folder.exists()) folder.mkdirs()
        return if (folder.exists()) folder else appStorageRoot()
    }

    /** تصفح مجلد مباشرة */
    suspend fun browseDirectory(directory: File): List<FileItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<FileItem>()
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return@withContext items
        children.forEach { child ->
            if (child.name.startsWith(".")) return@forEach
            val isDir = child.isDirectory
            items.add(
                FileItem(
                    id = child.absolutePath,
                    uri = null,
                    path = child.absolutePath,
                    name = child.name,
                    size = if (isDir) 0L else child.length(),
                    mimeType = if (isDir) null else guessMime(child.name),
                    lastModified = child.lastModified(),
                    isDirectory = isDir,
                    category = FileCategory.STORAGE
                )
            )
        }
        items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
    }

    /** تصنيف مبسط حسب الامتداد */
    fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> "image/*"
            "mp4", "mkv", "avi", "mov", "webm", "3gp" -> "video/*"
            "mp3", "wav", "ogg", "m4a", "flac", "aac" -> "audio/*"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt", "md", "log" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /** statistika حجم مجلد (يُستخدم في تنظيف التخزين والتقديرات) */
    suspend fun folderSize(folder: File): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(folder)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = runCatching { current.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        total
    }

    /** مساحة التخزين المتاحة والمستخدمة */
    data class StorageStats(val total: Long, val free: Long) {
        val used: Long get() = (total - free).coerceAtLeast(0L)
    }

    fun storageStats(): StorageStats = runCatching {
        val stat = android.os.StatFs(Environment.getDataDirectory().path)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            StorageStats(stat.totalBytes, stat.availableBytes)
        } else {
            @Suppress("DEPRECATION")
            StorageStats(stat.blockCount.toLong() * stat.blockSize, stat.availableBlocks.toLong() * stat.blockSize)
        }
    }.getOrDefault(StorageStats(0L, 0L))
}
