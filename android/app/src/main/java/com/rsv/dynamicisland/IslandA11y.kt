package com.rsv.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, recommended. Two jobs, and it reads nothing:
 *  1. Lends its window type to the island: an accessibility overlay sits *above* the status bar
 *     (normal overlays sit under it, so status-bar icons draw over the pill) and doesn't trigger
 *     Android's "is displaying over other apps" notification.
 *  2. Keeps IslandService alive by binding to it. Android already keeps accessibility services
 *     running, so the island needs no foreground-service ("Island is on") notification.
 */
class IslandA11y : AccessibilityService() {
    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onServiceConnected() {
        instance = this
        // Whatever was in front when we were last on may be long gone (e.g. switched off for payment mode).
        topPackage = null
        // Payment mode switched us off, so being switched back on while it still runs means you did it yourself:
        // it's over. (Its own exit clears the flag before switching us on, so this never fires for that.)
        if (PaymentMode.active(this)) PaymentMode.exit(this, manual = true)
        holdIsland()
        IslandService.hostChanged(this)
    }

    /** Bind (and so create/keep) the island service — only while the user has the island switched on. */
    fun holdIsland() {
        if (bound || !Prefs.get(this).getBoolean(Prefs.ISLAND_ON, false)) return
        bound = try { bindService(Intent(this, IslandService::class.java), conn, Context.BIND_AUTO_CREATE) } catch (_: Exception) { false }
    }

    fun releaseIsland() {
        if (!bound) return
        try { unbindService(conn) } catch (_: Exception) { }
        bound = false
    }

    /**
     * Only window changes are delivered, and only the package / class name is looked at:
     * payment apps → step aside; and which app is on screen, so the island doesn't show the
     * now-playing pill for the very app you are watching (YouTube open → no YouTube pill).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val pay = PaymentMode.isPaymentApp(this, pkg)
        if (pay) PaymentMode.enter(this, pkg)
        val cls = event.className?.toString() ?: return
        // Dialogs, menus, the keyboard, the notification shade and our own overlay also fire this event;
        // only a real activity means a different app is now in front.
        if (pkg != topPackage && isActivity(pkg, cls)) {
            topPackage = pkg
            if (!pay) PaymentMode.leftPaymentApps()
            IslandService.running?.onTopAppChanged()
        }
    }

    private val activityCache = HashMap<String, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val yes = try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (_: Exception) { false }
        if (activityCache.size > 400) activityCache.clear()
        activityCache[key] = yes
        return yes
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        releaseIsland()
        IslandService.hostChanged(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            releaseIsland()
            IslandService.hostChanged(applicationContext)
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: IslandA11y? = null

        /** Package of the app currently on screen (null until the first app switch is seen). */
        @Volatile
        var topPackage: String? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, IslandA11y::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
