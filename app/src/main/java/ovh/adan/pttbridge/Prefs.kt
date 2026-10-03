package ovh.adan.pttbridge

import android.content.Context

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    /** DVSwitch comes enabled: it is what the bridge was born for. */
    fun enabled(key: String) = sp.getBoolean("t_$key", key == "dvswitch")
    fun set(key: String, on: Boolean) = sp.edit().putBoolean("t_$key", on).apply()

    /** Reclaim the media buttons every few seconds (see BridgeService.reclaimer). */
    var reclaim: Boolean
        get() = sp.getBoolean("reclaim", true)
        set(v) = sp.edit().putBoolean("reclaim", v).apply()

    /** If the release is lost, release on our own after this. */
    var timeoutS: Int
        get() = sp.getInt("timeout", 180)
        set(v) = sp.edit().putInt("timeout", v.coerceIn(10, 600)).apply()

    /** Root mode: RootInput.MODE_OFF / MODE_KEY / MODE_TOUCH. */
    var rootMode: Int
        get() = sp.getInt("root_mode", RootInput.MODE_OFF)
        set(v) = sp.edit().putInt("root_mode", v).apply()

    /** Key held in KEY mode. 27 = CAMERA, one of those BlueDV takes as PTT. */
    var keyCode: Int
        get() = sp.getInt("key_code", 27)
        set(v) = sp.edit().putInt("key_code", v).apply()

    /** Screen point held in TOUCH mode (the radio app's PTT button). */
    var touchX: Int
        get() = sp.getInt("touch_x", 540)
        set(v) = sp.edit().putInt("touch_x", v).apply()
    var touchY: Int
        get() = sp.getInt("touch_y", 1800)
        set(v) = sp.edit().putInt("touch_y", v).apply()

    var on: Boolean
        get() = sp.getBoolean("on", true)
        set(v) = sp.edit().putBoolean("on", v).apply()
}
