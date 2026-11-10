package com.shadowupload.app.ui.clone

import android.os.Bundle
import android.view.View
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

        binding.btnStartClone.setOnClickListener {
            val chosen = selections.filterValues { it.cbCloneOption.isChecked }.keys
            if (chosen.isEmpty()) {
                UiUtils.snack(binding.root, R.string.clone_nothing_selected)
                return@setOnClickListener
            }
            UiUtils.snack(binding.root, R.string.clone_started)
        }
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
