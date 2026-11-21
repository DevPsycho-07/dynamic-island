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
 * Many of them also refuse to run while developer options or USB / wireless debugging are on, so
 * (unless "Also pause developer options" is off) those are switched off for the same stretch and
 * restored to exactly what they were afterwards. Only settings that were on are touched, and only
 * those are turned back on.
 *
 * You can always end it yourself: "Bring island back" on its notification, opening the island app, or switching
 * its accessibility service back on — any of them ends payment mode (see [exit]).
 *
 * Needs two one-time grants (no UI exists for them):
 *   adb shell pm grant com.rsv.dynamicisland android.permission.WRITE_SECURE_SETTINGS
 *   adb shell appops set com.rsv.dynamicisland GET_USAGE_STATS allow
 * Without WRITE_SECURE_SETTINGS it does nothing (it could switch off but never back on).
 * Without usage access it only comes back when the screen turns off (or by hand).
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

    /** Usage access (to see which app is in front). Without it payment mode only ends on lock or by hand. */
    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val op = android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
        val uid = android.os.Process.myUid()
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(op, uid, ctx.packageName)
        } else {
            @Suppress("DEPRECATION") ops.checkOpNoThrow(op, uid, ctx.packageName)
        }
        when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> true
            android.app.AppOpsManager.MODE_DEFAULT ->
                ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    } catch (_: Exception) { false }

    fun active(ctx: Context) = Prefs.get(ctx).getBoolean(Prefs.PAY_ACTIVE, false)

    /**
     * The payment app that was on screen when you ended payment mode by hand. It doesn't send the island straight
     * back into payment mode until some other app has been in front (see [leftPaymentApps]). In memory only.
     */
    @Volatile private var skipPkg: String? = null

    /** A payment app came to the front (seen by the accessibility service): step aside. */
    fun enter(a11y: IslandA11y, pkg: String) {
        val p = Prefs.get(a11y)
        if (!Prefs.on(p, Prefs.F_AUTO_PAY) || active(a11y) || !canSwitch(a11y) || pkg == skipPkg) return
        p.edit().putBoolean(Prefs.PAY_ACTIVE, true).putString(Prefs.PAY_APP, pkg)
            .putLong(Prefs.PAY_SINCE, System.currentTimeMillis()).commit()
        Log.i(TAG, "pausing for $pkg")
        // Keep the island process alive (paused, no window) while accessibility is off. IslandService stays in the
        // foreground for the whole stretch (see updateForeground): it can't get back there once accessibility is gone.
        try { a11y.startForegroundService(Intent(a11y, IslandService::class.java)) } catch (_: Exception) { }
        IslandService.running?.pauseForPayment()
        if (Prefs.on(p, Prefs.F_PAY_DEV)) pauseDevOptions(a11y)
        setAccessibility(a11y, false)
    }

    /**
     * Left the payment app (or screen off): switch accessibility back on; the island returns when it reconnects.
     *
     * [manual]: you asked for it — "Bring island back" in the notification, opening the island app, or switching
     * its accessibility service back on yourself. That always ends it, even if the saved state and the running
     * island disagree. If a payment app is on screen right then (ended from the notification shade over it), it
     * doesn't pull the island straight back into payment mode.
     */
    fun exit(ctx: Context, manual: Boolean = false) {
        val paused = IslandService.running?.paymentPaused == true
        if (!active(ctx) && !(manual && paused)) return
        if (manual) skipPkg = front(ctx, Prefs.get(ctx).getLong(Prefs.PAY_SINCE, 0L))?.pkg?.takeIf { isPaymentApp(ctx, it) }
        // Before clearing the flag: if we die half-way, the restarted service still owes everything back.
        restoreDevOptions(ctx)
        Prefs.get(ctx).edit().putBoolean(Prefs.PAY_ACTIVE, false).commit()
        Log.i(TAG, if (manual) "ended by hand" else "resuming")
        setAccessibility(ctx, true)
        IslandService.running?.resumeAfterPayment()
    }

    /** A non-payment app came to the front: the app you ended payment mode in by hand counts again. */
    fun leftPaymentApps() { skipPkg = null }

    /**
     * Developer options, then USB and wireless debugging. Switched off in reverse order (debugging
     * first, like the Settings app does) and back on in this order. Wireless debugging only comes
     * back by itself on a network you chose "Always allow" for; otherwise Android asks first.
     */
    private val DEV_KEYS = listOf(
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        Settings.Global.ADB_ENABLED,
        "adb_wifi_enabled",  // Settings.Global.ADB_WIFI_ENABLED, hidden constant (Android 11+)
    )

    private fun isOn(ctx: Context, key: String) =
        try { Settings.Global.getInt(ctx.contentResolver, key, 0) == 1 } catch (_: Exception) { false }

    private fun pauseDevOptions(ctx: Context) {
        val p = Prefs.get(ctx)
        val on = DEV_KEYS.filter { isOn(ctx, it) }
        if (on.isEmpty()) return
        // Note what we owe before touching anything, so a crash can't leave them off for good.
        val owed = p.getStringSet(Prefs.DEV_OFF_BY_US, null).orEmpty() + on
        p.edit().putStringSet(Prefs.DEV_OFF_BY_US, HashSet(owed)).commit()
        for (key in on.reversed()) {
            try { Settings.Global.putInt(ctx.contentResolver, key, 0) }
            catch (e: Exception) { Log.w(TAG, "couldn't switch $key off", e) }
        }
        Log.i(TAG, "developer settings off: $on")
    }

    /** Turn back on exactly the developer settings we switched off. Safe to call any time. */
    fun restoreDevOptions(ctx: Context) {
        val p = Prefs.get(ctx)
        val owed = p.getStringSet(Prefs.DEV_OFF_BY_US, null)?.toSet() ?: return
        for (key in DEV_KEYS) if (key in owed) {
            try { Settings.Global.putInt(ctx.contentResolver, key, 1) }
            catch (e: Exception) { Log.w(TAG, "couldn't switch $key back on", e) }
        }
        p.edit().remove(Prefs.DEV_OFF_BY_US).commit()
        Log.i(TAG, "developer settings restored: $owed")
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

    /** An app that came to the front, and when. */
    private class Front(val pkg: String, val at: Long)

    // The app in front, kept up to date from usage events since payment mode began (only touched from the main thread).
    private var front: Front? = null
    private var scannedTo = 0L
    private var scanSession = Long.MIN_VALUE

    /**
     * The app most recently brought to the front since shortly before payment mode began [since], via usage access;
     * null if unknown. Only new events are read each time, so it keeps working however long the payment app stays
     * open, and after the island restarts mid-way (the old version only looked 20 s back, and lost track for good
     * once you had been in another app longer than that).
     */
    private fun front(ctx: Context, since: Long): Front? = try {
        val now = System.currentTimeMillis()
        if (scanSession != since) {
            scanSession = since
            front = null
            scannedTo = (if (since > 0) since else now) - 60_000
        }
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        // A little overlap: an event can be logged just after the last read with a timestamp just before it.
        val events = usm.queryEvents(scannedTo - 5_000, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= (front?.at ?: Long.MIN_VALUE)) {
                front = Front(e.packageName, e.timeStamp)
            }
        }
        scannedTo = now
        front
    } catch (_: Exception) { null }

    /** Called every ~1.5 s by IslandService while paused. */
    fun check(ctx: Context) {
        if (!active(ctx)) return
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) { exit(ctx); return }
        val since = Prefs.get(ctx).getLong(Prefs.PAY_SINCE, 0L)
        val f = front(ctx, since) ?: return
        if (isPaymentApp(ctx, f.pkg)) return
        // Another app has been in front for a moment (not just passing through), and you switched to it after payment
        // mode began — a payment app's pop-up over another app can start it, and that app isn't a reason to stop.
        if (f.at >= since - 2_000 && System.currentTimeMillis() - f.at >= 1_500) exit(ctx)
    }
}
