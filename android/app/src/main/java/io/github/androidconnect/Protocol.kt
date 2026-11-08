package io.github.androidconnect

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Wire format (matches linux/androidconnect/protocol.py):
 *   u8 type | u32 length | payload
 *   type 1: UTF-8 JSON object with a "t" field
 *   type 2: u8 flags (bit0 keyframe) | u64 capture time (Unix epoch, us) | H.264 Annex-B access unit
 */
object Protocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_androidconnect._tcp"
    const val DEFAULT_PORT = 47100
    private const val MSG_JSON = 1
    private const val MSG_VIDEO = 2

    sealed interface Message
    class Json(val obj: JSONObject) : Message {
        val type: String get() = obj.optString("t")
    }
    class Video(val keyframe: Boolean, val ptsUs: Long, val data: ByteArray) : Message

    fun encode(obj: JSONObject): ByteArray {
        val payload = obj.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + payload.size).put(MSG_JSON.toByte()).putInt(payload.size).put(payload).array()
    }

    fun json(type: String, vararg pairs: Pair<String, Any?>): ByteArray {
        val o = JSONObject().put("t", type)
        for ((k, v) in pairs) o.put(k, v)
        return encode(o)
    }

    class Reader(input: InputStream) {
        private val din = DataInputStream(BufferedInputStream(input, 256 * 1024))

        fun read(): Message {
            val type = din.readUnsignedByte()
            val length = din.readInt()
            if (length < 0 || length > 32 * 1024 * 1024) throw IOException("bad frame length $length")
            return when (type) {
                MSG_JSON -> {
                    val bytes = ByteArray(length)
                    din.readFully(bytes)
                    Json(JSONObject(String(bytes, Charsets.UTF_8)))
                }
                MSG_VIDEO -> {
                    val flags = din.readUnsignedByte()
                    val pts = din.readLong()
                    val data = ByteArray(length - 9)
                    din.readFully(data)
                    Video(flags and 1 != 0, pts, data)
                }
                else -> {
                    din.readFully(ByteArray(length))  // unknown message type: skip it
                    read()
                }
            }
        }
    }
}

object Crypto {
    private val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun nonce() = ByteArray(32).also { random.nextBytes(it) }

    /** Same derivation as security.pairing_code() on the laptop. */
    fun pairingCode(serverFp: ByteArray, nonceC: ByteArray, nonceS: ByteArray): String {
        val digest = sha256("AC-SAS".toByteArray(), serverFp, nonceC, nonceS)
        val value = ByteBuffer.wrap(digest, 0, 8).long
        return "%06d".format(java.lang.Long.remainderUnsigned(value, 1_000_000L))
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/**
 * Accepts the laptop's self-signed certificate only if it matches the pinned
 * fingerprint. With no pin (first pairing) it records what it saw; the
 * pairing code then proves both sides saw the same certificate.
 */
class PinningTrustManager(private val expectedFp: String?) : X509TrustManager {
    @Volatile var seenFp: ByteArray? = null
        private set
    @Volatile var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("no certificate")
        val fp = Crypto.sha256(chain[0].encoded)
        seenFp = fp
        if (expectedFp != null && fp.hex() != expectedFp) {
            mismatch = true
            throw CertificateException("certificate does not match the paired laptop")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
