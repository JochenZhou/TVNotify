package com.zqc.tvnotify

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 局域网地址探测。
 *
 * 电视上通常只有一个 wlan/eth 接口，但同时存在 p2p0、dummy0 等虚拟网卡，
 * 所以按「优先物理网卡 -> 其次任意非回环 IPv4」的顺序挑一个可直接访问的地址。
 * 没有任何可用地址时返回 0.0.0.0，供 UI 明确提示「未联网」。
 */
object NetUtils {

    private const val FALLBACK_IP = "0.0.0.0"

    fun localIpv4(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return FALLBACK_IP
            var fallback = ""
            for (networkInterface in Collections.list(interfaces)) {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                val candidates = Collections.list(networkInterface.inetAddresses)
                for (address in candidates) {
                    if (address.isLoopbackAddress || address !is Inet4Address) {
                        continue
                    }
                    val ip = address.hostAddress ?: continue
                    // 169.254.x.x 是链路本地地址，外部设备无法访问
                    if (ip.startsWith("169.254.")) {
                        continue
                    }
                    val name = networkInterface.name ?: ""
                    val lowerName = name.toLowerCase()
                    if (lowerName.startsWith("wlan") || lowerName.startsWith("eth") || lowerName.startsWith("ap")) {
                        return ip
                    }
                    if (fallback.isEmpty()) {
                        fallback = ip
                    }
                }
            }
            return if (fallback.isNotEmpty()) fallback else FALLBACK_IP
        } catch (e: Exception) {
            return FALLBACK_IP
        }
    }

    /** IP 是否可用（非 0.0.0.0），供 UI 决定是否显示二维码。 */
    fun hasUsableIp(ip: String): Boolean = ip.isNotEmpty() && ip != FALLBACK_IP
}
