package com.shadowupload.app.ui.settings

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.databinding.ActivitySettingsBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.FormatUtils
import com.shadowupload.app.util.UiUtils
import com.shadowupload.app.data.model.TransportConfig

/**
 * شاشة الإعدادات: سرعة النقل، التحقق، الخصوصية.
 * كل تغيير يُحفظ فوراً في SharedPreferences ليصل إلى محرك النقل التالي.
 */
class SettingsActivity : BaseActivity() {

    private var _binding: ActivitySettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: ShadowUploadApp
    private var bindingInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as ShadowUploadApp

        binding.btnBack.setOnClickListener { finish() }
        binding.btnResetSettings.setOnClickListener { confirmReset() }

        loadValues()
        wireListeners()
        wireLanguage()
    }

    /** تعبئة القيم الحالية */
    private fun loadValues() {
        bindingInProgress = true
        val prefs = app.preferences

        binding.swMaxSpeed.isChecked = prefs.maxSpeedMode
        binding.sliderThreads.value = prefs.parallelStreams.toFloat().coerceIn(1f, 8f)
        binding.sliderBuffer.value = prefs.bufferIndex.toFloat().coerceIn(0f, 3f)
        binding.swVerifyChecksum.isChecked = prefs.verifyChecksums
        binding.swCompression.isChecked = prefs.compressionEnabled
        binding.swWifiOnly.isChecked = prefs.wifiOnly
        binding.swKeepScreenOn.isChecked = prefs.keepScreenOn
        binding.swAutoAccept.isChecked = prefs.autoAccept
        binding.swVibration.isChecked = prefs.vibrationEnabled

        updateThreadsLabel(prefs.parallelStreams)
        updateBufferLabel(prefs.bufferIndex)
        bindingInProgress = false
    }

    /** ربط كل عنصر بحفظ فوري */
    private fun wireListeners() {
        binding.swMaxSpeed.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.maxSpeedMode = checked
            if (checked) {
                app.preferences.parallelStreams = TransportConfig.MAX_STREAMS
                app.preferences.bufferIndex = 3
                loadValues()
            }
        }

        binding.sliderThreads.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || bindingInProgress) return@addOnChangeListener
            val threads = value.toInt()
            app.preferences.parallelStreams = threads
            updateThreadsLabel(threads)
        }

        binding.sliderBuffer.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || bindingInProgress) return@addOnChangeListener
            val index = value.toInt()
            app.preferences.bufferIndex = index
            updateBufferLabel(index)
        }

        binding.swVerifyChecksum.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.verifyChecksums = checked
        }

        binding.swCompression.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.compressionEnabled = checked
        }

        binding.swWifiOnly.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.wifiOnly = checked
        }

        binding.swKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.keepScreenOn = checked
        }

        binding.swAutoAccept.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.autoAccept = checked
        }

        binding.swVibration.setOnCheckedChangeListener { _, checked ->
            if (bindingInProgress) return@setOnCheckedChangeListener
            app.preferences.vibrationEnabled = checked
        }
    }

    /**
     * تبديل اللغة: العربية هي الافتراضية، والإنجليزية احتياطية.
     * نستخدم AppCompatDelegate.setApplicationLocales ليبقى الخيار محفوظاً.
     */
    private fun wireLanguage() {
        val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val isArabic = current.isEmpty || current.toLanguageTags().startsWith("ar")
        binding.toggleLanguage.check(if (isArabic) R.id.btnLanguageAr else R.id.btnLanguageEn)
        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val locales = androidx.core.os.LocaleListCompat.forLanguageTags(
                if (checkedId == R.id.btnLanguageAr) "ar" else "en"
            )
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            recreate()
        }
    }

    private fun updateThreadsLabel(threads: Int) {
        binding.tvThreadsLabel.text = getString(R.string.settings_threads) + ": " +
            getString(R.string.settings_threads_value, threads)
    }

    private fun updateBufferLabel(index: Int) {
        val bytes = TransportConfig.BUFFER_CHOICES[index.coerceIn(0, 3)]
        binding.tvBufferLabel.text = getString(R.string.settings_buffer) + ": " +
            FormatUtils.formatSize(bytes.toLong())
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_confirm)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                app.preferences.resetToDefaults()
                loadValues()
                UiUtils.snack(binding.root, R.string.settings_reset_done)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
