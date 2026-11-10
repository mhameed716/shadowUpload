package com.shadowupload.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * إدارة الصلاحيات المتوافقة مع كل إصدارات أندرويد (21 → 34+).
 *
 * الفكرة: لا نكتب شروط إصدارات متناثرة في الواجهات، بل نسأل هذا الكائن
 * عن الصلاحيات المطلوبة فعلياً على الجهاز الحالي.
 */
object PermissionUtils {

    /** صلاحيات قراءة الملفات المطلوبة حسب إصدار النظام */
    fun storagePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** صلاحيات الأجهزة القريبة (اكتشاف الشبكة/البلوتوث) */
    fun nearbyPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /** صلاحية التنبيهات على أندرويد 13+ */
    fun notificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun areAllGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { isGranted(context, it) }

    /**
     * هل نملك وصولاً كافياً لقراءة ملفات المستخدم؟
     * على أندرويد 11+ قد نحتاج إلى MANAGE_EXTERNAL_STORAGE لتصفح كامل الذاكرة.
     */
    fun hasStorageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
        }
        return areAllGranted(context, storagePermissions())
    }

    /** هل نملك وصولاً كاملاً لكل الملفات (MANAGE_EXTERNAL_STORAGE)؟ */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** فتح شاشة «كل الملفات» في إعدادات النظام (أندرويد 11+) */
    fun requestAllFilesAccess(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivityForResult(intent, requestCode)
        }.onFailure {
            runCatching {
                activity.startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    requestCode
                )
            }
        }
    }

    /** الصلاحيات الناقصة من مجموعة معينة */
    fun missing(context: Context, permissions: Array<String>): Array<String> =
        permissions.filterNot { isGranted(context, it) }.toTypedArray()

    /** صلاحيات جهات الاتصال والرسائل (لميزة نسخ الهاتف) */
    fun contactsPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS
    )

    fun smsPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_SMS
    )

    /** التحقق من استثناء تحسين البطارية (مهم لخدمة النقل الطويلة) */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
