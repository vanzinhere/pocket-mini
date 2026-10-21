package com.pocklet.companion

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetUtil {
    /** IPv4 privat (RFC1918) dari interface aktif, misalnya WiFi. */
    fun lanIp(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            Collections.list(ifaces)
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
