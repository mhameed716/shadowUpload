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
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.core.net.HandshakeToken
import com.shadowupload.app.core.net.QrUtils
import com.shadowupload.app.databinding.ActivityQrShareBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.NetworkUtils
import com.shadowupload.app.util.PermissionUtils
import com.shadowupload.app.util.UiUtils
import java.util.concurrent.Executors

/**
 * المشاركة عبر QR: يعرض رمزاً يحتوي على (العنوان + المنفذ + رمز الجلسة)
 * ليتم الاتصال في أقل من 0.2 ثانية بلا أي اكتشاف إضافي.
 *
 * وضعان:
 *  - استقبال: يعرض رمز هذا الجهاز ليمسحه الآخر.
 *  - إرسال: يفتح الكاميرا لمسح رمز الجهاز الآخر.
 */
class QrShareActivity : BaseActivity() {

    private var _binding: ActivityQrShareBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: ShadowUploadApp
    private var token: HandshakeToken.Token? = null
    private var countdown: CountDownTimer? = null
    private var cameraProvider: ProcessCameraProvider? = null
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
        binding.btnScanQr.setOnClickListener {
            // ننتقل لتبويب «الإرسال» ثم نشغّل الكاميرا
            binding.tabsQrMode.getTabAt(1)?.select()
            requestScanner()
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
        val bitmap = QrUtils.generate(uri, 640)
        if (bitmap != null) {
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.pbQrBusy.visibility = View.GONE
        } else {
            binding.pbQrBusy.visibility = View.VISIBLE
        }
        binding.tvQrToken.text = "$uri"
        startCountdown(newToken)
    }

    private fun startCountdown(current: HandshakeToken.Token) {
        countdown?.cancel()
        val total = (current.expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)
        countdown = object : CountDownTimer(total, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000L).toInt()
                binding.tvQrExpiry.text = getString(R.string.qr_expires_in, seconds)
            }

            override fun onFinish() {
                binding.tvQrExpiry.setText(R.string.qr_invalid)
            }
        }.start()
    }

    private fun copyToken() {
        val current = token ?: return
        val uri = HandshakeToken.buildUri(current.host, current.port, current.code)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("shadowUpload", uri))
        UiUtils.snack(binding.root, R.string.qr_link_copied)
    }

    // ================= المسح =================

    private fun requestScanner() {
        if (PermissionUtils.isGranted(this, Manifest.permission.CAMERA)) {
            startScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanner() {
        val container = binding.scanContainer
        container.removeAllViews()
        val previewView = PreviewView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(previewView)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure {
                UiUtils.snack(binding.root, R.string.qr_camera_unavailable)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** تحليل إطار الكاميرا بحثاً عن رمز QR */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val data = imageProxy.planes[0].buffer
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val source = PlanarYUVLuminanceSource(
                bytes,
                imageProxy.width,
                imageProxy.height,
                0,
                0,
                imageProxy.width,
                imageProxy.height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching {
                MultiFormatReader().decodeWithState(bitmap)
            }.getOrNull()
            val text = result?.text
            if (text != null) {
                runOnUiThread { handleScannedCode(text) }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun handleScannedCode(raw: String) {
        val parsed = HandshakeToken.parseUri(raw)
        if (parsed == null) {
            UiUtils.snack(binding.root, R.string.qr_invalid)
            return
        }
        stopScanner()
        binding.tvHandshake.visibility = View.VISIBLE
        binding.tvHandshake.setText(R.string.qr_handshake)

        // قياس زمن المصافحة الفعلي: نقيس من لحظة المسح حتى أول استجابة
        val startedAt = System.nanoTime()
        // نستخدم نطاق دورة حياة الشاشة لتفادي التسريب بعد الإغلاق
        lifecycleScope.launch {
            val reachable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.shadowupload.app.core.net.LanScanner(parsed.port, 300).probeHost(parsed.host)
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (isAliveBinding()) {
                if (reachable != null) {
                    binding.tvHandshake.text = getString(
                        R.string.qr_handshake_done,
                        elapsedMs.toInt()
                    )
                } else {
                    binding.tvHandshake.setText(R.string.join_failed)
                }
            }
        }
    }

    private fun isAliveBinding(): Boolean = _binding != null && !isFinishing && !isDestroyed

    private fun stopScanner() {
        runCatching { cameraProvider?.unbindAll() }
        binding.scanContainer.removeAllViews()
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
