package com.rsv.dynamicisland

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.rsv.dynamicisland.pet.PetEngine
import com.rsv.dynamicisland.pet.PetKind
import com.rsv.dynamicisland.pet.PetView
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

/**
 * The in-app screens, organised like the Windows settings window: a custom bottom bar with
 * Home / Water & pet / Island / Sync / Setup. Each tab is its own scrolling page built from the
 * same blocks as Windows (caps section captions, rounded cards with rows, iOS toggles, -/+
 * steppers, pill buttons). Built in code, no XML layouts.
 */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Number) = (v.toFloat() * d).toInt()

    private val tabNames = listOf("Home", "Water & pet", "Island", "Sync", "Setup")
    private val pages = ArrayList<ScrollView>()
    private lateinit var nav: NavBar
    private var tab = 0

    // Home
    private lateinit var pet: PetView
    private lateinit var litres: TextView
    private lateinit var paceText: TextView
    private lateinit var statusText: TextView
    private lateinit var nextText: TextView
    private lateinit var snack: LinearLayout
    private lateinit var snackText: TextView
    private lateinit var defaultCupBtn: TextView
    private val quickButtons = ArrayList<TextView>()
    private val typeChips = ArrayList<TextView>()
    private lateinit var timerText: TextView
    private lateinit var timerStats: TextView
    private lateinit var pauseBtn: TextView

    // Water & pet
    private val previews = HashMap<PetKind, PetView>()
    private val previewStage = HashMap<PetKind, TextView>()
    private lateinit var stageText: TextView
    private val cosChips = ArrayList<TextView>()
    private val tiles = HashMap<String, Pair<TextView, TextView>>()
    private lateinit var stats: StatsView
    private val achChips = HashMap<Achievement, TextView>()
    private lateinit var goalHolder: LinearLayout
    private lateinit var cupsHolder: LinearLayout
    private var todayGoalText: TextView? = null

    // Island / Sync / Setup
    private lateinit var islandToggle: ToggleView
    private lateinit var islandNote: TextView
    private lateinit var syncStatus: TextView
    private lateinit var pickBtn: TextView
    private val permRows = ArrayList<PermRow>()
    private val reloaders = ArrayList<() -> Unit>()
    private val hideSnack = Runnable { snack.visibility = View.GONE }

    private class PermRow(val check: () -> Boolean, val button: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Water.ensureInit(this)
        tab = savedInstanceState?.getInt(KEY_TAB) ?: prefs.getInt(KEY_TAB, 0)

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(66) })
        for (build in listOf(::buildHome, ::buildWater, ::buildIsland, ::buildSync, ::buildSetup)) {
            val sv = page(build())
            pages += sv
            content.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        nav = NavBar(this, tabNames) { showTab(it) }
        root.addView(nav, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
        nav.select(tab, notify = false)
        showTab(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshAll()
        Sync.requestNow(this)
        if (prefs.getBoolean(Prefs.ISLAND_ON, false)) {
            val s = IslandService.running
            if (s == null) IslandService.start(this) else if (!s.hasWindow()) s.rebuildWindow()
        }
        ui.post(timerLoop)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        ui.removeCallbacks(timerLoop)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
    }

    @Deprecated("Activity result API is fine here; no AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val ok = Sync.setTree(this, uri)
        toast(if (ok) "Folder linked — syncing…" else "Linked, but Android didn't keep the permission. Try again.")
        refreshSync()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshHome()
        refreshWaterTab()
        refreshSync()
    }

    private fun showTab(i: Int) {
        tab = i
        for ((k, p) in pages.withIndex()) p.visibility = if (k == i) View.VISIBLE else View.GONE
        prefs.edit().putInt(KEY_TAB, i).apply()
    }

    private fun refreshAll() {
        refreshPerms()
        refreshHome()
        refreshWaterTab()
        refreshSync()
        islandToggle.set(prefs.getBoolean(Prefs.ISLAND_ON, false))
        reloaders.forEach { it() }
    }

    // =====================================================================================
    // Home
    // =====================================================================================

    private fun buildHome(): View {
        val col = column()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        pet = PetView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
            contentDescription = "Your water pet. Tap to poke."
        }
        hero.addView(pet)
        litres = tv("", 30, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        paceText = tv("", 14, Ui.DIM).apply { gravity = Gravity.CENTER }
        statusText = tv("", 13, Ui.ORANGE).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        hero.addView(litres); hero.addView(paceText); hero.addView(statusText)
        col.addView(hero)

        col.addView(section("Add a drink"))
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(6)) }
        // Every cup by name (like the laptop); the default one is blue. If the default isn't one of the cups,
        // it gets its own "+400 ml" button first.
        defaultCupBtn = pill("", Ui.ACCENT, Color.BLACK) { add(Water.cup(prefs)) }
        quick.addView(defaultCupBtn); quick.addView(space(8))
        for (i in 0 until 5) {
            val b = pill("", Ui.CHIP, Color.WHITE) { Water.cups(prefs).getOrNull(i)?.let { add(it.second) } }
            quickButtons += b
            quick.addView(b); quick.addView(space(8))
        }
        quick.addView(pill("Custom", Ui.CHIP, Color.WHITE) { customAmount() })
        val types = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), dp(12)) }
        for (t in Drink.entries) {
            val chip = pill("${t.emoji} ${t.label}", Ui.CHIP, Color.WHITE) {
                prefs.edit().putInt(Prefs.DRINK_TYPE, t.ordinal).apply()
                refreshHome()
            }
            typeChips += chip
            types.addView(chip); types.addView(space(6))
        }
        col.addView(card(hscroll(quick), hscroll(types)))

        snackText = tv("", 14, Color.WHITE).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
        snack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = rounded(0xFF1E2A30.toInt(), 14)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            visibility = View.GONE
            addView(snackText)
            addView(tv("UNDO", 14, Ui.ACCENT, bold = true).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener {
                    val e = Water.undo(this@MainActivity)
                    snack.visibility = View.GONE
                    if (e != null) toast("Removed ${e.ml} ml ${e.drink.label}")
                }
            })
        }
        col.addView(snack)
        nextText = tv("", 12, Ui.DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        col.addView(nextText)

        col.addView(section("Focus timer"))
        timerText = tv("", 15, Color.WHITE)
        timerStats = tv("", 12, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) }
        val timerHead = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            addView(timerText); addView(timerStats)
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        for (m in intArrayOf(5, 15, 25, 45)) {
            chips.addView(pill("$m min", if (m == 25) Ui.ORANGE else Ui.CHIP, if (m == 25) Color.BLACK else Color.WHITE) {
                IslandTimer.start(this, m); updateTimer()
            })
            chips.addView(space(6))
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(14)) }
        pauseBtn = pill("Pause", Ui.CHIP, Color.WHITE) {
            if (IslandTimer.paused(this)) IslandTimer.resume(this) else IslandTimer.pause(this)
            updateTimer()
        }
        controls.addView(pauseBtn); controls.addView(space(8))
        controls.addView(pill("+1 min", Ui.CHIP, Color.WHITE) { IslandTimer.add(this, 60_000); updateTimer() })
        controls.addView(space(8))
        controls.addView(pill("Cancel", 0xFF3A1A17.toInt(), Ui.RED) { IslandTimer.cancel(this); updateTimer() })
        col.addView(card(timerHead, hscroll(chips), controls))
        return col
    }

    private fun add(ml: Int) {
        val t = Water.drinkType(prefs)
        Water.drink(this, ml, t)
        snackText.text = "Added $ml ml ${t.emoji} ${t.label}"
        snack.visibility = View.VISIBLE
        ui.removeCallbacks(hideSnack)
        ui.postDelayed(hideSnack, 4500)
    }

    private fun customAmount() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "ml"
            setText(Water.cup(prefs).toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("How much did you drink?")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("Add") { _, _ ->
                val ml = input.text.toString().toIntOrNull()
                if (ml != null && ml in 10..3000) add(ml) else toast("Enter 10–3000 ml")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshHome() {
        if (!::litres.isInitialized) return
        val ml = Water.todayMl(this)
        val goal = Water.goal(prefs)
        litres.text = "${Water.liters(ml)} / ${Water.liters(goal)} L"
        paceText.text = "${Water.paceLine(this)}  ·  ${Water.moodLine(Water.mood(this))}"
        val kind = PetEngine.kind(prefs)
        statusText.text = "🔥 ${Water.streak(this)}-day streak   ❄️ ${Water.freezes(this)}   " +
            "🌸 ${Water.blooms(this)} goal days   · ${kind.stages[Water.stage(this)]}"
        pet.dancing = Prefs.on(prefs, Prefs.F_DANCE) && MediaHub.current?.playing == true
        val def = Water.cup(prefs)
        val cups = Water.cups(prefs)
        defaultCupBtn.text = "+$def ml"
        defaultCupBtn.visibility = if (cups.any { it.second == def }) View.GONE else View.VISIBLE
        for ((i, b) in quickButtons.withIndex()) {
            val cup = cups.getOrNull(i)
            b.visibility = if (cup == null) View.GONE else View.VISIBLE
            if (cup != null) {
                val isDefault = cup.second == def
                b.text = "${cup.first} ${cup.second}"
                b.background = pillBg(if (isDefault) Ui.ACCENT else Ui.CHIP)
                b.setTextColor(if (isDefault) Color.BLACK else Color.WHITE)
            }
        }
        val type = Water.drinkType(prefs)
        for ((i, chip) in typeChips.withIndex()) {
            val on = i == type.ordinal
            chip.background = pillBg(if (on) Ui.ACCENT else Ui.CHIP)
            chip.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
        nextText.text = when {
            !Water.enabled(prefs) -> "Reminders are off"
            ml >= goal -> "Goal done for today — no more reminders"
            Water.asleep(prefs) -> "Sleep hours — reminders resume when you wake"
            else -> "Next check around " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Water.nextReminderAt(this))) +
                (Water.caffeineToday(this).takeIf { it > 0 }?.let { "  ·  ☕ $it mg caffeine" } ?: "")
        }
    }

    private val timerLoop = object : Runnable {
        override fun run() {
            updateTimer()
            refreshSync()
            ui.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L + 10L)
        }
    }

    private fun updateTimer() {
        val paused = IslandTimer.paused(this)
        timerText.text = when {
            paused -> "⏸  ${IslandTimer.format(IslandTimer.remainingMs(this))} left (paused)"
            IslandTimer.running(this) -> "⏱  ${IslandTimer.format(IslandTimer.remainingMs(this))} left"
            else -> "No timer running"
        }
        timerStats.text = IslandTimer.statsLine(this) + "  ·  shows on the island, buzzes when done"
        pauseBtn.text = if (paused) "Resume" else "Pause"
        pauseBtn.visibility = if (IslandTimer.active(this)) View.VISIBLE else View.GONE
        pet.dancing = Prefs.on(prefs, Prefs.F_DANCE) && MediaHub.current?.playing == true
    }

    // =====================================================================================
    // Water & pet
    // =====================================================================================

    private fun buildWater(): View {
        val col = column()
        col.addView(title("Water & pet"))

        col.addView(section("Choose your pet"))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(12), dp(8), dp(12)) }
        for (k in PetKind.entries) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val v = PetView(this, preview = k).apply {
                layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
                contentDescription = k.title
                onSelect = {
                    prefs.edit().putString(Prefs.PET_KIND, k.name).apply()
                    Sync.stamp(this@MainActivity, Prefs.PET_KIND)
                    refreshWaterTab(); refreshHome()
                }
            }
            previews[k] = v
            c.addView(v)
            c.addView(tv(k.title, 13, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            val st = tv("", 11, Ui.DIM).apply { gravity = Gravity.CENTER }
            previewStage[k] = st
            c.addView(st)
            grid.addView(c)
        }
        stageText = tv("", 13, Ui.DIM).apply { setPadding(dp(16), 0, dp(16), dp(14)) }
        col.addView(card(grid, stageText))

        col.addView(section("Look"))
        val cos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        for (i in -1..2) {
            val chip = pill("", Ui.CHIP, Color.WHITE) {
                if (i < 0 || Water.cosmeticUnlocked(this, i)) {
                    val key = Prefs.PET_COS + PetEngine.kind(prefs).name
                    prefs.edit().putInt(key, i).apply()
                    Sync.stamp(this, key)
                    refreshWaterTab()
                } else toast("Unlocks at ${Water.cosmeticRule(i)}")
            }
            cosChips += chip
            cos.addView(chip); cos.addView(space(6))
        }
        col.addView(card(hscroll(cos), tv("Looks unlock at 3 goal days, a 7-day streak and 20 goal days.", 12, Ui.DIM).apply {
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }))

        col.addView(section("Progress"))
        val tileRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(12), dp(8), 0) }
        val tileRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        for ((i, name) in listOf("Streak", "Best streak", "Goal days", "All time").withIndex()) {
            (if (i < 2) tileRow1 else tileRow2).addView(tile(name))
        }
        stats = StatsView(this).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val ach = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        var rowL: LinearLayout? = null
        for ((i, a) in Achievement.entries.withIndex()) {
            if (i % 2 == 0) {
                rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
                ach.addView(rowL)
            }
            val chip = tv("", 12, Color.WHITE).apply {
                background = rounded(Ui.CHIP, 12)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) }
                maxLines = 1
            }
            achChips[a] = chip
            rowL!!.addView(chip)
        }
        col.addView(card(tileRow1, tileRow2, stats, ach))

        col.addView(section("Daily goal"))
        goalHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(goalHolder)
        buildGoalCard()

        col.addView(section("Reminders"))
        col.addView(card(
            toggleRow("Remind me", "Only when you're behind pace, never right after a drink, and backs off if you ignore it.", Prefs.WATER_ON, true) { rescheduleWater() },
            stepper("Wake up", "Reminders and pace start here", 0, 23, 1, { Water.wake(prefs) / 60 }, { "%02d:00".format(it) }) {
                prefs.edit().putInt(Prefs.WAKE, it * 60).apply(); Sync.stamp(this, Prefs.WAKE); rescheduleWater()
            },
            stepper("Bedtime", "Your pet sleeps, no reminders", 0, 23, 1, { Water.sleep(prefs) / 60 }, { "%02d:00".format(it) }) {
                prefs.edit().putInt(Prefs.SLEEP, it * 60).apply(); Sync.stamp(this, Prefs.SLEEP); rescheduleWater()
            },
        ))

        col.addView(section("Cups"))
        cupsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(cupsHolder)
        buildCupsCard()
        return col
    }

    private fun buildGoalCard() {
        goalHolder.removeAllViews()
        val auto = prefs.getBoolean(Prefs.GOAL_AUTO, false)
        todayGoalText = tv("", 15, Ui.DIM)
        val rows = ArrayList<View?>()
        rows += toggleRow("Personal goal", "Calculated from your weight and activity (+10% on hot days). Not medical advice.", Prefs.GOAL_AUTO, false) {
            rescheduleWater(); buildGoalCard()
        }
        if (auto) {
            rows += stepper("Weight", "Used only for the goal, stays on your devices", 30, 200, 1, { prefs.getInt(Prefs.WEIGHT, 0) },
                { if (it == 0) "Not set" else "$it kg" }) { prefs.edit().putInt(Prefs.WEIGHT, it).apply(); Sync.stamp(this, Prefs.WEIGHT); rescheduleWater() }
            rows += stepper("Exercise per day", null, 0, 300, 10, { prefs.getInt(Prefs.ACTIVITY, 30) }, { "$it min" }) {
                prefs.edit().putInt(Prefs.ACTIVITY, it).apply(); Sync.stamp(this, Prefs.ACTIVITY); rescheduleWater()
            }
            rows += toggleRow("Hot day", "+10% today", Prefs.HOT, false) { rescheduleWater() }
        } else {
            rows += stepper("Goal", null, 500, 6000, 100, { prefs.getInt(Prefs.GOAL, 2000) }, { "$it ml" }) {
                prefs.edit().putInt(Prefs.GOAL, it).apply(); Sync.stamp(this, Prefs.GOAL); rescheduleWater()
            }
        }
        rows += row("Today's goal", "Hydration-adjusted: coffee counts 80%, tea 90%…", todayGoalText)
        goalHolder.addView(card(*rows.toTypedArray()))
        todayGoalText?.text = "${Water.goal(prefs)} ml"
    }

    private fun buildCupsCard() {
        cupsHolder.removeAllViews()
        val rows = ArrayList<View?>()
        rows += stepper("Default cup", "Used by the Drink button and reminders", 50, 2000, 50, { Water.cup(prefs) }, { "$it ml" }) {
            prefs.edit().putInt(Prefs.CUP, it).apply(); Sync.stamp(this, Prefs.CUP); rescheduleWater()
        }
        Water.cups(prefs).forEachIndexed { i, cup ->
            rows += stepper(cup.first, "Quick-add button on Home", 50, 2000, 50, { Water.cups(prefs).getOrNull(i)?.second ?: cup.second }, { "$it ml" }) { v ->
                setCupMl(i, v)
            }
        }
        cupsHolder.addView(card(*rows.toTypedArray()))
    }

    private fun setCupMl(i: Int, ml: Int) {
        val arr = try { JSONArray(prefs.getString(Prefs.CUPS, Water.DEFAULT_CUPS) ?: Water.DEFAULT_CUPS) } catch (_: Exception) { JSONArray(Water.DEFAULT_CUPS) }
        val o = arr.optJSONObject(i) ?: return
        o.put("ml", ml)
        prefs.edit().putString(Prefs.CUPS, arr.toString()).apply()
        Sync.stamp(this, Prefs.CUPS)
        refreshHome()
    }

    private fun rescheduleWater() {
        Water.invalidate()
        Water.scheduleNext(this)
        refreshHome()
        refreshWaterTab()
    }

    private fun refreshWaterTab() {
        if (!::stageText.isInitialized) return
        val kind = PetEngine.kind(prefs)
        val stage = Water.stage(this)
        previews.forEach { (k, v) -> v.chosen = k == kind }
        previewStage.forEach { (k, t) -> t.text = k.stages[stage] }
        val next = Water.bloomsToNext(this)
        stageText.text = "${kind.title} · ${kind.stages[stage]}  —  " +
            if (next < 0) "fully grown!" else "$next more goal day${if (next == 1) "" else "s"} to ${kind.stages[stage + 1]}"
        val chosen = PetEngine.cosmetic(this, kind)
        for ((idx, chip) in cosChips.withIndex()) {
            val i = idx - 1
            val unlocked = i < 0 || Water.cosmeticUnlocked(this, i)
            chip.text = when {
                i < 0 -> "None"
                unlocked -> kind.cosmetics[i]
                else -> "🔒 ${kind.cosmetics[i]} · ${Water.cosmeticRule(i)}"
            }
            val on = i == chosen
            chip.background = pillBg(if (on) Ui.ACCENT else Ui.CHIP)
            chip.setTextColor(if (on) Color.BLACK else if (unlocked) Color.WHITE else Ui.DIM)
        }
        val fr = Water.freezes(this)
        setTile("Streak", "${Water.streak(this)} d", if (fr > 0) "$fr freeze${if (fr == 1) "" else "s"}" else "80% counts")
        setTile("Best streak", "${Water.bestStreak(this)} d", "")
        setTile("Goal days", "${Water.blooms(this)}", if (next < 0) "Fully grown" else "$next to next stage")
        setTile("All time", "${Water.liters(Water.lifetimeMl(this).toInt())} L", "")
        stats.update(Water.last7(this))
        val have = Water.achievements(this)
        for ((a, t) in achChips) {
            val got = a in have
            t.text = "${if (got) a.emoji else "🔒"}  ${a.title}"
            t.alpha = if (got) 1f else 0.45f
        }
        todayGoalText?.text = "${Water.goal(prefs)} ml"
    }

    private fun tile(name: String): View {
        val v = tv("", 20, Color.WHITE, bold = true)
        val sub = tv("", 11, Ui.DIM)
        tiles[name] = v to sub
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x0FFFFFFF, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
            addView(tv(name.uppercase(), 10, Ui.DIM).apply { letterSpacing = 0.08f })
            addView(v)
            addView(sub)
        }
    }

    private fun setTile(name: String, value: String, sub: String) {
        val (v, s) = tiles[name] ?: return
        v.text = value
        s.text = sub
        s.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
    }

    // =====================================================================================
    // Island
    // =====================================================================================

    private fun buildIsland(): View {
        val col = column()
        col.addView(title("Island"))

        col.addView(section("Island"))
        islandToggle = ToggleView(this).apply {
            set(prefs.getBoolean(Prefs.ISLAND_ON, false))
            onChange = { setIsland(it) }
        }
        islandNote = tv("", 12, Ui.DIM).apply { setPadding(dp(16), 0, dp(16), dp(12)) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        actions.addView(pill("Auto-fit to camera", Ui.CHIP, Color.WHITE) {
            val ok = IslandService.autoFit(this)
            reloaders.forEach { it() }
            toast(if (ok) "Centred on the camera" else "No camera cutout reported — use the sliders")
        })
        actions.addView(space(8))
        actions.addView(pill("Test peek", Ui.CHIP, Color.WHITE) {
            val s = IslandService.running
            if (s == null || !s.hasWindow()) toast("Turn the island on first") else s.testPeek()
        })
        col.addView(card(
            row("Show the island", "Pill around the front camera", islandToggle),
            islandNote,
            toggleRow("Dock when idle", "Disappears into the camera; tap it to open", Prefs.F_DOCK, true) {},
            toggleRow("Show on lock screen", "Off: only while music or video plays. Never on the always-on display", Prefs.LOCK_ALWAYS, false) {},
            hscroll(actions),
        ))

        // Today's water traced around the camera while docked. Shape it to your camera: circle for a
        // punch hole, U for a notch. While this screen is open the ring always shows, to line it up.
        col.addView(section("Camera ring"))
        val shapeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        lateinit var circleChip: TextView
        lateinit var notchChip: TextView
        fun loadShape() {
            val notch = prefs.getInt(Prefs.RING_SHAPE, 0) == 1
            circleChip.background = pillBg(if (notch) Ui.CHIP else Ui.ACCENT)
            circleChip.setTextColor(if (notch) Color.WHITE else Color.BLACK)
            notchChip.background = pillBg(if (notch) Ui.ACCENT else Ui.CHIP)
            notchChip.setTextColor(if (notch) Color.BLACK else Color.WHITE)
        }
        circleChip = pill("◯  Circle · punch-hole", Ui.CHIP, Color.WHITE) { prefs.edit().putInt(Prefs.RING_SHAPE, 0).apply(); loadShape() }
        notchChip = pill("◡  U · notch", Ui.CHIP, Color.WHITE) { prefs.edit().putInt(Prefs.RING_SHAPE, 1).apply(); loadShape() }
        shapeRow.addView(circleChip); shapeRow.addView(space(8)); shapeRow.addView(notchChip)
        loadShape()
        reloaders += { loadShape() }
        col.addView(card(
            toggleRow("Water ring around the camera", "Shows for a few seconds after the island closes, filling anticlockwise from the top; not once the goal is met", Prefs.RING_ON, true) {},
            hscroll(shapeRow),
            slider("Width", 8, 140, 1, Prefs.RING_W, Prefs.DEF_RING_W) { "$it dp" },
            slider("Depth (U only)", 4, 80, 1, Prefs.RING_H, Prefs.DEF_RING_H) { "$it dp" },
            slider("Corner roundness (U only)", 0, 40, 1, Prefs.RING_R, Prefs.DEF_RING_R) { "$it dp" },
            slider("Thickness", 1, 6, 1, Prefs.RING_T, Prefs.DEF_RING_T) { "$it dp" },
            tv("While this screen is open the ring always shows, so you can line it up with your camera. " +
                "A U hangs from the island's top edge: for a notch set Position → Up / down to 0.", 12, Ui.DIM).apply {
                setPadding(dp(16), dp(4), dp(16), dp(12))
            },
        ))

        // Payment & bank apps block accessibility services and overlays: let the island step aside.
        col.addView(section("Payment apps"))
        val paySub = tv("", 12, Ui.DIM).apply { setPadding(dp(16), 0, dp(16), dp(12)) }
        fun loadPay() {
            val n = PaymentMode.apps(this).count { isInstalled(it) }
            paySub.text = when {
                !PaymentMode.canSwitch(this) -> "Needs a one-time permission over adb (see README → Auto payment mode)."
                else -> "$n app${if (n == 1) "" else "s"} selected. While one is open the island hides and turns its accessibility " +
                    (if (Prefs.on(prefs, Prefs.F_PAY_DEV)) "and developer options " else "") +
                    "off; they come back when you leave."
            }
        }
        loadPay()
        reloaders += { loadPay() }
        col.addView(card(
            toggleRow("Auto payment mode", "Step aside in GPay, Paytm, bank apps… so they don't block", Prefs.F_AUTO_PAY, true) { loadPay() },
            toggleRow("Also pause developer options", "Developer options and USB / wireless debugging off while a payment app is open (it drops any adb connection)", Prefs.F_PAY_DEV, true) { loadPay() },
            row("Choose apps", "Pick which apps count as payment apps", pill("Choose", Ui.CHIP, Color.WHITE) { showPaymentPicker { loadPay() } }),
            paySub,
        ))

        col.addView(section("Live activities"))
        col.addView(card(
            toggleRow("Now playing", "Album art, visualizer and controls", Prefs.F_MEDIA, true) {},
            toggleRow("Pet dances to music", "Bounces while something is playing", Prefs.F_DANCE, true) {},
            toggleRow("Notifications", "New notifications peek into the island", Prefs.PEEK_NOTIFS, true) {},
            toggleRow("Replace pop-up notifications", "Hide the system's pop-up banner while the island shows the peek, so you don't see it twice", Prefs.REPLACE_POPUPS, true) {
                if (Prefs.on(prefs, Prefs.REPLACE_POPUPS) && !HeadsUp.canControl(this)) toast("Needs the one-time adb permission (README → Auto payment mode)")
            },
            toggleRow("Copy OTP codes", "\"Copy 482913\" button on code messages", Prefs.F_OTP, true) {},
            toggleRow("Inline reply", "Reply to WhatsApp / Telegram / SMS from the island", Prefs.F_REPLY, true) {},
            toggleRow("Calls", "Green pill with call timer, End / Speaker", Prefs.F_CALL, true) {},
            toggleRow("Progress & navigation", "Downloads, uploads, deliveries, next turn", Prefs.F_LIVE, true) {},
            toggleRow("Bluetooth", "Headphones and devices connecting / disconnecting", Prefs.F_BT, true) {},
        ))

        col.addView(section("System"))
        col.addView(card(
            toggleRow("Volume", "Island turns into a level bar when volume changes", Prefs.F_VOLUME, true) {},
            toggleRow("Ring / Vibrate / Silent", "Bell peek when the ringer mode changes", Prefs.F_RINGER, true) {},
            toggleRow("Battery and charging", "Liquid battery on plug-in, full and low", Prefs.PEEK_CHARGING, true) {},
            toggleRow("Microphone and camera", "Orange / green dot while an app is using them (most phones already show one)", Prefs.F_PRIVACY, false) {},
        ))

        col.addView(section("Position & size"))
        col.addView(card(
            slider("Left / right", -80, 80, 1, Prefs.OFFSET_X, 0) { "${if (it > 0) "+" else ""}$it dp" },
            slider("Up / down", -10, 40, 1, Prefs.OFFSET_Y, Prefs.DEF_OFFSET_Y) { "$it dp" },
            slider("Width", 80, 220, 2, Prefs.WIDTH, Prefs.DEF_WIDTH) { "$it dp" },
            slider("Height", 20, 44, 1, Prefs.HEIGHT, Prefs.DEF_HEIGHT) { "$it dp" },
            slider("Pill gap from top", 0, 60, 1, Prefs.PILL_GAP, 0) { "$it dp" },
        ))
        col.addView(tv("While you drag these, the pill shows so you can see where it sits. " +
            "Pill gap from top moves the small pill down, clear of the camera; opened from the pill, the island grows from there. The docked view stays put. " +
            "Tap the island to open it, long-press to come here. Swipe down left of the camera for notifications, right of it for quick settings.", 12, Ui.DIM).apply {
            setPadding(dp(8), dp(14), dp(8), 0)
        })
        return col
    }

    private fun setIsland(on: Boolean) {
        prefs.edit().putBoolean(Prefs.ISLAND_ON, on).apply()
        if (on) {
            if (!Settings.canDrawOverlays(this) && !IslandA11y.isEnabled(this)) {
                toast("Allow \"Draw over apps\" so the island can appear")
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            }
            IslandService.start(this)
        } else {
            IslandService.stop(this)
        }
    }

    // =====================================================================================
    // Sync
    // =====================================================================================

    private fun buildSync(): View {
        val col = column()
        col.addView(title("Sync"))
        col.addView(section("Water & pet sync"))
        syncStatus = tv("", 14, Color.WHITE)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(tv("STATUS", 10, Ui.DIM).apply { letterSpacing = 0.08f })
            addView(syncStatus)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(14)) }
        pickBtn = pill("Pick folder", Ui.ACCENT, Color.BLACK) { pickFolder() }
        buttons.addView(pickBtn); buttons.addView(space(8))
        buttons.addView(pill("Sync now", Ui.CHIP, Color.WHITE) {
            if (Sync.tree(prefs) == null) toast("Pick the folder first") else { Sync.requestNow(this); toast("Syncing…") }
        })
        col.addView(card(
            statusRow,
            tv("Drinks, goal, streaks, pet growth and looks stay in step with the Windows island. Each device keeps working on its own and merges when they meet.", 12, Ui.DIM).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
            },
            buttons,
        ))

        col.addView(section("How to set it up"))
        col.addView(card(
            step(1, "On the laptop, open the island's Settings › Phone sync and turn on \"Sync with my phone\"."),
            step(2, "Connect the phone with TwinDesk and turn on Shared folders."),
            step(3, "Here, tap Pick folder › TwinDesk › PC › DynamicIsland (create a folder called \"DynamicIsland\" if it isn't there) › Use this folder."),
            step(4, "That's it. Syncs on every change, when you unlock the phone, and every 2 minutes while TwinDesk is connected."),
        ))
        return col
    }

    private fun step(n: Int, text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(tv("$n", 13, Color.BLACK, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.ACCENT, 11)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
        })
        addView(tv(text, 14, Color.WHITE).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        Sync.initialPickerUri()?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_TREE)
        } catch (_: Exception) {
            toast("No folder picker available")
        }
    }

    private fun refreshSync() {
        if (!::syncStatus.isInitialized) return
        val tree = Sync.tree(prefs)
        val state = prefs.getString(Prefs.SYNC_STATE, Sync.STATE_NONE)
        val at = prefs.getLong(Prefs.SYNC_AT, 0L)
        val last = if (at > 0) "Last synced ${ago(at)}" else "Not synced yet"
        syncStatus.text = when {
            tree == null -> "Not set up"
            state == Sync.STATE_LOST -> "$last · folder access lost — re-pick the folder"
            state == Sync.STATE_OFFLINE -> "$last · laptop not connected"
            state == Sync.STATE_WRITE -> "$last · couldn't write phone.json"
            else -> last
        }
        syncStatus.setTextColor(
            when (state) {
                Sync.STATE_LOST, Sync.STATE_WRITE -> Ui.RED
                Sync.STATE_OFFLINE -> Ui.ORANGE
                else -> Color.WHITE
            }
        )
        pickBtn.text = if (tree == null) "Pick folder" else "Re-pick folder"
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 10 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    // =====================================================================================
    // Setup
    // =====================================================================================

    private fun buildSetup(): View {
        val col = column()
        col.addView(title("Setup"))
        col.addView(section("Permissions"))
        val rows = ArrayList<View?>()
        rows += permRow("Draw over apps", "Needed to show the island", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        rows += permRow("Notification access", "Music, calls, live activities, peeks, OTP, reply", { NotifListener.isEnabled(this) }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        rows += permRow("Accessibility (recommended)", "Puts the island above the status bar so it's tappable, and lets a swipe down open the shade. Reads nothing.",
            { IslandA11y.isEnabled(this) }) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rows += permRow("Notifications", "Water reminders and timer alerts", {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rows += permRow("Nearby devices", "Bluetooth connect peeks", {
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            }) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2) }
        }
        rows += permRow("Unrestricted battery", "Stops Android from killing the island", {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        col.addView(card(*rows.toTypedArray()))

        col.addView(section("Phone tips"))
        val appInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(14))
            addView(pill("Open App info", Ui.CHIP, Color.WHITE) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        col.addView(card(
            tv("Access greyed out? Android 13+ blocks it for sideloaded apps: App info › ⋮ › Allow restricted settings.", 13, Color.WHITE).apply {
                setPadding(dp(16), dp(14), dp(16), dp(6))
            },
            tv("Infinix / XOS: turn on Auto-start for this app, set Battery to \"No restrictions\", and lock it in Recents.", 13, Color.WHITE).apply {
                setPadding(dp(16), dp(6), dp(16), dp(10))
            },
            appInfo,
        ))

        col.addView(section("About"))
        col.addView(card(
            row("Version", null, tv(BuildConfig.VERSION_NAME, 15, Ui.DIM)),
            row("Island gestures", "Tap = open · long-press = this screen · swipe down = notification shade · swipe up = close", null),
        ))
        return col
    }

    private fun permRow(title: String, sub: String, check: () -> Boolean, open: () -> Unit): View {
        val btn = pill("Allow", Ui.ACCENT, Color.BLACK) {
            try { open() } catch (_: Exception) { toast("Couldn't open that settings page") }
        }
        permRows += PermRow(check, btn)
        return row(title, sub, btn)
    }

    private fun refreshPerms() {
        for (r in permRows) {
            val ok = try { r.check() } catch (_: Exception) { false }
            r.button.text = if (ok) "✓ On" else "Allow"
            r.button.isEnabled = !ok
            r.button.background = pillBg(if (ok) Color.TRANSPARENT else Ui.ACCENT)
            r.button.setTextColor(if (ok) Ui.GREEN else Color.BLACK)
        }
        if (::islandNote.isInitialized) {
            islandNote.text = when {
                IslandA11y.isEnabled(this) -> "Sitting above the status bar (accessibility mode)."
                Settings.canDrawOverlays(this) -> "Normal overlay. Turn on Accessibility in Setup so the status bar doesn't steal taps."
                else -> "Allow \"Draw over apps\" (or Accessibility) in Setup to see the island."
            }
        }
    }

    // =====================================================================================
    // Building blocks (same set as the Windows settings window)
    // =====================================================================================

    private fun page(content: View) = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        scrollBarSize = dp(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            verticalScrollbarThumbDrawable = rounded(0x55FFFFFF, 2)
        }
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(28))
    }

    private fun title(text: String) = tv(text, 30, Color.WHITE, bold = true).apply { setPadding(dp(4), dp(10), 0, dp(2)) }

    private fun section(text: String) = tv(text.uppercase(), 12, Ui.DIM).apply {
        letterSpacing = 0.1f
        setPadding(dp(6), dp(22), 0, dp(8))
    }

    /** Rounded card; rows separated by hairlines. Null rows are skipped. */
    private fun card(vararg rows: View?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.CARD, 16)
            clipToOutline = true
        }
        var first = true
        for (r in rows) {
            if (r == null) continue
            if (!first) box.addView(View(this).apply {
                setBackgroundColor(Ui.DIVIDER)
                layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(0.6f))).apply { marginStart = dp(16) }
            })
            box.addView(r)
            first = false
        }
        return box
    }

    private fun row(title: String, sub: String?, right: View?): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            minimumHeight = dp(52)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(0, 0, dp(12), 0)
        }
        col.addView(tv(title, 15, Color.WHITE))
        if (!sub.isNullOrEmpty()) col.addView(tv(sub, 12, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(col)
        right?.let { r.addView(it) }
        return r
    }

    private fun toggleRow(title: String, sub: String, key: String, def: Boolean, after: () -> Unit): View {
        val t = ToggleView(this).apply {
            set(prefs.getBoolean(key, def))
            onChange = { on ->
                prefs.edit().putBoolean(key, on).apply()
                Sync.stamp(this@MainActivity, key)
                after()
            }
        }
        reloaders += { t.set(prefs.getBoolean(key, def)) }
        return row(title, sub, t)
    }

    private fun stepper(title: String, sub: String?, lo: Int, hi: Int, step: Int, get: () -> Int, fmt: (Int) -> String, set: (Int) -> Unit): View {
        val value = tv("", 15, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(72) }
        fun load() { value.text = fmt(get()) }
        load()
        reloaders += { load() }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(roundBtn("−") { set(stepValue(get(), -step, lo, hi)); load() })
            addView(value)
            addView(roundBtn("+") { set(stepValue(get(), step, lo, hi)); load() })
        }
        return row(title, sub, right)
    }

    private fun roundBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(Ui.CHIP, 16)
        foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(Color.WHITE, 16))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun slider(title: String, min: Int, max: Int, step: Int, key: String, def: Int, fmt: (Int) -> String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(4)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(tv(title, 15, Color.WHITE).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        val value = tv("", 14, Ui.ACCENT)
        head.addView(value)
        box.addView(head)
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            setPadding(dp(4), dp(10), dp(4), dp(10))
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
        }
        fun load() {
            val v = prefs.getInt(key, def).coerceIn(min, max)
            bar.progress = (v - min) / step
            value.text = fmt(v)
        }
        load()
        reloaders += { load() }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step
                value.text = fmt(v)
                prefs.edit().putInt(key, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(bar)
        return box
    }

    private fun hscroll(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(v)
    }

    private fun tv(text: String, sp: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun pillBg(color: Int): GradientDrawable = rounded(color, 100)

    private fun pill(text: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = pillBg(bg)
        foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, pillBg(Color.WHITE))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun isInstalled(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    /**
     * Full-screen dark picker: every launchable app with its icon and a switch, a search box,
     * chosen apps first. Saves to PaymentMode on Done.
     */
    private fun showPaymentPicker(onDone: () -> Unit) {
        val chosen = PaymentMode.apps(this).toMutableSet()
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
        titles.addView(tv("Payment apps", 22, Color.WHITE, bold = true))
        titles.addView(tv("The island steps aside while these are open", 13, Ui.DIM))
        header.addView(titles)
        header.addView(pill("Done", Ui.ACCENT, Color.BLACK) {
            PaymentMode.setApps(this, chosen)
            dialog.dismiss()
            onDone()
        })
        root.addView(header)

        val search = android.widget.EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Ui.DIM)
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            background = rounded(Ui.CARD, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14); bottomMargin = dp(10) }
        }
        root.addView(search)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        root.addView(scroll)
        val loading = tv("Loading apps…", 14, Ui.DIM).apply { setPadding(dp(8), dp(16), 0, 0) }
        list.addView(loading)

        class AppRow(val pkg: String, val label: String, val view: View)
        val rows = ArrayList<AppRow>()

        fun render(query: String) {
            list.removeAllViews()
            val q = query.trim().lowercase()
            val shown = rows.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
                .sortedWith(compareBy({ it.pkg !in chosen }, { it.label.lowercase() }))
            var group: View? = null
            for (r in shown) {
                (r.view.parent as? android.view.ViewGroup)?.removeView(r.view)
                list.addView(r.view)
                group = r.view
            }
            if (group == null) list.addView(tv("No apps match", 14, Ui.DIM).apply { setPadding(dp(8), dp(16), 0, 0) })
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (rows.isNotEmpty()) render(s?.toString() ?: "") }
        })

        // Load apps (labels + icons) off the main thread.
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { Triple(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            runOnUiThread {
                for ((pkg, label, icon) in apps) {
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(6), dp(8))
                    }
                    r.addView(android.widget.ImageView(this).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
                    })
                    val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
                    names.addView(tv(label, 15, Color.WHITE))
                    names.addView(tv(pkg, 11, Ui.DIM))
                    r.addView(names)
                    val t = ToggleView(this).apply {
                        set(pkg in chosen)
                        onChange = { on -> if (on) chosen.add(pkg) else chosen.remove(pkg) }
                    }
                    r.addView(t)
                    r.setOnClickListener { t.set(pkg !in chosen, animate = true, notify = true) }
                    rows.add(AppRow(pkg, label, r))
                }
                render(search.text?.toString() ?: "")
            }
        }.start()

        dialog.setContentView(root)
        dialog.show()
    }

    companion object {
        private const val REQ_TREE = 7
        private const val KEY_TAB = "ui_tab"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
