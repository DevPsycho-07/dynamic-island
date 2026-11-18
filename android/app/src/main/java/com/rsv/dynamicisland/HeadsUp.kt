package com.rsv.dynamicisland

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Stops a notification showing twice (the system's pop-up banner *and* the island's peek).
 *
 * Android gives no app a way to hide another app's pop-up, so while the island is on screen with
 * notification peeks on, this switches the system's heads-up pop-ups off (Settings.Global
 * "heads_up_notifications_enabled"); notifications still arrive in the shade, on the lock screen
 * and the always-on display as usual. Whenever the island steps away — landscape, payment mode,
 * the island switched off — the pop-ups come back, restored to exactly what they were.
 *
 * Needs WRITE_SECURE_SETTINGS (the same one-time adb grant as Auto payment mode); without it this does nothing.
 */
object HeadsUp {
    private const val TAG = "HeadsUp"
    private const val KEY = "heads_up_notifications_enabled"

    fun canControl(ctx: Context) = PaymentMode.canSwitch(ctx)

    /** true = the island is standing in for the pop-ups right now; false = give the system its pop-ups back. */
    fun apply(ctx: Context, suppress: Boolean) {
        val p = Prefs.get(ctx)
        val ours = p.getBoolean(Prefs.HEADSUP_OFF_BY_US, false)
        if (suppress == ours || !canControl(ctx)) return
        val cr = ctx.contentResolver
        try {
            if (suppress) {
                // Already off by the user's own choice: leave it, and never turn it on later.
                if (Settings.Global.getInt(cr, KEY, 1) == 0) return
                Settings.Global.putInt(cr, KEY, 0)
            } else {
                Settings.Global.putInt(cr, KEY, 1)
            }
            p.edit().putBoolean(Prefs.HEADSUP_OFF_BY_US, suppress).commit()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't switch heads-up pop-ups", e)
        }
    }
}
