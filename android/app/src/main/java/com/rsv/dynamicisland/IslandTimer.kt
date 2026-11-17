package com.rsv.dynamicisland

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Calendar

/**
 * One focus timer. Running = end time in the future. Paused = end time 0 and the remaining
 * ms stored. Finished/cancelled sessions add to today's focus stats.
 */
object IslandTimer {
    const val NOTIF_ID = 2002
    private const val REQ_DONE = 12

    fun endAt(p: SharedPreferences) = p.getLong(Prefs.TIMER_END, 0)
    fun totalMs(p: SharedPreferences) = p.getLong(Prefs.TIMER_TOTAL, 0)
    fun pausedLeft(p: SharedPreferences) = p.getLong(Prefs.TIMER_PAUSED_LEFT, 0)

    fun running(ctx: Context): Boolean = endAt(Prefs.get(ctx)) > System.currentTimeMillis()
    fun paused(ctx: Context): Boolean = pausedLeft(Prefs.get(ctx)) > 0

    /** Running or paused. */
    fun active(ctx: Context): Boolean = running(ctx) || paused(ctx)

    fun remainingMs(ctx: Context): Long {
        val p = Prefs.get(ctx)
        val left = pausedLeft(p)
        if (left > 0) return left
        return (endAt(p) - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun start(ctx: Context, minutes: Int) {
        val p = Prefs.get(ctx)
        if (active(ctx)) logFocus(ctx, totalMs(p) - remainingMs(ctx), completed = false)
        val total = minutes * 60_000L
        val end = System.currentTimeMillis() + total
        p.edit().putLong(Prefs.TIMER_TOTAL, total).putLong(Prefs.TIMER_PAUSED_LEFT, 0).putLong(Prefs.TIMER_END, end).apply()
        cancelDoneNotification(ctx)
        schedule(ctx, end)
    }

    fun add(ctx: Context, ms: Long) {
        val p = Prefs.get(ctx)
        val left = pausedLeft(p)
        if (left > 0) {
            p.edit().putLong(Prefs.TIMER_PAUSED_LEFT, left + ms).putLong(Prefs.TIMER_TOTAL, totalMs(p) + ms).apply()
            return
        }
        val end = endAt(p)
        if (end <= System.currentTimeMillis()) {
            start(ctx, (ms / 60_000L).toInt().coerceAtLeast(1))
            return
        }
        p.edit().putLong(Prefs.TIMER_END, end + ms).putLong(Prefs.TIMER_TOTAL, totalMs(p) + ms).apply()
        schedule(ctx, end + ms)
    }

    fun pause(ctx: Context) {
        val p = Prefs.get(ctx)
        val left = endAt(p) - System.currentTimeMillis()
        if (left <= 0) return
        p.edit().putLong(Prefs.TIMER_PAUSED_LEFT, left).putLong(Prefs.TIMER_END, 0).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(doneIntent(ctx))
    }

    fun resume(ctx: Context) {
        val p = Prefs.get(ctx)
        val left = pausedLeft(p)
        if (left <= 0) return
        val end = System.currentTimeMillis() + left
        p.edit().putLong(Prefs.TIMER_PAUSED_LEFT, 0).putLong(Prefs.TIMER_END, end).apply()
        schedule(ctx, end)
    }

    fun cancel(ctx: Context) {
        val p = Prefs.get(ctx)
        if (active(ctx)) logFocus(ctx, totalMs(p) - remainingMs(ctx), completed = false)
        p.edit().putLong(Prefs.TIMER_END, 0).putLong(Prefs.TIMER_PAUSED_LEFT, 0).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(doneIntent(ctx))
    }

    /** Called by the alarm, or by the island if it notices first. Safe to call twice. */
    fun finish(ctx: Context) {
        val p = Prefs.get(ctx)
        if (endAt(p) == 0L) return
        val total = totalMs(p)
        p.edit().putLong(Prefs.TIMER_END, 0).putLong(Prefs.TIMER_PAUSED_LEFT, 0)
            .putLong(Prefs.TIMER_DONE_AT, System.currentTimeMillis()).apply()
        logFocus(ctx, total, completed = true)
        vibrate(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, App.CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(0xFFFF9F0A.toInt())
            .setContentTitle("Timer done")
            .setContentText("Your ${format(total)} focus session finished.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }

    // ---- focus stats ------------------------------------------------------------------------

    private fun dayKey(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun rollDay(p: SharedPreferences) {
        val k = dayKey()
        if (p.getString(Prefs.FOCUS_DAY, null) != k) {
            p.edit().putString(Prefs.FOCUS_DAY, k).putInt(Prefs.FOCUS_SESSIONS, 0).putInt(Prefs.FOCUS_MINUTES, 0).apply()
        }
    }

    private fun logFocus(ctx: Context, focusedMs: Long, completed: Boolean) {
        val p = Prefs.get(ctx)
        rollDay(p)
        val min = (focusedMs / 60_000L).toInt().coerceAtLeast(0)
        p.edit()
            .putInt(Prefs.FOCUS_MINUTES, p.getInt(Prefs.FOCUS_MINUTES, 0) + min)
            .putInt(Prefs.FOCUS_SESSIONS, p.getInt(Prefs.FOCUS_SESSIONS, 0) + if (completed) 1 else 0)
            .apply()
    }

    /** [sessions completed, minutes focused] today. */
    fun todayStats(ctx: Context): IntArray {
        val p = Prefs.get(ctx)
        rollDay(p)
        return intArrayOf(p.getInt(Prefs.FOCUS_SESSIONS, 0), p.getInt(Prefs.FOCUS_MINUTES, 0))
    }

    fun statsLine(ctx: Context): String {
        val s = todayStats(ctx)
        return "Today · ${s[0]} session${if (s[0] == 1) "" else "s"} · ${s[1]} min"
    }

    // ---- misc --------------------------------------------------------------------------------

    fun cancelDoneNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** After a reboot / app update the alarm is gone: re-arm it, or finish if it already passed. */
    fun restore(ctx: Context) {
        val end = endAt(Prefs.get(ctx))
        if (end == 0L) return
        if (end <= System.currentTimeMillis()) finish(ctx) else schedule(ctx, end)
    }

    fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun doneIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, REQ_DONE,
        Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.TIMER_DONE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun schedule(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = doneIntent(ctx)
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun vibrate(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 450), -1))
        } catch (_: Exception) {
        }
    }
}
