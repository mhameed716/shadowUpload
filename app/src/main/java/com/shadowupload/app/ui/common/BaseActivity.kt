package com.shadowupload.app.ui.common

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.shadowupload.app.R

/**
 * أساس كل الشاشات: يضبط اتجاه RTL، ألوان شريط الحالة،
 * ويوفّر أدوات مشتركة للتنقل.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = getColorCompat(R.color.shadow_black)
        window.navigationBarColor = getColorCompat(R.color.shadow_black)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                window.decorView.systemUiVisibility = 0
            }
        }
    }

    protected fun getColorCompat(resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, resId)

    /** إظهار/إخفاء عنصر */
    protected fun View.show() {
        visibility = View.VISIBLE
    }

    protected fun View.hide() {
        visibility = View.GONE
    }

    /** إنهاء الشاشة بانتقال ناعم */
    protected fun finishWithAnimation() {
        finish()
        overridePendingTransitionCompat()
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.slide_out_end, R.anim.fade_out)
        } else {
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_end)
        }
    }
}
