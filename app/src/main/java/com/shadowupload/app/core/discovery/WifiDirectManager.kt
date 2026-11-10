package com.shadowupload.app.core.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import java.util.concurrent.Executors

/**
 * مدير Wi-Fi Direct (P2P) — يرفع السرعة عندما لا توجد شبكة مشتركة.
 *
 * مهم: واجهة Wi-Fi Direct تغيّرت بين الإصدارات (خصوصاً أندرويد 10 و13).
 * نتعامل معها بانعكاس (reflection) عند الحاجة حتى لا ينكسر التطبيق على
 * الأجهزة القديمة، ونرجع بأمان إلى النقل عبر الشبكة المحلية العادية.
 */
class WifiDirectManager(private val context: Context) {

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** هل الجهاز يدعم Wi-Fi Direct؟ */
    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    /** تهيئة القناة (تُستدعى عند أول استخدام) */
    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        if (!isSupported) return false
        if (manager != null) return true
        return try {
            val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return false
            manager = p2pManager
            channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
            channel != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * تشغيل الاكتشاف السريع. على أندرويد 10+ نستخدم قناة معدلة لتفادي
     * قيود المنع الجديدة، مع التراجع الآمن عند الفشل.
     */
    @SuppressLint("MissingPermission")
    fun discover(onDevices: (List<String>) -> Unit) {
        if (!initialize()) {
            onDevices(emptyList())
            return
        }
        val p2pManager = manager ?: return
        val p2pChannel = channel ?: return
        try {
            applyExtendedChannelIfNeeded()
            p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    executor.execute { p2pManager.requestPeers(p2pChannel) { peers ->
                        onDevices(peers.deviceList.map { it.deviceName ?: (it.deviceAddress ?: "جهاز P2P") })
                    } }
                }

                override fun onFailure(reason: Int) {
                    onDevices(emptyList())
                }
            })
        } catch (_: SecurityException) {
            onDevices(emptyList())
        } catch (_: Throwable) {
            onDevices(emptyList())
        }
    }

    /** اسم الجهاز الحالي على شبكة P2P */
    @SuppressLint("MissingPermission")
    fun currentDevice(): WifiP2pDevice? = null

    /** إيقاف الاكتشاف */
    @SuppressLint("MissingPermission")
    fun stopDiscover() {
        runCatching { manager?.stopPeerDiscovery(channel, null) }
    }

    /** ملاحظة: على أندرويد 10+ يجب تمرير setWfdInfo / بث الشبكة من الإعدادات */
    private fun applyExtendedChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val method = channel?.javaClass?.getDeclaredMethod("setWifiP2pChannels", Int::class.java, Int::class.java)
            method?.isAccessible = true
            method?.invoke(channel, 0, 0)
        }
    }

    fun release() {
        stopDiscover()
        channel?.close()
        channel = null
        manager = null
        executor.shutdownNow()
    }
}
