package com.shadowupload.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * أدوات الشبكة المحلية: عنوان IP، حالة WiFi، والتحقق من توفر النقل.
 */
object NetworkUtils {

    /** عنوان IPv4 المحلي للجهاز (يفضّل واجهة wlan) */
    fun localIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = mutableListOf<String>()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in Collections.list(iface.inetAddresses)) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        // تجاهل عناوين النفق الافتراضية
                        if (!ip.startsWith("169.254")) candidates.add(ip)
                    }
                }
            }
            // تفضيل شبكات 192.168 / 10. / 172.16-31
            candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull { it.startsWith("172.") }
                ?: candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** هل الجهاز متصل بأي شبكة؟ */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /** هل نحن على شبكة WiFi؟ (مطلوب للنقل السريع) */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            info.type == ConnectivityManager.TYPE_WIFI && info.isConnected
        }
    }

    /** اسم شبكة WiFi الحالية إن توفر */
    @Suppress("DEPRECATION")
    fun currentWifiName(context: Context): String? {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = wm.connectionInfo ?: return null
            val name = info.ssid?.replace("\"", "")
            if (name.isNullOrBlank() || name == "<unknown ssid>") null else name
        } catch (_: SecurityException) {
            null
        }
    }

    /** عنوان MAC مبهم كمعرف جهاز بصمة (لا يكشف الجهاز الحقيقي) */
    fun pseudoDeviceId(context: Context): String {
        val seed = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}"
        return "SU-" + seed.hashCode().toUInt().toString(16).uppercase().take(6)
    }

    /**
     * إيجاد بادئة الشبكة الفرعية من عنوان IP وقناع (تقديري /24).
     * تُستخدم لاكتشاف الأجهزة القريبة بمسح محدود.
     */
    fun subnetPrefix(ip: String?): String? {
        val address = ip ?: return null
        val parts = address.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    /** قائمة عناوين محتملة للفحص السريع في الشبكة المحلية */
    fun candidateHosts(limit: Int = 254): List<String> {
        val prefix = subnetPrefix(localIpAddress()) ?: return emptyList()
        return (1..limit).map { "$prefix.$it" }
    }
}
