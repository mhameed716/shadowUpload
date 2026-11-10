package com.shadowupload.app.ui.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Vibrator
import androidx.lifecycle.lifecycleScope
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.core.net.LanScanner
import com.shadowupload.app.databinding.ActivityShakeConnectBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * تحريك الهاتف للاتصال.
 *
 * نستخدم مستشعر التسارع ونحسب مقدار التسارع الصافي بعد طرح الجاذبية
 * (high-pass filter بسيط). عند تجاوز العتبة نطلق مسحاً للشبكة المحلية
 * ونعرض أقرب جهاز. العتبة قابلة للضبط من الإعدادات.
 */
class ShakeConnectActivity : BaseActivity(), SensorEventListener {

    private var _binding: ActivityShakeConnectBinding? = null
    private val binding get() = _binding!!

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeAt = 0L
    private var scanning = false
    private var lastFound: LanScanner.DiscoveredPeer? = null

    // حالة مرشح الجاذبية
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityShakeConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            binding.tvShakeStatus.setText(R.string.shake_sensor_missing)
            binding.ivShake.alpha = 0.4f
        } else {
            binding.tvShakeStatus.setText(R.string.shake_waiting)
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        runShakeHintAnimation()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        binding.ivShake.animate().cancel()
    }

    /** حركة إيحائية للايقونة */
    private fun runShakeHintAnimation() {
        binding.ivShake.animate()
            .rotationBy(6f)
            .setDuration(180)
            .withEndAction {
                binding.ivShake.animate().rotationBy(-12f).setDuration(180).withEndAction {
                    binding.ivShake.animate().rotationBy(6f).setDuration(180).start()
                }.start()
            }
            .start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val alpha = 0.8f
        gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
        gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
        gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]

        val x = event.values[0] - gravityX
        val y = event.values[1] - gravityY
        val z = event.values[2] - gravityZ
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (magnitude > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeAt > SHAKE_COOLDOWN_MS) {
                lastShakeAt = now
                onShakeDetected(magnitude)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** عند التحريك: اهتزاز قصير + فحص الشبكة */
    private fun onShakeDetected(magnitude: Float) {
        val app = application as ShadowUploadApp
        if (app.preferences.vibrationEnabled) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(45L)
        }
        UiUtils.pulse(binding.ivShake)
        binding.tvShakeStatus.setText(R.string.shake_detecting)
        if (scanning) return
        scanning = true

        lifecycleScope.launch {
            val peer = withContext(Dispatchers.IO) {
                findClosestPeer(app.preferences.receivePort)
            }
            if (_binding == null) return@launch
            scanning = false
            if (peer != null) {
                lastFound = peer
                binding.tvShakeStatus.text = getString(R.string.shake_found, peer.name)
            } else {
                binding.tvShakeStatus.setText(R.string.shake_not_found)
            }
        }
    }

    /** فحص الشبكة والعودة بأول جهاز */
    private fun findClosestPeer(port: Int): LanScanner.DiscoveredPeer? {
        var found: LanScanner.DiscoveredPeer? = null
        val scanner = LanScanner(port)
        val latch = java.util.concurrent.CountDownLatch(1)
        scanner.scanOnce(
            onFound = { peer -> if (found == null) found = peer },
            onFinished = { latch.countDown() }
        )
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return found
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        /** عتبة التحريك (م/ث²) بعد طرح الجاذبية */
        private const val SHAKE_THRESHOLD = 13.0f
        private const val SHAKE_COOLDOWN_MS = 1_200L
    }
}
