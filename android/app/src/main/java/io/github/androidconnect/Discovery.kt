package io.github.androidconnect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress

data class FoundLaptop(val serviceName: String, val host: String, val port: Int, val serverId: String?)

/** Finds laptops on the local network via mDNS. Reference-counted so the
 *  UI and the reconnect loop can both keep it running. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _found = MutableStateFlow<Map<String, FoundLaptop>>(emptyMap())
    val found: StateFlow<Map<String, FoundLaptop>> = _found

    private var users = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun acquire() = main.post {
        if (users++ == 0) start()
    }

    fun release() = main.post {
        if (--users == 0) stop()
    }

    fun find(serverId: String?) = _found.value.values.firstOrNull { serverId != null && it.serverId == serverId }

    private fun start() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                main.post { if (listener === this) listener = null }
                // Retry later; e.g. Wi-Fi was off or the permission was just granted.
                main.postDelayed({ if (users > 0 && listener == null) start() }, 5000)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { enqueueResolve(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                _found.value = _found.value - info.serviceName
            }.let {}
        }
        listener = l
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discovery error", e)
            listener = null
        }
    }

    private fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        resolveQueue.clear()
    }

    // Older Android versions only allow one resolve at a time.
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || resolveQueue.isEmpty()) return
        resolving = true
        val info = resolveQueue.removeFirst()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                resolveNext()
            }.let {}

            override fun onServiceResolved(info: NsdServiceInfo) = main.post {
                resolving = false
                val host = pickAddress(info)
                if (host != null && listener != null) {
                    val id = info.attributes["id"]?.let { String(it) }
                    _found.value = _found.value + (info.serviceName to FoundLaptop(info.serviceName, host, info.port, id))
                }
                resolveNext()
            }.let {}
        })
    }

    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): String? {
        val addrs: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // Prefer IPv4: link-local IPv6 addresses need a scope id that NSD may drop.
        return (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())?.hostAddress
    }

    companion object {
        private const val TAG = "Discovery"
    }
}
