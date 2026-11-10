package com.shadowupload.app.ui.qr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.core.net.HandshakeToken
import com.shadowupload.app.core.net.QrUtils
import com.shadowupload.app.core.transfer.TransferListener
import com.shadowupload.app.data.model.TransferProgress
import com.shadowupload.app.databinding.ActivityQrShareBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.ui.transfer.SendCoordinator
import com.shadowupload.app.util.FormatUtils
import com.shadowupload.app.util.NetworkUtils
import com.shadowupload.app.util.PermissionUtils
import com.shadowupload.app.util.UiUtils
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors

/**
 * المشاركة عبر QR: يعرض رمزاً يحتوي على (العنوان + المنفذ + رمز الجلسة)
 * ليتم الاتصال في أقل من 0.2 ثانية بلا أي اكتشاف إضافي.
 *
 * وضعان:
 *  - استقبال: يعرض رمز هذا الجهاز ليمسحه الآخر.
 *  - إرسال: يفتح الكاميرا لمسح رمز الجهاز الآخر ثم يرسل المحدد.
 */
class QrShareActivity : BaseActivity() {

    private var _binding: ActivityQrShareBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: ShadowUploadApp
    private var token: HandshakeToken.Token? = null
    private var countdown: CountDownTimer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var scannerActive = false
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScanner() else UiUtils.snack(binding.root, R.string.qr_camera_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityQrShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as ShadowUploadApp

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopyToken.setOnClickListener { copyToken() }
        binding.btnRegenerate.setOnClickListener { regenerateToken() }
        // ملاحظة: onTabSelected هو المسؤول عن تشغيل الماسح،
        // فلا نستدعيه هنا أيضاً (would-open-twice)
        binding.btnScanQr.setOnClickListener {
            binding.tabsQrMode.getTabAt(1)?.select()
        }

        setupTabs()
        regenerateToken()

        // بدء الاستقبال الفعلي على المنفذ مباشرة: الرمز المعروض جاهز للاستخدام
        com.shadowupload.app.service.TransferService.startReceiving(this)

        val startMode = intent?.getStringExtra(EXTRA_MODE)
        if (startMode == MODE_SEND) {
            binding.tabsQrMode.getTabAt(1)?.select()
            requestScanner()
        }
    }

    private fun setupTabs() {
        binding.tabsQrMode.addTab(binding.tabsQrMode.newTab().setText(R.string.qr_receive_title))
        binding.tabsQrMode.addTab(binding.tabsQrMode.newTab().setText(R.string.qr_send_title))
        binding.tabsQrMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val receiveMode = tab.position == 0
                binding.qrContainer.visibility = if (receiveMode) View.VISIBLE else View.GONE
                binding.scanContainer.visibility = if (receiveMode) View.GONE else View.VISIBLE
                binding.tvQrHint.setText(
                    if (receiveMode) R.string.qr_show_code else R.string.qr_scan_code
                )
                binding.btnScanQr.visibility = if (receiveMode) View.VISIBLE else View.GONE
                binding.btnRegenerate.visibility = if (receiveMode) View.VISIBLE else View.GONE
                if (!receiveMode) requestScanner() else stopScanner()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    // ================= توليد الرمز =================

    private fun regenerateToken() {
        val host = NetworkUtils.localIpAddress()
        val port = app.preferences.receivePort
        if (host == null) {
            binding.tvQrExpiry.setText(R.string.wifi_not_connected)
            return
        }
        val code = HandshakeToken.generateNumericCode()
        val newToken = HandshakeToken.Token(
            code = code,
            host = host,
            port = port,
            expiresAt = System.currentTimeMillis() + HandshakeToken.DEFAULT_TTL_MS,
            fingerprint = HandshakeToken.fingerprintOf(host, port, code)
        )
        token = newToken

        val uri = HandshakeToken.buildUri(newToken.host, newToken.port, newToken.code)
        binding.tvQrToken.text = newToken.code
        binding.tvHandshake.setText(R.string.qr_handshake)

        val bitmap = QrUtils.generate(uri, 640)
        if (bitmap != null) {
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.pbQrBusy.visibility = View.GONE
        } else {
            // لا كاميرا/ذاكرة كافية لتوليد الصورة: يبقى الرمز النصي صالحاً
            binding.pbQrBusy.visibility = View.GONE
            UiUtils.snack(binding.root, R.string.qr_camera_unavailable)
        }
        startExpiryCountdown(newToken)
    }

    /** عدّاد تنازلي حتى انتهاء صلاحية الرمز */
    private fun startExpiryCountdown(current: HandshakeToken.Token) {
        countdown?.cancel()
        val remaining = (current.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        countdown = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null) return
                val seconds = (millisUntilFinished / 1000L).coerceAtLeast(0L)
                binding.tvQrExpiry.text = getString(R.string.qr_expires_in, seconds)
            }

            override fun onFinish() {
                if (_binding == null) return
                // انتهت الصلاحية: نولّد رمزاً جديداً تلقائياً بدل رمز ميت
                regenerateToken()
            }
        }.start()
    }

    // ================= نسخ الرمز =================

    private fun copyToken() {
        val current = token ?: return
        val link = HandshakeToken.buildUri(current.host, current.port, current.code)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_title), link))
        UiUtils.snack(binding.root, R.string.qr_link_copied)
    }

    // ================= الماسح =================

    private fun requestScanner() {
        if (scannerActive) return
        when {
            !PermissionUtils.isGranted(this, Manifest.permission.CAMERA) ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            else -> startScanner()
        }
    }

    private fun startScanner() {
        if (_binding == null || scannerActive) return
        scannerActive = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (_binding == null) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val previewView = PreviewView(this)
                binding.scanContainer.removeAllViews()
                binding.scanContainer.addView(previewView)

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image -> analyzeFrame(image) }

                provider.unbindAll()
                val hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                provider.bindToLifecycle(
                    this,
                    if (hasBackCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
            } catch (error: Throwable) {
                scannerActive = false
                UiUtils.snack(binding.root, R.string.qr_camera_unavailable)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes, image.width, image.height, 0, 0, image.width, image.height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { MultiFormatReader().decode(bitmap) }.getOrNull()
            val text = result?.text
            if (!text.isNullOrBlank()) {
                val parsed = HandshakeToken.parseUri(text)
                runOnUiThread {
                    if (parsed != null) onQrScanned(parsed) else {
                        if (_binding != null) UiUtils.snack(binding.root, R.string.qr_invalid)
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun onQrScanned(peer: HandshakeToken.Token) {
        if (_binding == null) return
        stopScanner()
        binding.tvHandshake.setText(R.string.qr_handshake)
        binding.pbQrBusy.visibility = View.VISIBLE

        val pending = com.shadowupload.app.ui.transfer.PendingTransfer.items
        if (pending.isEmpty()) {
            // لا ملفات محددة: نربط الجهاز فقط ليكمله المستخدم من تبويب الملفات
            binding.pbQrBusy.visibility = View.GONE
            UiUtils.snack(binding.root, R.string.transfer_no_peer)
            return
        }

        lifecycleScope.launch {
            val result = SendCoordinator.sendTo(
                context = this@QrShareActivity,
                host = peer.host,
                port = peer.port,
                items = pending,
                deviceId = app.preferences.deviceId,
                deviceName = app.preferences.deviceName,
                listener = object : TransferListener {
                    override fun onProgress(progress: TransferProgress) {
                        val text = FormatUtils.formatSpeed(progress.bytesPerSecond)
                        runOnUiThread {
                            if (isAliveBinding()) binding.tvQrExpiry.text = text
                        }
                    }
                }
            )
            if (!isAliveBinding()) return@launch
            binding.pbQrBusy.visibility = View.GONE
            if (result != null) {
                binding.tvHandshake.text = if (result.success) {
                    getString(R.string.transfer_done)
                } else {
                    getString(R.string.transfer_failed) + " — " + result.message
                }
                SendCoordinator.persistResult(
                    scope = lifecycleScope,
                    repository = app.historyRepository,
                    result = result,
                    peerName = peer.host,
                    sampleName = pending.firstOrNull()?.name.orEmpty()
                )
            } else {
                binding.tvHandshake.setText(R.string.transfer_failed)
            }
            com.shadowupload.app.ui.transfer.PendingTransfer.clear()
        }
    }

    private fun isAliveBinding(): Boolean = _binding != null && !isFinishing && !isDestroyed

    private fun stopScanner() {
        scannerActive = false
        runCatching { cameraProvider?.unbindAll() }
        runCatching { _binding?.scanContainer?.removeAllViews() }
    }

    override fun onPause() {
        super.onPause()
        stopScanner()
    }

    override fun onDestroy() {
        // نوقف خادم الاستقبال عند مغادرة الشاشة (الخدمة تُنهي نفسها)
        com.shadowupload.app.service.TransferService.stop(this)
        countdown?.cancel()
        stopScanner()
        analyzerExecutor.shutdownNow()
        _binding = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "qr_mode"
        const val MODE_RECEIVE = "receive"
        const val MODE_SEND = "send"
    }
}
