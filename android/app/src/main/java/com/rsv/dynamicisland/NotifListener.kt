package com.rsv.dynamicisland

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.math.abs

/** A notification the island should briefly show. */
class PeekNotif(
    val key: String,
    val appLabel: String,
    val icon: Drawable?,
    val title: String,
    val text: String,
    val intent: PendingIntent?,
    val otp: String? = null,
    val reply: Notification.Action? = null,
)

/** Hands notification peeks to the island (if it is running). */
object IslandBus {
    @Volatile
    var onNotification: ((PeekNotif) -> Unit)? = null
}

/** Finds one-time codes ("Your OTP is 482913"). */
object Otp {
    private val KEY = Regex(
        "(?i)(otp|one[ -]?time|verification|verify|passcode|security code|login code|auth\\w* code|\\bcode\\b|\\bpin\\b)"
    )
    private val NUM = Regex("(?<![\\d.,₹$])\\b(\\d{4,8})\\b(?![.,]?\\d)")

    fun find(s: String): String? {
        val k = KEY.find(s) ?: return null
        val nums = NUM.findAll(s).toList()
        if (nums.isEmpty()) return null
        return nums.minByOrNull { abs(it.range.first - k.range.first) }?.groupValues?.get(1)
    }
}

/**
 * Notification access gives us: notification peeks (with OTP copy + inline reply), the ongoing
 * call, live activities (progress / navigation), and the media sessions list.
 */
class NotifListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { MediaHub.setControllers(it) }
    private val alertedOnce = HashSet<String>()
    private val labels = HashMap<String, String>()
    private var lastSig = ""
    private var lastSigAt = 0L

    override fun onListenerConnected() {
        connected = true
        try {
            val m = getSystemService(MediaSessionManager::class.java)
            if (m != null) {
                msm = m
                val cn = ComponentName(this, NotifListener::class.java)
                m.addOnActiveSessionsChangedListener(sessionsListener, cn, Handler(Looper.getMainLooper()))
                MediaHub.setControllers(m.getActiveSessions(cn))
            }
        } catch (_: Exception) {
        }
        try {
            activeNotifications?.forEach { trackOngoing(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        MediaHub.setControllers(null)
        LiveHub.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        try {
            if (trackOngoing(sbn)) return
            peek(sbn, rankingMap)
        } catch (_: Exception) {
            // Never let one odd notification take the listener down.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        alertedOnce.remove(sbn.key)
        LiveHub.remove(sbn.key)
    }

    // ---- calls + live activities ---------------------------------------------------------------

    /** Returns true when the notification is a call / live activity (so it doesn't also peek). */
    private fun trackOngoing(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false
        val p = Prefs.get(this)
        if (isCall(n)) {
            if (Prefs.on(p, Prefs.F_CALL)) LiveHub.setCall(buildCall(sbn, n))
            return true
        }
        if (!Prefs.on(p, Prefs.F_LIVE)) return false
        val live = buildLive(sbn, n)
        if (live != null) {
            LiveHub.put(live)
            return true
        }
        LiveHub.remove(sbn.key) // e.g. a download that just finished turns into a normal notification
        return false
    }

    private fun isCall(n: Notification): Boolean {
        val callish = n.category == Notification.CATEGORY_CALL || n.extras.containsKey("android.callType")
        val live = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || n.fullScreenIntent != null
        return callish && live
    }

    private fun buildCall(sbn: StatusBarNotification, n: Notification): CallInfo {
        val ex = n.extras
        var name = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (name.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val person: Person? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ex.getParcelable("android.callPerson", Person::class.java)
                else @Suppress("DEPRECATION") ex.getParcelable("android.callPerson")
            } catch (_: Exception) { null }
            name = person?.name?.toString().orEmpty()
        }
        if (name.isEmpty()) name = "Call"
        val acts = n.actions ?: emptyArray()
        fun find(vararg words: String): PendingIntent? = acts.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { t.contains(it) }
        }?.actionIntent
        val answer = find("answer", "accept")
        val end = find("hang up", "end", "decline", "reject", "dismiss")
        val speaker = find("speaker")
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val incoming = answer != null && !chrono
        val now = System.currentTimeMillis()
        val prev = LiveHub.call?.takeIf { it.key == sbn.key }
        val started = when {
            incoming -> 0L
            chrono && n.`when` in 1..now -> n.`when`
            prev != null && prev.startedAt > 0 -> prev.startedAt
            else -> now
        }
        return CallInfo(sbn.key, name, started, incoming, end, speaker, answer, n.contentIntent)
    }

    private fun buildLive(sbn: StatusBarNotification, n: Notification): LiveItem? {
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val ex = n.extras
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indet = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val nav = n.category == "navigation" || (sbn.isOngoing && sbn.packageName in NAV_PKGS)
        val hasProgress = (max > 0 && prog in 0 until max) || (indet && sbn.isOngoing)
        if (!nav && !hasProgress) return null
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val old = LiveHub.get(sbn.key)
        return LiveItem(
            key = sbn.key,
            appLabel = label(sbn.packageName),
            icon = old?.icon ?: icon(sbn.packageName, n),
            title = title.ifEmpty { text },
            text = if (title.isEmpty()) "" else text,
            progress = prog,
            max = max,
            indeterminate = indet || max <= 0,
            nav = nav,
            open = n.contentIntent,
            updatedAt = System.currentTimeMillis(),
        )
    }

    // ---- peeks ---------------------------------------------------------------------------------

    private fun peek(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val cb = IslandBus.onNotification ?: return
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.PEEK_NOTIFS, true)) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        when (n.category) {
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM -> return
        }
        if (rankingMap != null) {
            val r = Ranking()
            if (rankingMap.getRanking(sbn.key, r)) {
                if (r.importance < NotificationManager.IMPORTANCE_DEFAULT) return
                if (!r.matchesInterruptionFilter()) return // respects Do Not Disturb
            }
        }
        if ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && !alertedOnce.add(sbn.key)) return

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val sig = sbn.packageName + "|" + title + "|" + text
        val now = System.currentTimeMillis()
        if (sig == lastSig && now - lastSigAt < 5_000) return
        lastSig = sig
        lastSigAt = now

        val label = label(sbn.packageName)
        val otp = if (Prefs.on(p, Prefs.F_OTP)) Otp.find("$title $text") else null
        val reply = if (Prefs.on(p, Prefs.F_REPLY)) {
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true && a.actionIntent != null }
        } else null
        cb(PeekNotif(sbn.key, label, icon(sbn.packageName, n), title.ifEmpty { label }, text, n.contentIntent, otp, reply))
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    /** The app's real launcher icon (WhatsApp, Gmail…); the notification's own picture only if that fails. */
    private fun icon(pkg: String, n: Notification): Drawable? =
        try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
            ?: try { n.getLargeIcon()?.loadDrawable(this) } catch (_: Exception) { null }

    companion object {
        @Volatile
        var connected = false

        private val NAV_PKGS = setOf(
            "com.google.android.apps.maps", "com.google.android.apps.mapslite", "com.waze",
            "com.here.app.maps", "com.sygic.aura", "net.osmand", "net.osmand.plus", "com.mapmyindia.app",
        )

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotifListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
