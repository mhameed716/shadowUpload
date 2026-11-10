package com.shadowupload.app.ui.files.category

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.shadowupload.app.R
import com.shadowupload.app.data.model.FileCategory
import com.shadowupload.app.data.model.FileItem
import com.shadowupload.app.databinding.FragmentCategoryFilesBinding
import com.shadowupload.app.ui.files.FilesViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * صفحة تصنيف واحد داخل تبويب الملفات.
 * تُنشأ ديناميكياً لكل تصنيف وتحتفظ بحالتها عبر ViewModel المشترك.
 */
class CategoryFilesFragment : Fragment() {

    private var _binding: FragmentCategoryFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: FilesViewModel
    private lateinit var adapter: FileListAdapter
    private lateinit var category: FileCategory

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCategoryFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        category = runCatching {
            FileCategory.valueOf(requireArguments().getString(ARG_CATEGORY) ?: FileCategory.STORAGE.name)
        }.getOrDefault(FileCategory.STORAGE)

        viewModel = ViewModelProvider(requireParentFragment())[FilesViewModel::class.java]

        adapter = FileListAdapter(
            onItemClick = { item -> handleItemClick(item) },
            onItemLongClick = { item ->
                viewModel.toggleSelection(item)
                adapter.refreshSelectionState()
                true
            },
            isSelected = { item -> viewModel.isSelected(item) },
            selectionActive = { (viewModel.selectedCount.value ?: 0) > 0 }
        )

        binding.recyclerFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFiles.adapter = adapter
        binding.btnGrant.setOnClickListener {
            (parentFragment as? com.shadowupload.app.ui.files.FilesFragment)?.openAppSettings()
        }

        viewModel.loadCategory(category)
        observeData()
    }

    private fun observeData() {
        viewModel.itemsByCategory.observe(viewLifecycleOwner) { map ->
            if (!isAdded || _binding == null) return@observe
            val items = viewModel.itemsFor(category)
            adapter.submit(items)
            updateEmptyState(items, map.containsKey(category))
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            if (_binding == null) return@observe
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun updateEmptyState(items: List<FileItem>, loaded: Boolean) {
        val showEmpty = items.isEmpty() && loaded
        binding.emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.recyclerFiles.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** نقرة قصيرة: تنقّل داخل المجلدات أو تحديد ملف للإرسال */
    private fun handleItemClick(item: FileItem) {
        if (item.isDirectory) {
            navigateInto(item)
            return
        }
        val checked = viewModel.isSelected(item)
        if (checked || viewModel.selectedCount.value ?: 0 > 0) {
            viewModel.toggleSelection(item)
            adapter.refreshSelectionState()
        } else {
            // نقرة على ملف بدون تحديد: عرض تفاصيل مختصرة
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.name)
                .setMessage(
                    getString(
                        R.string.files_items_count,
                        (item.size / 1024).coerceAtLeast(0)
                    ) + " • " + com.shadowupload.app.util.FormatUtils.formatSize(item.size)
                )
                .setPositiveButton(R.string.action_send) { _, _ ->
                    viewModel.toggleSelection(item)
                    adapter.refreshSelectionState()
                    com.shadowupload.app.util.UiUtils.snack(
                        binding.root,
                        getString(R.string.files_selected_count, viewModel.selectedCount.value ?: 0)
                    )
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    /** فتح مجلد فرعي (إبقاء نفس التصنيف في تبويب الذاكرة) */
    private fun navigateInto(folder: FileItem) {
        val path = folder.path ?: return
        val file = java.io.File(path)
        val context = requireContext()
        binding.progressLoading.visibility = View.VISIBLE
        viewModel.viewModelScope.launch {
            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.shadowupload.app.data.repo.MediaRepository(context).browseDirectory(file)
            }
            if (_binding == null) return@launch
            binding.progressLoading.visibility = View.GONE
            adapter.submit(items)
            updateEmptyState(items, true)
        }
    }

    override fun onResume() {
        super.onResume()
        // تحديث لطيف عند العودة للشاشة (قد يكون ملف جديد استُلم)
        if (category == FileCategory.STORAGE) viewModel.loadCategory(category, force = true)
    }

    override fun onDestroyView() {
        binding.recyclerFiles.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_CATEGORY = "arg_category"

        fun newInstance(categoryName: String): CategoryFilesFragment =
            CategoryFilesFragment().apply {
                arguments = Bundle().apply { putString(ARG_CATEGORY, categoryName) }
            }
    }
}
