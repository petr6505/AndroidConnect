package io.github.androidconnect

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Fullscreen view of the laptop's virtual monitor. Forwards touch, pen and mouse input. */
class DisplayActivity : Activity(), SurfaceHolder.Callback {
    private val link get() = App.instance.link
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: LinearLayout
    private lateinit var overlayText: TextView
    private lateinit var statsText: TextView
    private val settings get() = App.instance.store.settings.value
    private var videoW = 0
    private var videoH = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, e -> onInput(e); true }
        surfaceView.setOnGenericMotionListener { _, e -> onGenericInput(e) }
        root.addView(surfaceView, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))

        overlayText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xB0000000.toInt())
            addView(ProgressBar(this@DisplayActivity))
            addView(overlayText)
            visibility = View.GONE
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        statsText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0x99000000.toInt())
            setPadding(12, 6, 12, 6)
            visibility = View.GONE
        }
        root.addView(statsText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                confirmDisconnect()
            }
        }

        scope.launch { showStats() }
        scope.launch {
            link.state.collect { s ->
                when (s) {
                    is LinkState.Streaming -> {
                        overlay.visibility = View.GONE
                        if (s.width != videoW || s.height != videoH) {
                            videoW = s.width
                            videoH = s.height
                            layoutVideo()
                        }
                    }
                    is LinkState.Reconnecting -> showOverlay("Reconnecting to ${s.name}…")
                    is LinkState.Connecting -> showOverlay("Connecting to ${s.name}…")
                    is LinkState.Pairing, is LinkState.Idle, is LinkState.Failed -> finish()
                }
            }
        }
    }

    /** Updates the performance overlay once a second while it is enabled. */
    private suspend fun showStats() {
        val decoder = link.decoder
        var frames = decoder.framesRendered
        var bytes = decoder.bytesReceived
        var at = System.nanoTime()
        while (true) {
            delay(1000)
            if (!settings.showStats) {
                statsText.visibility = View.GONE
                continue
            }
            val now = System.nanoTime()
            val secs = (now - at) / 1e9
            val fps = (decoder.framesRendered - frames) / secs
            val mbps = (decoder.bytesReceived - bytes) * 8 / secs / 1e6
            frames = decoder.framesRendered
            bytes = decoder.bytesReceived
            at = now
            statsText.text = "%dx%d  %.0f fps  %.1f Mbit/s\nlatency %.0f ms  decode %.0f ms  ping %d ms".format(
                videoW, videoH, fps, mbps, decoder.latencyMs, decoder.decodeMs, link.rttMs)
            statsText.visibility = View.VISIBLE
        }
    }

    @Deprecated("Used below Android 13 only")
    override fun onBackPressed() = confirmDisconnect()

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showOverlay(text: String) {
        overlayText.text = text
        overlay.visibility = View.VISIBLE
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect?")
            .setMessage("The extra screen will be removed from your laptop.")
            .setPositiveButton("Disconnect") { _, _ ->
                link.disconnect()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fit the video into the screen, keeping its aspect ratio. */
    private fun layoutVideo() {
        if (videoW == 0 || root.width == 0) return
        val scale = minOf(root.width.toFloat() / videoW, root.height.toFloat() / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            surfaceView.layoutParams = lp
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        link.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        link.attachSurface(null)
    }

    private fun nx(x: Float) = (x / surfaceView.width).coerceIn(0f, 1f).toDouble()
    private fun ny(y: Float) = (y / surfaceView.height).coerceIn(0f, 1f).toDouble()

    private fun onInput(e: MotionEvent) {
        val tool = e.getToolType(e.actionIndex)
        if (tool == MotionEvent.TOOL_TYPE_MOUSE || tool == MotionEvent.TOOL_TYPE_STYLUS ||
            tool == MotionEvent.TOOL_TYPE_ERASER) {
            onPointer(e)
            return
        }
        if (swallowGesture(e)) return
        if (settings.touchMode == "mouse") {
            touchAsMouse(e)
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> touch("down", e, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touch("up", e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> touch("move", e, null)
            MotionEvent.ACTION_CANCEL -> touch("up", e, null)
        }
    }

    private var swallowing = false

    /**
     * A four-finger tap toggles the performance overlay. The gesture is kept
     * from the laptop: fingers already forwarded are released, and the rest
     * of the gesture is ignored until all fingers are lifted.
     */
    private fun swallowGesture(e: MotionEvent): Boolean {
        if (swallowing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_POINTER_DOWN || e.pointerCount != 4) return false
        swallowing = true
        if (settings.touchMode == "mouse") {
            link.send(Protocol.json("ptr", "btn" to "left", "down" to false))
        } else {
            val points = JSONArray()
            for (i in 0 until e.pointerCount) {
                if (i != e.actionIndex) points.put(JSONArray().put(e.getPointerId(i)).put(nx(e.getX(i))).put(ny(e.getY(i))))
            }
            link.send(Protocol.encode(JSONObject().put("t", "touch").put("a", "up").put("p", points)))
        }
        val store = App.instance.store
        store.updateSettings(settings.copy(showStats = !settings.showStats))
        if (settings.showStats) {
            statsText.text = "measuring…"
            statsText.visibility = View.VISIBLE
        } else {
            statsText.visibility = View.GONE
        }
        return true
    }

    private fun touch(action: String, e: MotionEvent, index: Int?) {
        val points = JSONArray()
        val indices = if (index != null) listOf(index) else (0 until e.pointerCount).toList()
        for (i in indices) {
            points.put(JSONArray().put(e.getPointerId(i)).put(nx(e.getX(i))).put(ny(e.getY(i))))
        }
        link.send(Protocol.encode(JSONObject().put("t", "touch").put("a", action).put("p", points)))
    }

    /** Mouse mode: the first finger moves the pointer and holds the left button; other fingers are ignored. */
    private fun touchAsMouse(e: MotionEvent) {
        val msg = JSONObject().put("t", "ptr").put("x", nx(e.getX(0))).put("y", ny(e.getY(0)))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> msg.put("btn", "left").put("down", true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> msg.put("btn", "left").put("down", false)
            MotionEvent.ACTION_MOVE -> {}
            else -> return
        }
        link.send(Protocol.encode(msg))
    }

    private var mouseButtons = 0

    private fun onPointer(e: MotionEvent) {
        val msg = JSONObject().put("t", "ptr").put("x", nx(e.x)).put("y", ny(e.y))
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            val changed = e.buttonState xor mouseButtons
            for ((mask, name) in listOf(MotionEvent.BUTTON_PRIMARY to "left",
                                        MotionEvent.BUTTON_SECONDARY to "right",
                                        MotionEvent.BUTTON_TERTIARY to "middle")) {
                if (changed and mask != 0) {
                    link.send(Protocol.encode(JSONObject(msg.toString()).put("btn", name)
                        .put("down", e.buttonState and mask != 0)))
                }
            }
            mouseButtons = e.buttonState
            if (changed == 0) link.send(Protocol.encode(msg))
            return
        }
        // Stylus: tip acts as left button, barrel button as right button.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val btn = if (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) "right" else "left"
                msg.put("btn", btn).put("down", true)
                stylusBtn = btn
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                msg.put("btn", stylusBtn).put("down", false)
            }
        }
        link.send(Protocol.encode(msg))
    }

    private var stylusBtn = "left"

    private fun onGenericInput(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                link.send(Protocol.json("ptr", "x" to nx(e.x), "y" to ny(e.y)))
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                link.send(Protocol.json("ptr", "sx" to (h * 10).toDouble(), "sy" to (-v * 10).toDouble()))
            }
            else -> return false
        }
        return true
    }
}
