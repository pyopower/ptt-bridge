package ovh.adan.pttbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** One screen: status light, mode, accessibility service, taught buttons,
 *  manual/root options and the latest events. */
class MainActivity : Activity(), BridgeService.Listener {

    private lateinit var prefs: Prefs
    private lateinit var light: TextView
    private lateinit var events: TextView
    private lateinit var button: Button
    private lateinit var access: TextView
    private lateinit var taught: LinearLayout
    private var dp = 1f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        dp = resources.displayMetrics.density
        val pad = px(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        light = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(24), 0, px(24))
        }
        col.addView(light)

        // ---- mode ----
        col.addView(title(R.string.mode_title))
        col.addView(RadioGroup(this).apply {
            addView(RadioButton(this@MainActivity).apply { id = 1; setText(R.string.mode_universal) })
            addView(RadioButton(this@MainActivity).apply { id = 2; setText(R.string.mode_manual) })
            check(if (prefs.universal) 1 else 2)
            setOnCheckedChangeListener { _, id ->
                prefs.universal = id == 1
                BridgeService.instance?.refresh()
            }
        })
        col.addView(small(R.string.universal_hint))

        // ---- accessibility service ----
        access = TextView(this).apply { setPadding(0, px(12), 0, 0); typeface = Typeface.DEFAULT_BOLD }
        col.addView(access)
        col.addView(Button(this).apply {
            setText(R.string.access_open)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // ---- taught screen buttons ----
        col.addView(title(R.string.taught_title))
        col.addView(Button(this).apply {
            setText(R.string.learn_button)
            setOnClickListener {
                prefs.universal = true
                BridgeService.instance?.learnLater()
                moveTaskToBack(true)              // out of the way: open the radio app
            }
        })
        col.addView(small(R.string.taught_hint))
        taught = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(taught)
        col.addView(Button(this).apply {
            setText(R.string.add_manual)
            setOnClickListener { pickApp() }
        })

        // ---- manual mode ----
        col.addView(title(R.string.manual_title))
        col.addView(TextView(this).apply { setText(R.string.send_to) })
        for (t in BridgeService.TARGETS) {
            col.addView(CheckBox(this).apply {
                text = t.name(this@MainActivity)
                isChecked = prefs.enabled(t.key)
                setOnCheckedChangeListener { _, on -> prefs.set(t.key, on) }
            })
        }

        // ---- root mode (manual, and last resort of the universal one) ----
        col.addView(title(R.string.root_title))
        col.addView(small(R.string.root_hint))
        val modes = intArrayOf(R.string.root_off, R.string.root_key, R.string.root_touch)
        col.addView(RadioGroup(this).apply {
            for ((i, m) in modes.withIndex())
                addView(RadioButton(this@MainActivity).apply { id = 100 + i; setText(m) })
            check(100 + prefs.rootMode)
            setOnCheckedChangeListener { _, id -> prefs.rootMode = id - 100 }
        })
        number(col, R.string.root_keycode, { prefs.keyCode }, { prefs.keyCode = it })
        number(col, R.string.root_x, { prefs.touchX }, { prefs.touchX = it })
        number(col, R.string.root_y, { prefs.touchY }, { prefs.touchY = it })
        col.addView(small(R.string.root_keys))

        // ---- common ----
        col.addView(CheckBox(this).apply {
            setText(R.string.reclaim)
            isChecked = prefs.reclaim
            setOnCheckedChangeListener { _, on -> prefs.reclaim = on }
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
        col.addView(small(R.string.hint))
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
        paintTaught()
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
        val on = PttAccessibilityService.instance != null
        access.setText(if (on) R.string.access_on else R.string.access_off)
        access.setTextColor(if (on) Color.rgb(60, 170, 80) else Color.rgb(220, 120, 40))
        button.setText(if (s == null) R.string.turn_on else R.string.turn_off)
        val err = s?.rootError?.let { "root: $it\n" } ?: ""
        events.text = err + synchronized(BridgeService.events) { BridgeService.events.joinToString("\n") }
        if (taught.childCount != prefs.taughtApps().size.coerceAtLeast(1)) paintTaught()
    }

    private fun paintTaught() {
        taught.removeAllViews()
        val apps = prefs.taughtApps()
        if (apps.isEmpty()) { taught.addView(small(R.string.taught_none)); return }
        for (pkg in apps) {
            val p = prefs.point(pkg) ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = PttAccessibilityService.appLabel(this@MainActivity, pkg)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // Editable X / Y: saved as you type.
            var x = p.first; var y = p.second
            row.addView(coord(x) { x = it; prefs.setPoint(pkg, x, y) })
            row.addView(coord(y) { y = it; prefs.setPoint(pkg, x, y) })
            row.addView(Button(this).apply {
                setText(R.string.forget)
                setOnClickListener { prefs.forgetPoint(pkg); paintTaught() }
            })
            taught.addView(row)
        }
    }

    private fun coord(v: Int, set: (Int) -> Unit) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(v.toString())
        minWidth = px(64)
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { e?.toString()?.toIntOrNull()?.let(set) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
    }

    /** "Add by hand": pick an installed app, then type its PTT button's X/Y. */
    private fun pickApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.add_manual)
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> askPoint(apps[i].first, apps[i].second) }
            .show()
    }

    private fun askPoint(pkg: String, label: String) {
        val old = prefs.point(pkg)
        val m = resources.displayMetrics
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(16), px(8), px(16), 0)
        }
        val ex = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "X"
            setText((old?.first ?: m.widthPixels / 2).toString())
        }
        val ey = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "Y"
            setText((old?.second ?: m.heightPixels * 9 / 10).toString())
        }
        box.addView(ex, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(ey, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        android.app.AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(R.string.add_manual_hint)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val x = ex.text.toString().toIntOrNull()
                val y = ey.text.toString().toIntOrNull()
                if (x != null && y != null) { prefs.setPoint(pkg, x, y); paintTaught() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun title(res: Int) = TextView(this).apply {
        setText(res)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, px(16), 0, 0)
    }

    private fun small(res: Int) = TextView(this).apply {
        setText(res)
        textSize = 12f
        setPadding(0, px(4), 0, px(4))
    }

    private fun number(col: LinearLayout, label: Int, get: () -> Int, set: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { setText(label); minWidth = px(110) })
        row.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(get().toString())
            minWidth = px(100)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { e?.toString()?.toIntOrNull()?.let(set) }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        })
        col.addView(row)
    }
}
