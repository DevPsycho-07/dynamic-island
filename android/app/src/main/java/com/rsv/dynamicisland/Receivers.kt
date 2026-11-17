package com.rsv.dynamicisland

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification buttons + our own alarms. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            DRINK -> Water.drink(ctx)
            SNOOZE -> Water.snooze(ctx)
            REMIND -> Water.onReminderAlarm(ctx)
            TIMER_DONE -> IslandTimer.finish(ctx)
        }
    }

    companion object {
        const val DRINK = "com.rsv.dynamicisland.DRINK"
        const val SNOOZE = "com.rsv.dynamicisland.SNOOZE"
        const val REMIND = "com.rsv.dynamicisland.REMIND"
        const val TIMER_DONE = "com.rsv.dynamicisland.TIMER_DONE"

        fun pending(ctx: Context, action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Alarms are wiped on reboot and app updates; put everything back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Water.ensureInit(ctx)
        Water.scheduleNext(ctx)
        IslandTimer.restore(ctx)
        if (Prefs.get(ctx).getBoolean(Prefs.ISLAND_ON, false)) IslandService.start(ctx)
    }
}
