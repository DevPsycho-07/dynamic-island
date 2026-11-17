package com.rsv.dynamicisland

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.DocumentsContract
import android.util.Log
import com.rsv.dynamicisland.pet.PetEngine
import com.rsv.dynamicisland.pet.PetKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Water / pet sync with the Windows island through stock TwinDesk's "Shared folders":
 * the laptop's %USERPROFILE%\MacShare shows up here as a DocumentsProvider
 * (authority com.twindesk.android.pcshare, root "pc") only while TwinDesk is connected.
 *
 * We write phone.json and read + merge pc.json in the folder the user picked (DynamicIsland).
 * Every call may fail (laptop not connected, grant lost) - all of it is caught; status goes
 * to prefs for the settings card. All file I/O runs on one background thread.
 */
object Sync {
    private const val TAG = "Sync"
    const val AUTHORITY = "com.twindesk.android.pcshare"
    const val ROOT_ID = "pc"
    private const val PC_FILE = "pc.json"
    private const val PHONE_FILE = "phone.json"

    const val STATE_NONE = "none"
    const val STATE_OK = "ok"
    const val STATE_OFFLINE = "offline"
    const val STATE_LOST = "lost"
    const val STATE_WRITE = "write"

    /** Synced settings, in merge order (Pet before Cosmetic). Keys are the wire names. */
    val KEYS = listOf("GoalMl", "AutoGoal", "WeightKg", "ActivityMin", "CupMl", "WakeHour", "SleepHour", "Reminders", "Pet", "Cosmetic", "Cups")

    private val thread by lazy { HandlerThread("island-sync").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var app: Context? = null
    private var securityFails = 0
    private val job = Runnable { app?.let { runSync(it) } }

    fun tree(p: SharedPreferences): Uri? = p.getString(Prefs.SYNC_TREE, null)?.let { Uri.parse(it) }

    fun initialPickerUri(): Uri? = try { DocumentsContract.buildRootUri(AUTHORITY, ROOT_ID) } catch (_: Exception) { null }

    /** Debounced (3 s) — for local changes. */
    fun requestSoon(ctx: Context) {
        app = ctx.applicationContext
        if (tree(Prefs.get(ctx)) == null) return
        handler.removeCallbacks(job)
        handler.postDelayed(job, 3000)
    }

    /** Right away — screen on / unlock / the 2-minute tick / "Sync now". */
    fun requestNow(ctx: Context) {
        app = ctx.applicationContext
        if (tree(Prefs.get(ctx)) == null) return
        handler.removeCallbacks(job)
        handler.post(job)
    }

    /** Called after the user picks the TwinDesk folder. */
    fun setTree(ctx: Context, uri: Uri): Boolean {
        val ok = try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "persist grant failed", e)
            false
        }
        securityFails = 0
        Prefs.get(ctx).edit().putString(Prefs.SYNC_TREE, uri.toString()).putString(Prefs.SYNC_STATE, STATE_NONE).apply()
        requestNow(ctx)
        return ok
    }

    // ---- settings timestamps -------------------------------------------------------------------

    private fun tsMap(p: SharedPreferences): JSONObject =
        try { JSONObject(p.getString(Prefs.SYNC_TS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun wireFor(prefKey: String): String? = when {
        prefKey == Prefs.GOAL -> "GoalMl"
        prefKey == Prefs.GOAL_AUTO -> "AutoGoal"
        prefKey == Prefs.WEIGHT -> "WeightKg"
        prefKey == Prefs.ACTIVITY -> "ActivityMin"
        prefKey == Prefs.CUP -> "CupMl"
        prefKey == Prefs.WAKE -> "WakeHour"
        prefKey == Prefs.SLEEP -> "SleepHour"
        prefKey == Prefs.WATER_ON -> "Reminders"
        prefKey == Prefs.PET_KIND -> "Pet"
        prefKey.startsWith(Prefs.PET_COS) -> "Cosmetic"
        prefKey == Prefs.CUPS -> "Cups"
        else -> null
    }

    /** Same defaults as the Windows app. A setting still at its default never overrides the other device. */
    private val DEFAULTS = mapOf(
        "GoalMl" to "2000", "AutoGoal" to "0", "WeightKg" to "0", "ActivityMin" to "30", "CupMl" to "250",
        "WakeHour" to "8", "SleepHour" to "23", "Reminders" to "1", "Pet" to "Axolotl", "Cosmetic" to "0",
        "Cups" to Water.DEFAULT_CUPS,
    )

    private fun cupsKey(json: String): String = try {
        val a = JSONArray(json)
        (0 until a.length()).joinToString("|") { i ->
            val o = a.optJSONObject(i)
            "${o?.optString("name", "")?.trim()?.lowercase()}=${o?.optInt("ml", 0)}"
        }
    } catch (_: Exception) { json.trim() }

    private fun isDefault(wire: String, v: String): Boolean {
        val d = DEFAULTS[wire] ?: return false
        return if (wire == "Cups") cupsKey(v) == cupsKey(d) else v.trim().equals(d, ignoreCase = true)
    }

    /**
     * Stamp rule (matches Windows): default value -> ts stays 0; a non-default value with ts 0
     * (customised before sync existed) -> ts = now. Later local edits are stamped in [stamp].
     */
    private fun normalizeTs(ctx: Context) {
        val p = Prefs.get(ctx)
        val ts = tsMap(p)
        var dirty = false
        val now = System.currentTimeMillis()
        for (k in KEYS) {
            if (ts.optLong(k, 0L) == 0L && !isDefault(k, value(ctx, k))) {
                ts.put(k, now)
                dirty = true
            }
        }
        if (dirty) p.edit().putString(Prefs.SYNC_TS, ts.toString()).commit()
    }

    /** The user changed a setting here: stamp it so it wins over older laptop values. */
    fun stamp(ctx: Context, prefKey: String) {
        val wire = wireFor(prefKey) ?: return
        val p = Prefs.get(ctx)
        val ts = tsMap(p).put(wire, System.currentTimeMillis())
        p.edit().putString(Prefs.SYNC_TS, ts.toString()).apply()
        Water.invalidate()
        requestSoon(ctx)
    }

    private fun value(ctx: Context, wire: String): String {
        val p = Prefs.get(ctx)
        return when (wire) {
            "GoalMl" -> p.getInt(Prefs.GOAL, 2000).toString()
            "AutoGoal" -> if (p.getBoolean(Prefs.GOAL_AUTO, false)) "1" else "0"
            "WeightKg" -> p.getInt(Prefs.WEIGHT, 0).toString()
            "ActivityMin" -> p.getInt(Prefs.ACTIVITY, 30).toString()
            "CupMl" -> Water.cup(p).toString()
            "WakeHour" -> (Water.wake(p) / 60).toString()
            "SleepHour" -> (Water.sleep(p) / 60).toString()
            "Reminders" -> if (Water.enabled(p)) "1" else "0"
            "Pet" -> PetEngine.kind(p).title
            // Index into [none, #1, #2, #3] - same order as the cosmetic unlock list.
            "Cosmetic" -> (p.getInt(Prefs.PET_COS + PetEngine.kind(p).name, -1).coerceIn(-1, 2) + 1).toString()
            "Cups" -> p.getString(Prefs.CUPS, Water.DEFAULT_CUPS) ?: Water.DEFAULT_CUPS
            else -> ""
        }
    }

    /** Apply a laptop value. Returns false if it wasn't valid. */
    private fun apply(ctx: Context, wire: String, v: String): Boolean {
        val p = Prefs.get(ctx)
        val e = p.edit()
        val n = v.trim().toIntOrNull()
        val flag = v.trim() == "1" || v.trim().equals("true", ignoreCase = true)
        when (wire) {
            "GoalMl" -> e.putInt(Prefs.GOAL, n?.takeIf { it in 500..10000 } ?: return false)
            "AutoGoal" -> e.putBoolean(Prefs.GOAL_AUTO, flag)
            "WeightKg" -> e.putInt(Prefs.WEIGHT, n?.takeIf { it == 0 || it in 20..300 } ?: return false)
            "ActivityMin" -> e.putInt(Prefs.ACTIVITY, n?.takeIf { it in 0..600 } ?: return false)
            "CupMl" -> e.putInt(Prefs.CUP, n?.takeIf { it in 10..5000 } ?: return false)
            "WakeHour" -> e.putInt(Prefs.WAKE, (n?.takeIf { it in 0..23 } ?: return false) * 60)
            "SleepHour" -> e.putInt(Prefs.SLEEP, (n?.takeIf { it in 0..23 } ?: return false) * 60)
            "Reminders" -> e.putBoolean(Prefs.WATER_ON, flag)
            "Pet" -> {
                val k = PetKind.entries.firstOrNull { it.title.equals(v.trim(), true) || it.name.equals(v.trim(), true) } ?: return false
                e.putString(Prefs.PET_KIND, k.name)
            }
            "Cosmetic" -> {
                // Pet may have just changed in this same merge: commit it first.
                e.commit()
                val kind = PetEngine.kind(p)
                val idx = n?.let { it - 1 } ?: kind.cosmetics.indexOfFirst { it.equals(v.trim(), true) }
                return p.edit().putInt(Prefs.PET_COS + kind.name, idx.coerceIn(-1, 2)).commit()
            }
            "Cups" -> {
                try { JSONArray(v) } catch (_: Exception) { return false }
                e.putString(Prefs.CUPS, v)
            }
            else -> return false
        }
        return e.commit()
    }

    // ---- wire format ---------------------------------------------------------------------------

    fun exportJson(ctx: Context): String {
        normalizeTs(ctx)
        val o = Water.exportData(ctx)
        o.put("schema", 1)
        o.put("device", Water.deviceId(ctx))
        val ts = tsMap(Prefs.get(ctx))
        val s = JSONObject()
        for (k in KEYS) s.put(k, JSONObject().put("v", value(ctx, k)).put("ts", ts.optLong(k, 0L)))
        o.put("settings", s)
        return o.toString()
    }

    /** Merge the laptop's pc.json. Returns true if anything changed here. */
    fun merge(ctx: Context, remote: JSONObject): Boolean {
        if (remote.optInt("schema", 0) != 1) return false
        if (remote.optString("device", "") == Water.deviceId(ctx)) return false
        normalizeTs(ctx)
        var changed = Water.mergeData(ctx, remote)
        val settings = remote.optJSONObject("settings")
        if (settings != null) {
            val p = Prefs.get(ctx)
            val ts = tsMap(p)
            var settingsChanged = false
            for (k in KEYS) {
                // Windows writes the same keys; match case-insensitively to be safe.
                val rk = settings.keys().asSequence().firstOrNull { it.equals(k, ignoreCase = true) } ?: continue
                val r = settings.optJSONObject(rk) ?: continue
                val v = r.opt("v")?.toString() ?: continue
                val rts = r.optLong("ts", 0L)
                if (rts > ts.optLong(k, 0L) && v != value(ctx, k) && apply(ctx, k, v)) {
                    ts.put(k, rts)
                    settingsChanged = true
                }
            }
            if (settingsChanged) {
                p.edit().putString(Prefs.SYNC_TS, ts.toString()).commit()
                Water.invalidate()
                Water.scheduleNext(ctx)
                changed = true
            }
        }
        return changed
    }

    // ---- transport -----------------------------------------------------------------------------

    private fun runSync(ctx: Context) {
        val p = Prefs.get(ctx)
        val tree = tree(p) ?: return
        try {
            val cr = ctx.contentResolver
            val granted = cr.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
            if (!granted) {
                status(p, STATE_LOST)
                return
            }
            val folderId = DocumentsContract.getTreeDocumentId(tree)
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
            var pcId: String? = null
            var phoneId: String? = null
            val c = cr.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            ) ?: throw IOException("TwinDesk folder not reachable")
            c.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    if (name.equals(PC_FILE, ignoreCase = true)) pcId = it.getString(0)
                    if (name.equals(PHONE_FILE, ignoreCase = true)) phoneId = it.getString(0)
                }
            }

            // 1. Read + merge the laptop's state.
            pcId?.let { id ->
                val text = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    ?.use { s -> s.readBytes().toString(Charsets.UTF_8) }
                if (!text.isNullOrBlank()) {
                    val remote = try { JSONObject(text.trim().removePrefix("﻿")) } catch (_: Exception) { null }
                    if (remote != null && merge(ctx, remote)) {
                        p.edit().putLong(Prefs.SYNC_CHANGED_AT, System.currentTimeMillis()).apply()
                    }
                }
            }

            securityFails = 0

            // 2. Write ours. A failure here is a write problem, not "laptop not connected".
            try {
                val bytes = exportJson(ctx).toByteArray(Charsets.UTF_8)
                val target = phoneId?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                    ?: DocumentsContract.createDocument(cr, folder, "application/json", PHONE_FILE)
                    ?: throw IOException("Couldn't create phone.json")
                write(ctx, target, bytes)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't write phone.json", e)
                status(p, STATE_WRITE)
                return
            }
            p.edit().putString(Prefs.SYNC_STATE, STATE_OK).putLong(Prefs.SYNC_AT, System.currentTimeMillis()).apply()
        } catch (e: SecurityException) {
            // A vanished root can also look like this; only call it "lost" if it keeps happening.
            Log.w(TAG, "permission denied", e)
            securityFails++
            status(p, if (securityFails >= 3) STATE_LOST else STATE_OFFLINE)
        } catch (e: Exception) {
            Log.i(TAG, "laptop not reachable: ${e.message}")
            status(p, STATE_OFFLINE)
        }
    }

    /**
     * Overwrite phone.json in place. Stock TwinDesk can't open "w"/"wt" (it calls
     * File.createTempFile("w", ...) and the 1-char prefix throws), but "rw" copies the file
     * and uploads it when we close the descriptor. So: open "rw", truncate, write, truncate
     * to length, close.
     */
    private fun write(ctx: Context, target: Uri, bytes: ByteArray) {
        val pfd = ctx.contentResolver.openFileDescriptor(target, "rw") ?: throw IOException("no file descriptor")
        pfd.use { fd ->
            FileOutputStream(fd.fileDescriptor).channel.use { ch ->
                ch.truncate(0)
                ch.position(0)
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.truncate(bytes.size.toLong())
                try { ch.force(true) } catch (_: Exception) { }
            }
        }
    }

    private fun status(p: SharedPreferences, state: String) {
        if (p.getString(Prefs.SYNC_STATE, STATE_NONE) != state) p.edit().putString(Prefs.SYNC_STATE, state).apply()
    }
}
