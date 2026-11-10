package com.shadowupload.app.ui.transfer

import android.content.Context
import com.shadowupload.app.core.net.LanScanner
import com.shadowupload.app.core.transfer.TransferListener
import com.shadowupload.app.core.transfer.TransferResult
import com.shadowupload.app.core.transfer.TransferSource
import com.shadowupload.app.data.model.FileItem
import com.shadowupload.app.data.model.TransferDirection
import com.shadowupload.app.data.model.TransferState
import com.shadowupload.app.data.repo.MediaRepository
import com.shadowupload.app.data.repo.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * منسق الإرسال: يحوّل عناصر الملفات إلى مصادر نقل، يبحث عن الجهاز الهدف،
 * ثم يشغل محرك النقل ويحفظ النتيجة في السجل.
 *
 * فُصل عن الواجهات حتى تستخدمه شاشات عدة (QR، التحريك، المجموعة، الملفات)
 * بمنطق واحد بلا تكرار.
 */
object SendCoordinator {

    /** هل هناك جهاز target؟ إن لم يُحدد نبحث تلقائياً على الشبكة */
    fun resolvePeer(context: Context, port: Int, preferredHost: String? = null): LanScanner.DiscoveredPeer? {
        if (!preferredHost.isNullOrBlank()) {
            return LanScanner(port, timeoutMs = 400).probeHost(preferredHost)
        }
        var found: LanScanner.DiscoveredPeer? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        LanScanner(port).scanOnce(
            onFound = { if (found == null) found = it },
            onFinished = { latch.countDown() }
        )
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return found
    }

    /** تحويل عناصر الواجهة إلى مصادر نقل قابلة للإرسال */
    fun toSources(context: Context, items: List<FileItem>): List<TransferSource> {
        val resolver = context.contentResolver
        return items.mapNotNull { item ->
            when {
                item.path != null -> TransferSource.FromPath(
                    path = item.path,
                    name = item.name,
                    size = item.size,
                    mimeType = item.mimeType,
                    lastModified = item.lastModified
                )
                item.uri != null -> {
                    // بعض عناصر MediaStore لا تُرجع الحجم؛ نقرأه من ContentResolver
                    val size = if (item.size > 0L) item.size else runCatching {
                        resolver.openAssetFileDescriptor(item.uri, "r")?.use { it.length } ?: 0L
                    }.getOrDefault(0L)
                    TransferSource.FromUri(
                        uriString = item.uri.toString(),
                        name = item.name,
                        size = size,
                        mimeType = item.mimeType,
                        lastModified = item.lastModified
                    )
                }
                else -> null
            }
        }
    }

    /** إرسال قائمة عناصر إلى جهاز والعودة بالنتيجة */
    fun sendTo(
        context: Context,
        host: String,
        port: Int,
        items: List<FileItem>,
        deviceId: String,
        deviceName: String,
        listener: TransferListener
    ) {
        val app = context.applicationContext as com.shadowupload.app.ShadowUploadApp
        val engine = com.shadowupload.app.core.transfer.TransferEngine(
            context,
            app.preferences.snapshot()
        )
        val sources = toSources(context, items)
        if (sources.isEmpty()) {
            listener.onStateChanged(TransferState.FAILED)
            listener.onFinished(
                TransferResult(
                    success = false,
                    filesTransferred = 0,
                    filesFailed = 0,
                    totalBytes = 0,
                    durationMillis = 0,
                    averageBytesPerSecond = 0,
                    message = "لا توجد ملفات قابلة للإرسال"
                )
            )
            return
        }
        engine.send(
            peerHost = host,
            peerPort = port,
            sources = sources,
            deviceId = deviceId,
            deviceName = deviceName,
            listener = listener
        )
    }

    /** حفظ نتيجة الإرسال في السجل */
    fun persistResult(
        scope: CoroutineScope,
        repository: HistoryRepository,
        result: TransferResult,
        peerName: String,
        sampleName: String
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.addRecord(
                    com.shadowupload.app.data.model.TransferRecord(
                        direction = TransferDirection.SEND,
                        peerName = peerName,
                        fileCount = result.filesTransferred,
                        totalBytes = result.totalBytes,
                        durationMillis = result.durationMillis,
                        timestamp = System.currentTimeMillis(),
                        success = result.success,
                        averageSpeed = result.averageBytesPerSecond,
                        sampleFileName = sampleName
                    )
                )
            }
        }
    }

    /** مجلد الاستلام الافتراضي */
    fun receivedFolder(context: Context) = MediaRepository(context).receivedFolder()
}
