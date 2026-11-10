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
            // sendTo دالة suspend تنفّذ النقل على خيط الإدخال/الإخراج
            val result = com.shadowupload.app.ui.transfer.SendCoordinator.sendTo(
                context = this@QrShareActivity,
                host = peer.ip,
                port = peer.port,
                items = pending,
                deviceId = app.preferences.deviceId,
                deviceName = app.preferences.deviceName,
                listener = object : com.shadowupload.app.core.transfer.TransferListener {
                    override fun onProgress(progress: com.shadowupload.app.data.model.TransferProgress) {
                        val text = com.shadowupload.app.util.FormatUtils.formatSpeed(progress.bytesPerSecond)
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
                com.shadowupload.app.ui.transfer.SendCoordinator.persistResult(
                    scope = lifecycleScope,
                    repository = app.historyRepository,
                    result = result,
                    peerName = peer.name,
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
