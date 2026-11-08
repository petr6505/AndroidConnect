package io.github.androidconnect

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.264 decoder rendering straight to a Surface. All codec work
 * happens on one handler thread, so no locking is needed.
 */
class VideoDecoder(private val requestKeyframe: () -> Unit) {
    private val thread = HandlerThread("decoder", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Protocol.Video>()
    private var needKeyframe = true
    private var maxInputSize = 0  // 0 = codec default; grown if a frame doesn't fit
    private var candidates = emptyList<String>()  // hardware decoders first
    private var candidate = 0
    private var failuresWithoutOutput = 0

    @Volatile var framesRendered = 0L
        private set
    @Volatile var framesReceived = 0L
        private set
    @Volatile var bytesReceived = 0L
        private set
    /** Smoothed time from handing a frame to the codec until it is rendered. */
    @Volatile var decodeMs = 0.0
        private set
    /** Laptop clock minus this device's clock, from the ping exchange; null until known. */
    @Volatile var clockOffsetUs: Long? = null
    /** Smoothed time from capture on the laptop until the frame is handed to the display. */
    @Volatile var latencyMs = 0.0
        private set
    private val queuedAt = HashMap<Long, Long>()

    fun setSurface(s: Surface?) = handler.post {
        if (s === surface) return@post
        releaseCodec()
        surface = s
        needKeyframe = true
        if (s != null) requestKeyframe()
    }

    /** Called when a new stream starts. */
    fun configure(w: Int, h: Int) = handler.post {
        width = w
        height = h
        releaseCodec()
        needKeyframe = true
        if (w > 0) {
            candidates = decodersFor(w, h)
            candidate = 0
            failuresWithoutOutput = 0
        }
    }

    fun submit(input: Protocol.Video) = handler.post {
        framesReceived++
        bytesReceived += input.data.size
        if (surface == null || width == 0) return@post
        val frame = if (input.keyframe) Protocol.Video(true, input.ptsUs, patchSps(input.data)) else input
        if (needKeyframe) {
            if (!frame.keyframe) return@post
            if (codec == null && !startCodec(frame.data)) {
                requestKeyframe()
                return@post
            }
            needKeyframe = false
        }
        pending.addLast(frame)
        if (pending.size > 6) {
            // Decoder can't keep up: skip ahead to the next keyframe.
            pending.clear()
            needKeyframe = true
            requestKeyframe()
            return@post
        }
        feed()
    }

    private var spsIn: ByteArray? = null
    private var spsOut: ByteArray? = null

    /**
     * Replaces the SPS in a keyframe with one that declares no frame
     * reordering (see [SpsRewriter]). The result is cached because the
     * encoder repeats the same SPS with every keyframe.
     */
    private fun patchSps(au: ByteArray): ByteArray {
        val nals = splitNals(au)
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 } ?: return au
        if (!sps.contentEquals(spsIn)) {
            spsIn = sps
            spsOut = SpsRewriter.rewrite(sps)
            if (spsOut == null) Log.w(TAG, "could not parse SPS; decoder may add latency")
        }
        val patched = spsOut ?: return au
        val out = java.io.ByteArrayOutputStream(au.size + 16)
        for (nal in nals) {
            out.write(START_CODE)
            out.write(if (nal === sps) patched else nal)
        }
        return out.toByteArray()
    }

    fun release() = handler.post {
        releaseCodec()
        surface = null
    }

    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val frame = pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buf = c.getInputBuffer(index) ?: continue
                if (frame.data.size > buf.capacity()) {
                    Log.w(TAG, "frame larger than input buffer, restarting decoder")
                    maxInputSize = frame.data.size * 2
                    restart()
                    return
                }
                buf.clear()
                buf.put(frame.data)
                c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
                if (queuedAt.size > 64) queuedAt.clear()
                queuedAt[frame.ptsUs] = System.nanoTime()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "queueInputBuffer failed", e)
                restart()
                return
            }
        }
    }

    private fun startCodec(keyframe: ByteArray): Boolean {
        val nals = splitNals(keyframe)
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 }
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
        if (sps == null || pps == null) {
            Log.w(TAG, "keyframe without SPS/PPS")
            return false
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
            if (maxInputSize > 0) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            setInteger(MediaFormat.KEY_PRIORITY, 0)  // realtime
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Vendor low-latency switches; unknown keys are ignored.
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.low-latency.enable", 1)
            setInteger("vdec-lowlatency", 1)
        }
        return try {
            val name = candidates.getOrNull(candidate)
            val c = if (name != null) MediaCodec.createByCodecName(name)
                    else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(Callback(c), handler)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "decoder ${c.name} started at ${width}x$height")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not start decoder", e)
            releaseCodec()
            nextCandidate()
            false
        }
    }

    private fun restart() {
        if (++failuresWithoutOutput >= 2) nextCandidate()
        releaseCodec()
        needKeyframe = true
        requestKeyframe()
    }

    /** A decoder that keeps failing is skipped in favour of the next one on the device. */
    private fun nextCandidate() {
        if (candidates.size > 1) {
            candidate = (candidate + 1) % candidates.size
            Log.w(TAG, "switching to decoder ${candidates[candidate]}")
        }
        failuresWithoutOutput = 0
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        freeInputs.clear()
        pending.clear()
        queuedAt.clear()
    }

    private inner class Callback(private val owner: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (codec !== owner) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== owner) return
            try {
                c.releaseOutputBuffer(index, info.size > 0)  // render immediately
                framesRendered++
                queuedAt.remove(info.presentationTimeUs)?.let {
                    val ms = (System.nanoTime() - it) / 1e6
                    decodeMs = if (decodeMs == 0.0) ms else decodeMs * 0.9 + ms * 0.1
                }
                clockOffsetUs?.let { offset ->
                    // presentationTimeUs carries the laptop's capture time (Unix epoch).
                    val ms = (System.currentTimeMillis() * 1000 + offset - info.presentationTimeUs) / 1000.0
                    if (ms in 0.0..10_000.0) latencyMs = if (latencyMs == 0.0) ms else latencyMs * 0.9 + ms * 0.1
                }
                failuresWithoutOutput = 0
            } catch (e: IllegalStateException) {
                restart()
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== owner) return
            Log.w(TAG, "decoder error", e)
            restart()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Splits an Annex-B access unit into NAL units (without start codes). */
        fun splitNals(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Pair<Int, Int>>()  // (start code position, payload position)
            var i = 0
            while (i + 3 <= data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) {
                        starts.add(i to i + 3); i += 3; continue
                    }
                    if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                        starts.add(i to i + 4); i += 4; continue
                    }
                }
                i++
            }
            return starts.mapIndexed { n, (_, payload) ->
                val end = if (n + 1 < starts.size) starts[n + 1].first else data.size
                data.copyOfRange(payload, end)
            }
        }

        fun decodersFor(w: Int, h: Int): List<String> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        ?.isSizeSupported(w, h) == true
            }.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }.map { it.name }

        /** Largest size <= (w, h) with the same aspect ratio that a hardware decoder can handle. */
        fun supportedSize(w: Int, h: Int, fps: Int): Pair<Int, Int> {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
            var scale = 1.0
            while (scale > 0.3) {
                val sw = (w * scale).toInt() and -2
                val sh = (h * scale).toInt() and -2
                val ok = decoders.any { info ->
                    val caps: MediaCodecInfo.VideoCapabilities? =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    caps != null && caps.isSizeSupported(sw, sh) &&
                        runCatching { caps.areSizeAndRateSupported(sw, sh, fps.toDouble()) }.getOrDefault(true)
                }
                if (ok) return sw to sh
                scale -= 0.05
            }
            return 1920 to (1920.0 * h / w).toInt().and(-2)
        }
    }
}
