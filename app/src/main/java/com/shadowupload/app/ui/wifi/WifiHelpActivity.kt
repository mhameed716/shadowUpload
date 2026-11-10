package com.shadowupload.app.ui.wifi

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.databinding.ActivityWifiHelpBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.NetworkUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * إعدادات الـ WiFi والمساعدة: حالة الشبكة، فتح إعدادات النظام،
 * نقطة الاتصال، والفحص الذاتي للمنفذ والشبكة، ثم الأسئلة الشائعة.
 */
class WifiHelpActivity : BaseActivity() {

    private var _binding: ActivityWifiHelpBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityWifiHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnWifiSettings.setOnClickListener { openWifiSettings() }
        binding.btnHotspot.setOnClickListener { openHotspotSettings() }
        binding.btnDiagnose.setOnClickListener { runDiagnostics() }

        refreshNetworkState()
    }

    private fun refreshNetworkState() {
        val app = application as ShadowUploadApp
        val onWifi = NetworkUtils.isOnWifi(this)
        val ssid = NetworkUtils.currentWifiName(this)
        val ip = NetworkUtils.localIpAddress()

        binding.tvWifiState.text = when {
            onWifi && ssid != null -> getString(R.string.wifi_connected_to, ssid)
            onWifi -> getString(R.string.wifi_state, getString(R.string.me_permission_granted))
            else -> getString(R.string.wifi_not_connected)
        }
        binding.tvPortInfo.text = getString(
            R.string.wifi_port_info,
            app.preferences.receivePort
        ) + " • " + getString(
            R.string.net_local_ip
        ) + ": " + (ip ?: getString(R.string.wifi_unknown))
    }

    private fun openWifiSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }.onFailure { UiUtils.snack(binding.root, R.string.error_generic) }
    }

    private fun openHotspotSettings() {
        val intents = listOf(
            Intent().setAction("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                runCatching { startActivity(intent) }
                return
            }
            if (intent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(intent) }
                return
            }
        }
        UiUtils.snack(binding.root, R.string.error_generic)
    }

    /** فحص ذاتي: هل المنفذ متاح؟ هل الشبكة تعمل؟ */
    private fun runDiagnostics() {
        binding.tvDiagnoseResult.visibility = View.VISIBLE
        binding.tvDiagnoseResult.setText(R.string.diagnose_running)
        val app = application as ShadowUploadApp

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val engine = com.shadowupload.app.core.transfer.TransferEngine(
                    this@WifiHelpActivity,
                    app.preferences.snapshot()
                )
                val portFree = engine.probePort(app.preferences.receivePort)
                val hasIp = NetworkUtils.localIpAddress() != null
                Triple(portFree, hasIp, NetworkUtils.isOnWifi(this@WifiHelpActivity))
            }
            if (_binding == null) return@launch
            val (portFree, hasIp, onWifi) = result
            binding.tvDiagnoseResult.text = when {
                !hasIp -> getString(R.string.wifi_diagnose_failed, getString(R.string.wifi_not_connected))
                !portFree -> getString(R.string.wifi_diagnose_failed, getString(R.string.error_port_busy))
                !onWifi -> getString(R.string.wifi_diagnose_failed, getString(R.string.net_wifi_required))
                else -> getString(R.string.wifi_diagnose_ok)
            }
            binding.tvDiagnoseResult.setTextColor(
                getColorCompat(if (hasIp && portFree && onWifi) R.color.neon_lime else R.color.neon_amber)
            )
            refreshNetworkState()
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
