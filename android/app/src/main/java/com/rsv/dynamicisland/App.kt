package com.rsv.dynamicisland

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ISLAND, "Island running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The quiet notification Android needs while the island is on."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WATER, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your pet nudges you when you fall behind pace."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Timer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when a quick timer finishes."
            }
        )
        Water.ensureInit(this)
        // The process was killed while the island had the system pop-ups switched off and the island
        // isn't coming back: hand them back. (If it is coming back, IslandService takes over again.)
        if (!Prefs.get(this).getBoolean(Prefs.ISLAND_ON, false)) HeadsUp.apply(this, false)
        // Same for developer options: owed back unless payment mode is still running.
        if (!PaymentMode.active(this)) PaymentMode.restoreDevOptions(this)
    }

    companion object {
        const val CH_ISLAND = "island"
        const val CH_WATER = "water"
        const val CH_TIMER = "timer"
    }
}
