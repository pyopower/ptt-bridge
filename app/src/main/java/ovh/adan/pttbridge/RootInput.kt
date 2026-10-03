package ovh.adan.pttbridge

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * ROOT MODE, for radio apps that only read a physical key or their on-screen
 * button (found by decompiling them: BlueDV, Peanut, Mumla, DroidStar). It
 * needs root and the radio app in the FOREGROUND with the screen on: Android
 * delivers keys and touches only to the focused window.
 *
 *  - KEY: holds a key code down while the PTT is pressed. `input keyevent`
 *    cannot send a lone DOWN, so on press it is started with a long
 *    `--duration` in the background and on release it is killed and a full
 *    tap of the same key follows. The app then sees DOWN ... DOWN UP: the
 *    second DOWN is ignored by apps already transmitting (all those checked
 *    guard against it) and the UP releases.
 *  - TOUCH: `input motionevent DOWN x y` on press and `UP x y` on release:
 *    a finger held on the app's PTT button.
 *
 * One `su` shell is kept open for the whole session, so each press does not
 * pay for starting `su` (and Magisk asks only once).
 */
class RootInput(private val prefs: Prefs) {

    private val worker = Executors.newSingleThreadExecutor()
    private var shell: Process? = null
    private var out: OutputStreamWriter? = null
    @Volatile private var keyPid = ""
    @Volatile var error: String? = null; private set

    /** Returns 1 if something was sent (for the event log), 0 otherwise. */
    fun press(down: Boolean): Int {
        val mode = prefs.rootMode
        if (mode == MODE_OFF) return 0
        val cmd = when (mode) {
            MODE_KEY -> {
                val k = prefs.keyCode
                if (down) "input keyevent --duration ${prefs.timeoutS * 1000L} $k & echo PID:$!"
                else "kill $keyPid 2>/dev/null; input keyevent $k"
            }
            else -> {
                val x = prefs.touchX; val y = prefs.touchY
                "input motionevent ${if (down) "DOWN" else "UP"} $x $y"
            }
        }
        worker.execute { run(cmd) }
        return 1
    }

    private fun run(cmd: String) {
        try {
            if (shell == null) open()
            out!!.write(cmd + "\n")
            out!!.flush()
        } catch (e: Exception) {
            Log.w(BridgeService.TAG, "root shell failed", e)
            error = e.message ?: e.toString()
            close()
        }
    }

    private fun open() {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        shell = p
        out = OutputStreamWriter(p.outputStream)
        Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { l ->
                    if (l.startsWith("PID:")) keyPid = l.substring(4).trim()
                    else if (l.isNotBlank()) Log.i(BridgeService.TAG, "su: $l")
                }
            } catch (_: Exception) {}
            if (shell === p) { error = "su exited"; shell = null; out = null }
        }.start()
        error = null
    }

    fun close() {
        try { out?.write("exit\n"); out?.flush() } catch (_: Exception) {}
        try { shell?.destroy() } catch (_: Exception) {}
        shell = null
        out = null
    }

    fun shutdown() {
        worker.execute { close() }
        worker.shutdown()
    }

    companion object {
        const val MODE_OFF = 0
        const val MODE_KEY = 1
        const val MODE_TOUCH = 2
    }
}
