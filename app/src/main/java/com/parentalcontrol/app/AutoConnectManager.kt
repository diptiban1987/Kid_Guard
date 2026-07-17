package com.parentalcontrol.app

import android.content.Context
import android.util.Log
import com.parentalcontrol.app.api.CloudConfig
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

object AutoConnectManager {
    private const val TAG = "AutoConnect"
    private const val SERVER_PORT = 5000
    private const val TIMEOUT_MS = 1500
    private const val PRODUCTION_DOMAIN = ""

    private val COMMON_SUFFIXES = listOf(
        ".100", ".101", ".102", ".103", ".104", ".105",
        ".1", ".2", ".10", ".50", ".200", ".150",
        ".254", ".250", ".251", ".252"
    )

    private var isDiscovering = false

    fun discoverServer(context: Context): String? {
        if (isDiscovering) return CloudConfig.serverUrl
        isDiscovering = true

        try {
            // Priority 1: Already saved (non-default) URL
            val saved = CloudConfig.serverUrl
            if (saved != CloudConfig.DEFAULT_SERVER && pingServer(saved)) {
                Log.d(TAG, "Using saved URL: $saved")
                return saved
            }

            // Priority 2: Cloud server
            if (pingServer(CloudConfig.CLOUD_SERVER)) {
                CloudConfig.serverUrl = CloudConfig.CLOUD_SERVER
                Log.d(TAG, "Found cloud server: ${CloudConfig.CLOUD_SERVER}")
                return CloudConfig.CLOUD_SERVER
            }

            // Priority 3: Production domain (if set)
            if (PRODUCTION_DOMAIN.isNotEmpty()) {
                val prodUrl = "https://$PRODUCTION_DOMAIN"
                if (pingServer(prodUrl)) {
                    CloudConfig.serverUrl = prodUrl
                    return prodUrl
                }
            }

            // Priority 4: BuildConfig default (local server)
            val buildUrl = BuildConfig.SERVER_URL
            if (pingServer(buildUrl)) {
                CloudConfig.serverUrl = buildUrl
                Log.d(TAG, "Found server at build URL: $buildUrl")
                return buildUrl
            }

            // Priority 5: Scan local network
            val subnet = getDeviceSubnet() ?: run {
                Log.d(TAG, "No WiFi subnet found")
                return@run null
            }
            Log.d(TAG, "Scanning subnet: $subnet")

            val candidates = mutableListOf<String>()
            for (suffix in COMMON_SUFFIXES) {
                val url = "http://$subnet$suffix:$SERVER_PORT"
                candidates.add(url)
            }

            // Also scan gateway + nearby IPs
            val gateway = getGatewayIp()
            if (gateway != null) {
                val gwParts = gateway.split(".")
                if (gwParts.size == 4) {
                    val gwSubnet = "${gwParts[0]}.${gwParts[1]}.${gwParts[2]}"
                    for (i in 1..20) {
                        val url = "http://$gwSubnet.$i:$SERVER_PORT"
                        if (!candidates.contains(url)) candidates.add(url)
                    }
                }
            }

            val result = scanParallel(candidates)
            if (result != null) {
                CloudConfig.serverUrl = result
                Log.d(TAG, "Auto-discovered server: $result")
                return result
            }

            Log.d(TAG, "No server found on local network")
            return null
        } finally {
            isDiscovering = false
        }
    }

    private fun scanParallel(urls: List<String>): String? {
        val results = ConcurrentLinkedQueue<String>()
        val threads = urls.map { url ->
            Thread {
                if (pingServer(url)) {
                    results.add(url)
                }
            }
        }
        threads.forEach { it.start() }
        for (t in threads) {
            try { t.join(5000) } catch (_: InterruptedException) {}
            if (results.isNotEmpty()) break
        }
        return results.firstOrNull()
    }

    private fun pingServer(url: String): Boolean {
        return try {
            val conn = URL("$url/api/auth/me").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            // Any HTTP response (including 401 = needs auth) means server is alive
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    private fun getDeviceSubnet(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addr = ni.interfaceAddresses.firstOrNull { a ->
                    val ip = a.address?.hostAddress ?: ""
                    ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
                } ?: continue
                val ip = addr.address.hostAddress ?: continue
                val prefix = addr.networkPrefixLength
                val ipParts = ip.split(".")
                if (ipParts.size == 4) {
                    return "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceSubnet error", e)
        }
        return null
    }

    private fun getGatewayIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.interfaceAddresses
                for (addr in addrs) {
                    val broadcast = addr.broadcast ?: continue
                    val ip = broadcast.hostAddress ?: continue
                    val parts = ip.split(".")
                    if (parts.size == 4) {
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGatewayIp error", e)
        }
        return null
    }

    fun tryAutoConnect(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val url = discoverServer(context)
            callback(url != null)
        }.start()
    }
}
