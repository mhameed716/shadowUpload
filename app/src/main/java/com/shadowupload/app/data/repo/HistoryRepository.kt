package com.shadowupload.app.data.repo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.shadowupload.app.data.model.TransferDirection
import com.shadowupload.app.data.model.TransferRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * سجل النقل — قاعدة بيانات SQLite محلية بالكامل (لا مزامنة سحابية).
 *
 * الجدول بسيط عمداً: كل صف عملية نقل واحدة (لا ملف بملف) ليبقى السجل
 * خفيفاً وسريع الفتح حتى بعد آلاف العمليات.
 */
class HistoryRepository(context: Context) {

    private val helper = HistoryDbHelper(context.applicationContext)

    /** إضافة عملية نقل إلى السجل */
    suspend fun addRecord(record: TransferRecord): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(COL_DIRECTION, record.direction.name)
            put(COL_PEER, record.peerName)
            put(COL_FILE_COUNT, record.fileCount)
            put(COL_TOTAL_BYTES, record.totalBytes)
            put(COL_DURATION, record.durationMillis)
            put(COL_TIMESTAMP, record.timestamp)
            put(COL_SUCCESS, if (record.success) 1 else 0)
            put(COL_AVG_SPEED, record.averageSpeed)
            put(COL_SAMPLE_NAME, record.sampleFileName)
        }
        helper.writableDatabase.insert(TABLE_NAME, null, values)
    }

    /** قراءة كل السجل بترتيب تنازلي بالزمن */
    suspend fun allRecords(limit: Int = 200): List<TransferRecord> = withContext(Dispatchers.IO) {
        val records = mutableListOf<TransferRecord>()
        helper.readableDatabase.query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COL_ID)
            val dirIndex = cursor.getColumnIndexOrThrow(COL_DIRECTION)
            val peerIndex = cursor.getColumnIndexOrThrow(COL_PEER)
            val countIndex = cursor.getColumnIndexOrThrow(COL_FILE_COUNT)
            val bytesIndex = cursor.getColumnIndexOrThrow(COL_TOTAL_BYTES)
            val durationIndex = cursor.getColumnIndexOrThrow(COL_DURATION)
            val timeIndex = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val successIndex = cursor.getColumnIndexOrThrow(COL_SUCCESS)
            val speedIndex = cursor.getColumnIndexOrThrow(COL_AVG_SPEED)
            val sampleIndex = cursor.getColumnIndexOrThrow(COL_SAMPLE_NAME)
            while (cursor.moveToNext()) {
                records.add(
                    TransferRecord(
                        id = cursor.getLong(idIndex),
                        direction = runCatching {
                            TransferDirection.valueOf(cursor.getString(dirIndex))
                        }.getOrDefault(TransferDirection.SEND),
                        peerName = cursor.getString(peerIndex) ?: "",
                        fileCount = cursor.getInt(countIndex),
                        totalBytes = cursor.getLong(bytesIndex),
                        durationMillis = cursor.getLong(durationIndex),
                        timestamp = cursor.getLong(timeIndex),
                        success = cursor.getInt(successIndex) == 1,
                        averageSpeed = cursor.getLong(speedIndex),
                        sampleFileName = cursor.getString(sampleIndex) ?: ""
                    )
                )
            }
        }
        records
    }

    /** إحصاء سريع لجلسات اليوم */
    suspend fun todayCount(): Int = withContext(Dispatchers.IO) {
        val startOfDay = startOfToday()
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME WHERE $COL_TIMESTAMP >= ?",
            arrayOf(startOfDay.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** مسح كل السجل */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(TABLE_NAME, null, null)
        Unit
    }

    private fun startOfToday(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** مساعد قاعدة البيانات */
    private class HistoryDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_NAME (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_DIRECTION TEXT NOT NULL,
                    $COL_PEER TEXT NOT NULL,
                    $COL_FILE_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COL_TOTAL_BYTES INTEGER NOT NULL DEFAULT 0,
                    $COL_DURATION INTEGER NOT NULL DEFAULT 0,
                    $COL_TIMESTAMP INTEGER NOT NULL,
                    $COL_SUCCESS INTEGER NOT NULL DEFAULT 1,
                    $COL_AVG_SPEED INTEGER NOT NULL DEFAULT 0,
                    $COL_SAMPLE_NAME TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_transfer_time ON $TABLE_NAME($COL_TIMESTAMP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // ترقية بسيطة: إعادة إنشاء الجدول كسجل غير حرج
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "shadowupload_history.db"
        const val DB_VERSION = 1
        const val TABLE_NAME = "transfer_history"
        const val COL_ID = "id"
        const val COL_DIRECTION = "direction"
        const val COL_PEER = "peer_name"
        const val COL_FILE_COUNT = "file_count"
        const val COL_TOTAL_BYTES = "total_bytes"
        const val COL_DURATION = "duration_ms"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_SUCCESS = "success"
        const val COL_AVG_SPEED = "avg_speed"
        const val COL_SAMPLE_NAME = "sample_name"
    }
}
