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
 *    (one long gesture stroke, released with a tap; API 24+), for apps whose
 *    only PTT is an on-screen button;
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
        fingerDown = false
        stopLearning()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------- held finger ---
    /* The finger is ONE long stroke (as long as Android allows, ~60 s) and the
       release is a short TAP on the same point: dispatching it interrupts the
       long stroke, and its own UP is a real release for the app.
       (Chaining short strokes with willContinue was tried first: the finger
       lifted between links and DroidStar dropped the TX after 0.4 s. A bare
       cancel is no good either: apps built with Qt take a cancelled touch as
       still pressed.) Only one long stroke at a time: a new press while the
       finger is down just keeps it. */
    @Volatile private var fingerDown = false
    private var x = 0f
    private var y = 0f
    private var stroke = 0                  // which long stroke is the current one

    fun canHold() = Build.VERSION.SDK_INT >= 24

    fun holdStart(px: Int, py: Int): Boolean {
        if (!canHold()) return false
        main.post {
            if (fingerDown) return@post
            x = px.toFloat(); y = py.toFloat()
            fingerDown = true
            val n = ++stroke
            val len = (GestureDescription.getMaxGestureDuration() - 500).coerceAtLeast(1000)
            val p = Path().apply { moveTo(x, y) }
            val ok = dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, len)).build(),
                object : GestureResultCallback() {
                    // Ended by itself (held ~60 s) or interrupted by the release
                    // tap: either way the finger is up.
                    override fun onCompleted(g: GestureDescription?) { if (n == stroke) fingerDown = false }
                    override fun onCancelled(g: GestureDescription?) { if (n == stroke) fingerDown = false }
                }, main)
            if (!ok) fingerDown = false
        }
        return true
    }

    fun holdEnd() {
        main.post {
            if (!fingerDown) return@post        // already up (ran out after ~60 s)
            stroke++                             // the long one is no longer current
            fingerDown = false
            tap()
        }
    }

    private fun tap() {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50)).build(), null, main)
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
        @Volatile var instance: PttAccessibilityService? = null; private set
        /** Package in the foreground, ignoring the bridge, the shade and keyboards. */
        @Volatile var foreground: String? = null; private set

        fun appLabel(c: Context, pkg: String): String = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }
}
