package com.rsv.dynamicisland

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Payment and banking apps (GPay, Paytm, PhonePe, bank apps…) refuse to run while any third-party
 * accessibility service is on — and many also block screen overlays. So when one of them opens, the
 * island turns its own accessibility service off and hides completely; when you leave the app (or
 * lock the phone) it switches accessibility back on and returns.
 *
 * Needs two one-time grants (no UI exists for them):
 *   adb shell pm grant com.rsv.dynamicisland android.permission.WRITE_SECURE_SETTINGS
 *   adb shell appops set com.rsv.dynamicisland GET_USAGE_STATS allow
 * Without WRITE_SECURE_SETTINGS it does nothing (it could switch off but never back on).
 * Without usage access it only comes back when the screen turns off.
 */
object PaymentMode {
    private const val TAG = "PaymentMode"

    /** Pre-selected when installed; the user picks the real list in Island settings → Payment apps. */
    val DEFAULT_APPS = setOf(
        "com.google.android.apps.nbu.paisa.user",   // Google Pay
        "net.one97.paytm",                          // Paytm
        "com.phonepe.app",                          // PhonePe
        "in.org.npci.upiapp",                       // BHIM
        "com.dreamplug.androidapp",                 // CRED
        "com.mobikwik_new", "com.freecharge.android",
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus",               // SBI YONO
        "com.snapwork.hdfc", "com.hdfcbank.payzapp",                     // HDFC
        "com.csam.icici.bank.imobile",                                   // ICICI iMobile
        "com.axis.mobile", "com.msf.kbank.mobile",                       // Axis, Kotak
        "com.bankofbaroda.mconnect", "com.mgs.induspsp", "com.fss.unbipay",
        "com.lcode.smartz", "com.idbibank.abhay_card", "com.infrasofttech.nutan",
        "com.canarabank.mobility", "com.pnb.pnbone", "com.yesbank.yespay",
    )

    @Volatile private var cache: Set<String>? = null

    /** The apps the user chose (defaults until they pick). */
    fun apps(ctx: Context): Set<String> = cache ?: (Prefs.get(ctx).getStringSet(Prefs.PAY_APPS, null)?.toSet() ?: DEFAULT_APPS).also { cache = it }

    fun setApps(ctx: Context, pkgs: Set<String>) {
        Prefs.get(ctx).edit().putStringSet(Prefs.PAY_APPS, HashSet(pkgs)).apply()
        cache = pkgs
    }

    fun isPaymentApp(ctx: Context, pkg: String?) = pkg != null && pkg in apps(ctx)

    fun canSwitch(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(ctx: Context) = foreground(ctx, probe = true) != null || try {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun active(ctx: Context) = Prefs.get(ctx).getBoolean(Prefs.PAY_ACTIVE, false)

    /** A payment app came to the front (seen by the accessibility service): step aside. */
    fun enter(a11y: IslandA11y, pkg: String) {
        val p = Prefs.get(a11y)
        if (!Prefs.on(p, Prefs.F_AUTO_PAY) || active(a11y) || !canSwitch(a11y)) return
        p.edit().putBoolean(Prefs.PAY_ACTIVE, true).putString(Prefs.PAY_APP, pkg).commit()
        Log.i(TAG, "pausing for $pkg")
        // Keep the island process alive (paused, no window) while accessibility is off.
        try { a11y.startForegroundService(Intent(a11y, IslandService::class.java)) } catch (_: Exception) { }
        IslandService.running?.pauseForPayment()
        setAccessibility(a11y, false)
    }

    /** Left the payment app (or screen off): switch accessibility back on; the island returns when it reconnects. */
    fun exit(ctx: Context) {
        if (!active(ctx)) return
        Prefs.get(ctx).edit().putBoolean(Prefs.PAY_ACTIVE, false).commit()
        Log.i(TAG, "resuming")
        setAccessibility(ctx, true)
        IslandService.running?.resumeAfterPayment()
    }

    private fun setAccessibility(ctx: Context, on: Boolean): Boolean = try {
        val cr = ctx.contentResolver
        val me = ComponentName(ctx, IslandA11y::class.java)
        val others = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        val list = if (on) others + me.flattenToString() else others
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    } catch (e: Exception) {
        Log.w(TAG, "couldn't switch accessibility", e)
        false
    }

    /** Package most recently brought to the front (last 20 s), via usage access; null if unknown. */
    private fun foreground(ctx: Context, probe: Boolean = false): String? = try {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - if (probe) 3_600_000 else 20_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        last
    } catch (_: Exception) { null }

    private var away = 0

    /** Called every ~1.5 s by IslandService while paused. */
    fun check(ctx: Context) {
        if (!active(ctx)) return
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) { away = 0; exit(ctx); return }
        val fg = foreground(ctx) ?: return
        if (isPaymentApp(ctx, fg)) { away = 0; return }
        if (++away >= 2) { away = 0; exit(ctx) }
    }
}
