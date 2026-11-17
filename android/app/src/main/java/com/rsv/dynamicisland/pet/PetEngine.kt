package com.rsv.dynamicisland.pet

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import com.rsv.dynamicisland.Mood
import com.rsv.dynamicisland.Prefs
import com.rsv.dynamicisland.Water
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Same characters, stages and cosmetics as the Windows app (the Pets folder skins). */
enum class PetKind(val title: String, val stages: Array<String>, val cosmetics: Array<String>) {
    AXOLOTL("Axolotl", arrayOf("Baby", "Teen", "Adult", "Royal"), arrayOf("Bow", "Crown", "Flower crown")),
    DROPLET("Droplet", arrayOf("Droplet", "Pup", "Sprite", "Spirit"), arrayOf("Beanie", "Scarf", "Shades")),
    PLANT("Plant", arrayOf("Sprout", "Sapling", "Bush", "Bloom"), arrayOf("Blue glaze pot", "Ribbon", "Golden pot")),
    OTTER("Otter", arrayOf("Pup", "Kit", "Adult", "Elder"), arrayOf("Beanie", "Sunglasses", "Bow tie")),
}

/**
 * The one place that knows which pet is active and drives the ported skins. Each view "slot"
 * (island pill, island card, settings hero, picker previews) owns a live skin instance, like
 * the Windows PetView: it pushes mood / water / gaze, blinks every 2.5-6.5 s, idles, dances,
 * and plays the one-shot animations (drink, goal, evolve, celebrate, poke).
 */
object PetEngine {
    private class Host {
        var skin: PetSkin? = null
        var kind: PetKind? = null
        var stage = -1
        var cos = -1
        var compact = false
        var lastWater = -1.0
        var nextBlink = 0L
        var lastBlink = 0L
        var idle = false
        var dancing = false
        var evolveUntil = 0L
    }

    private val hosts = HashMap<String, Host>()
    @Volatile private var lastEventAt = 0L
    @Volatile private var pokeAt = 0L
    var pokeLine = ""
        private set

    val pokeLines = listOf("Hey!", "Hehe", "Sip with me?", "That tickles!", "Boop!", "Water? 👀", "Hi hi!")

    fun kind(p: SharedPreferences): PetKind =
        try { PetKind.valueOf(p.getString(Prefs.PET_KIND, PetKind.AXOLOTL.name) ?: PetKind.AXOLOTL.name) } catch (_: Exception) { PetKind.AXOLOTL }

    /** Our cosmetic index (-1 = none, 0..2), only if unlocked. */
    fun cosmetic(ctx: Context, kind: PetKind): Int {
        val i = Prefs.get(ctx).getInt(Prefs.PET_COS + kind.name, -1)
        return if (i in 0..2 && Water.cosmeticUnlocked(ctx, i)) i else -1
    }

    fun stageName(ctx: Context): String {
        val p = Prefs.get(ctx)
        return kind(p).stages[Water.stage(ctx)]
    }

    // ---- animation triggers (main thread) -----------------------------------------------------

    fun onDrink() { event { it.playDrink() } }
    fun onGoal() { event { it.playGoal() } }
    fun onCelebrate() { event { it.playCelebrate() } }

    fun onEvolve() {
        val now = Clock.now()
        for (h in hosts.values) h.evolveUntil = now + 500 // swap to the new stage halfway through the flourish
        event { it.playEvolve() }
    }

    fun poke(): String {
        pokeAt = Clock.now()
        pokeLine = pokeLines.random()
        event { it.playPoke() }
        return pokeLine
    }

    fun pokeShowing(): Boolean = Clock.now() - pokeAt < 1800

    private inline fun event(f: (PetSkin) -> Unit) {
        lastEventAt = Clock.now()
        for (h in hosts.values) h.skin?.let { try { f(it) } catch (_: Exception) { } }
    }

    /** True while a one-shot animation is playing (views should redraw every frame). */
    fun busy(): Boolean = Clock.now() - lastEventAt < 2600

    /** 0 while a blink is running in [slot], else ms until its next blink. */
    fun msToNextBlink(now: Long, slot: String = "compact"): Long {
        val h = hosts[slot] ?: return 3000
        if (now - h.lastBlink < 220) return 0
        return max(16L, h.nextBlink - now)
    }

    /**
     * Draw the pet with its 100x100 box centred on ([cx], [cy]) at [size] px. [compact] = the bold
     * face-only version for the pill. [slot] keeps a separate live skin per view.
     */
    fun draw(
        c: Canvas, ctx: Context, cx: Float, cy: Float, size: Float, compact: Boolean = false,
        lookX: Float = 0f, lookY: Float = 0f, lively: Boolean = true, dancing: Boolean = false,
        kind: PetKind = kind(Prefs.get(ctx)), stageOverride: Int = -1,
        slot: String = if (compact) "compact" else "main",
    ) {
        try {
            val h = hosts.getOrPut(slot) { Host() }
            val now = Clock.now()
            var stage = if (stageOverride >= 0) stageOverride else Water.stage(ctx)
            if (now < h.evolveUntil && h.skin != null && h.kind == kind) stage = h.stage
            val cos = cosmetic(ctx, kind) + 1 // Windows numbering: 0 = none

            // SetPet: keep mood + water across re-attaches.
            var skin = h.skin
            val newSkin = skin == null || h.kind != kind
            if (skin == null || h.kind != kind) {
                val prev = skin
                skin = PetSkin.create(kind)
                if (prev != null) { skin.setMoodSilently(prev.mood); skin.setWaterSilently(prev.water) }
                else { skin.setMoodSilently(Water.mood(ctx)); skin.setWaterSilently(Water.progress(ctx).toDouble()) }
                h.skin = skin
                h.kind = kind
                h.idle = false
            }
            if (newSkin || h.stage != stage || h.cos != cos || h.compact != compact) {
                skin.attach(stage, cos, compact)
                h.stage = stage; h.cos = cos; h.compact = compact
                h.lastWater = skin.water
            }

            val m = Water.mood(ctx)
            if (skin.mood != m) skin.changeMood(m)
            val w = Water.progress(ctx).toDouble()
            if (abs(w - h.lastWater) > 0.001) {
                skin.setWater(w, animate = h.lastWater >= 0)
                h.lastWater = w
            }
            skin.lookAt(lookX.toDouble(), lookY.toDouble())

            val live = lively && !compact
            if (live && !h.idle) { skin.startIdle(); h.idle = true }
            else if (!live && h.idle) { skin.stopIdle(); h.idle = false }

            if (now >= h.nextBlink) {
                if (h.nextBlink != 0L && m != Mood.SLEEPING) { skin.blink(); h.lastBlink = now }
                h.nextBlink = now + 2500 + Random.nextLong(4000)
            }

            if (dancing && live) {
                skin.dance(0.55 + 0.45 * sin(now / 230.0))
                h.dancing = true
            } else if (h.dancing) {
                skin.stopDance()
                h.dancing = false
            }

            c.save()
            c.translate(cx - size / 2f, cy - size / 2f)
            c.scale(size / 100f, size / 100f)
            skin.draw(c, now)
            c.restore()
        } catch (_: Exception) {
            // A drawing bug must never take the island down.
        }
    }
}
