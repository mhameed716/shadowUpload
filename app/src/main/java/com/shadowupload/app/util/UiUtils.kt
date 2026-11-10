package com.shadowupload.app.util

import android.content.Context
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.snackbar.Snackbar
import com.shadowupload.app.R

/**
 * أدوات واجهة مشتركة: التوست، السنackبار، وتثبيت اللغة العربية.
 */
object UiUtils {

    fun toast(context: Context, message: CharSequence) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun toast(context: Context, @StringRes resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    fun snack(view: View, message: CharSequence) {
        Snackbar.make(view, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(view.context.getColorCompat(R.color.shadow_surface_elevated))
            .setTextColor(view.context.getColorCompat(R.color.text_primary))
            .show()
    }

    fun snack(view: View, @StringRes resId: Int) {
        snack(view, view.context.getString(resId))
    }

    private fun Context.getColorCompat(colorRes: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, colorRes)

    /** نبضة نيون خفيفة تُستخدم في المؤشرات الحية */
    fun pulse(view: View) {
        view.animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .setDuration(240)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .start()
            }
            .start()
    }

    /** تبديل شفافية عنصر */
    fun setVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** تثبيت لغة التطبيق على العربية (مع دعم RTL الكامل) */
    fun applyArabicLocale() {
        val locales = LocaleListCompat.forLanguageTags("ar")
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** إعادة اللغة إلى لغة النظام */
    fun clearLocaleOverride() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
}
