package ovh.adan.pttbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent

/**
 * PTT BRIDGE: turns the PTT button of a Bluetooth speaker-mic into what radio
 * apps understand.
 *
 * The Abbree speaker-mic (shows up as KST_vHMIC010) does not send a "PTT".
 * Connected to a phone, its button arrives over AVRCP as FAST FORWARD on press
 * (always the same burst: a tap, then about half a second held) and REWIND on
 * release. Almost no radio app understands that. This service catches those
 * keys with a MediaSession and re-sends them as the PTT broadcasts those apps
 * listen to, with separate press and release:
 *   - DVSwitch Mobile: org.dvswitch.intent.action.PTT_KEY_DOWN / _UP
 *   - Generic POC:     android.intent.action.PTT.down / .up
 *   - Zello:           com.zello.ptt.down / .up
 * The Abbree's P1 button sends AT+BLDN, which Android itself handles as
 * "redial the last number": it never reaches any app.
 */
class BridgeService : Service() {

    interface Listener { fun onChange() }

    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        createChannel()
        startInForeground()
        openSession()
        log(getString(R.string.log_started))
        main.postDelayed(reclaimer, RECLAIM_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        reclaimButtons()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (pressed) release(getString(R.string.why_stop))
        try { session?.release() } catch (_: Exception) {}
        session = null
        stopSilence()
        instance = null
        log(getString(R.string.log_stopped))
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    // ------------------------------------------------------- mic buttons ---
    private fun openSession() {
        val s = MediaSession(this, "ptt-bridge")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(i: Intent): Boolean {
                @Suppress("DEPRECATION")
                val e = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return super.onMediaButtonEvent(i)
                return onKey(e) || super.onMediaButtonEvent(i)
            }
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)
            .setState(PlaybackState.STATE_PLAYING, 0, 0f)
            .build())
        s.isActive = true
        session = s
    }

    private fun onKey(e: KeyEvent): Boolean {
        val down = when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            KeyEvent.KEYCODE_MEDIA_REWIND -> false
            else -> return false
        }
        // The FF burst brings two DOWNs plus auto-repeat: only the first DOWN
        // of each counts, and the UPs mean nothing.
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            main.post { if (down) press() else release(getString(R.string.why_release)) }
        return true
    }

    // ---------------------------------------------------------------- PTT ---
    var pressed = false; private set
    private var tPressed = 0L

    private fun press() {
        if (pressed) return
        pressed = true
        tPressed = System.currentTimeMillis()
        val n = broadcast(true)
        log(getString(R.string.log_down, n))
        vibrate(30)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, prefs.timeoutS * 1000L)
        reclaimAfterPress()
        notifyChange()
    }

    private fun release(why: String) {
        if (!pressed) return
        pressed = false
        main.removeCallbacks(timeout)
        broadcast(false)
        val s = (System.currentTimeMillis() - tPressed) / 1000.0
        log(getString(R.string.log_up, why, s))
        notifyChange()
    }

    /* If the release is lost (mic out of range, flat battery...) the radio app
       would keep transmitting: release on our own after the timeout. */
    private val timeout = Runnable {
        release(getString(R.string.why_timeout, prefs.timeoutS))
    }

    private fun broadcast(down: Boolean): Int {
        var n = 0
        for (t in TARGETS) {
            if (!prefs.enabled(t.key)) continue
            // FOREGROUND = the fast broadcast queue. Without it DVSwitch got
            // the PTT half a second late.
            sendBroadcast(Intent(if (down) t.down else t.up)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND))
            n++
        }
        return n
    }

    // --------------------------------------------------- keeping the keys ---
    /* Android delivers media keys to the app that most recently STARTED
       playing among those currently playing. DVSwitch starts playing as soon as
       it transmits (measured: it took the keys 60 ms after PTT_KEY_DOWN), so
       the mic's REWIND went to DVSwitch and the PTT never released.
       A short burst of silence is not enough: when it ends, DVSwitch (still
       playing) wins again. So we keep a LOOPING silence playing all the time
       and "restart" it (pause + play) every few seconds: each play() counts as
       starting to play. More often while transmitting, which is exactly when
       the REWIND must not be lost. No audio focus is requested, so nobody is
       interrupted. */
    private var silence: AudioTrack? = null

    private val reclaimer = object : Runnable {
        override fun run() {
            if (prefs.reclaim) reclaimButtons()
            main.postDelayed(this, if (pressed) RECLAIM_TX_MS else RECLAIM_MS)
        }
    }

    /* Right after PTT_KEY_DOWN the radio app starts its audio: reassert soon. */
    private fun reclaimAfterPress() {
        for (t in longArrayOf(150, 400))
            main.postDelayed({ if (pressed && prefs.reclaim) reclaimButtons() }, t)
        main.removeCallbacks(reclaimer)              // then at the TX pace
        main.postDelayed(reclaimer, RECLAIM_TX_MS)
    }

    fun reclaimButtons() {
        if (Build.VERSION.SDK_INT < 23) return       // AudioTrack.Builder
        try {
            val at = silence ?: run {
                val sr = 8000
                val n = sr / 5                       // 200 ms, looped
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build().also {
                        it.write(ShortArray(n), 0, n)
                        it.setLoopPoints(0, n, -1)
                        silence = it
                    }
            }
            if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.pause()
            at.play()
        } catch (e: Exception) {
            Log.w(TAG, "could not reclaim the media buttons", e)
            stopSilence()
        }
    }

    private fun stopSilence() {
        try { silence?.stop(); silence?.release() } catch (_: Exception) {}
        silence = null
    }

    // ------------------------------------------------------- notification ---
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW))
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        val to = TARGETS.filter { prefs.enabled(it.key) }
            .joinToString(", ") { it.name(this) }
            .ifEmpty { getString(R.string.notif_none) }
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(if (pressed) R.string.notif_tx else R.string.notif_active))
            .setContentText("PTT → $to")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, build())
    }

    private fun notifyChange() {
        try {
            getSystemService(NotificationManager::class.java).notify(1, build())
        } catch (_: Exception) {}
        listener?.onChange()
    }

    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, 80))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {}
    }

    companion object {
        const val TAG = "pttbridge"
        const val CHANNEL = "bridge"
        const val ACTION_STOP = "ovh.adan.pttbridge.STOP"
        const val RECLAIM_MS = 3000L
        const val RECLAIM_TX_MS = 700L

        /** A radio app the PTT can be sent to. `label` is a string resource
         *  for names that get translated, 0 for brand names. */
        class Target(val key: String, private val brand: String, private val label: Int,
                     val down: String, val up: String) {
            fun name(c: Context) = if (label != 0) c.getString(label) else brand
        }
        val TARGETS = listOf(
            Target("dvswitch", "DVSwitch", 0,
                "org.dvswitch.intent.action.PTT_KEY_DOWN", "org.dvswitch.intent.action.PTT_KEY_UP"),
            Target("poc", "", R.string.target_poc,
                "android.intent.action.PTT.down", "android.intent.action.PTT.up"),
            Target("zello", "Zello", 0, "com.zello.ptt.down", "com.zello.ptt.up"),
        )

        @Volatile var instance: BridgeService? = null; private set
        @Volatile var listener: Listener? = null

        /** Latest events, for the screen. */
        val events = ArrayDeque<String>()

        fun log(s: String) {
            Log.i(TAG, s)
            val h = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date())
            synchronized(events) {
                events.addFirst("$h  $s")
                while (events.size > 12) events.removeLast()
            }
            listener?.onChange()
        }

        fun start(c: Context) {
            val i = Intent(c, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
