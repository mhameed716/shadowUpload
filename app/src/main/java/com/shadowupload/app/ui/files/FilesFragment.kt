package com.shadowupload.app.ui.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.shadowupload.app.R
import com.shadowupload.app.data.model.FileCategory
import com.shadowupload.app.databinding.FragmentFilesBinding
import com.shadowupload.app.ui.files.category.CategoryFilesFragment
import com.shadowupload.app.util.PermissionUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.launch

/**
 * تبويب «الملفات»: تبويبات التصنيفات (تطبيقات، فيديو، صور، صوت،
 * مستندات، ذاكرة الجهاز) + بحث + تحديد متعدد للإرسال.
 */
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: FilesViewModel
    private var pagerAdapter: CategoriesPagerAdapter? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (!granted) {
            UiUtils.snack(binding.root, R.string.files_permission_denied)
        }
        viewModel.refreshAll()
    }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAll()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[FilesViewModel::class.java]
        setupPager()
        setupSearch()
        setupSelectionBar()
        ensurePermissions()
        observeSelection()
    }

    // ================= التبويبات =================

    private fun setupPager() {
        pagerAdapter = CategoriesPagerAdapter(this)
        binding.pagerCategories.adapter = pagerAdapter
        binding.pagerCategories.offscreenPageLimit = 1

        val titles = listOf(
            R.string.files_tab_apps,
            R.string.files_tab_videos,
            R.string.files_tab_photos,
            R.string.files_tab_audio,
            R.string.files_tab_documents,
            R.string.files_tab_storage
        )
        TabLayoutMediator(binding.tabsCategories, binding.pagerCategories) { tab, position ->
            tab.text = getString(titles[position])
        }.attach()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setQuery(s?.toString()?.trim().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun setupSelectionBar() {
        binding.btnSendSelected.setOnClickListener {
            val selected = viewModel.selectedItems()
            if (selected.isEmpty()) {
                UiUtils.snack(binding.root, R.string.transfer_hub_action)
                return@setOnClickListener
            }
            com.shadowupload.app.ui.transfer.PendingTransfer.items = selected
            startActivity(
                Intent(requireContext(), com.shadowupload.app.ui.hub.TransferHubActivity::class.java)
                    .putExtra(com.shadowupload.app.ui.hub.TransferHubActivity.EXTRA_MODE, "send")
            )
        }
        binding.btnSelectAll.setOnClickListener { viewModel.clearSelection() }
    }

    private fun observeSelection() {
        viewModel.selectedCount.observe(viewLifecycleOwner) { count ->
            if (count > 0) {
                binding.selectionBar.visibility = View.VISIBLE
                binding.tvSelectionCount.text =
                    getString(R.string.files_selected_count, count)
            } else {
                binding.selectionBar.visibility = View.GONE
            }
        }
    }

    // ================= الصلاحيات =================

    private fun ensurePermissions() {
        val storage = PermissionUtils.storagePermissions()
        if (!PermissionUtils.areAllGranted(requireContext(), storage)) {
            storagePermissionLauncher.launch(storage)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !PermissionUtils.hasAllFilesAccess() &&
            !viewModel.allFilesPrompted()
        ) {
            // تنبيه لطيف مرة واحدة فقط لصلاحية «كل الملفات»
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.files_grant_access)
                .setMessage(R.string.files_all_files_access)
                .setPositiveButton(R.string.files_open_settings) { _, _ ->
                    viewModel.markAllFilesPrompted()
                    PermissionUtils.requestAllFilesAccess(requireActivity(), REQUEST_ALL_FILES)
                }
                .setNegativeButton(R.string.perm_later, null)
                .show()
        }
    }

    /** فتح إعدادات التطبيق (يُستخدم من زر المنح) */
    fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        }
    }

    override fun onDestroyView() {
        binding.pagerCategories.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /** محوّل صفحات التصنيفات */
    private inner class CategoriesPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = CATEGORY_COUNT

        override fun createFragment(position: Int): Fragment =
            CategoryFilesFragment.newInstance(categories()[position].name)

        private fun categories(): List<FileCategory> = listOf(
            FileCategory.APPS,
            FileCategory.VIDEOS,
            FileCategory.PHOTOS,
            FileCategory.AUDIO,
            FileCategory.DOCUMENTS,
            FileCategory.STORAGE
        )
    }

    companion object {
        private const val CATEGORY_COUNT = 6
        private const val REQUEST_ALL_FILES = 1041

        @Suppress("unused")
        private val UNUSED_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
