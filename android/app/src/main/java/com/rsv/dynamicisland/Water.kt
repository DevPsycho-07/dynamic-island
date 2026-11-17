package com.rsv.dynamicisland

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import com.rsv.dynamicisland.pet.PetEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TreeMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the pet feels. Same five moods on Windows. */
enum class Mood { IDLE, HAPPY, THIRSTY, VERY_THIRSTY, SLEEPING }

/** Drink types and how much they count towards hydration. [label] is also the wire name. */
enum class Drink(val label: String, val emoji: String, val factor: Float, val caffeinePerMl: Float) {
    WATER("Water", "💧", 1.0f, 0f),
    TEA("Tea", "🍵", 0.9f, 0.19f),
    COFFEE("Coffee", "☕", 0.8f, 0.38f),
    JUICE("Juice", "🧃", 0.95f, 0f),
    MILK("Milk", "🥛", 0.95f, 0f),
    SODA("Soda", "🥤", 0.85f, 0.1f);

    companion object {
        fun parse(s: String?): Drink = entries.firstOrNull { it.label.equals(s?.trim(), ignoreCase = true) } ?: WATER
    }
}

enum class Achievement(val title: String, val emoji: String, val how: String) {
    FIRST_SIP("First sip", "💧", "Log your first drink"),
    GOAL_GETTER("Goal getter", "🎯", "Hit your daily goal"),
    STREAK_3("3-day streak", "🔥", "3 days in a row at 80%+"),
    STREAK_7("7-day streak", "🔥", "A full week at 80%+"),
    STREAK_30("30-day streak", "🏆", "A whole month at 80%+"),
    EARLY_BIRD("Early bird", "🌅", "A drink before 9 am"),
    CLUB_10L("10 L club", "🪣", "10 litres in total"),
    CLUB_50L("50 L club", "🌊", "50 litres in total"),
    VARIETY("Variety", "🌈", "3 drink types in one day"),
}

/** One live drink, as the UI sees it. */
class Entry(val id: String, val at: Long, val ml: Int, val eff: Int, val drink: Drink)

/**
 * Hydration, event-sourced (identical rules on Windows so both compute the same streaks):
 *
 * Stored: drinks {id, time, ml, effective, type, device}, a deleted-id set (Undo never removes a
 * drink, it tombstones it), dayGoals {date: goal used that day}. Everything else - totals,
 * bloom days, streak, freezes, stage, cosmetics, achievements - is recomputed from the live
 * drinks grouped by local date.
 *
 * Reminders are pace-based over the waking day; reminder state (last alert, snooze, ignored)
 * stays local in prefs.
 */
object Water {
    const val NOTIF_ID = 2001
    private const val MIN = 60_000L
    private const val REQ_REMIND = 11
    private const val KEEP_DAYS = 400L
    val STAGE_AT = intArrayOf(0, 3, 10, 25)
    const val DEFAULT_CUPS = "[{\"name\":\"Sip\",\"ml\":100},{\"name\":\"Glass\",\"ml\":250},{\"name\":\"Mug\",\"ml\":300},{\"name\":\"Bottle\",\"ml\":500}]"
    private val ISO_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    // ---- settings ------------------------------------------------------------------------------

    fun enabled(p: SharedPreferences) = p.getBoolean(Prefs.WATER_ON, true)
    fun cup(p: SharedPreferences) = p.getInt(Prefs.CUP, 250)
    fun wake(p: SharedPreferences) = p.getInt(Prefs.WAKE, 8 * 60)
    fun sleep(p: SharedPreferences) = p.getInt(Prefs.SLEEP, 23 * 60)
    fun drinkType(p: SharedPreferences): Drink =
        Drink.entries.getOrElse(p.getInt(Prefs.DRINK_TYPE, 0)) { Drink.WATER }

    /** weight x 33 ml + 350 ml per 30 min of activity, +10% on a hot day. */
    fun autoGoal(p: SharedPreferences): Int {
        val kg = p.getInt(Prefs.WEIGHT, 0).takeIf { it > 0 } ?: 65 // 0 = not set
        var g = kg * 33f + 350f * p.getInt(Prefs.ACTIVITY, 30) / 30f
        if (p.getBoolean(Prefs.HOT, false)) g *= 1.1f
        return ((g / 50f).roundToInt() * 50).coerceIn(1000, 6000)
    }

    fun goal(p: SharedPreferences): Int =
        if (p.getBoolean(Prefs.GOAL_AUTO, false)) autoGoal(p) else p.getInt(Prefs.GOAL, 2000)

    /** Quick-add cups (synced with the laptop). Falls back to 100 / 250 / 500. */
    fun cups(p: SharedPreferences): List<Pair<String, Int>> {
        val list = try {
            val a = JSONArray(p.getString(Prefs.CUPS, DEFAULT_CUPS) ?: DEFAULT_CUPS)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val ml = o.optInt("ml", 0)
                if (ml in 10..5000) o.optString("name", "${ml} ml") to ml else null
            }
        } catch (_: Exception) { emptyList() }
        return list.ifEmpty { listOf("Sip" to 100, "Glass" to 250, "Mug" to 300, "Bottle" to 500) }
    }

    fun deviceId(ctx: Context): String {
        val p = Prefs.get(ctx)
        p.getString(Prefs.DEVICE_ID, null)?.let { return it }
        val id = "phone-" + UUID.randomUUID().toString().replace("-", "").take(8)
        p.edit().putString(Prefs.DEVICE_ID, id).apply()
        return id
    }

    // ---- event store -----------------------------------------------------------------------------

    private class Rec(
        val id: String, val time: String, val ml: Int, val eff: Int, val type: Drink, val device: String,
        val at: Long, val day: String, val hour: Int,
    )

    private val lock = Any()
    private var loaded = false
    private val drinks = LinkedHashMap<String, Rec>()
    private val deleted = LinkedHashSet<String>()
    private val dayGoals = TreeMap<String, Int>()
    @Volatile private var cache: Stats? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "water.json")

    private fun rec(id: String, time: String, ml: Int, eff: Int, type: Drink, device: String): Rec? = try {
        val odt = OffsetDateTime.parse(time)
        val z = odt.atZoneSameInstant(ZoneId.systemDefault())
        Rec(id, time, ml, eff, type, device, odt.toInstant().toEpochMilli(), z.toLocalDate().toString(), z.hour)
    } catch (_: Exception) { null }

    private fun recFrom(o: JSONObject): Rec? {
        val id = o.optString("id", "")
        val ml = o.optInt("ml", 0)
        if (id.isEmpty() || ml <= 0 || ml > 5000) return null
        val type = Drink.parse(o.optString("type", "Water"))
        val eff = o.optInt("effective", 0).takeIf { it > 0 } ?: (ml * type.factor).roundToInt()
        return rec(id, o.optString("time", ""), ml, eff, type, o.optString("device", ""))
    }

    private fun Rec.toJson() = JSONObject()
        .put("id", id).put("time", time).put("ml", ml).put("effective", eff).put("type", type.label).put("device", device)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (f.exists()) {
            try {
                val o = JSONObject(f.readText())
                o.optJSONArray("drinks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { recFrom(it) }?.let { drinks[it.id] = it } }
                o.optJSONArray("deleted")?.let { a -> for (i in 0 until a.length()) deleted.add(a.optString(i)) }
                o.optJSONObject("dayGoals")?.let { g -> g.keys().forEach { k -> dayGoals[k] = g.optInt(k) } }
            } catch (_: Exception) {
            }
        } else {
            migrateV2(ctx)
            save(ctx)
        }
        prune()
    }

    /** v2 kept today's entries + per-day totals in prefs. Turn them into drinks once. */
    private fun migrateV2(ctx: Context) {
        val p = Prefs.get(ctx)
        val dev = deviceId(ctx)
        try {
            val hist = JSONObject(p.getString(Prefs.HISTORY, "{}") ?: "{}")
            hist.keys().forEach { day ->
                val a = hist.optJSONArray(day) ?: return@forEach
                val eff = a.optInt(0, 0)
                val g = a.optInt(1, 0)
                if (g > 0) dayGoals[day] = g
                if (eff in 1..5000) {
                    val t = LocalDateTime.of(LocalDate.parse(day), LocalTime.NOON).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    val id = newId()
                    rec(id, t.format(ISO_OUT), eff, eff, Drink.WATER, dev)?.let { drinks[id] = it }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val s = p.getString(Prefs.ENTRIES, "") ?: ""
            if (s.isNotEmpty() && p.getString(Prefs.TODAY_KEY, null) == LocalDate.now().toString()) {
                for (e in s.split('|')) {
                    val f = e.split(':')
                    if (f.size != 3) continue
                    val at = f[0].toLong()
                    val ml = f[1].toInt()
                    val type = Drink.entries.getOrElse(f[2].toInt()) { Drink.WATER }
                    val t = OffsetDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
                    val id = newId()
                    rec(id, t.format(ISO_OUT), ml, (ml * type.factor).roundToInt(), type, dev)?.let { drinks[id] = it }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * MIN
        drinks.values.removeAll { it.at < cutoff }
        deleted.retainAll(drinks.keys)
        val oldDay = LocalDate.now().minusDays(KEEP_DAYS).toString()
        dayGoals.headMap(oldDay).clear()
    }

    private fun save(ctx: Context) {
        try {
            val o = JSONObject()
                .put("drinks", JSONArray().apply { drinks.values.forEach { put(it.toJson()) } })
                .put("deleted", JSONArray().apply { deleted.forEach { put(it) } })
                .put("dayGoals", JSONObject().apply { dayGoals.forEach { (k, v) -> put(k, v) } })
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "")

    fun ensureInit(ctx: Context) {
        deviceId(ctx)
        synchronized(lock) { load(ctx) }
    }

    // ---- derived ---------------------------------------------------------------------------------

    class Stats(
        val day: String,
        val goal: Int,
        val todayMl: Int,
        val total: Long,
        val blooms: Int,
        val streakBase: Int,
        val todayCounted: Boolean,
        val best: Int,
        val freezes: Int,
        val dayTotals: Map<String, Int>,
        val today: List<Entry>,
        val achievements: Set<Achievement>,
        val lastDrinkAt: Long,
    ) {
        val streak: Int get() = streakBase + if (todayCounted) 1 else 0
        val stage: Int get() = stage(blooms)
    }

    fun stats(ctx: Context): Stats {
        val today = LocalDate.now().toString()
        val goal = goal(Prefs.get(ctx))
        cache?.let { if (it.day == today && it.goal == goal) return it }
        synchronized(lock) {
            load(ctx)
            val s = compute(today, goal)
            cache = s
            return s
        }
    }

    private fun compute(today: String, goal: Int): Stats {
        val live = drinks.values.filter { it.id !in deleted }
        val totals = HashMap<String, Int>()
        val types = HashMap<String, MutableSet<Drink>>()
        var total = 0L
        var early = false
        var lastAt = 0L
        for (r in live) {
            totals[r.day] = (totals[r.day] ?: 0) + r.eff
            types.getOrPut(r.day) { HashSet() }.add(r.type)
            total += r.eff
            if (r.hour < 9) early = true
            lastAt = max(lastAt, r.at)
        }
        var blooms = 0
        var streak = 0
        var best = 0
        var freezes = 0
        val first = live.minOfOrNull { it.day }
        val todayD = LocalDate.parse(today)
        if (first != null) {
            var d = LocalDate.parse(first)
            while (d.isBefore(todayD)) {
                val k = d.toString()
                val ml = totals[k] ?: 0
                val g = dayGoals[k] ?: goal
                if (ml >= g) blooms++
                if (ml >= 0.8 * g) {
                    streak++
                    best = max(best, streak)
                    if (streak % 7 == 0 && freezes < 2) freezes++
                } else if (freezes > 0) {
                    freezes-- // a freeze saves the streak
                } else {
                    streak = 0
                }
                d = d.plusDays(1)
            }
        }
        val todayMl = totals[today] ?: 0
        if (todayMl >= goal) blooms++
        val counted = todayMl >= 0.8 * goal
        val ach = buildSet {
            if (live.isNotEmpty()) add(Achievement.FIRST_SIP)
            if (blooms > 0) add(Achievement.GOAL_GETTER)
            if (best >= 3) add(Achievement.STREAK_3)
            if (best >= 7) add(Achievement.STREAK_7)
            if (best >= 30) add(Achievement.STREAK_30)
            if (early) add(Achievement.EARLY_BIRD)
            if (total >= 10_000) add(Achievement.CLUB_10L)
            if (total >= 50_000) add(Achievement.CLUB_50L)
            if (types.values.any { it.size >= 3 }) add(Achievement.VARIETY)
        }
        val todayList = live.filter { it.day == today }.sortedBy { it.at }.map { Entry(it.id, it.at, it.ml, it.eff, it.type) }
        return Stats(today, goal, todayMl, total, blooms, streak, counted, best, freezes, totals, todayList, ach, lastAt)
    }

    fun todayMl(ctx: Context): Int = stats(ctx).todayMl
    fun progress(ctx: Context): Float =
        (todayMl(ctx).toFloat() / goal(Prefs.get(ctx)).coerceAtLeast(1)).coerceIn(0f, 1f)
    fun entries(ctx: Context): List<Entry> = stats(ctx).today
    fun caffeineToday(ctx: Context): Int = entries(ctx).sumOf { (it.ml * it.drink.caffeinePerMl).toDouble() }.roundToInt()
    fun lifetimeMl(ctx: Context): Long = stats(ctx).total
    fun blooms(ctx: Context): Int = stats(ctx).blooms
    fun freezes(ctx: Context): Int = stats(ctx).freezes
    fun streak(ctx: Context): Int = stats(ctx).streak
    fun bestStreak(ctx: Context): Int = stats(ctx).best
    fun achievements(ctx: Context): Set<Achievement> = stats(ctx).achievements
    fun lastDrinkAt(ctx: Context): Long = stats(ctx).lastDrinkAt

    /** Count of thresholds reached, minus one. */
    fun stage(blooms: Int): Int = (STAGE_AT.count { it <= blooms } - 1).coerceAtLeast(0)
    fun stage(ctx: Context): Int = stats(ctx).stage

    /** Blooms still needed for the next stage, or -1 at the final stage. */
    fun bloomsToNext(ctx: Context): Int {
        val s = stats(ctx)
        return if (s.stage >= STAGE_AT.size - 1) -1 else STAGE_AT[s.stage + 1] - s.blooms
    }

    /** Cosmetic unlocks [none, #1, #2, #3] = [always, 3 blooms, best streak 7, 20 blooms]. */
    fun cosmeticUnlocked(ctx: Context, i: Int): Boolean {
        val s = stats(ctx)
        val u = booleanArrayOf(true, s.blooms >= 3, s.best >= 7, s.blooms >= 20)
        return u.getOrElse(i + 1) { false }
    }

    fun cosmeticRule(i: Int) = when (i) {
        0 -> "3 blooms"
        1 -> "7-day streak"
        else -> "20 blooms"
    }

    /** Last 7 days (oldest first) as (date, effective ml, goal). Today is last. */
    fun last7(ctx: Context): List<Triple<String, Int, Int>> {
        val s = stats(ctx)
        val today = LocalDate.parse(s.day)
        return (6 downTo 0).map { back ->
            val k = today.minusDays(back.toLong()).toString()
            val g = if (back == 0) s.goal else synchronized(lock) { dayGoals[k] } ?: s.goal
            Triple(k, s.dayTotals[k] ?: 0, g)
        }
    }

    // ---- logging ---------------------------------------------------------------------------------

    fun drink(ctx: Context, ml: Int = cup(Prefs.get(ctx)), type: Drink = drinkType(Prefs.get(ctx))) {
        val p = Prefs.get(ctx)
        val before = stats(ctx)
        synchronized(lock) {
            load(ctx)
            val m = ml.coerceIn(10, 5000)
            val id = newId()
            val r = rec(id, OffsetDateTime.now().format(ISO_OUT), m, (m * type.factor).roundToInt(), type, deviceId(ctx)) ?: return
            drinks[id] = r
            dayGoals[r.day] = goal(p)
            save(ctx)
            cache = null
        }
        PetEngine.onDrink()
        announce(ctx, before, stats(ctx), local = true)
        p.edit().putLong(Prefs.SNOOZE_UNTIL, 0).putInt(Prefs.IGNORED, 0).putLong(Prefs.LAST_DRINK, System.currentTimeMillis()).apply()
        cancelReminder(ctx)
        scheduleNext(ctx)
        Sync.requestSoon(ctx)
    }

    /** Undo = tombstone the most recent of today's live drinks (never removed from the log). */
    fun undo(ctx: Context): Entry? {
        val before = stats(ctx)
        val last = before.today.maxByOrNull { it.at } ?: return null
        synchronized(lock) {
            deleted.add(last.id)
            save(ctx)
            cache = null
        }
        announce(ctx, before, stats(ctx), local = false)
        scheduleNext(ctx)
        Sync.requestSoon(ctx)
        return last
    }

    fun snooze(ctx: Context, minutes: Int = 10) {
        Prefs.get(ctx).edit().putLong(Prefs.SNOOZE_UNTIL, System.currentTimeMillis() + minutes * MIN).apply()
        cancelReminder(ctx)
        scheduleNext(ctx)
    }

    /**
     * Push derived changes out through prefs "signal" keys (the island + settings listen to
     * them). Peeks for goal / evolve / achievements only for drinks logged here.
     */
    private fun announce(ctx: Context, before: Stats, after: Stats, local: Boolean) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        val e = p.edit().putInt(Prefs.TODAY_ML, after.todayMl).putInt(Prefs.BLOOMS, after.blooms)
        if (local) {
            if (before.todayMl < after.goal && after.todayMl >= after.goal) {
                e.putLong(Prefs.GOAL_HIT_AT, now)
                PetEngine.onGoal()
            }
            if (after.stage > before.stage) {
                e.putLong(Prefs.EVOLVED_AT, now)
                PetEngine.onEvolve()
            }
        }
        val seen = (p.getString(Prefs.ACHIEVEMENTS, "") ?: "").split(',').toSet()
        val fresh = after.achievements.filter { it.name !in seen }
        if (fresh.isNotEmpty()) {
            e.putString(Prefs.ACHIEVEMENTS, (seen + fresh.map { it.name }).filter { it.isNotEmpty() }.joinToString(","))
            if (local) {
                e.putString(Prefs.ACH_LAST, fresh.last().name).putLong(Prefs.ACH_AT, now)
                PetEngine.onCelebrate()
            }
        }
        e.apply()
    }

    // ---- sync (wire format lives in Sync.kt) -----------------------------------------------------

    /** drinks + deleted + dayGoals in wire shape. */
    fun exportData(ctx: Context): JSONObject = synchronized(lock) {
        load(ctx)
        JSONObject()
            .put("drinks", JSONArray().apply { drinks.values.forEach { put(it.toJson()) } })
            .put("deleted", JSONArray().apply { deleted.forEach { put(it) } })
            .put("dayGoals", JSONObject().apply { dayGoals.forEach { (k, v) -> put(k, v) } })
    }

    /** Union in the laptop's drinks / tombstones / past day goals. Returns true if anything changed. */
    fun mergeData(ctx: Context, remote: JSONObject): Boolean {
        val before = stats(ctx)
        var changed = false
        synchronized(lock) {
            load(ctx)
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * MIN
            remote.optJSONArray("drinks")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i)?.let { recFrom(it) } ?: continue
                    if (r.at < cutoff || drinks.containsKey(r.id)) continue
                    drinks[r.id] = r
                    changed = true
                }
            }
            remote.optJSONArray("deleted")?.let { a ->
                for (i in 0 until a.length()) {
                    val id = a.optString(i, "")
                    if (id.isNotEmpty() && deleted.add(id)) changed = true
                }
            }
            val today = LocalDate.now().toString()
            remote.optJSONObject("dayGoals")?.let { g ->
                g.keys().forEach { k ->
                    val v = g.optInt(k, 0)
                    if (k != today && v > 0 && !dayGoals.containsKey(k)) {
                        dayGoals[k] = v
                        changed = true
                    }
                }
            }
            if (changed) {
                prune()
                save(ctx)
                cache = null
            }
        }
        if (changed) {
            announce(ctx, before, stats(ctx), local = false)
            scheduleNext(ctx)
        }
        return changed
    }

    /** A synced setting (e.g. the goal) changed underneath us. */
    fun invalidate() { cache = null }

    // ---- pace + mood -----------------------------------------------------------------------------

    /** [start, end] of the waking window containing (or just before) [t]. */
    private fun wakeWindow(p: SharedPreferences, t: Long): LongArray {
        val w = wake(p)
        val len = ((sleep(p) - w + 1440) % 1440).let { if (it == 0) 1440 else it }
        val c = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, w / 60)
            set(Calendar.MINUTE, w % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > t) c.add(Calendar.DAY_OF_MONTH, -1)
        val start = c.timeInMillis
        return longArrayOf(start, start + len * MIN)
    }

    fun asleep(p: SharedPreferences, t: Long = System.currentTimeMillis()): Boolean = t >= wakeWindow(p, t)[1]

    /** How much you "should" have had by now: goal x fraction of the waking day gone. */
    fun expected(ctx: Context, t: Long = System.currentTimeMillis()): Int {
        val p = Prefs.get(ctx)
        val win = wakeWindow(p, t)
        val frac = ((t - win[0]).toFloat() / (win[1] - win[0])).coerceIn(0f, 1f)
        return (goal(p) * frac).roundToInt()
    }

    fun paceDelta(ctx: Context): Int = todayMl(ctx) - expected(ctx)

    fun paceLine(ctx: Context): String {
        val p = Prefs.get(ctx)
        val eff = todayMl(ctx)
        if (eff >= goal(p)) return "Goal reached 🎉"
        if (asleep(p)) return "Sleep hours — no reminders"
        val d = paceDelta(ctx)
        return when {
            d >= 100 -> "Ahead of pace by $d ml"
            d > -cup(p) * 0.3f -> "Right on pace"
            else -> "Behind pace by ${-d} ml"
        }
    }

    fun mood(ctx: Context, t: Long = System.currentTimeMillis()): Mood {
        val p = Prefs.get(ctx)
        if (asleep(p, t)) return Mood.SLEEPING
        val eff = todayMl(ctx)
        if (eff >= goal(p) || t - lastDrinkAt(ctx) in 0 until 10 * MIN) return Mood.HAPPY
        val behind = expected(ctx, t) - eff
        val cup = cup(p)
        return when {
            behind < cup * 0.8f -> Mood.IDLE
            behind < cup * 2.5f -> Mood.THIRSTY
            else -> Mood.VERY_THIRSTY
        }
    }

    fun moodLine(m: Mood): String = when (m) {
        Mood.HAPPY -> "Ahh, refreshing! 💦"
        Mood.IDLE -> "Doing fine. Keep sipping."
        Mood.THIRSTY -> "I'm thirsty! 💧"
        Mood.VERY_THIRSTY -> "So thirsty… 🥺"
        Mood.SLEEPING -> "Zzz… sleep hours"
    }

    private val askLines = listOf(
        "I'm thirsty! 💧", "Sip time? 🥤", "Water break, please!", "You're a bit behind — one glass?"
    )

    fun askLine(m: Mood): String = if (m == Mood.VERY_THIRSTY) "So thirsty… 🥺" else askLines.random()

    fun minutesSinceDrink(ctx: Context): Long {
        val last = lastDrinkAt(ctx)
        return if (last <= 0) -1 else (System.currentTimeMillis() - last) / MIN
    }

    fun liters(ml: Int): String = "%.1f".format(ml / 1000f)

    // ---- reminders -------------------------------------------------------------------------------

    private fun gapMs(ignored: Int): Long =
        (if (ignored < 2) 30L else min(120L, 30L shl (ignored - 1))) * MIN

    fun isDue(ctx: Context, t: Long = System.currentTimeMillis()): Boolean {
        val p = Prefs.get(ctx)
        if (!enabled(p) || asleep(p, t)) return false
        val eff = todayMl(ctx)
        if (eff >= goal(p)) return false
        if (expected(ctx, t) - eff < cup(p) * 0.8f) return false
        if (t - lastDrinkAt(ctx) < 20 * MIN) return false
        if (t < p.getLong(Prefs.SNOOZE_UNTIL, 0)) return false
        val lastRem = p.getLong(Prefs.LAST_REMINDER, 0)
        return lastRem <= 0 || t >= lastRem + gapMs(p.getInt(Prefs.IGNORED, 0))
    }

    fun nextReminderAt(ctx: Context, t: Long = System.currentTimeMillis()): Long {
        val p = Prefs.get(ctx)
        val goal = goal(p).coerceAtLeast(1)
        val eff = todayMl(ctx)
        val win = wakeWindow(p, t)
        val nextWake = win[0] + 24 * 60 * MIN
        if (eff >= goal || t >= win[1]) return nextWake + 45 * MIN
        val target = eff + cup(p) * 0.8f
        val tPace = win[0] + ((target / goal).coerceAtMost(1f) * (win[1] - win[0])).toLong()
        var at = maxOf(tPace, lastDrinkAt(ctx) + 20 * MIN, p.getLong(Prefs.SNOOZE_UNTIL, 0), t + 5_000)
        val lastRem = p.getLong(Prefs.LAST_REMINDER, 0)
        if (lastRem > 0) at = max(at, lastRem + gapMs(p.getInt(Prefs.IGNORED, 0)))
        if (at >= win[1]) at = nextWake + 45 * MIN
        return at
    }

    private fun remindIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, REQ_REMIND,
        Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Inexact alarm: still fires in Doze (a few minutes late at worst), which is fine for water. */
    fun scheduleNext(ctx: Context) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = remindIntent(ctx)
            if (!enabled(Prefs.get(ctx))) {
                am.cancel(pi)
                return
            }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextReminderAt(ctx), pi)
        } catch (_: Exception) {
        }
    }

    fun onReminderAlarm(ctx: Context) {
        if (isDue(ctx)) {
            val p = Prefs.get(ctx)
            p.edit().putInt(Prefs.IGNORED, p.getInt(Prefs.IGNORED, 0) + 1)
                .putLong(Prefs.LAST_REMINDER, System.currentTimeMillis()).apply()
            postReminder(ctx)
        }
        scheduleNext(ctx)
    }

    private fun postReminder(ctx: Context) {
        val p = Prefs.get(ctx)
        val m = mood(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat_drop)
        val n = Notification.Builder(ctx, App.CH_WATER)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setColor(0xFF4FC3F7.toInt())
            .setContentTitle(askLine(m))
            .setContentText("${liters(todayMl(ctx))} / ${liters(goal(p))} L · ${paceLine(ctx)}")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(icon, "Drink ${cup(p)} ml", ActionReceiver.pending(ctx, ActionReceiver.DRINK, 21)).build()
            )
            .addAction(
                Notification.Action.Builder(icon, "Snooze 10 min", ActionReceiver.pending(ctx, ActionReceiver.SNOOZE, 22)).build()
            )
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }

    fun cancelReminder(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (_: Exception) { }
    }
}
