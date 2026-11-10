package com.shadowupload.app.core.clone

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.shadowupload.app.data.model.CloneItem
import com.shadowupload.app.data.model.FileItem
import com.shadowupload.app.data.model.FileCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * تصدير عناصر «نسخ الهاتف» إلى ملفات قياسية يمكن استيرادها على الجهاز الجديد.
 *
 * الصيغ المختارة معيارية ومفتوحة:
 *   - جهات الاتصال → vCard 3.0 (ملف .vcf يستورده أي هاتف)
 *   - الرسائل      → JSON (نص، مرسل، تاريخ)
 *   - سجل المكالمات → JSON
 *   - التطبيقات    → JSON (اسم الحزمة والإصدار)
 *   - الإعدادات    → JSON (إعدادات التطبيق)
 *
 * كل التصدير محلي: تُكتب الملفات في ذاكرة التطبيق المؤقتة ثم تُرسل عبر
 * محرك النقل نفسه، وتُحذف بعد الإرسال.
 */
class CloneExporter(private val context: Context) {

    /** مجلد مؤقت خاص بالنسخ */
    private fun workDir(): File {
        val dir = File(context.cacheDir, "clone_export")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** نتيجة التصدير: ملفات جاهزة + ملخص */
    data class ExportResult(
        val files: List<FileItem>,
        val totalBytes: Long,
        val summary: String
    )

    /**
     * تصدير العناصر المختارة إلى ملفات.
     * يعمل على خيط الخلفية (يُستدعى من coroutine في الواجهة).
     */
    fun export(selected: Set<CloneItem>, mediaItems: List<FileItem>): ExportResult {
        val files = mutableListOf<FileItem>()
        val issues = mutableListOf<String>()

        if (selected.contains(CloneItem.CONTACTS)) {
            runCatching { exportContacts() }
                .onSuccess { it?.let(files::add) }
                .onFailure { issues.add("جهات الاتصال") }
        }
        if (selected.contains(CloneItem.SMS)) {
            runCatching { exportSms() }
                .onSuccess { it?.let(files::add) }
                .onFailure { issues.add("الرسائل") }
        }
        if (selected.contains(CloneItem.CALL_LOG)) {
            runCatching { exportCallLog() }
                .onSuccess { it?.let(files::add) }
                .onFailure { issues.add("سجل المكالمات") }
        }
        if (selected.contains(CloneItem.APPS)) {
            runCatching { exportApps() }
                .onSuccess { it?.let(files::add) }
                .onFailure { issues.add("التطبيقات") }
        }
        if (selected.contains(CloneItem.SETTINGS)) {
            runCatching { exportSettings() }
                .onSuccess { it?.let(files::add) }
                .onFailure { issues.add("الإعدادات") }
        }

        // الوسائط والمستندات تُنقل كما هي (بلا تحويل)
        files.addAll(mediaItems)

        val total = files.sumOf { it.size }
        val summary = buildString {
            append("${files.size} عنصر")
            if (issues.isNotEmpty()) append(" • تعذر: ${issues.joinToString("، ")}")
        }
        return ExportResult(files, total, summary)
    }

    /** حذف ملفات التصدير المؤقتة بعد نجاح الإرسال */
    fun cleanup() {
        runCatching { workDir().deleteRecursively() }
    }

    // =========================================================================
    //  جهات الاتصال → vCard 3.0
    // =========================================================================

    private fun exportContacts(): FileItem? {
        val output = File(workDir(), "contacts_shadowupload.vcf")
        val builder = StringBuilder()
        var count = 0
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(projection[0])
            val numberIndex = cursor.getColumnIndex(projection[1])
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() && number.isBlank()) continue
                builder.append("BEGIN:VCARD\r\n")
                builder.append("VERSION:3.0\r\n")
                builder.append("FN:").append(escapeVCard(name)).append("\r\n")
                builder.append("TEL;TYPE=CELL:").append(number.replace(" ", "")).append("\r\n")
                builder.append("END:VCARD\r\n")
                count++
            }
        }
        if (count == 0) return null
        output.writeText(builder.toString(), Charsets.UTF_8)
        return FileItem(
            id = output.absolutePath,
            uri = null,
            path = output.absolutePath,
            name = output.name,
            size = output.length(),
            mimeType = "text/x-vcard",
            lastModified = output.lastModified(),
            category = FileCategory.DOCUMENTS
        )
    }

    private fun escapeVCard(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    // =========================================================================
    //  الرسائل → JSON
    // =========================================================================

    private fun exportSms(): FileItem? {
        val output = File(workDir(), "sms_shadowupload.json")
        val array = JSONArray()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val entry = JSONObject()
                entry.put("address", cursor.getString(0).orEmpty())
                entry.put("body", cursor.getString(1).orEmpty())
                entry.put("date", cursor.getLong(2))
                entry.put("type", cursor.getInt(3))
                array.put(entry)
            }
        }
        if (array.length() == 0) return null
        output.writeText(array.toString(), Charsets.UTF_8)
        return FileItem(
            id = output.absolutePath,
            uri = null,
            path = output.absolutePath,
            name = output.name,
            size = output.length(),
            mimeType = "application/json",
            lastModified = output.lastModified(),
            category = FileCategory.DOCUMENTS
        )
    }

    // =========================================================================
    //  سجل المكالمات → JSON
    // =========================================================================

    private fun exportCallLog(): FileItem? {
        val output = File(workDir(), "call_log_shadowupload.json")
        val array = JSONArray()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val entry = JSONObject()
                entry.put("number", cursor.getString(0).orEmpty())
                entry.put("type", cursor.getInt(1))
                entry.put("date", cursor.getLong(2))
                entry.put("duration", cursor.getLong(3))
                array.put(entry)
            }
        }
        if (array.length() == 0) return null
        output.writeText(array.toString(), Charsets.UTF_8)
        return FileItem(
            id = output.absolutePath,
            uri = null,
            path = output.absolutePath,
            name = output.name,
            size = output.length(),
            mimeType = "application/json",
            lastModified = output.lastModified(),
            category = FileCategory.DOCUMENTS
        )
    }

    // =========================================================================
    //  التطبيقات → JSON (اسم الحزمة والإصدار)
    // =========================================================================

    private fun exportApps(): FileItem? {
        val output = File(workDir(), "apps_shadowupload.json")
        val array = JSONArray()
        val pm = context.packageManager
        val packages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        packages.forEach { info ->
            val appInfo = info.applicationInfo ?: return@forEach
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) return@forEach
            val entry = JSONObject()
            entry.put("package", info.packageName)
            entry.put("label", runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(info.packageName))
            entry.put("versionName", info.versionName ?: "")
            entry.put(
                "versionCode",
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            )
            array.put(entry)
        }
        if (array.length() == 0) return null
        output.writeText(array.toString(), Charsets.UTF_8)
        return FileItem(
            id = output.absolutePath,
            uri = null,
            path = output.absolutePath,
            name = output.name,
            size = output.length(),
            mimeType = "application/json",
            lastModified = output.lastModified(),
            category = FileCategory.DOCUMENTS
        )
    }

    // =========================================================================
    //  إعدادات التطبيق → JSON
    // =========================================================================

    private fun exportSettings(): FileItem? {
        val output = File(workDir(), "settings_shadowupload.json")
        val prefs = (context.applicationContext as com.shadowupload.app.ShadowUploadApp).preferences
        val json = JSONObject()
            .put("deviceName", prefs.deviceName)
            .put("parallelStreams", prefs.parallelStreams)
            .put("bufferBytes", prefs.socketBufferBytes)
            .put("verifyChecksums", prefs.verifyChecksums)
            .put("wifiOnly", prefs.wifiOnly)
            .put("keepScreenOn", prefs.keepScreenOn)
            .put("receivePort", prefs.receivePort)
            .put("maxSpeedMode", prefs.maxSpeedMode)
        output.writeText(json.toString(2), Charsets.UTF_8)
        return FileItem(
            id = output.absolutePath,
            uri = null,
            path = output.absolutePath,
            name = output.name,
            size = output.length(),
            mimeType = "application/json",
            lastModified = output.lastModified(),
            category = FileCategory.DOCUMENTS
        )
    }
}
