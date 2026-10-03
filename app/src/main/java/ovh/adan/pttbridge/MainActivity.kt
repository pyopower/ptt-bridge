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
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
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
        // ---- root mode, for apps that only read a key or their screen ----
        col.addView(TextView(this).apply {
            setText(R.string.root_title)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })
        col.addView(TextView(this).apply {
            setText(R.string.root_hint)
            textSize = 12f
        })
        val modes = intArrayOf(R.string.root_off, R.string.root_key, R.string.root_touch)
        col.addView(RadioGroup(this).apply {
            for ((i, m) in modes.withIndex())
                addView(RadioButton(this@MainActivity).apply { id = 100 + i; setText(m) })
            check(100 + prefs.rootMode)
            setOnCheckedChangeListener { _, id -> prefs.rootMode = id - 100 }
        })
        fun number(label: Int, get: () -> Int, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { setText(label); minWidth = (110 * dp).toInt() })
            row.addView(EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(get().toString())
                minWidth = (100 * dp).toInt()
                setOnFocusChangeListener { _, has -> if (!has) text.toString().toIntOrNull()?.let(set) }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) {
                        e?.toString()?.toIntOrNull()?.let(set)
                    }
                    override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                    override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                })
            })
            col.addView(row)
        }
        number(R.string.root_keycode, { prefs.keyCode }, { prefs.keyCode = it })
        number(R.string.root_x, { prefs.touchX }, { prefs.touchX = it })
        number(R.string.root_y, { prefs.touchY }, { prefs.touchY = it })
        col.addView(TextView(this).apply {
            setText(R.string.root_keys)
            textSize = 12f
        })
        col.addView(Button(this).apply {
            setText(R.string.test)
            setOnClickListener { BridgeService.instance?.test() }
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
        val err = s?.rootError?.let { "root: $it\n" } ?: ""
        events.text = err + synchronized(BridgeService.events) { BridgeService.events.joinToString("\n") }
    }
}
