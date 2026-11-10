package com.shadowupload.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.shadowupload.app.data.model.TransportConfig
import com.shadowupload.app.util.NetworkUtils

/**
 * تخزين الإعدادات محلياً فقط عبر SharedPreferences.
 * لا يوجد أي إرسال لهذه القيم خارج الجهاز.
 */
class AppPreferences(context: Context) {

    /** سياق التطبيق محفوظ لأن خاصية deviceId تحتاج ContentResolver لاحقاً */
    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** مستمع تغييرات الإعدادات (تستخدمه خدمة النقل لإعادة بناء المحرك) */
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun registerListener(onChanged: () -> Unit) {
        unregisterListener()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // نتجاهل تغيير الاسم: لا يؤثر على إعدادات المحرك
            if (key != KEY_DEVICE_NAME && key != KEY_ALL_FILES_PROMPTED) onChanged()
        }
        settingsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener() {
        settingsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        settingsListener = null
    }

    // ============ اسم الجهاز ============
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)
            ?: "shadowUpload-${android.os.Build.MODEL.take(12)}"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    /** معرف الجهاز الثابت (مبهم ولا يكشف بيانات الجهاز الحقيقية) */
    val deviceId: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (stored != null) return stored
            val generated = NetworkUtils.pseudoDeviceId(appContext)
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    // ============ إعدادات النقل ============
    var parallelStreams: Int
        get() = prefs.getInt(
            KEY_PARALLEL,
            if (maxSpeedMode) TransportConfig.MAX_STREAMS else 2
        )
        set(value) = prefs.edit()
            .putInt(KEY_PARALLEL, value.coerceIn(TransportConfig.MIN_STREAMS, TransportConfig.MAX_STREAMS))
            .apply()

    var bufferIndex: Int
        get() = prefs.getInt(KEY_BUFFER_INDEX, 2)
        set(value) = prefs.edit().putInt(KEY_BUFFER_INDEX, value.coerceIn(0, 3)).apply()

    val socketBufferBytes: Int
        get() = TransportConfig.BUFFER_CHOICES[bufferIndex.coerceIn(0, 3)]

    var maxSpeedMode: Boolean
        get() = prefs.getBoolean(KEY_MAX_SPEED, true)
        set(value) = prefs.edit().putBoolean(KEY_MAX_SPEED, value).apply()

    var verifyChecksums: Boolean
        get() = prefs.getBoolean(KEY_VERIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_VERIFY, value).apply()

    var compressionEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPRESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPRESSION, value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var autoAccept: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ACCEPT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ACCEPT, value).apply()

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION, value).apply()

    var shakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHAKE, value).apply()

    var receivePort: Int
        get() = prefs.getInt(KEY_RECEIVE_PORT, TransportConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_RECEIVE_PORT, value).apply()

    /** هل عُرض تنبيه صلاحية «كل الملفات» مرة واحدة؟ */
    var allFilesPrompted: Boolean
        get() = prefs.getBoolean(KEY_ALL_FILES_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_ALL_FILES_PROMPTED, value).apply()

    /** لقطة الإعدادات الحالية لمحرك النقل */
    fun snapshot(): TransportConfig = TransportConfig(
        parallelStreams = parallelStreams,
        socketBufferBytes = socketBufferBytes,
        verifyChecksums = verifyChecksums,
        compress = compressionEnabled,
        maxSpeedMode = maxSpeedMode,
        receivePort = receivePort
    )

    /**
     * تغيير اسم الجهاز وانتظار الكتابة فعلياً.
     * (commit لا apply) لأن انعكاس اسم الجهاز فوراً في QR والمصافحة يحتاج
     * القيمة محفوظة قبل القراءة التالية على خيط آخر.
     */
    fun commitDeviceName(value: String): Boolean =
        prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).commit()

    /** استعادة الإعدادات الافتراضية */
    fun resetToDefaults() {
        prefs.edit()
            .remove(KEY_PARALLEL)
            .remove(KEY_BUFFER_INDEX)
            .putBoolean(KEY_MAX_SPEED, true)
            .putBoolean(KEY_VERIFY, true)
            .putBoolean(KEY_COMPRESSION, false)
            .putBoolean(KEY_WIFI_ONLY, true)
            .putBoolean(KEY_KEEP_SCREEN_ON, true)
            .putBoolean(KEY_AUTO_ACCEPT, false)
            .putBoolean(KEY_VIBRATION, true)
            .putBoolean(KEY_SHAKE, true)
            .apply()
    }

    companion object {
        private const val PREF_NAME = "shadowupload_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PARALLEL = "parallel_streams"
        private const val KEY_BUFFER_INDEX = "buffer_index"
        private const val KEY_MAX_SPEED = "max_speed"
        private const val KEY_VERIFY = "verify_checksums"
        private const val KEY_COMPRESSION = "compression"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTO_ACCEPT = "auto_accept"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_SHAKE = "shake_enabled"
        private const val KEY_RECEIVE_PORT = "receive_port"
        private const val KEY_ALL_FILES_PROMPTED = "all_files_prompted"
    }
}
