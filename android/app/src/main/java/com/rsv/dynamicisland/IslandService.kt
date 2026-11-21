package com.rsv.dynamicisland

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The brain. Feeds battery / screen / timer / water / media / calls / live activities /
 * privacy / volume / ringer / Bluetooth into the island and owns the overlay window.
 * Uses the accessibility overlay when that service is on (sits above the status bar),
 * otherwise a normal "draw over apps" overlay. Every input fails quietly without its permission.
 */
class IslandService : Service(), IslandView.Host {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: IslandRoot? = null
    private val view: IslandView? get() = root?.island
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var landscape = false
    private var screenOn = true
    private var startedAt = 0L
    private var level = -1
    private var charging = false
    private var lowWarned = false
    private var fullShown = false
    private var lastThirstyPeek = 0L
    private var lastRinger = -1
    private val camsInUse = HashSet<String>()
    private var micInUse = false
    private val btNames = HashMap<String, String>()
    private val btSeen = HashMap<String, Long>()

    // ---- auto payment mode: hidden + accessibility off while a payment/bank app is open ----------------

    @Volatile var paymentPaused = false
        private set

    private val payPoll = object : Runnable {
        override fun run() {
            if (!paymentPaused) return
            PaymentMode.check(this@IslandService)
            if (paymentPaused) main.postDelayed(this, 1500)
        }
    }

    fun pauseForPayment() {
        main.post {
            paymentPaused = true
            syncVisibility()
            if (isForeground) updateNotification()
            main.removeCallbacks(payPoll)
            main.postDelayed(payPoll, 1500)
        }
    }

    fun resumeAfterPayment() {
        main.post {
            paymentPaused = false
            main.removeCallbacks(payPoll)
            if (root == null) rebuildWindow() else syncVisibility()
            if (isForeground) updateNotification()
        }
    }

    /** Bound by IslandA11y so the island lives as long as the accessibility service, with no notification. */
    override fun onBind(intent: Intent?): IBinder = android.os.Binder()

    private var isForeground = false

    /** Foreground notification only when nothing else keeps us alive (accessibility service off). */
    fun updateForeground() {
        if (IslandA11y.instance != null) {
            if (isForeground) {
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
                isForeground = false
            }
            // Clear any leftover copy (e.g. re-posted by an older build).
            try { getSystemService(NotificationManager::class.java).cancel(FG_ID) } catch (_: Exception) { }
        } else if (!isForeground) {
            try { goForeground(); isForeground = true } catch (_: Exception) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        startedAt = System.currentTimeMillis()
        // Restarted while a payment app was open: stay hidden and keep watching for when it closes.
        if (Prefs.get(this).getBoolean(Prefs.PAY_ACTIVE, false)) { paymentPaused = true; main.postDelayed(payPoll, 1500) }
        prefs = Prefs.get(this)
        updateForeground()
        if (!prefs.getBoolean(Prefs.AUTOFIT_DONE, false)) autoFit(this)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        rebuildWindow()

        register(batteryRx, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, exported = false)
        register(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, exported = false)
        register(soundRx, IntentFilter().apply {
            addAction(VOLUME_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }, exported = true)
        register(btRx, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BT_BATTERY_CHANGED)
        }, exported = true)
        try {
            getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(camCb, main)
        } catch (_: Exception) {
        }
        try {
            val am = getSystemService(AudioManager::class.java)
            am?.registerAudioRecordingCallback(recCb, main)
            micInUse = recording(am?.activeRecordingConfigurations)
        } catch (_: Exception) {
        }
        pushPrivacy()

        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        MediaHub.addListener(dataListener)
        LiveHub.addListener(dataListener)
        LiveHub.hidden = { inFront(it.pkg) }
        IslandBus.onNotification = notifListener
        Water.scheduleNext(this)
        IslandTimer.restore(this)
        tick.run()
        syncTick.run()
    }

    /** Laptop sync every 2 minutes while the island runs (a no-op until a folder is picked). */
    private val syncTick = object : Runnable {
        override fun run() {
            Sync.requestNow(this@IslandService)
            main.postDelayed(this, 120_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService(): Android requires startForeground() now…
        if (!isForeground) { try { goForeground(); isForeground = true } catch (_: Exception) { } }
        // …but if the accessibility service is keeping us alive, drop the notification straight away.
        updateForeground()
        if (root == null) rebuildWindow()
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        main.removeCallbacksAndMessages(null)
        for (rx in listOf(batteryRx, screenRx, soundRx, btRx)) {
            try { unregisterReceiver(rx) } catch (_: Exception) { }
        }
        try { getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(camCb) } catch (_: Exception) { }
        try { getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(recCb) } catch (_: Exception) { }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        MediaHub.removeListener(dataListener)
        LiveHub.removeListener(dataListener)
        LiveHub.hidden = { false }
        if (IslandBus.onNotification === notifListener) IslandBus.onNotification = null
        removeWindow()
        root = null
        HeadsUp.apply(this, false) // island gone: the system pop-ups come back
        PaymentMode.restoreDevOptions(this) // …and so do developer options, if payment mode had them off
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        params?.x = offsetX()
        syncVisibility()
        applyParams()
        view?.refresh(force = true)
    }

    private fun register(rx: BroadcastReceiver, f: IntentFilter, exported: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(rx, f, if (exported) RECEIVER_EXPORTED else RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(rx, f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "register failed", e)
        }
    }

    private fun keyguardLocked() = try {
        getSystemService(KeyguardManager::class.java).isKeyguardLocked
    } catch (_: Exception) { false }

    private fun settled() = System.currentTimeMillis() - startedAt > 2500

    // ---- foreground notification ---------------------------------------------------------

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val paused = paymentPaused
        return Notification.Builder(this, App.CH_ISLAND)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(if (paused) "Island paused for payment" else "Island is on")
            .setContentText(if (paused) "Back when you leave the payment app" else "💧 ${Water.todayMl(this)} / ${Water.goal(prefs)} ml today")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_drop),
                    "+${Water.cup(prefs)} ml",
                    ActionReceiver.pending(this, ActionReceiver.DRINK, 23)
                ).build()
            )
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FG_ID, n)
        }
    }

    /** Refresh the "Island is on" text — but only while it's actually needed (no accessibility host). */
    private fun updateNotification() {
        if (!isForeground) return
        try { getSystemService(NotificationManager::class.java).notify(FG_ID, buildNotification()) } catch (_: Exception) { }
    }

    // ---- window ------------------------------------------------------------------------------

    private fun dpi(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    fun rebuildWindow() {
        removeWindow()
        val a11y = IslandA11y.instance
        val hostCtx: Context
        val type: Int
        when {
            a11y != null -> { hostCtx = a11y; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            Settings.canDrawOverlays(this) -> { hostCtx = this; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            else -> {
                root = null; params = null; wm = null
                return
            }
        }
        wm = hostCtx.getSystemService(WindowManager::class.java)
        val r = IslandRoot(hostCtx, this)
        r.island.setBattery(level, charging)
        r.island.setHole(holeDiameterPx(hostCtx))
        root = r
        pushPrivacy()
        params = WindowManager.LayoutParams(
            centeredWidth(r.island.windowW), r.island.windowH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = offsetX()
            y = dpi(prefs.getInt(Prefs.OFFSET_Y, Prefs.DEF_OFFSET_Y))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Dynamic Island"
        }
        syncVisibility()
    }

    /** Keyguard showing, refreshed by [syncVisibility]. */
    private var locked = false

    /** A notification (or reply) is on the island right now — the one thing it still shows in landscape. */
    private var notifUp = false

    override fun onNotifShowing(showing: Boolean) {
        notifUp = showing
        if (landscape) main.post { syncVisibility() }
    }

    /** The camera offset is for portrait; in landscape the island (notifications only) sits at the top centre. */
    private fun offsetX() = if (landscape) 0 else dpi(prefs.getInt(Prefs.OFFSET_X, 0))

    /**
     * Screen off / always-on display: never shown. Lock screen: only while music or video plays
     * (unless "Show on lock screen" is on). Unlocked: always. Landscape: only while a notification
     * shows, in place of the system pop-up.
     */
    private fun syncVisibility() {
        val r = root
        locked = screenOn && keyguardLocked()
        val lockOk = !locked || prefs.getBoolean(Prefs.LOCK_ALWAYS, false) ||
            (Prefs.on(prefs, Prefs.F_MEDIA) && MediaHub.showing())
        val want = r != null && (!landscape || notifUp) && !paymentPaused && screenOn && lockOk
        if (want && !attached) {
            try {
                wm?.addView(r, params)
                attached = true
                r?.island?.refresh(force = true)
            } catch (e: Exception) {
                Log.w(TAG, "addView failed", e)
            }
        } else if (!want && attached) {
            removeWindow()
        }
        // Island available (not payment mode) with peeks on: it replaces the system pop-ups, in landscape too.
        // Not tied to screen / lock state — pop-ups don't show there anyway, and this keeps writes rare.
        HeadsUp.apply(
            this, r != null && !paymentPaused &&
                prefs.getBoolean(Prefs.PEEK_NOTIFS, true) && Prefs.on(prefs, Prefs.REPLACE_POPUPS)
        )
    }

    /** The accessibility service saw a different app come to the front. */
    fun onTopAppChanged() {
        main.post { view?.refresh() }
    }

    /**
     * [pkg] is the app on screen (and the phone is unlocked): its now-playing / live pill stays off the island
     * until you leave it.
     */
    override fun inFront(pkg: String?): Boolean {
        if (locked || pkg.isNullOrEmpty()) return false
        return IslandA11y.topPackage == pkg
    }

    private fun removeWindow() {
        if (!attached) return
        try { wm?.removeViewImmediate(root) } catch (_: Exception) { }
        attached = false
    }

    private fun applyParams() {
        if (attached) try { wm?.updateViewLayout(root, params) } catch (_: Exception) { }
    }

    private fun applyPosition() {
        val p = params ?: return
        p.x = offsetX()
        p.y = dpi(prefs.getInt(Prefs.OFFSET_Y, Prefs.DEF_OFFSET_Y))
        applyParams()
    }

    override fun requestWindowSize(w: Int, h: Int) {
        val p = params ?: return
        val cw = centeredWidth(w)
        if (p.width == cw && p.height == h) return
        p.width = cw
        p.height = h
        applyParams()
    }

    /**
     * CENTER_HORIZONTAL places the window at (screen - width) / 2, rounded down. If that difference is odd,
     * every grow/shrink during an animation shifts the island by a pixel — the sideways "tremble".
     * Keeping (screen - width) even makes the window sit on the exact same centre at every size.
     */
    private fun centeredWidth(w: Int): Int {
        val screen = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm?.currentWindowMetrics?.bounds?.width() ?: 0
            else resources.displayMetrics.widthPixels
        } catch (_: Exception) { 0 }
        return if (screen > 0 && (screen - w) % 2 != 0) w + 1 else w
    }

    /** Inline reply needs keyboard focus; the rest of the time the island must never take it. */
    override fun setFocusable(focus: Boolean) {
        val p = params ?: return
        p.flags = if (focus) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        applyParams()
    }

    /**
     * A swipe down that began on the island: open the real notification shade. The island
     * window sits above the status bar, so the system never sees that gesture itself.
     */
    override fun openShade() = pullDown(
        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "expandNotificationsPanel"
    )

    /** Same, for a swipe that began right of the camera: the quick settings / shortcuts panel. */
    override fun openQuickSettings() = pullDown(
        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "expandSettingsPanel"
    )

    private fun pullDown(globalAction: Int, statusBarMethod: String) {
        val a11y = IslandA11y.instance
        if (a11y != null) {
            try {
                if (a11y.performGlobalAction(globalAction)) return
            } catch (_: Throwable) {
            }
        }
        try {
            @android.annotation.SuppressLint("WrongConstant")
            val sbm = getSystemService("statusbar")
            sbm?.javaClass?.getMethod(statusBarMethod)?.invoke(sbm)
        } catch (_: Throwable) {
        }
    }

    override fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "openApp failed", e)
        }
    }

    override fun send(pi: PendingIntent?) {
        pi ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        } catch (_: Exception) {
        }
    }

    /** From the settings screen: shows a sample notification peek (with OTP + reply-less). */
    fun testPeek() {
        val icon = try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
        view?.showNotification(
            PeekNotif("test", "Dynamic Island", icon, "Your code is 482913", "Use this one-time code to sign in.", null, "482913")
        )
    }

    fun hasWindow() = attached

    // ---- inputs ------------------------------------------------------------------------------

    private val dataListener: () -> Unit = {
        main.post {
            syncVisibility() // music starting / stopping shows / hides the island on the lock screen
            view?.refresh()
        }
    }

    private val notifListener: (PeekNotif) -> Unit = { n ->
        main.post {
            val v = view ?: return@post
            // In landscape the window is away; the peek itself brings it up (see onNotifShowing).
            if (!screenOn || !(attached || landscape)) return@post
            // Nothing on screen yet, so an unseen peek (water nag, volume…) mustn't hold the notification back.
            if (!attached) v.collapse()
            // On the lock screen, show who it's from but not what it says (and no code / reply).
            v.showNotification(if (keyguardLocked()) PeekNotif(n.key, n.appLabel, n.icon, n.appLabel, "New notification", null) else n)
        }
    }

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (lv >= 0 && sc > 0) level = lv * 100 / sc
                    charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    view?.setBattery(level, charging)
                    if (charging) {
                        lowWarned = false
                        if (level >= 100 && !fullShown) {
                            fullShown = true
                            if (screenOn && settled()) view?.showCharging(full = true)
                        }
                    } else if (level in 1..20 && !lowWarned) {
                        lowWarned = true
                        if (screenOn) view?.showLowBattery()
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    charging = true
                    lowWarned = false
                    view?.setBattery(level, true)
                    if (screenOn) view?.showCharging(full = level >= 100)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    charging = false
                    fullShown = false
                    view?.setBattery(level, false)
                }
            }
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Also fires when the always-on display takes over: the island leaves with the screen.
                    screenOn = false
                    view?.onScreenOff()
                    syncVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    syncVisibility()
                    tick.run()
                    Sync.requestNow(this@IslandService)
                }
                Intent.ACTION_USER_PRESENT -> {
                    syncVisibility()
                    maybeAskForWater()
                    Sync.requestNow(this@IslandService)
                }
            }
        }
    }

    private val soundRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    VOLUME_CHANGED -> {
                        if (!Prefs.on(prefs, Prefs.F_VOLUME) || !screenOn) return
                        val type = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        val v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        val prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                        if (v < 0 || v == prev) return
                        if (type != AudioManager.STREAM_MUSIC && type != AudioManager.STREAM_RING &&
                            type != AudioManager.STREAM_VOICE_CALL && type != AudioManager.STREAM_ALARM
                        ) return
                        val max = getSystemService(AudioManager::class.java)?.getStreamMaxVolume(type)?.coerceAtLeast(1) ?: return
                        view?.showVolume(type, v.toFloat() / max)
                    }
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val mode = i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                        val first = lastRinger == -1 || isInitialStickyBroadcast
                        val changed = mode != lastRinger
                        lastRinger = mode
                        if (first || !changed || !Prefs.on(prefs, Prefs.F_RINGER) || !screenOn) return
                        when (mode) {
                            AudioManager.RINGER_MODE_SILENT -> view?.showInfo(IslandView.Glyph.BELL_OFF, "", "Silent", IslandView.RED, 1800)
                            AudioManager.RINGER_MODE_VIBRATE -> view?.showInfo(IslandView.Glyph.VIBRATE, "", "Vibrate", IslandView.ORANGE, 1800)
                            AudioManager.RINGER_MODE_NORMAL -> view?.showInfo(IslandView.Glyph.BELL, "", "Ring", 0xFFDDDDDD.toInt(), 1800)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Needs BLUETOOTH_CONNECT on Android 12+; without it these broadcasts simply never arrive. */
    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val addr = try { dev?.address } catch (_: SecurityException) { null } ?: "?"
                val name = btNames[addr] ?: (try { dev?.name } catch (_: SecurityException) { null } ?: "Bluetooth device")
                btNames[addr] = name
                when (i.action) {
                    BT_BATTERY_CHANGED -> {
                        val lv = i.getIntExtra(BT_EXTRA_LEVEL, -1)
                        if (lv in 0..100) view?.updateInfoLevel(name, lv)
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (!Prefs.on(prefs, Prefs.F_BT) || !screenOn) return
                        val bonded = try { dev?.bondState == BluetoothDevice.BOND_BONDED } catch (_: SecurityException) { true }
                        if (!bonded) return // skip random BLE beacons
                        val connected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                        val k = addr + connected
                        val now = System.currentTimeMillis()
                        if (now - (btSeen[k] ?: 0L) < 30_000) return // flappy watches
                        btSeen[k] = now
                        val major = try { dev?.bluetoothClass?.majorDeviceClass } catch (_: SecurityException) { null }
                        val g = when (major) {
                            BluetoothClass.Device.Major.AUDIO_VIDEO -> IslandView.Glyph.HEADPHONES
                            BluetoothClass.Device.Major.WEARABLE -> IslandView.Glyph.WATCH
                            else -> IslandView.Glyph.BLUETOOTH
                        }
                        view?.showInfo(
                            g, name, if (connected) "Connected" else "Disconnected",
                            if (connected) IslandView.BLUE else IslandView.GREY, 3500
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private val camCb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (camsInUse.remove(cameraId)) pushPrivacy()
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (camsInUse.add(cameraId)) {
                pushPrivacy()
                privacyPeek(camera = true)
            }
        }
    }

    private val recCb = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val now = recording(configs)
            if (now == micInUse) return
            micInUse = now
            pushPrivacy()
            if (now) privacyPeek(camera = false)
        }
    }

    /** Ignore the always-listening hotword detector and silenced clients. */
    private fun recording(list: List<AudioRecordingConfiguration>?): Boolean = list?.any { cfg ->
        cfg.clientAudioSource != 1999 && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cfg.isClientSilenced)
    } == true

    /** Off by default: Android 12+ already shows its own green / orange dot in the status bar. */
    private fun privacyOn() = prefs.getBoolean(Prefs.F_PRIVACY, false)

    private fun pushPrivacy() {
        val on = privacyOn()
        view?.setPrivacy(on && camsInUse.isNotEmpty(), on && micInUse)
    }

    private fun privacyPeek(camera: Boolean) {
        if (!privacyOn() || !screenOn || keyguardLocked() || !settled()) return
        if (camera) view?.showInfo(IslandView.Glyph.CAMERA, "Camera", "in use", IslandView.GREEN, 2200)
        else view?.showInfo(IslandView.Glyph.MIC, "Microphone", "in use", IslandView.ORANGE, 2200)
    }

    /** When you unlock the phone and you're behind pace, the pet pops up (at most every 10 min). */
    private fun maybeAskForWater() {
        val now = System.currentTimeMillis()
        if (Water.isDue(this) && now - lastThirstyPeek > 10 * 60_000L) {
            lastThirstyPeek = now
            view?.showThirsty()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val v = view
        when (key) {
            // Shown live while you drag a Position & size slider (it's otherwise hidden in the dock).
            Prefs.OFFSET_X, Prefs.OFFSET_Y -> { applyPosition(); v?.previewPill() }
            Prefs.WIDTH, Prefs.HEIGHT, Prefs.PILL_GAP -> { v?.reloadGeometry(); v?.previewPill() }
            Prefs.LAST_DRINK -> {
                v?.showSplash()
                updateNotification()
            }
            Prefs.TODAY_ML, Prefs.GOAL, Prefs.CUP -> {
                updateNotification()
                v?.refresh()
            }
            Prefs.LAST_REMINDER -> {
                lastThirstyPeek = System.currentTimeMillis()
                if (screenOn) v?.showThirsty()
            }
            Prefs.SNOOZE_UNTIL -> v?.dismissThirsty()
            Prefs.SYNC_CHANGED_AT -> {
                updateNotification()
                v?.refresh()
                if (screenOn) v?.showInfo(IslandView.Glyph.CHECK, "Synced", "with laptop", IslandView.BLUE, 1800)
            }
            Prefs.GOAL_HIT_AT -> if (screenOn) v?.showGoal()
            Prefs.EVOLVED_AT -> if (screenOn) v?.showEvolve()
            Prefs.ACH_AT -> {
                // Shown after the drink splash / goal peek that usually comes with it.
                val a = Achievement.entries.firstOrNull { it.name == prefs.getString(Prefs.ACH_LAST, "") }
                if (a != null) main.postDelayed({
                    if (screenOn) view?.showInfo(IslandView.Glyph.STAR, "Achievement", "${a.emoji} ${a.title}", 0xFFFFD54F.toInt(), 3000)
                }, 4200)
            }
            Prefs.TIMER_END, Prefs.TIMER_PAUSED_LEFT -> tick.run()
            Prefs.TIMER_DONE_AT -> v?.showTimerDone()
            Prefs.F_PRIVACY -> pushPrivacy()
            Prefs.ISLAND_ON -> if (!prefs.getBoolean(Prefs.ISLAND_ON, false)) { IslandA11y.instance?.releaseIsland(); stopSelf() }
            Prefs.LOCK_ALWAYS, Prefs.F_MEDIA, Prefs.PEEK_NOTIFS, Prefs.REPLACE_POPUPS -> { syncVisibility(); v?.refresh() }
            Prefs.HEADSUP_OFF_BY_US -> {}
            else -> v?.refresh()
        }
    }

    /** 1 s while a timer runs (and the screen is on), otherwise every 30 s for the pet's mood. */
    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (!screenOn) return
            syncVisibility() // catches an unlock / relock the broadcasts missed
            val end = IslandTimer.endAt(prefs)
            if (end != 0L && System.currentTimeMillis() >= end) IslandTimer.finish(this@IslandService)
            view?.refresh()
            val timing = IslandTimer.endAt(prefs) != 0L
            main.postDelayed(this, if (timing) 1000L - System.currentTimeMillis() % 1000L + 10L else 30_000L)
        }
    }

    companion object {
        private const val TAG = "IslandService"
        const val FG_ID = 1001
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val BT_BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BT_EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        @Volatile
        var running: IslandService? = null

        fun start(ctx: Context) {
            // With the accessibility service on, it hosts the island (bound, no notification).
            val a11y = IslandA11y.instance
            if (a11y != null) { a11y.holdIsland(); return }
            try {
                ctx.startForegroundService(Intent(ctx, IslandService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }

        fun stop(ctx: Context) {
            IslandA11y.instance?.releaseIsland()
            ctx.stopService(Intent(ctx, IslandService::class.java))
        }

        /** The accessibility service connected or went away: move the island to the best window. */
        fun hostChanged(ctx: Context) {
            Handler(Looper.getMainLooper()).post {
                val s = running
                if (s != null) { s.updateForeground(); s.rebuildWindow() }
                else if (Prefs.get(ctx).getBoolean(Prefs.ISLAND_ON, false)) start(ctx)
            }
        }

        /** Centre the pill on the punch-hole camera using the display cutout. */
        /** Punch-hole diameter in px from the display cutout, or 0 if the phone reports none. */
        fun holeDiameterPx(ctx: Context): Float = try {
            val wm = ctx.getSystemService(WindowManager::class.java)
            val r: Rect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.minByOrNull { it.top }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.cutout?.boundingRects?.minByOrNull { it.top }
            } else null
            if (r == null || r.isEmpty) 0f else min(r.width(), r.height()).toFloat()
        } catch (_: Exception) { 0f }

        fun autoFit(ctx: Context): Boolean {
            val p = Prefs.get(ctx)
            val dm = ctx.resources.displayMetrics
            val d = dm.density
            var screenW = min(dm.widthPixels, dm.heightPixels)
            var rect: Rect? = null
            try {
                val wm = ctx.getSystemService(WindowManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val m = wm.currentWindowMetrics
                    screenW = min(m.bounds.width(), m.bounds.height())
                    rect = m.windowInsets.displayCutout?.boundingRects?.minByOrNull { it.top }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    rect = wm.defaultDisplay.cutout?.boundingRects?.minByOrNull { it.top }
                }
            } catch (_: Exception) {
            }
            val e = p.edit().putBoolean(Prefs.AUTOFIT_DONE, true)
            val r = rect
            if (r == null || r.isEmpty || r.top > 200 * d) {
                e.apply()
                return false
            }
            val holeD = min(r.width(), r.height()).toFloat()
            // Punch-hole rects often extend up to y=0; the hole itself is the bottom square.
            val cy = if (r.width() <= r.height()) r.bottom - r.width() / 2f else r.exactCenterY()
            val hDp = (holeD / d + 12f).roundToInt().coerceIn(24, 40)
            val yDp = (cy / d - hDp / 2f).roundToInt().coerceIn(0, 40)
            val xDp = ((r.exactCenterX() - screenW / 2f) / d).roundToInt().coerceIn(-150, 150)
            e.putInt(Prefs.HEIGHT, hDp).putInt(Prefs.OFFSET_Y, yDp).putInt(Prefs.OFFSET_X, xDp).apply()
            return true
        }
    }
}
