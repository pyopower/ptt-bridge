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

    var on: Boolean
        get() = sp.getBoolean("on", true)
        set(v) = sp.edit().putBoolean("on", v).apply()
}
