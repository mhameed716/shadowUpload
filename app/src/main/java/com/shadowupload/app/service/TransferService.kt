package com.shadowupload.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.core.net.TransferEngine
import com.shadowupload.app.core.transfer.ConflictPolicy
import com.shadowupload.app.core.transfer.ReceiveSession
import com.shadowupload.app.core.transfer.TransferListener
import com.shadowupload.app.core.transfer.TransferResult
import com.shadowupload.app.data.model.TransferDirection
import com.shadowupload.app.data.model.TransferProgress
import com.shadowupload.app.data.model.TransferState
import com.shadowupload.app.data.model.TransferRecord
import com.shadowupload.app.data.repo.MediaRepository
import com.shadowupload.app.ui.transfer.TransferProgressActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * خدمة النقل في الواجهة الأمامية (Foreground Service).
 *
 * لماذا خدمة؟ لأن نقل ملف كبير قد يستغرق دقائق، ويجب ألا يتوقف
 * عند إغلاق الشاشة أو الانتقال لتطبيق آخر. الإشعار يعرض السرعة والنسبة،
 * والخدمة تعمل من أندرويد 5.0 إلى 14+ (مع نوع dataSync على أندرويد 10+).
 */
class TransferService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: TransferEngine
    private lateinit var app: ShadowUploadApp
    private var receiveSession: ReceiveSession? = null
    private var currentDirection = TransferDirection.RECEIVE
    private var startedAt = 0L
    private var sampleFileName = ""
    private var completedFiles = 0
    private var totalBytesTransferred = 0L

    private val _progress = MutableLiveData<TransferProgress>()
    val progress: LiveData<TransferProgress> get() = _progress

    private val notifyExecutor = Executors.newSingleThreadExecutor()

    inner class LocalBinder : Binder() {
        val service: TransferService get() = this@TransferService
    }

    override fun onCreate() {
        super.onCreate()
        app = application as ShadowUploadApp
        engine = TransferEngine(this, app.preferences.snapshot())
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECEIVE -> startReceiving()
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    // ================= الاستقبال =================

    /** بدء الاستماع لاستقبال ملفات */
    fun startReceiving() {
        if (receiveSession?.isRunning() == true) return
        startedAt = System.currentTimeMillis()
        currentDirection = TransferDirection.RECEIVE
        completedFiles = 0
        sampleFileName = ""
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.transfer_preparing), 0))
        _progress.postValue(TransferProgress(state = TransferState.WAITING_PEER))

        val destination = MediaRepository(this).receivedFolder()
        val session = engine.startReceiveServer(
            port = app.preferences.receivePort,
            destinationDir = destination,
            conflictPolicy = ConflictPolicy.RENAME,
            listener = listener
        )
        receiveSession = session
        if (!session.isRunning()) {
            // المنفذ مشغول: نُبلّغ الواجهة برسالة قابلة للعرض بدل الفشل الصامت
            _progress.postValue(
                TransferProgress(
                    state = TransferState.FAILED,
                    message = getString(R.string.error_port_busy)
                )
            )
        }
    }

    /** إلغاء العمل الحالي */
    fun cancelCurrentTransfer() {
        receiveSession?.cancel()
        engine.cancel()
        _progress.postValue(TransferProgress(state = TransferState.CANCELLED))
        stopEverything()
    }

    private fun stopEverything() {
        runCatching { receiveSession?.stop() }
        receiveSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /** المستمع الموحد لكل عمليات النقل */
    private val listener = object : TransferListener {
        override fun onStateChanged(state: TransferState) {
            _progress.postValue(
                TransferProgress(state = state)
            )
        }

        override fun onProgress(progress: TransferProgress) {
            _progress.postValue(progress)
            updateNotification(progress)
        }

        override fun onFileCompleted(name: String, success: Boolean, detail: String) {
            completedFiles++
            if (sampleFileName.isBlank()) sampleFileName = name
        }

        override fun onFinished(result: TransferResult) {
            totalBytesTransferred = result.totalBytes
            persistRecord(result)
            updateNotificationFinal(result)
        }
    }

    /** حفظ السجل محلياً بعد انتهاء الجلسة */
    private fun persistRecord(result: TransferResult) {
        scope.launch {
            runCatching {
                app.historyRepository.addRecord(
                    TransferRecord(
                        direction = currentDirection,
                        peerName = app.preferences.deviceName,
                        fileCount = result.filesTransferred,
                        totalBytes = result.totalBytes,
                        durationMillis = result.durationMillis,
                        timestamp = System.currentTimeMillis(),
                        success = result.success,
                        averageSpeed = result.averageBytesPerSecond,
                        sampleFileName = sampleFileName
                    )
                )
            }
        }
    }

    // ================= الإشعارات =================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.transfer_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.app_tagline)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String, progress: Int): Notification {
        val intent = Intent(this, TransferProgressActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(this, 0, intent, flags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress in 1..99) {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }

    /** تحديث الإشعار بلا تجميد الواجهة */
    private fun updateNotification(progress: TransferProgress) {
        val text = getString(
            R.string.transfer_notification_body,
            progress.currentFileName.ifBlank { getString(R.string.transfer_title) },
            FormatUtils.formatSpeed(progress.bytesPerSecond)
        )
        notifyExecutor.execute {
            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(text, progress.percent.toInt()))
            }
        }
    }

    private fun updateNotificationFinal(result: TransferResult) {
        notifyExecutor.execute {
            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val text = if (result.success) {
                    getString(
                        R.string.transfer_completed_summary,
                        result.filesTransferred,
                        FormatUtils.formatSize(result.totalBytes),
                        FormatUtils.formatDuration(result.durationMillis)
                    )
                } else {
                    getString(R.string.transfer_failed)
                }
                manager.notify(NOTIFICATION_ID, buildNotification(text, 100))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    override fun onDestroy() {
        runCatching { receiveSession?.stop() }
        notifyExecutor.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "shadowupload_transfer"
        private const val NOTIFICATION_ID = 4782
        const val ACTION_START_RECEIVE = "com.shadowupload.app.action.START_RECEIVE"
        const val ACTION_STOP = "com.shadowupload.app.action.STOP"

        /** تشغيل الخدمة من أي مكان */
        fun startReceiving(context: Context) {
            val intent = Intent(context, TransferService::class.java)
                .setAction(ACTION_START_RECEIVE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
        }
    }
}
