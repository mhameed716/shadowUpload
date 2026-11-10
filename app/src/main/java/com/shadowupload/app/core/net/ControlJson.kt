package com.shadowupload.app.core.net

import com.shadowupload.app.data.model.PeerDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * رسائل التحكم بصيغة JSON (مكتبة org.json المدمجة في أندرويد).
 * الحمولات صغيرة (أقل من 1 كيلوبايت) فلا تؤثر على السرعة، أما البيانات
 * نفسها فتُرسل خاماً عبر Zero-Copy.
 */
object ControlJson {

    // ============ مفتاح الرسالة ============
    private const val KEY_TYPE = "t"
    private const val KEY_DEVICE_ID = "id"
    private const val KEY_DEVICE_NAME = "n"
    private const val KEY_APP_VERSION = "v"
    private const val KEY_OS = "os"

    /** حمولة HELLO: هوية المرسل */
    fun encodeHello(deviceId: String, deviceName: String, appVersion: String): ByteArray {
        val json = JSONObject()
            .put(KEY_TYPE, "hello")
            .put(KEY_DEVICE_ID, deviceId)
            .put(KEY_DEVICE_NAME, deviceName)
            .put(KEY_APP_VERSION, "1.0.0")
            .put(KEY_OS, android.os.Build.VERSION.SDK_INT)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** تحليل HELLO القادم مع حماية من الحقول الناقصة */
    fun decodePeer(payload: String, fallbackHost: String, fallbackPort: Int): PeerDevice {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        val id = json?.optString(KEY_DEVICE_ID, "")?.ifBlank { "unknown" } ?: "unknown"
        val name = json?.optString(KEY_DEVICE_NAME, "")?.ifBlank { "جهاز مجهول" } ?: "جهاز مجهول"
        return PeerDevice(
            deviceId = id.take(48),
            name = name.take(64),
            host = fallbackHost,
            port = fallbackPort
        )
    }

    /** وصف ملف في بيان الإرسال */
    data class FileEntry(
        val name: String,
        val size: Long,
        val mimeType: String?
    )

    /** حمولة OFFER: بيان الملفات المراد إرسالها */
    fun encodeOffer(files: List<FileEntry>, totalBytes: Long): ByteArray {
        val array = JSONArray()
        files.forEach { file ->
            array.put(
                JSONObject()
                    .put("n", file.name)
                    .put("s", file.size)
                    .put("m", file.mimeType ?: "")
            )
        }
        val json = JSONObject()
            .put(KEY_TYPE, "offer")
            .put("count", files.size)
            .put("total", totalBytes)
            .put("files", array)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** تحليل OFFER */
    fun decodeOffer(payload: String): Pair<List<FileEntry>, Long> {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return emptyList<FileEntry>() to 0L
        val array = json.optJSONArray("files") ?: JSONArray()
        val files = ArrayList<FileEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            files.add(
                FileEntry(
                    name = item.optString("n", "ملف"),
                    size = item.optLong("s", 0L),
                    mimeType = item.optString("m", "").ifBlank { null }
                )
            )
        }
        return files to json.optLong("total", 0L)
    }

    /** رسالة قبول/رفض مع سبب اختياري */
    fun encodeResult(type: String, accepted: Boolean, reason: String? = null): ByteArray {
        val json = JSONObject()
            .put(KEY_TYPE, type)
            .put("ok", accepted)
            .put("reason", reason ?: "")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** رسالة خطأ موحدة */
    fun encodeError(message: String): ByteArray {
        val json = JSONObject().put(KEY_TYPE, "error").put("message", message)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** استخراج حقل نصي من حمولة */
    fun optString(payload: String, key: String): String? =
        runCatching { JSONObject(payload).optString(key, "").ifBlank { null } }.getOrNull()

    /** استخراج حقل منطقي */
    fun optBoolean(payload: String, key: String): Boolean =
        runCatching { JSONObject(payload).optBoolean(key, false) }.getOrDefault(false)
}
