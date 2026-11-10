package com.shadowupload.app.ui.splash

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import com.shadowupload.app.databinding.ActivitySplashBinding
import com.shadowupload.app.ui.MainActivity
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.PermissionUtils

/**
 * شاشة البداية: تفحص الصلاحيات الأساسية ثم تنتقل للشاشة الرئيسية.
 * لا تحجب المستخدم: الانتقال يتم بعد ~900 مللي ثانية كحد أقصى.
 */
class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // نكمل حتى لو رفض المستخدم: التطبيق يعمل ويعرض دعوة للمنح داخل «أنا»
        goToMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvStatus.text = getString(com.shadowupload.app.R.string.splash_checking_permissions)
        binding.ivLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420)
            .start()

        handler.postDelayed({ checkPermissionsAndContinue() }, 700)
    }

    private fun checkPermissionsAndContinue() {
        if (navigated) return
        val storage = PermissionUtils.storagePermissions()
        val notifications = PermissionUtils.notificationPermissions()
        val needed = (storage + notifications).filterNot {
            PermissionUtils.isGranted(this, it)
        }
        if (needed.isEmpty()) {
            goToMain()
        } else {
            // نطلب فقط صلاحيات الوسائط والتنبيهات؛ صلاحيات الموقع/البلوتوث تُطلب عند الحاجة
            val request = buildList {
                addAll(storage)
                addAll(notifications)
            }.filter { PermissionUtils.isGranted(this@SplashActivity, it).not() }
            if (request.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                goToMain()
            } else {
                permissionLauncher.launch(request.toTypedArray())
            }
        }
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        binding.tvStatus.text = getString(com.shadowupload.app.R.string.splash_ready)
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(com.shadowupload.app.R.anim.fade_in, com.shadowupload.app.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
