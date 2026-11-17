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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, IslandA11y::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
