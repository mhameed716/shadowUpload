package com.shadowupload.app.ui.files

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.shadowupload.app.data.model.FileCategory
import com.shadowupload.app.data.model.FileItem
import com.shadowupload.app.data.repo.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * حالة شاشة الملفات: نتيجة التحميل لكل تصنيف، البحث، والتحديد المتعدد.
 *
 * نستخدم LiveData لا Compose/Flow لتبقى الشاشة خفيفة وسريعة على API 21
 * وتعمل بسلاسة على الأجهزة الضعيفة.
 */
class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val prefs = (application as com.shadowupload.app.ShadowUploadApp).preferences

    private val _itemsByCategory = MutableLiveData<Map<FileCategory, List<FileItem>>>(emptyMap())
    val itemsByCategory: LiveData<Map<FileCategory, List<FileItem>>> = _itemsByCategory

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount

    /** جذر SAF الذي اختاره المستخدم (بديل «كل الملفات») */
    private var safRoot: android.net.Uri? = null

    fun setSafRoot(uri: android.net.Uri) {
        safRoot = uri
        loadCategory(FileCategory.STORAGE, force = true)
    }

    /** نص البحث الحالي (يُعرض في كل التصنيفات) */
    var query: String = ""
        private set

    /** التحديد المتعدد: مفاتيح العناصر المختارة */
    private val selected = LinkedHashMap<String, FileItem>()

    /** مخزن مؤقت للنتائج لتجنب إعادة الاستعلام عند كل تبديل تبويب */
    private val cache = mutableMapOf<FileCategory, List<FileItem>>()

    fun setQuery(value: String) {
        if (query == value) return
        query = value
        // إعادة نشر نفس البيانات مع عامل التصفية الجديد
        _itemsByCategory.value = _itemsByCategory.value?.mapValues { (_, items) ->
            filter(items, value)
        } ?: emptyMap()
    }

    /** تحميل تصنيف واحد (يُستدعى من كل صفحة عند ظهورها) */
    fun loadCategory(category: FileCategory, force: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            val items = withContext(Dispatchers.IO) {
                if (!force && cache.containsKey(category)) {
                    cache.getValue(category)
                } else {
                    val loaded = when {
                        category == FileCategory.STORAGE && safRoot != null ->
                            repository.browseDocumentTree(safRoot!!)
                        else -> repository.loadCategory(category)
                    }
                    loaded.also { cache[category] = it }
                }
            }
            val current = _itemsByCategory.value.orEmpty().toMutableMap()
            current[category] = items
            _itemsByCategory.value = current
            _loading.value = false
        }
    }

    /** إعادة تحميل كل شيء (بعد تغيير الصلاحيات) */
    fun refreshAll() {
        cache.clear()
        _itemsByCategory.value = emptyMap()
    }

    /** عناصر تصنيف بعد تطبيق البحث */
    fun itemsFor(category: FileCategory): List<FileItem> {
        val items = _itemsByCategory.value?.get(category).orEmpty()
        return filter(items, query)
    }

    private fun filter(items: List<FileItem>, text: String): List<FileItem> {
        if (text.isBlank()) return items
        val needle = text.trim().lowercase()
        return items.filter { it.name.lowercase().contains(needle) }
    }

    // ================= التحديد المتعدد =================

    fun toggleSelection(item: FileItem) {
        if (selected.containsKey(item.id)) {
            selected.remove(item.id)
        } else {
            selected[item.id] = item
        }
        _selectedCount.value = selected.size
    }

    fun isSelected(item: FileItem): Boolean = selected.containsKey(item.id)

    fun clearSelection() {
        selected.clear()
        _selectedCount.value = 0
    }

    fun selectedItems(): List<FileItem> = selected.values.toList()

    // ================= حالة طلب صلاحية «كل الملفات» =================

    fun allFilesPrompted(): Boolean = prefs.allFilesPrompted

    fun markAllFilesPrompted() {
        prefs.allFilesPrompted = true
    }

    /** إحصاءات التخزين لعرضها في شاشة «أنا» */
    fun storageStats(): MediaRepository.StorageStats = repository.storageStats()

    /** مجلد الاستلام الحالي */
    fun receivedFolderPath(): String {
        val folder = repository.receivedFolder()
        return folder.absolutePath
    }

    companion object {
        /** وصول آمن للتطبيق من أي سياق */
        fun appOf(context: Context): com.shadowupload.app.ShadowUploadApp =
            context.applicationContext as com.shadowupload.app.ShadowUploadApp
    }
}
