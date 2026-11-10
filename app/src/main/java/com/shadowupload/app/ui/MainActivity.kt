package com.shadowupload.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.shadowupload.app.R
import com.shadowupload.app.databinding.ActivityMainBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.ui.files.FilesFragment
import com.shadowupload.app.ui.hub.TransferHubActivity
import com.shadowupload.app.ui.me.MeFragment
import com.shadowupload.app.util.UiUtils

/**
 * الشاشة الرئيسية.
 *
 * شريط سفلي من ثلاث نقاط فقط حسب المواصفات:
 *   1) «الملفات»  — تصنيفات محلية ومتصفح الذاكرة.
 *   2) زر عائم مركزي — يفتح مركز النقل.
 *   3) «أنا»     — الإعدادات والسجل والتنظيف.
 *
 * لا يوجد تبويب «اكتشاف» ولا «انص به» في الشريط السفلي إطلاقاً.
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTab = TAB_FILES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupFab()

        if (savedInstanceState == null) {
            showFragment(FilesFragment(), TAB_FILES)
        } else {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_FILES)
            applyTabVisuals(currentTab)
        }
    }

    // ================= التنقل =================

    private fun setupNavigation() {
        binding.navFiles.setOnClickListener {
            if (currentTab != TAB_FILES) showFragment(FilesFragment(), TAB_FILES)
        }
        binding.navMe.setOnClickListener {
            if (currentTab != TAB_ME) showFragment(MeFragment(), TAB_ME)
        }
    }

    private fun setupFab() {
        binding.fabTransferHub.setOnClickListener {
            startActivity(Intent(this, TransferHubActivity::class.java))
            overridePendingTransition(R.anim.slide_up, R.anim.fade_in)
        }
    }

    /** عرض الشاشة المطلوبة (الملفات/أنا) */
    private fun showFragment(fragment: Fragment, tab: Int) {
        currentTab = tab
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            .replace(R.id.screenContainer, fragment, fragment::class.java.simpleName)
            .commit()
        applyTabVisuals(tab)
    }

    /** تحديث ألوان الشريط العلوي والسفلي حسب التبويب */
    private fun applyTabVisuals(tab: Int) {
        val cyan = ContextCompat.getColor(this, R.color.neon_cyan)
        val muted = ContextCompat.getColor(this, R.color.text_tertiary)

        val isFiles = tab == TAB_FILES
        binding.tvNavFiles.setTextColor(if (isFiles) cyan else muted)
        binding.tvNavMe.setTextColor(if (isFiles) muted else cyan)
        binding.ivNavFiles.setColorFilter(if (isFiles) cyan else muted)
        binding.ivNavMe.setColorFilter(if (isFiles) muted else cyan)

        binding.tvTopTitle.text = getString(
            if (isFiles) R.string.files_title else R.string.me_title
        )
        binding.tvTopSubtitle.text = getString(
            if (isFiles) R.string.files_subtitle else R.string.me_subtitle
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    /** إظهار رسالة قصيرة من أي شاشة فرعية */
    fun toast(message: Int) {
        UiUtils.toast(this, message)
    }

    companion object {
        private const val STATE_TAB = "state_tab"
        private const val TAB_FILES = 0
        private const val TAB_ME = 1
    }
}
