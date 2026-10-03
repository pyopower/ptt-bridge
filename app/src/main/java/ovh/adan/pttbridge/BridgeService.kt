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
    private lateinit var root: RootInput

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        root = RootInput(prefs)
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
        if (intent?.action == ACTION_LEARN) {
            learn()
            return START_STICKY
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
        root.shutdown()
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

    /* How the current press was sent, so the release goes the same way even if
       the foreground app changed in between. */
    private enum class Via { APP, TOUCH, ROOT, ALL, MANUAL }
    private var via = Via.MANUAL
    private var viaTarget: Target? = null

    private fun press() {
        if (pressed) return
        pressed = true
        tPressed = System.currentTimeMillis()
        val what = if (prefs.universal) pressUniversal() else {
            via = Via.MANUAL
            getString(R.string.log_apps, broadcastChecked(true))
        }
        log(getString(R.string.log_down, what))
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
        when (via) {
            Via.APP -> viaTarget?.let { send(it, false) }
            Via.TOUCH -> PttAccessibilityService.instance?.holdEnd()
            Via.ROOT -> root.press(false)
            Via.ALL -> sendAll(false)
            Via.MANUAL -> broadcastChecked(false)
        }
        val s = (System.currentTimeMillis() - tPressed) / 1000.0
        log(getString(R.string.log_up, why, s))
        notifyChange()
    }

    /* If the release is lost (mic out of range, flat battery...) the radio app
       would keep transmitting: release on our own after the timeout. */
    private val timeout = Runnable {
        release(getString(R.string.why_timeout, prefs.timeoutS))
    }

    /**
     * UNIVERSAL MODE: pick the best method for the radio app in use.
     * The radio app is the one in the foreground if it is one we know or were
     * taught; otherwise (home screen, screen off) the last one used. Then:
     *   1. it listens to a PTT intent  -> send it, addressed to that app
     *      (works in the background and with the screen off);
     *   2. it was taught its PTT button and is in front -> hold a finger on it
     *      through the accessibility service (no root);
     *   3. root mode is set and it is in front -> root key / screen point;
     *   4. nothing known -> every PTT intent we know, to whoever listens.
     */
    private fun pressUniversal(): String {
        // Screen off or locked: nothing is "in front" (the last app seen would
        // still be remembered, and a held finger would land on the lock screen).
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val kg = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val usable = pm.isInteractive && !kg.isKeyguardLocked
        val fg = if (usable) PttAccessibilityService.foreground else null
        val fgIsRadio = fg != null && isRadio(fg)
        if (fgIsRadio) prefs.lastRadio = fg
        val app = if (fgIsRadio) fg else prefs.lastRadio
        val inFront = app != null && app == fg
        val name = app?.let { PttAccessibilityService.appLabel(this, it) }
        val known = TARGETS.firstOrNull { it.pkg != null && it.pkg == app }
        val point = app?.let { prefs.point(it) }
        val acc = PttAccessibilityService.instance
        when {
            known != null -> {
                via = Via.APP; viaTarget = known
                send(known, true)
                return getString(R.string.via_intent, name)
            }
            point != null && inFront && acc != null &&
                acc.holdStart(point.first, point.second) -> {
                via = Via.TOUCH
                return getString(R.string.via_touch, name)
            }
            prefs.rootMode != RootInput.MODE_OFF && inFront -> {
                via = Via.ROOT
                root.press(true)
                return getString(R.string.via_root, name)
            }
            else -> {
                via = Via.ALL
                sendAll(true)
                return getString(R.string.via_all)
            }
        }
    }

    private fun isRadio(pkg: String) =
        TARGETS.any { it.pkg == pkg } || prefs.point(pkg) != null

    private fun intent(action: String, pkg: String?) = Intent(action)
        // FOREGROUND = the fast broadcast queue. Without it DVSwitch got the
        // PTT half a second late.
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        // Since Android 8 an implicit broadcast does not reach receivers
        // declared in a manifest (EchoLink's are): known apps get it
        // addressed to their package, which reaches both kinds.
        .apply { if (pkg != null) setPackage(pkg) }

    private fun send(t: Target, down: Boolean) =
        sendBroadcast(intent(if (down) t.down else t.up, t.pkg))

    /** Every PTT intent we know, implicit, for whatever app listens. */
    private fun sendAll(down: Boolean) {
        for (t in TARGETS) sendBroadcast(intent(if (down) t.down else t.up, null))
        for ((d, u) in EXTRA_INTENTS) sendBroadcast(intent(if (down) d else u, null))
    }

    /** Manual mode: the ticked apps, plus root mode if set. */
    private fun broadcastChecked(down: Boolean): Int {
        var n = 0
        for (t in TARGETS) {
            if (!prefs.enabled(t.key)) continue
            send(t, down)
            n++
        }
        n += root.press(down)
        return n
    }

    /** "Learn" from the app's own screen: time to switch to the radio app,
     *  then the overlay goes over whatever is in front. */
    fun learnLater() {
        log(getString(R.string.log_learn_wait, LEARN_DELAY_S))
        main.postDelayed({ learn() }, LEARN_DELAY_S * 1000L)
    }

    /** "Learn" from the notification: teach the foreground app's PTT button. */
    private fun learn() {
        val acc = PttAccessibilityService.instance
        val fg = PttAccessibilityService.foreground
        when {
            acc == null -> log(getString(R.string.log_need_access))
            fg == null -> log(getString(R.string.log_no_app))
            else -> acc.startLearning(fg)
        }
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

    /** Error of the root shell, if any, for the screen. */
    val rootError: String? get() = root.error

    /** Repaint the notification (mode changed on the screen). */
    fun refresh() = notifyChange()

    /** "Test" button: a 1 s press through the same path as the mic. */
    fun test() {
        if (pressed) return
        press()
        main.postDelayed({ release(getString(R.string.why_test)) }, 1000)
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
        val to = if (prefs.universal) getString(R.string.notif_universal)
            else (TARGETS.filter { prefs.enabled(it.key) }.map { it.name(this) } +
                  listOfNotNull(if (prefs.rootMode != RootInput.MODE_OFF) "root" else null))
            .joinToString(", ")
            .ifEmpty { getString(R.string.notif_none) }
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(if (pressed) R.string.notif_tx else R.string.notif_active))
            .setContentText("PTT → $to")
            .setContentIntent(open)
            .setOngoing(true)
        if (prefs.universal) {
            val learn = PendingIntent.getService(this, 1,
                Intent(this, BridgeService::class.java).setAction(ACTION_LEARN),
                PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(0, getString(R.string.notif_learn), learn).build())
        }
        return b.build()
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
        const val ACTION_LEARN = "ovh.adan.pttbridge.LEARN"
        const val LEARN_DELAY_S = 5

        /* PTT intents seen in other apps' receivers (DVSwitch, EchoLink,
           VoxDMR listen to them too), sent in the "nothing known" case. */
        val EXTRA_INTENTS = listOf(
            "android.intent.action.PTT_DOWN" to "android.intent.action.PTT_UP",
            "com.sonim.intent.action.PTT_KEY_DOWN" to "com.sonim.intent.action.PTT_KEY_UP",
            "com.runbo.poc.key.down" to "com.runbo.poc.key.up",
        )
        const val RECLAIM_MS = 3000L
        const val RECLAIM_TX_MS = 700L

        /** A radio app the PTT can be sent to. `label` is a string resource
         *  for names that get translated, 0 for brand names. */
        class Target(val key: String, private val brand: String, private val label: Int,
                     val pkg: String?, val down: String, val up: String) {
            fun name(c: Context) = if (label != 0) c.getString(label) else brand
        }
        /* Found by decompiling each app (see README). The generic POC one is
           sent to nobody in particular: many POC apps listen to it. */
        val TARGETS = listOf(
            Target("dvswitch", "DVSwitch", 0, "org.dvswitch",
                "org.dvswitch.intent.action.PTT_KEY_DOWN", "org.dvswitch.intent.action.PTT_KEY_UP"),
            Target("echolink", "EchoLink", 0, "org.echolink.android",
                "com.echolink.ptt.down", "com.echolink.ptt.up"),
            Target("voxdmr", "VoxDMR", 0, "com.jcalado.voxdmr",
                "com.voxdmr.ptt.DOWN", "com.voxdmr.ptt.UP"),
            Target("zello", "Zello", 0, "com.loudtalks", "com.zello.ptt.down", "com.zello.ptt.up"),
            Target("poc", "", R.string.target_poc, null,
                "android.intent.action.PTT.down", "android.intent.action.PTT.up"),
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
