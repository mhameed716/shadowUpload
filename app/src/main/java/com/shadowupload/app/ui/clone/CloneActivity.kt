package com.shadowupload.app.ui.clone

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.shadowupload.app.R
import com.shadowupload.app.data.model.CloneItem
import com.shadowupload.app.databinding.ActivityCloneBinding
import com.shadowupload.app.databinding.ItemCloneOptionBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.FormatUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * نسخ هاتف آخر بالكامل.
 *
 * ملاحظة مهمة وصادقة: أندرويد يمنع تطبيقاً عادياً من نقل «بيانات تطبيق»
 * تطبيق آخر (sandbox). لذلك النسخ الكامل هنا يشمل ما يمكن الوصول إليه
 * فعلياً وبقوانين النظام: الصور والفيديو والصوت والمستندات، جهات الاتصال،
 * الرسائل، سجل المكالمات، وقائمة التطبيقات (كحزم APK + إصدارات).
 */
class CloneActivity : BaseActivity() {

    private var _binding: ActivityCloneBinding? = null
    private val binding get() = _binding!!
    private val selections = linkedMapOf<CloneItem, ItemCloneOptionBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        buildOptions()
        estimateSize()

        binding.btnStartClone.setOnClickListener { startClone() }
    }

    /** بناء صفوف الخيارات برمجياً لتفادي تكرار XML */
    private fun buildOptions() {
        val definitions = listOf(
            Triple(CloneItem.APPS, R.string.clone_apps, R.string.clone_apps_desc),
            Triple(CloneItem.PHOTOS, R.string.clone_photos, R.string.hub_clone_phone_desc),
            Triple(CloneItem.AUDIO, R.string.clone_audio, R.string.file_audio),
            Triple(CloneItem.DOCUMENTS, R.string.clone_documents, R.string.file_document),
            Triple(CloneItem.CONTACTS, R.string.clone_contacts, R.string.perm_contacts),
            Triple(CloneItem.SMS, R.string.clone_sms, R.string.perm_sms),
            Triple(CloneItem.CALL_LOG, R.string.clone_call_log, R.string.history_title),
            Triple(CloneItem.SETTINGS, R.string.clone_settings, R.string.me_settings_desc)
        )

        definitions.forEachIndexed { index, (item, titleRes, descRes) ->
            val option = ItemCloneOptionBinding.inflate(layoutInflater, binding.cloneItems, false)
            option.tvCloneTitle.setText(titleRes)
            option.tvCloneDesc.setText(descRes)
            option.cbCloneOption.isChecked = index < 4 // الوسائط والتطبيقات مفعّلة افتراضياً
            option.root.setOnClickListener {
                option.cbCloneOption.isChecked = !option.cbCloneOption.isChecked
                estimateSize()
            }
            option.cbCloneOption.setOnCheckedChangeListener { _, _ -> estimateSize() }
            binding.cloneItems.addView(option.root)
            selections[item] = option
        }
    }

    /**
     * بدء النسخ: تصدير العناصر المختارة إلى ملفات، البحث عن الجهاز الهدف
     * على الشبكة، ثم الإرسال بمحرك النقل نفسه.
     */
    /**
     * الصلاحيات المطلوبة حسب العناصر المختارة — تُطلب قبل البدء
     * حتى لا يفشل التصدير بصمت على أندرويد 6+.
     */
    private fun requiredPermissions(selected: Set<CloneItem>): Array<String> {
        val needed = mutableListOf<String>()
        if (CloneItem.CONTACTS in selected) {
            needed += com.shadowupload.app.util.PermissionUtils.contactsPermissions()
        }
        if (CloneItem.SMS in selected) {
            needed += com.shadowupload.app.util.PermissionUtils.smsPermissions()
        }
        if (CloneItem.CALL_LOG in selected) {
            needed += com.shadowupload.app.util.PermissionUtils.callLogPermissions()
        }
        return needed.filterNot {
            com.shadowupload.app.util.PermissionUtils.isGranted(requireContext(), it)
        }.toTypedArray()
    }

    private val clonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // نكمل بالعناصر التي مُنحت صلاحياتها فقط
        if (granted.values.any { it }) startClone() else {
            if (_binding != null) UiUtils.snack(binding.root, R.string.error_permission)
        }
    }

    private fun startClone() {
        val chosen = selections.filterValues { it.cbCloneOption.isChecked }.keys
        if (chosen.isEmpty()) {
            UiUtils.snack(binding.root, R.string.clone_nothing_selected)
            return
        }
        val missing = requiredPermissions(chosen)
        if (missing.isNotEmpty()) {
            clonePermissionLauncher.launch(missing)
            return
        }
        binding.btnStartClone.isEnabled = false
        binding.btnStartClone.setText(R.string.clone_searching)

        val app = application as com.shadowupload.app.ShadowUploadApp
        lifecycleScope.launch {
            // 1) جمع الوسائط المختارة
            val media = withContext(Dispatchers.IO) {
                val repository = com.shadowupload.app.data.repo.MediaRepository(this@CloneActivity)
                buildList {
                    if (CloneItem.PHOTOS in chosen) {
                        addAll(repository.loadCategory(com.shadowupload.app.data.model.FileCategory.PHOTOS))
                    }
                    if (CloneItem.VIDEOS in chosen) {
                        addAll(repository.loadCategory(com.shadowupload.app.data.model.FileCategory.VIDEOS))
                    }
                    if (CloneItem.AUDIO in chosen) {
                        addAll(repository.loadCategory(com.shadowupload.app.data.model.FileCategory.AUDIO))
                    }
                    if (CloneItem.DOCUMENTS in chosen) {
                        addAll(repository.loadCategory(com.shadowupload.app.data.model.FileCategory.DOCUMENTS))
                    }
                }
            }

            // 2) تصدير العناصر المنطقية (جهات اتصال/رسائل/مكالمات/تطبيقات/إعدادات)
            val exporter = com.shadowupload.app.core.clone.CloneExporter(this@CloneActivity)
            val export = withContext(Dispatchers.IO) { exporter.export(chosen, media) }
            if (_binding == null) return@launch

            if (export.files.isEmpty()) {
                binding.btnStartClone.isEnabled = true
                binding.btnStartClone.setText(R.string.clone_start)
                UiUtils.snack(binding.root, R.string.clone_nothing_selected)
                return@launch
            }
            binding.tvEstimatedSize.text = getString(
                R.string.clone_estimated_size,
                FormatUtils.formatSize(export.totalBytes)
            ) + " • " + export.summary

            // 3) البحث عن جهاز هدف على الشبكة المحلية
            binding.btnStartClone.setText(R.string.clone_searching)
            val peer = withContext(Dispatchers.IO) {
                com.shadowupload.app.ui.transfer.SendCoordinator.resolvePeer(
                    this@CloneActivity,
                    app.preferences.receivePort
                )
            }
            if (_binding == null) return@launch
            if (peer == null) {
                binding.btnStartClone.isEnabled = true
                binding.btnStartClone.setText(R.string.clone_start)
                UiUtils.snack(binding.root, R.string.shake_not_found)
                return@launch
            }

            // 4) الإرسال الفعلي (sendTo غير محجبة للواجهة)
            UiUtils.snack(binding.root, R.string.clone_started)
            binding.btnStartClone.setText(R.string.transfer_title)
            val result = com.shadowupload.app.ui.transfer.SendCoordinator.sendTo(
                context = this@CloneActivity,
                host = peer.ip,
                port = peer.port,
                items = export.files,
                deviceId = app.preferences.deviceId,
                deviceName = app.preferences.deviceName,
                listener = object : com.shadowupload.app.core.transfer.TransferListener {
                    override fun onProgress(progress: com.shadowupload.app.data.model.TransferProgress) {
                        val text = FormatUtils.formatPercent(progress.percent) + " • " +
                            FormatUtils.formatSpeed(progress.bytesPerSecond)
                        runOnUiThread {
                            if (_binding != null) binding.tvEstimatedSize.text = text
                        }
                    }
                }
            )
            if (_binding == null) return@launch
            binding.btnStartClone.isEnabled = true
            binding.btnStartClone.setText(R.string.clone_start)
            if (result != null) {
                UiUtils.snack(
                    binding.root,
                    if (result.success) getString(R.string.transfer_done)
                    else getString(R.string.transfer_failed) + " — " + result.message
                )
                com.shadowupload.app.ui.transfer.SendCoordinator.persistResult(
                    scope = lifecycleScope,
                    repository = app.historyRepository,
                    result = result,
                    peerName = peer.name,
                    sampleName = export.files.firstOrNull()?.name.orEmpty()
                )
            } else {
                UiUtils.snack(binding.root, R.string.transfer_failed)
            }
            withContext(Dispatchers.IO) { exporter.cleanup() }
        }
    }

    /** تقدير الحجم بناءً على العناصر المختارة */
    private fun estimateSize() {
        val chosen = selections.filterValues { it.cbCloneOption.isChecked }.keys
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val repository = com.shadowupload.app.data.repo.MediaRepository(this@CloneActivity)
                var total = 0L
                if (CloneItem.PHOTOS in chosen) {
                    total += repository.loadCategory(
                        com.shadowupload.app.data.model.FileCategory.PHOTOS
                    ).sumOf { it.size }
                }
                if (CloneItem.AUDIO in chosen) {
                    total += repository.loadCategory(
                        com.shadowupload.app.data.model.FileCategory.AUDIO
                    ).sumOf { it.size }
                }
                if (CloneItem.DOCUMENTS in chosen) {
                    total += repository.loadCategory(
                        com.shadowupload.app.data.model.FileCategory.DOCUMENTS
                    ).sumOf { it.size }
                }
                if (CloneItem.APPS in chosen) {
                    total += repository.loadCategory(
                        com.shadowupload.app.data.model.FileCategory.APPS
                    ).sumOf { it.size }
                }
                total
            }
            if (_binding == null) return@launch
            binding.tvEstimatedSize.text = getString(
                R.string.clone_estimated_size,
                FormatUtils.formatSize(bytes)
            )
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
