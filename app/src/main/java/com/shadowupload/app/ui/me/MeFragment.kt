package com.shadowupload.app.ui.me

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.data.repo.MediaRepository
import com.shadowupload.app.databinding.FragmentMeBinding
import com.shadowupload.app.ui.cleaner.StorageCleanupActivity
import com.shadowupload.app.ui.history.TransferHistoryActivity
import com.shadowupload.app.ui.settings.SettingsActivity
import com.shadowupload.app.ui.wifi.WifiHelpActivity
import com.shadowupload.app.util.FormatUtils
import com.shadowupload.app.util.PermissionUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.launch

/**
 * تبويب «أنا»: الهوية، حالة التخزين، السجل، التنظيف، الإعدادات، والخصوصية.
 */
class MeFragment : Fragment() {

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: ShadowUploadApp

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = requireActivity().application as ShadowUploadApp
        bindIdentity()
        bindStorage()
        bindRows()
        bindVersion()
    }

    // ================= الهوية =================

    private fun bindIdentity() {
        val prefs = app.preferences
        binding.etDeviceName.setText(prefs.deviceName)
        binding.tvDeviceId.text = prefs.deviceId

        binding.etDeviceName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveDeviceName()
        }
        binding.etDeviceName.setOnEditorActionListener { _, _, _ ->
            saveDeviceName()
            true
        }
    }

    private fun saveDeviceName() {
        val name = binding.etDeviceName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.etDeviceName.setText(app.preferences.deviceName)
            return
        }
        app.preferences.deviceName = name
        UiUtils.snack(binding.root, R.string.settings_saved)
    }

    // ================= التخزين =================

    private fun bindStorage() {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaRepository(requireContext()).storageStats()
            }
            if (_binding == null) return@launch
            val percent = if (stats.total > 0) {
                (stats.used.toDouble() * 100.0 / stats.total.toDouble()).toInt()
            } else 0
            binding.pbStorage.progress = percent
            binding.tvStorageSummary.text = getString(
                R.string.me_storage_summary,
                FormatUtils.formatSize(stats.used),
                FormatUtils.formatSize(stats.total)
            )
            binding.tvStorageFree.text = getString(
                R.string.files_storage_free,
                FormatUtils.formatSize(stats.free)
            )
            binding.tvReceivedFolder.text = MediaRepository(requireContext())
                .receivedFolder().absolutePath
        }
    }

    // ================= الصفوف =================

    private fun bindRows() {
        setupRow(
            binding.rowHistory, R.drawable.ic_history,
            R.string.me_history, R.string.me_history_desc
        ) {
            startActivity(Intent(requireContext(), TransferHistoryActivity::class.java))
        }

        setupRow(
            binding.rowCleanup, R.drawable.ic_clean,
            R.string.me_cleanup, R.string.me_cleanup_desc
        ) {
            startActivity(Intent(requireContext(), StorageCleanupActivity::class.java))
        }

        setupRow(
            binding.rowSettings, R.drawable.ic_settings,
            R.string.me_settings, R.string.me_settings_desc
        ) {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        setupRow(
            binding.rowSecurity, R.drawable.ic_shield,
            R.string.me_security, R.string.me_security_desc
        ) {
            showSecurityInfo()
        }

        setupRow(
            binding.rowPermissions, R.drawable.ic_shield,
            R.string.me_permissions_status, R.string.files_grant_access
        ) {
            startActivity(Intent(requireContext(), WifiHelpActivity::class.java))
        }
    }

    private fun setupRow(
        row: com.shadowupload.app.databinding.ItemMeRowBinding,
        icon: Int,
        titleRes: Int,
        subtitleRes: Int,
        action: () -> Unit
    ) {
        row.ivRowIcon.setImageResource(icon)
        row.tvRowTitle.setText(titleRes)
        row.tvRowSubtitle.setText(subtitleRes)
        row.root.setOnClickListener { action() }
    }

    private fun showSecurityInfo() {
        val context = requireContext()
        val storageOk = PermissionUtils.hasStorageAccess(context)
        val allFiles = PermissionUtils.hasAllFilesAccess()
        val nearbyOk = PermissionUtils.areAllGranted(context, PermissionUtils.nearbyPermissions())
        val message = buildString {
            append(getString(R.string.perm_storage_label)).append(": ")
            append(getString(if (storageOk) R.string.me_permission_granted else R.string.me_permission_missing))
            append("\n")
            append(getString(R.string.perm_nearby)).append(": ")
            append(getString(if (nearbyOk) R.string.me_permission_granted else R.string.me_permission_missing))
            append("\n")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                append(getString(R.string.files_all_files_access)).append(": ")
                append(getString(if (allFiles) R.string.me_permission_granted else R.string.me_permission_missing))
                append("\n")
            }
            append("\n").append(getString(R.string.help_a2))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.me_security)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun bindVersion() {
        val versionName = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrDefault("1.0.0")
        binding.tvVersion.text = getString(R.string.me_version_info, versionName)
    }

    override fun onResume() {
        super.onResume()
        bindStorage()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
