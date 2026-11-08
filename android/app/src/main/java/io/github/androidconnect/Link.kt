package io.github.androidconnect

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

data class Target(val host: String, val port: Int, val name: String, val serverId: String?)

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val name: String) : LinkState
    data class Pairing(val name: String, val code: String) : LinkState
    data class Streaming(val name: String, val width: Int, val height: Int) : LinkState
    data class Reconnecting(val name: String, val reason: String) : LinkState
    data class Failed(val name: String, val message: String, val serverId: String? = null) : LinkState
}

/** Ends the connect loop without retrying. */
private class Fatal(message: String, val serverId: String? = null) : Exception(message)

/** The laptop asked us to stop (user pressed Disconnect there). */
private class Dismissed : Exception()

/**
 * Owns the connection to one laptop. Reconnects automatically (with backoff)
 * until the user disconnects; the laptop keeps the virtual monitor alive for
 * a while, so short Wi-Fi hiccups don't move any windows.
 */
class Link(private val context: Context, private val store: Store, private val discovery: Discovery) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state

    val decoder = VideoDecoder { send(Protocol.json("idr")) }

    @Volatile private var generation = 0
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var outbox: LinkedBlockingQueue<ByteArray>? = null
    @Volatile private var surfaceAttached = false
    @Volatile var target: Target? = null
        private set

    @Volatile var rttMs = 0L
        private set

    fun connect(t: Target) {
        val gen = synchronized(this) {
            closeSocket()
            target = t
            ++generation
        }
        _state.value = LinkState.Connecting(t.name)
        discovery.acquire()
        DisplayService.start(context)
        thread(name = "link-$gen", isDaemon = true) { runLoop(gen, t) }
    }

    /** User-initiated disconnect: tells the laptop to remove the monitor now. */
    fun disconnect() {
        if (_state.value is LinkState.Idle || _state.value is LinkState.Failed) return
        // The screen may be turning off: keep the CPU awake long enough to say goodbye.
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidconnect:disconnect").acquire(2000)
        val box = outbox
        synchronized(this) { generation++ }
        if (box != null) {
            box.offer(Protocol.json("stop"))
            thread(isDaemon = true) {
                Thread.sleep(300)
                closeSocket()
            }
        } else {
            closeSocket()
        }
        decoder.configure(0, 0)
        _state.value = LinkState.Idle
    }

    fun dismissFailure() {
        if (_state.value is LinkState.Failed) _state.value = LinkState.Idle
    }

    fun attachSurface(surface: Surface?) {
        surfaceAttached = surface != null
        decoder.setSurface(surface)
        send(Protocol.json(if (surface != null) "resume" else "pause"))
    }

    fun send(bytes: ByteArray) {
        outbox?.offer(bytes)
    }

    private fun isCurrent(gen: Int) = generation == gen

    /** Closing a TLS socket writes a close alert, so never do it on the UI thread. */
    private fun closeSocket() {
        val s = socket ?: return
        socket = null
        thread(isDaemon = true) { runCatching { s.close() } }
    }

    private fun runLoop(gen: Int, initial: Target) {
        var t = initial
        var failures = 0
        var resume = false              // a monitor exists on the laptop; only reattach to it
        var lostSince = 0L
        try {
            while (isCurrent(gen)) {
                try {
                    session(gen, t, resume) { failures = 0; resume = true; lostSince = 0L }
                    if (!isCurrent(gen)) break
                    failures++
                    publish(gen, LinkState.Reconnecting(t.name, "Connection closed"))
                } catch (e: Fatal) {
                    publish(gen, LinkState.Failed(t.name, e.message ?: "Failed", e.serverId))
                    break
                } catch (e: Dismissed) {
                    publish(gen, LinkState.Failed(t.name, "${t.name} disconnected this screen."))
                    break
                } catch (e: Exception) {
                    if (!isCurrent(gen)) break
                    failures++
                    Log.i(TAG, "connection attempt failed: $e")
                    publish(gen, LinkState.Reconnecting(t.name, describe(e)))
                }
                // Don't keep the radio busy forever: the laptop drops the monitor
                // soon after the tablet goes away, so there is nothing left to resume.
                if (lostSince == 0L) lostSince = System.nanoTime()
                if (System.nanoTime() - lostSince > GIVE_UP_NANOS) {
                    publish(gen, LinkState.Failed(t.name, "Lost the connection to ${t.name}."))
                    break
                }
                // Backoff: 0.5 s, 1 s, 2 s, then every 3 s.
                val delay = when (failures) { 0, 1 -> 500L; 2 -> 1000L; 3 -> 2000L; else -> 3000L }
                Thread.sleep(delay)
                // The laptop may have a new IP address after a Wi-Fi reconnect.
                discovery.find(t.serverId)?.let { t = t.copy(host = it.host, port = it.port) }
            }
        } catch (_: InterruptedException) {
        } finally {
            discovery.release()
        }
    }

    private fun publish(gen: Int, s: LinkState) {
        if (isCurrent(gen)) _state.value = s
    }

    private fun describe(e: Exception) = when (e) {
        is SocketTimeoutException -> "The laptop is not responding"
        is java.net.ConnectException -> "Can't reach the laptop"
        is java.net.NoRouteToHostException -> "No network route to the laptop"
        is IOException -> "Connection lost"
        else -> e.message ?: "Connection lost"
    }

    private fun session(gen: Int, t: Target, resume: Boolean, onStreaming: () -> Unit) {
        val pinned = store.laptop(t.serverId)
        val trust = PinningTrustManager(pinned?.fingerprint)
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.receiveBufferSize = 2 * 1024 * 1024
        raw.connect(InetSocketAddress(t.host, t.port), 4000)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            .socketFactory.createSocket(raw, t.host, t.port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.soTimeout = 10_000
        synchronized(this) {
            if (!isCurrent(gen)) { ssl.close(); return }
            socket = ssl
        }
        val box = LinkedBlockingQueue<ByteArray>()
        var writer: Thread? = null
        var pinger: Thread? = null
        try {
            try {
                ssl.startHandshake()
            } catch (e: SSLHandshakeException) {
                if (trust.mismatch) {
                    throw Fatal("This laptop's identity has changed (was it reinstalled?). Forget it and pair again.",
                        t.serverId)
                }
                throw e
            }
            val serverFp = trust.seenFp ?: throw IOException("no certificate")
            // A paired laptop reached by IP address: recognise it by its certificate.
            val known = pinned ?: store.laptopByFingerprint(serverFp.hex())

            outbox = box
            val out = ssl.outputStream
            writer = thread(name = "link-writer", isDaemon = true) {
                try {
                    while (true) {
                        out.write(box.take())
                        if (box.isEmpty()) out.flush()
                    }
                } catch (_: Exception) {
                    runCatching { ssl.close() }
                }
            }
            val reader = Protocol.Reader(ssl.inputStream)

            box.offer(Protocol.encode(JSONObject()
                .put("t", "hello").put("v", Protocol.VERSION)
                .put("device_id", store.deviceId).put("name", deviceName())
                .put("token", known?.token ?: JSONObject.NULL)))

            var msg = readJson(reader)
            var token = known?.token
            if (msg.type == "pair_required") {
                token = pair(gen, t, reader, box, ssl, serverFp)
                msg = readJson(reader)
            }
            if (msg.type == "bye") throw Fatal(byeMessage(msg.obj.optString("reason")))
            if (msg.type != "welcome") throw IOException("unexpected ${msg.type}")
            val serverId = msg.obj.getString("server_id")
            val serverName = msg.obj.optString("server_name", t.name)
            store.save(Laptop(serverId, serverName, serverFp.hex(), token!!, t.host, t.port))
            store.lastServerId = serverId
            target = t.copy(serverId = serverId, name = serverName)

            val (w, h, scale) = streamGeometry()
            val settings = store.settings.value
            decoder.configure(w, h)
            box.offer(Protocol.encode(JSONObject().put("t", "start").put("width", w).put("height", h)
                .put("fps", settings.fps).put("quality", settings.quality).put("scale", scale)
                .put("resume", resume)))
            while (true) {
                msg = readJson(reader)
                if (msg.type == "started") break
                if (msg.type == "bye") handleBye(msg, serverId)
            }
            if (!surfaceAttached) box.offer(Protocol.json("pause"))
            publish(gen, LinkState.Streaming(serverName, w, h))
            onStreaming()
            Log.i(TAG, "streaming ${w}x$h from $serverName")

            pinger = thread(name = "link-ping", isDaemon = true) {
                try {
                    while (true) {
                        box.offer(Protocol.json("ping", "ts" to System.nanoTime() / 1_000_000))
                        Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) {
                }
            }

            var statsAt = System.nanoTime()
            var lastRendered = decoder.framesRendered
            var lastReceived = decoder.framesReceived
            while (isCurrent(gen)) {
                val now = System.nanoTime()
                if (now - statsAt > 10_000_000_000L) {
                    val secs = (now - statsAt) / 1e9
                    Log.i(TAG, "rx %.1f fps, shown %.1f fps, decode %.1f ms, latency %.1f ms, rtt %d ms".format(
                        (decoder.framesReceived - lastReceived) / secs,
                        (decoder.framesRendered - lastRendered) / secs, decoder.decodeMs, decoder.latencyMs, rttMs))
                    statsAt = now
                    lastRendered = decoder.framesRendered
                    lastReceived = decoder.framesReceived
                }
                when (val m = reader.read()) {
                    is Protocol.Video -> decoder.submit(m)
                    is Protocol.Json -> when (m.type) {
                        "pong" -> {
                            rttMs = System.nanoTime() / 1_000_000 - m.obj.optLong("ts")
                            // Laptop clock minus ours, assuming the reply took half the round trip.
                            val laptopUs = m.obj.optLong("now_us")
                            if (laptopUs > 0) {
                                val offset = laptopUs - (System.currentTimeMillis() - rttMs / 2) * 1000
                                decoder.clockOffsetUs = if (decoder.clockOffsetUs == null) offset
                                    else (decoder.clockOffsetUs!! * 7 + offset) / 8
                            }
                        }
                        "bye" -> handleBye(m, serverId)
                    }
                }
            }
        } finally {
            pinger?.interrupt()
            writer?.interrupt()
            if (outbox === box) outbox = null
            runCatching { ssl.close() }
            synchronized(this) { if (socket === ssl) socket = null }
        }
    }

    private fun pair(gen: Int, t: Target, reader: Protocol.Reader, box: LinkedBlockingQueue<ByteArray>,
                     ssl: SSLSocket, serverFp: ByteArray): String {
        val nonceC = Crypto.nonce()
        box.offer(Protocol.json("pair_commit", "commit" to Crypto.sha256(nonceC).hex()))
        var msg = readJson(reader)
        if (msg.type != "pair_nonce") throw IOException("unexpected ${msg.type}")
        val nonceS = msg.obj.getString("nonce").unhex()
        box.offer(Protocol.json("pair_reveal", "nonce" to nonceC.hex()))
        publish(gen, LinkState.Pairing(t.name, Crypto.pairingCode(serverFp, nonceC, nonceS)))

        ssl.soTimeout = 150_000  // the user is deciding on the laptop
        msg = readJson(reader)
        ssl.soTimeout = 10_000
        return when (msg.type) {
            "pair_ok" -> msg.obj.getString("token")
            "pair_denied" -> throw Fatal(when (msg.obj.optString("reason")) {
                "timeout" -> "The laptop didn't respond to the pairing request in time."
                "busy" -> "The laptop is busy pairing with another device. Try again in a moment."
                else -> "Pairing was rejected on the laptop."
            })
            else -> throw IOException("unexpected ${msg.type}")
        }
    }

    private fun readJson(reader: Protocol.Reader): Protocol.Json {
        while (true) {
            val m = reader.read()
            if (m is Protocol.Json) return m
        }
    }

    private fun handleBye(msg: Protocol.Json, serverId: String): Nothing {
        when (msg.obj.optString("reason")) {
            "disconnected" -> throw Dismissed()
            "forgotten" -> {
                store.remove(serverId)
                throw Fatal("This laptop removed the pairing. Connect again to pair.")
            }
            "version" -> throw Fatal(byeMessage("version"))
            else -> throw IOException("Laptop closed the connection")
        }
    }

    private fun byeMessage(reason: String) = when (reason) {
        "version" -> "The laptop app and the tablet app versions don't match. Update both."
        else -> "The laptop closed the connection."
    }

    private fun deviceName(): String =
        AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Stream size (landscape), plus a suggested desktop scale factor for the laptop. */
    private fun streamGeometry(): Triple<Int, Int, Double> {
        val (nw, nh) = nativeLandscapeSize()
        val settings = store.settings.value
        var w = nw
        var h = nh
        if (settings.resolution == "fhd" && w > 1920) {
            h = (h * 1920.0 / w).toInt()
            w = 1920
        }
        w = minOf(w, 4096) and -2
        h = minOf(h, 4096) and -2
        val (sw, sh) = VideoDecoder.supportedSize(w, h, settings.fps)
        val density = context.resources.displayMetrics.density.toDouble()
        return Triple(sw, sh, density * sw / nw)
    }

    @Suppress("DEPRECATION")
    private fun nativeLandscapeSize(): Pair<Int, Int> {
        val (a, b) = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val p = Point()
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
            p.x to p.y
        }
        return maxOf(a, b) to minOf(a, b)
    }

    companion object {
        private const val TAG = "Link"
        private const val GIVE_UP_NANOS = 60_000_000_000L
    }
}
