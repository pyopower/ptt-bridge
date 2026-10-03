package ovh.adan.pttbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The universal mode's eyes and finger, without root:
 *  - knows which app is in the foreground (window state changes), so the
 *    bridge can pick the right method per app without any setting;
 *  - holds a finger on a screen point for as long as the PTT is pressed
 *    (chained gesture strokes, API 26+), for apps whose only PTT is an
 *    on-screen button;
 *  - "learn" overlay: the user taps the radio app's PTT button once and the
 *    point is remembered for that app.
 * It reads no window content: only the package name of the foreground app.
 */
class PttAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var ignored = setOf<String>()

    override fun onServiceConnected() {
        instance = this
        ignored = buildSet {
            add(packageName)
            add("com.android.systemui")
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.enabledInputMethodList.forEach { add(it.packageName) }
            } catch (_: Exception) {}
        }
        BridgeService.listener?.onChange()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored || learning != null) return
        if (pkg != foreground) {
            foreground = pkg
            BridgeService.listener?.onChange()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        holding = false
        stopLearning()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------- held finger ---
    /* A gesture has a maximum length, so an open-ended hold is a chain of short
       strokes that continue each other (willContinue = true): the finger never
       lifts between them. On release the next link is the last one, and that
       lifts it. Release latency is at most one link. */
    @Volatile private var holding = false
    private var x = 0f
    private var y = 0f

    fun canHold() = Build.VERSION.SDK_INT >= 26

    fun holdStart(px: Int, py: Int): Boolean {
        if (!canHold()) return false
        x = px.toFloat(); y = py.toFloat()
        holding = true
        main.post { link(null) }
        return true
    }

    fun holdEnd() { holding = false }

    @SuppressLint("NewApi")
    private fun link(prev: GestureDescription.StrokeDescription?) {
        val p = Path().apply { moveTo(x, y) }
        val last = !holding
        val s = if (prev == null) GestureDescription.StrokeDescription(p, 0, LINK_MS, !last)
                else prev.continueStroke(p, 0, if (last) 40 else LINK_MS, !last)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (!last) link(s)
                }
                override fun onCancelled(g: GestureDescription?) {
                    // Something else touched the screen: the finger is gone.
                    holding = false
                }
            }, main)
        if (!ok) holding = false
    }

    // ------------------------------------------------------------- learn ---
    private var learning: FrameLayout? = null

    /** Shows a see-through layer over [pkg]; the next tap is its PTT button. */
    @SuppressLint("ClickableViewAccessibility")
    fun startLearning(pkg: String) {
        stopLearning()
        if (Build.VERSION.SDK_INT >= 31)
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val label = appLabel(this, pkg)
        val v = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 120, 255))
            addView(TextView(this@PttAccessibilityService).apply {
                text = getString(R.string.learn_overlay, label)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(200, 0, 0, 0))
                textSize = 18f
                setPadding(40, 30, 40, 30)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    Prefs(this@PttAccessibilityService)
                        .setPoint(pkg, ev.rawX.toInt(), ev.rawY.toInt())
                    BridgeService.log(getString(R.string.log_learned, label,
                        ev.rawX.toInt(), ev.rawY.toInt()))
                    stopLearning()
                }
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        // Wait for the notification shade to go away before covering the app.
        main.postDelayed({
            try { wm.addView(v, lp); learning = v } catch (_: Exception) {}
        }, 600)
        main.postDelayed({ stopLearning() }, 20_000)
    }

    private fun stopLearning() {
        val v = learning ?: return
        learning = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {}
    }

    companion object {
        const val LINK_MS = 200L

        @Volatile var instance: PttAccessibilityService? = null; private set
        /** Package in the foreground, ignoring the bridge, the shade and keyboards. */
        @Volatile var foreground: String? = null; private set

        fun appLabel(c: Context, pkg: String): String = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }
}
