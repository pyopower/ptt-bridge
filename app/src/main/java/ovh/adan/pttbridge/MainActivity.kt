package ovh.adan.pttbridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Minimal screen: which apps get the PTT, on/off, and a status light with the
 *  latest events to check that the mic gets through. */
class MainActivity : Activity(), BridgeService.Listener {

    private lateinit var prefs: Prefs
    private lateinit var light: TextView
    private lateinit var events: TextView
    private lateinit var button: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        light = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
        }
        col.addView(light)
        col.addView(TextView(this).apply {
            setText(R.string.send_to)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        for (t in BridgeService.TARGETS) {
            col.addView(CheckBox(this).apply {
                text = t.name(this@MainActivity)
                isChecked = prefs.enabled(t.key)
                setOnCheckedChangeListener { _, on -> prefs.set(t.key, on) }
            })
        }
        col.addView(CheckBox(this).apply {
            setText(R.string.reclaim)
            isChecked = prefs.reclaim
            setOnCheckedChangeListener { _, on -> prefs.reclaim = on }
        })
        button = Button(this).apply {
            setOnClickListener {
                prefs.on = BridgeService.instance == null
                if (prefs.on) BridgeService.start(this@MainActivity)
                else BridgeService.stop(this@MainActivity)
                postDelayed({ paint() }, 300)
            }
        }
        col.addView(button)
        col.addView(TextView(this).apply {
            setText(R.string.hint)
            textSize = 12f
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        })
        events = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        col.addView(events)
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (prefs.on) BridgeService.start(this)
    }

    override fun onResume() {
        super.onResume()
        BridgeService.listener = this
        BridgeService.instance?.reclaimButtons()
        paint()
    }

    override fun onPause() {
        if (BridgeService.listener === this) BridgeService.listener = null
        super.onPause()
    }

    override fun onChange() { runOnUiThread { paint() } }

    private fun paint() {
        val s = BridgeService.instance
        when {
            s == null -> { light.setText(R.string.state_off); light.setBackgroundColor(Color.DKGRAY) }
            s.pressed -> { light.setText(R.string.state_tx); light.setBackgroundColor(Color.rgb(190, 20, 20)) }
            else -> { light.setText(R.string.state_idle); light.setBackgroundColor(Color.rgb(20, 110, 40)) }
        }
        light.setTextColor(Color.WHITE)
        button.setText(if (s == null) R.string.turn_on else R.string.turn_off)
        events.text = synchronized(BridgeService.events) { BridgeService.events.joinToString("\n") }
    }
}
