package com.rsv.dynamicisland.pet

import android.graphics.Canvas
import com.rsv.dynamicisland.Mood
import kotlin.math.min
import kotlin.math.sin

/**
 * Port of the Windows PetSkin (Pets/PetSkin.cs). One character drawn into a 100x100 canvas in
 * three layers: Scene (tank / pot / pond - shows today's water), Body (the character, moved by
 * the base animations), Fx (sparkles, hearts, splash, Zzz - owned here).
 *
 * Skins build a retained element tree (see Wpf.kt) in [buildScene]/[buildBody] and mutate it in
 * [changeMood]/[setWater]/[lookAt]/[blink], exactly like the WPF originals.
 */
abstract class PetSkin {
    protected var scene = Cv()
    protected var body = Cv()
    protected var fx = Cv()
    protected val bodyScale = ScaleT(1f, 1f, 50f, 92f)
    protected val bodyTilt = RotT(0f, 50f, 92f)
    protected val bodyMove = TransT()

    private var sparkles = Cv()
    private var hearts = Cv()
    private var zzz = Cv()
    private var splash = Cv()
    protected var idling = false

    var stage = 0; private set
    var cosmetic = 0; private set
    var compact = false; private set
    var mood: Mood = Mood.IDLE; protected set
    var water = 0.3; protected set

    abstract val kind: PetKind

    fun attach(stage: Int, cosmetic: Int, compact: Boolean) {
        this.stage = stage.coerceIn(0, 3)
        this.cosmetic = cosmetic.coerceIn(0, 3)
        this.compact = compact
        scene = Cv()
        body = Cv().apply { rt = GroupT(bodyScale, bodyTilt, bodyMove) }
        fx = Cv()
        buildScene(scene)
        buildBody(body)
        buildFx()
        changeMood(mood)
        setWater(water, false)
    }

    fun draw(c: Canvas, now: Long) {
        scene.render(c, now, 1f)
        body.render(c, now, 1f)
        fx.render(c, now, 1f)
    }

    internal fun setMoodSilently(m: Mood) { mood = m }
    internal fun setWaterSilently(w: Double) { water = w }

    protected abstract fun buildScene(scene: Cv)
    protected abstract fun buildBody(body: Cv)

    /** Change expression / pose / colours. Implementations set [mood] and call [setSleepFx]. */
    abstract fun changeMood(m: Mood)
    /** Today's progress 0..1 shown in the scene. */
    abstract fun setWater(fraction: Double, animate: Boolean)
    /** Point eyes toward a direction, each axis -1..1. Ignore while sleeping. */
    abstract fun lookAt(dx: Double, dy: Double)
    abstract fun blink()

    // ---------- default animations (skins may override and call super) ----------

    open fun startIdle() {
        idling = true
        val sl = mood == Mood.SLEEPING
        bodyMove.y.begin(DA(0f, if (sl) -1.2f else -3f, if (sl) 2200 else 1300, SineE()).apply { autoReverse = true; forever = true })
    }

    open fun stopIdle() {
        idling = false
        bodyMove.y.begin(null)
        bodyTilt.angle.begin(null)
    }

    /** Logged a drink: gulp + splash. */
    open fun playDrink() {
        squash(1.12f, 0.88f, 420)
        flash(splash, 700)
    }

    open fun playCelebrate() {
        jump(16f)
        flash(sparkles, 1500)
    }

    open fun playGoal() {
        jump(20f)
        flash(hearts, 1800)
        flash(sparkles, 1800)
    }

    /** Stage-up flourish (~1.5 s). The host re-attaches with the new stage halfway through. */
    open fun playEvolve() {
        bodyTilt.angle.begin(DA(0f, 360f, 900, CubicE(EM.INOUT)))
        squash(1.25f, 1.25f, 900)
        flash(sparkles, 1600)
    }

    open fun playPoke() {
        squash(1.16f, 0.8f, 600)
        blink()
    }

    /** Called every frame with music loudness 0..1 while a song plays. */
    open fun dance(peak: Double) {
        if (mood == Mood.SLEEPING) return
        val p = peak.coerceIn(0.0, 1.0)
        bodyScale.sx.begin(null)
        bodyScale.sy.begin(null)
        bodyScale.sy.set((1 - p * 0.09).toFloat())
        bodyScale.sx.set((1 + p * 0.06).toFloat())
        bodyTilt.angle.begin(null)
        bodyTilt.angle.set((sin(Clock.now() / 260.0) * 6 * min(1.0, p * 2)).toFloat())
    }

    open fun stopDance() {
        bodyScale.sx.set(1f); bodyScale.sy.set(1f)
        bodyTilt.angle.set(0f)
    }

    // ---------- helpers for skins ----------

    protected fun setSleepFx(sleeping: Boolean) { zzz.visible = sleeping }

    protected fun squash(sx: Float, sy: Float, ms: Int) {
        bodyScale.sx.begin(KA(listOf(KF(sx, (ms * 0.18).toLong()), KF(1f, ms.toLong(), ElasticE(2, 5.0)))))
        bodyScale.sy.begin(KA(listOf(KF(sy, (ms * 0.18).toLong()), KF(1f, ms.toLong(), ElasticE(2, 5.0)))))
    }

    protected fun jump(height: Float) {
        val j = KA(listOf(KF(-height, 220, QuadE(EM.OUT)), KF(0f, 560, BounceE(2, 3.0, EM.OUT))))
        j.onDone = { if (idling) startIdle() }
        bodyMove.y.begin(j)
    }

    protected fun flash(e: El, ms: Int) {
        e.opacity.begin(KA(listOf(KF(1f, 120), KF(1f, (ms * 0.7).toLong()), KF(0f, ms.toLong()))))
    }

    protected fun g(d: String) = Geo.g(d)

    private fun buildFx() {
        val star = "M0,-1 L0.25,-0.25 1,0 0.25,0.25 0,1 -0.25,0.25 -1,0 -0.25,-0.25 Z"
        sparkles = Cv().apply { opacity.set(0f) }
        for ((x, y, s) in listOf(Triple(8f, 24f, 9f), Triple(88f, 16f, 11f), Triple(92f, 64f, 7f), Triple(6f, 70f, 6f), Triple(50f, 4f, 7f)))
            sparkles.add(at(pathE(g(star), SolidB(rgb(0xFFF176))).apply { rt = ScaleT(s, s) }, x, y))

        hearts = Cv().apply { opacity.set(0f) }
        for ((x, y, s) in listOf(Triple(4f, 14f, 0.8f), Triple(82f, 6f, 1.0f), Triple(88f, 46f, 0.65f)))
            hearts.add(at(pathE(g("M0,3 C0,-1 6,-1 6,3 C6,-1 12,-1 12,3 C12,7 6,10 6,12 C6,10 0,7 0,3 Z"), SolidB(rgb(0xFF6B9A))).apply { rt = ScaleT(s, s) }, x, y))

        splash = Cv().apply { opacity.set(0f) }
        for ((x, y, s) in listOf(Triple(22f, 30f, 0.6f), Triple(74f, 26f, 0.7f), Triple(14f, 50f, 0.45f), Triple(84f, 48f, 0.5f)))
            splash.add(at(pathE(g("M5,0 C4,3 0,6 0,9 A5,5 0 0 0 10,9 C10,6 6,3 5,0 Z"), SolidB(rgb(0x81D4FA))).apply { rt = ScaleT(s, s) }, x, y))

        zzz = Cv().apply { visible = false }
        for ((x, y, size) in listOf(Triple(72f, 18f, 12f), Triple(82f, 4f, 16f)))
            zzz.add(at(TextE("z", size, SolidB(rgb(0xB3E5FC))), x, y))

        if (compact) { hearts.visible = false; splash.visible = false }
        fx.add(splash); fx.add(sparkles); fx.add(hearts); fx.add(zzz)
    }

    companion object {
        fun create(k: PetKind): PetSkin = when (k) {
            PetKind.DROPLET -> DropletSkin()
            PetKind.PLANT -> PlantSkin()
            PetKind.OTTER -> OtterSkin()
            PetKind.AXOLOTL -> AxolotlSkin()
        }
    }
}
