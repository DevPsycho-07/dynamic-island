package com.rsv.dynamicisland.pet

import android.graphics.Path
import com.rsv.dynamicisland.Mood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of Windows Pets/OtterSkin.cs. Sea otter floating upright in a round porthole pool; the
 * water level is today's progress. Low water: sits on dry sand looking parched.
 */
class OtterSkin : PetSkin() {
    override val kind = PetKind.OTTER

    // ---------- palette ----------
    private fun s(h: String, a: Int = 255) = SolidB(hex(h, a))
    private fun grad(a: String, b: String, aa: Int = 255, ab: Int = 255) = LinB.two(hex(a, aa), hex(b, ab), 0f, 0f, 0f, 1f)
    private fun grad3(a: String, b: String, c: String, aa: Int = 255, ab: Int = 255, ac: Int = 255) =
        LinB(listOf(Stop(hex(a, aa), 0f), Stop(hex(b, ab), 0.4f), Stop(hex(c, ac), 1f)), 0f, 0f, 0f, 1f)

    private val furN = grad("#9C6B4E", "#6D4632")
    private val furP = grad("#B9A093", "#8D7468")
    private val tailN = s("#7A4F37")
    private val tailP = s("#9C8576")
    private val bellyN = grad("#F3DEC4", "#E2C197")
    private val bellyP = grad("#E8DDD0", "#CFBFAD")
    private val cream = grad("#F8E8D2", "#EBD1AE")
    private val dark = s("#2A1A13")
    private val eyeFill = grad("#4D3428", "#140B07")
    private val white = s("#FFFFFF")
    private val silver = s("#E4E9ED")

    // headScale, headX, headY, bodyX, bodyY, bodyRx, bodyRy, rigScale
    private val lay = arrayOf(
        doubleArrayOf(1.30, 50.0, 48.0, 50.0, 71.0, 20.0, 16.0, 1.00),
        doubleArrayOf(1.00, 50.0, 40.0, 50.0, 67.0, 16.0, 19.0, 1.00),
        doubleArrayOf(1.06, 50.0, 38.0, 50.0, 66.0, 24.0, 22.0, 1.00),
        doubleArrayOf(1.10, 50.0, 38.0, 50.0, 66.0, 25.0, 22.0, 1.06),
    )

    // ---------- geometry helpers ----------
    private fun shp(geo: Path, fill: Br?, stroke: Br? = null, sw: Double = 0.0) =
        pathE(geo, fill, stroke, sw, round = stroke != null)

    private fun el(cx: Double, cy: Double, rx: Double, ry: Double, fill: Br, stroke: Br? = null, sw: Double = 0.0) =
        shp(Geo.ellipse(cx.toFloat(), cy.toFloat(), rx.toFloat(), ry.toFloat()), fill, stroke, sw)

    private fun ln(d: String, stroke: Br, w: Double) = shp(g(d), null, stroke, w)
    private fun fl(d: String, fill: Br) = shp(g(d), fill)
    private fun q(x0: Double, y0: Double, cx: Double, cy: Double, x1: Double, y1: Double) =
        Geo.quad(x0.toFloat(), y0.toFloat(), cx.toFloat(), cy.toFloat(), x1.toFloat(), y1.toFloat())
    private fun lq(x0: Double, y0: Double, cx: Double, cy: Double, x1: Double, y1: Double, st: Br, w: Double) = shp(q(x0, y0, cx, cy, x1, y1), null, st, w)

    private fun fluffy(cx: Double, cy: Double, rx: Double, ry: Double, bumps: Int, amp: Double): Path {
        val p = Path()
        p.fillType = Path.FillType.EVEN_ODD
        val n = 120
        for (i in 0 until n) {
            val th = PI * 2 * i / n
            val r = (1 - amp) + amp * abs(sin(bumps * th / 2.0)) * 1.0
            val x = (cx + rx * r * cos(th)).toFloat()
            val y = (cy + ry * r * sin(th)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun n(v: Double) = f(v)

    private val wavePeriod = 26.0

    private fun waveGeom(amp: Double, phase: Double, fill: Boolean): Path {
        val p = Path()
        val x0 = -50.0
        val x1 = 160.0
        fun y(x: Double) = (amp * sin(2 * PI * x / wavePeriod + phase)).toFloat()
        p.moveTo(x0.toFloat(), y(x0))
        var x = x0 + 1
        while (x <= x1) { p.lineTo(x.toFloat(), y(x)); x += 1 }
        if (fill) {
            p.lineTo(x1.toFloat(), 72f)
            p.lineTo(x0.toFloat(), 72f)
            p.close()
        }
        return p
    }

    // ---------- state ----------
    private class Fur(val p: PathE, val n: Br, val pa: Br, val stroke: Boolean)

    private class EyeParts {
        val root = Cv()
        val open = Cv()
        var happy: PathE? = null
        var sleep: PathE? = null
        val blink = ScaleT(1f, 1f)
        val look = TransT()
    }

    private val fur = ArrayList<Fur>()
    private var eL = EyeParts()
    private var eR = EyeParts()
    private var rig = Cv()
    private var headG = Cv()
    private var blushC = Cv()
    private var sweatC = Cv()
    private var sadBrows = Cv()
    private var mIdle = Cv()
    private var mHappy = Cv()
    private var mThirsty = Cv()
    private var mSad = Cv()
    private var mSleep = Cv()
    private var cup = Cv()
    private var cupGlass: Cv? = null
    private var cupWater: El? = null
    private var cracks: PathE? = null
    private var hs = 1.0

    private val rock = RotT(0f, 50f, 78f)
    private val rise = TransT()
    private val headTilt = RotT(0f, 0f, 14f)
    private val headMove = TransT()
    private val cupRot = RotT(0f, 0f, 5.5f)
    private val cupMove = TransT()
    private val pawL = TransT()
    private val pawR = TransT()
    private var earL = RotT(0f, -11f, -9f)
    private var earR = RotT(0f, 11f, -9f)
    private val lvlBack = TransT(0f, 100f)
    private val lvlFront = TransT(0f, 101.5f)
    private val waveBack = TransT(0f, 0f)
    private val waveFront = TransT(-12f, 0f)

    // =====================================================================================
    //  SCENE
    // =====================================================================================
    override fun buildScene(scene: Cv) {
        if (compact) return

        val inner = Cv().apply { clip = Geo.ellipse(50f, 52f, 44f, 44f) }
        inner.add(shp(Geo.rect(0f, 0f, 100f, 100f), grad("#21496B", "#0C1E31")))

        inner.add(fl("M20,0 L34,0 L52,70 L30,70Z", s("#FFFFFF", 14)))
        inner.add(fl("M58,0 L68,0 L84,70 L68,70Z", s("#FFFFFF", 10)))

        inner.add(fl("M0,80 Q22,76 45,79 T100,78 L100,100 L0,100Z", grad("#EDCB98", "#C39660")))
        inner.add(el(14.0, 90.0, 4.2, 2.6, s("#B58A58")))
        inner.add(el(84.0, 87.0, 3.6, 2.2, s("#B58A58")))
        inner.add(el(79.0, 92.0, 2.4, 1.5, s("#A87E4E")))
        inner.add(el(20.0, 84.0, 2.0, 1.3, s("#D9B382")))
        val cr = shp(g("M10,84 L18,87 L16,92 M18,87 L26,86 M86,82 L80,86 L83,92 M80,86 L72,85"), null, s("#8C6535", 220), 1.1)
        cr.roundJoin = true
        cracks = cr
        inner.add(cr)

        inner.add(makeWave(2.3, 0.0, waveBack, lvlBack, grad3("#81D4FA", "#43B4F2", "#1E88E5", 225, 232, 245), s("#E1F5FE", 190), 1.3))
        scene.add(inner)

        scene.add(shp(Geo.ellipse(50f, 52f, 45f, 45f), null, grad("#B8C7CF", "#4A6270"), 4.6))
        scene.add(shp(Geo.ellipse(50f, 52f, 42.4f, 42.4f), null, s("#102532", 200), 1.2))
        for (i in 0 until 8) {
            val a = PI * 2 * i / 8 + PI / 8
            scene.add(el(50 + 45 * cos(a), 52 + 45 * sin(a), 1.35, 1.35, s("#EAF1F4")))
        }
        scene.add(ln("M20,34 A33,33 0 0 1 36,19", s("#FFFFFF", 105), 2.6))
        scene.add(el(41.0, 15.5, 1.3, 1.3, s("#FFFFFF", 105)))
    }

    private fun makeWave(amp: Double, phase: Double, wx: TransT, lvl: TransT, fill: Br, edge: Br, edgeW: Double): Cv {
        val c = Cv()
        c.add(shp(waveGeom(amp, phase, true), fill))
        c.add(shp(waveGeom(amp, phase, false), null, edge, edgeW))
        c.rt = GroupT(wx, lvl)
        return c
    }

    private fun buildFrontWater() {
        val host = Cv().apply { clip = Geo.ellipse(50f, 52f, 43.5f, 43.5f) }
        host.add(makeWave(1.8, 1.4, waveFront, lvlFront, grad3("#81D4FA", "#4FC3F7", "#1E88E5", 82, 105, 140), s("#FFFFFF", 150), 1.2))
        fx.add(host)
    }

    // =====================================================================================
    //  BODY
    // =====================================================================================
    override fun buildBody(body: Cv) {
        fur.clear()
        eL = EyeParts(); eR = EyeParts()
        earL = RotT(0f, -11f, -9f); earR = RotT(0f, 11f, -9f)
        val f = lay[stage]

        rig = Cv()
        if (!compact) {
            rig.rt = GroupT(ScaleT(f[7].toFloat(), f[7].toFloat(), 50f, 90f), rock, rise)
            buildTorso(rig, f)
        }
        buildHead(rig, f)
        if (!compact) buildCup(rig, f)
        body.add(rig)
        if (!compact) buildFrontWater()
    }

    private fun addFur(host: Cv, p: PathE, n: Br, pa: Br, stroke: Boolean = false): PathE {
        fur.add(Fur(p, n, pa, stroke))
        host.add(p)
        return p
    }

    private fun buildTorso(c: Cv, f: DoubleArray) {
        val bx = f[3]; val by = f[4]; val rx = f[5]; val ry = f[6]

        if (stage == 1) addFur(c, shp(g("M60,84 C73,88 81,80 79,69"), null, tailN, 6.0), tailN, tailP, true)
        else if (stage >= 2) {
            val w = if (stage == 3) 10.0 else 9.0
            addFur(c, shp(g("M66,85 C84,91 94,79 89,62"), null, tailN, w), tailN, tailP, true)
            if (stage == 3) c.add(el(89.0, 62.5, 3.2, 3.6, silver))
        }

        val tg = if (stage == 0) fluffy(bx, by, rx, ry, 14, 0.07) else Geo.ellipse(bx.toFloat(), by.toFloat(), rx.toFloat(), ry.toFloat())
        addFur(c, shp(tg, furN), furN, furP)

        addFur(c, el(bx, by + ry * 0.14, rx * 0.74, ry * 0.76, bellyN), bellyN, bellyP)
        if (stage >= 2) {
            c.add(ln("M43,78 q1.5,-3 3,0", s("#C9A57A", 150), 1.1))
            c.add(ln("M54,80 q1.5,-3 3,0", s("#C9A57A", 150), 1.1))
            c.add(ln("M48,84 q1.5,-3 3,0", s("#C9A57A", 150), 1.1))
        }
        if (stage == 3) {
            for (i in 0 until 9) {
                val a = (-170 + i * 17.5) * PI / 180
                val px = bx + rx * cos(a); val py = by + ry * sin(a)
                val nx = cos(a); val ny = sin(a)
                c.add(lq(px, py, px + nx * 2 + 1.5, py + ny * 2, px + nx * 3.6 + 2.2, py + ny * 3.6 - 1, silver, 1.7))
            }
        }

        val fy = by + ry - 1.2
        addFur(c, el(bx - rx * 0.52, fy, 7.6, 4.2, furN), furN, furP)
        addFur(c, el(bx + rx * 0.52, fy, 7.6, 4.2, furN), furN, furP)
        c.add(ln("M${n(bx - rx * 0.52 - 2)},${n(fy + 0.8)} l0,2", s("#4A2E20", 150), 0.9))
    }

    private fun buildCup(c: Cv, f: DoubleArray) {
        val cx = 50.0
        val cy = when (stage) { 0 -> f[4] + 1; 1 -> 66.0; else -> 65.0 }
        cup = Cv()
        val glass = Cv()
        cupGlass = glass
        cupWater = PathE(null)
        cup.rt = GroupT(cupRot, cupMove, TransT(cx.toFloat(), cy.toFloat()))

        if (stage == 1) {
            glass.add(fl("M0,7 L-8.6,-0.6 Q-9.4,-8.4 0,-8.4 Q9.4,-8.4 8.6,-0.6 Z", grad("#F8C8DA", "#EE87AC")))
            glass.add(ln("M0,6 L-5.2,-6 M0,6 L0,-7.4 M0,6 L5.2,-6", s("#D9457F", 170), 1.0))
            val w = fl("M-7.8,-2.6 Q0,-5.6 7.8,-2.6 Q0,0.4 -7.8,-2.6Z", s("#4FC3F7"))
            cupWater = w
            glass.add(w)
        } else if (stage >= 2) {
            glass.add(fl("M-6.6,-6 L6.6,-6 L5.1,6 Q0,7.4 -5.1,6 Z", grad("#E1F5FE", "#B3E5FC", 150, 120)))
            val w = fl("M-6,-1.6 Q0,0.2 6,-1.6 L5.1,6 Q0,7.4 -5.1,6 Z", grad("#4FC3F7", "#1E88E5", 235, 245))
            cupWater = w
            glass.add(w)
            glass.add(ln("M-6.6,-6 L6.6,-6", if (stage == 3) s("#FFD54F") else s("#FFFFFF", 230), 1.4))
            glass.add(ln("M-4.2,-3.6 L-3.6,3.6", s("#FFFFFF", 150), 1.1))
            if (stage == 3) glass.add(ln("M6.2,-3.2 Q11.6,-2.6 9.4,2.6 Q8.2,4.4 5.4,4", s("#FFD54F"), 1.5))
        }
        if (stage == 1) glass.rt = ScaleT(0.82f, 0.82f, 0f, 3f)
        cup.add(glass)

        val px = if (stage == 0) 8.4 else 6.8
        val py = if (stage == 0) 0.0 else 1.6
        val pl = Cv().apply { rt = pawL }
        val pr = Cv().apply { rt = pawR }
        addFur(pl, el(-px, py, 4.0, 3.5, furN), furN, furP)
        addFur(pr, el(px, py, 4.0, 3.5, furN), furN, furP)
        pl.add(ln("M${n(-px - 1)},${n(py - 0.6)} l0,2.2", s("#4A2E20", 150), 0.8))
        pr.add(ln("M${n(px + 1)},${n(py - 0.6)} l0,2.2", s("#4A2E20", 150), 0.8))
        cup.add(pl); cup.add(pr)
        c.add(cup)
    }

    // ---------------------------------------------------------------- head
    private fun buildHead(rigC: Cv, f: DoubleArray) {
        val hsv = if (compact) 2.0 else f[0]
        val hx = if (compact) 50.0 else f[1]
        val hy = if (compact) 52.0 else f[2]
        hs = hsv
        val k = if (compact) 1.28 else 1.0
        val sw = if (compact) 1.9 else 1.2

        val h = Cv()
        headG = h
        h.rt = GroupT(headTilt, ScaleT(hsv.toFloat(), hsv.toFloat()), TransT(hx.toFloat(), hy.toFloat()), headMove)

        for (side in intArrayOf(-1, 1)) {
            val ear = Cv().apply { rt = if (side < 0) earL else earR }
            addFur(ear, el(side * 15.8, -13.4, 5.0, 4.7, furN), furN, furP)
            ear.add(el(side * 15.8, -13.0, 2.7, 2.5, s("#F0BFA8")))
            if (stage == 3) ear.add(el(side * 15.8, -17.2, 2.4, 1.3, silver))
            h.add(ear)
        }

        val hg = if (stage == 0) fluffy(0.0, 0.0, 21.6, 18.6, 16, 0.07) else Geo.ellipse(0f, 0f, 21f, 18f)
        addFur(h, shp(hg, furN), furN, furP)
        h.add(el(0.0, -11.4, 10.5, 4.2, s("#FFFFFF", 36)))
        if (stage == 0 && cosmetic != 1) h.add(ln("M0,-17 Q-3.4,-23.5 2.4,-24", s("#9C6B4E"), 2.6))

        if (stage == 3) {
            h.add(fl("M-8.5,-12.6 Q0,-20 8.5,-12.6 Q0,-8.6 -8.5,-12.6Z", s("#E3E8EC", 195)))
            for (i in 0 until 6) {
                val a = (-150 + i * 24) * PI / 180
                val px = 21 * cos(a); val py = 18 * sin(a); val nx = cos(a); val ny = sin(a)
                h.add(lq(px - nx * 1.2, py - ny * 1.2, px + nx * 1.4 + 1.2, py + ny * 1.4, px + nx * 2.4 + 1.2, py + ny * 2.4 - 0.4, silver, 1.25))
            }
        }

        h.add(el(0.0, 8.0, 13.0, 8.8, cream))
        h.add(el(-7.2, 8.4, 7.6, 6.5, cream))
        h.add(el(7.2, 8.4, 7.6, 6.5, cream))
        h.add(el(0.0, 9.5, 11.0, 6.0, s("#FFFFFF", 40)))

        blushC = Cv()
        blushC.add(el(-12.8, 5.2, 3.6, 2.3, s("#FF8A80", 170)))
        blushC.add(el(12.8, 5.2, 3.6, 2.3, s("#FF8A80", 170)))
        h.add(blushC)

        h.add(fl("M-3.6,3.1 Q0,1.1 3.6,3.1 Q2.8,6.7 0,7.1 Q-2.8,6.7 -3.6,3.1Z", s("#34200F")))
        h.add(el(-1.2, 3.3, 1.2, 0.7, s("#FFFFFF", 130)))
        h.add(ln("M0,7 L0,8.7", dark, sw))

        mIdle = Cv().apply { add(ln("M-4.6,8.9 Q-2.3,11.9 0,8.7 Q2.3,11.9 4.6,8.9", dark, sw)) }
        mHappy = Cv().apply {
            add(fl("M-6.2,8.6 Q0,9.6 6.2,8.6 Q5.2,16.4 0,16.4 Q-5.2,16.4 -6.2,8.6Z", s("#4A1D1D")))
            add(fl("M-3.4,15.7 Q0,11.7 3.4,15.7 Q0,16.9 -3.4,15.7Z", s("#F48FB1")))
        }
        mThirsty = Cv().apply {
            add(ln("M-4.2,9.6 L4.2,9.6", dark, sw))
            add(fl("M-1,9.8 L-1,13 Q1.3,15.6 3.6,13 L3.6,9.8Z", s("#F06292")))
            add(ln("M1.3,10.4 L1.3,12.6", s("#C2185B", 190), 0.7))
        }
        mSad = Cv().apply { add(ln("M-4.8,12.6 Q0,7.4 4.8,12.6", dark, sw)) }
        mSleep = Cv().apply { add(ln("M-3.2,8.9 Q-1.6,11 0,8.7 Q1.6,11 3.2,8.9", dark, sw)) }
        h.add(mIdle); h.add(mHappy); h.add(mThirsty); h.add(mSad); h.add(mSleep)

        buildEye(eL, -8.6, k); buildEye(eR, 8.6, k)
        h.add(eL.root); h.add(eR.root)

        sadBrows = Cv()
        sadBrows.add(ln("M-13,-5.4 L-5,-9.4", s("#F3DEC4"), 1.7))
        sadBrows.add(ln("M13,-5.4 L5,-9.4", s("#F3DEC4"), 1.7))
        if (!compact) h.add(sadBrows)

        if (stage == 3 && !compact) {
            for (sd in intArrayOf(-1, 1)) h.add(lq(sd * 12.6, -6.4, sd * 9.6, -10.6, sd * 5.0, -8.0, silver, 2.1))
        }

        sweatC = Cv().apply { rt = TransT(17.8f, -7.6f) }
        sweatC.add(shp(g("M0,-5 C-1.6,-2.2 -3.4,-0.4 -3.4,1.7 A3.4,3.4 0 0 0 3.4,1.7 C3.4,-0.4 1.6,-2.2 0,-5Z"), grad("#B3E5FC", "#29B6F6"), s("#0277BD"), 0.7))
        sweatC.add(el(-1.1, 1.3, 0.9, 1.4, s("#FFFFFF", 200)))
        h.add(sweatC)

        if (!compact) {
            val elder = stage == 3
            val wb = if (elder) s("#FFFFFF", 235) else s("#FFF6E6", 230)
            val w = if (elder) 1.35 else 1.15
            for (sd in intArrayOf(-1, 1)) {
                h.add(lq(sd * 9.5, 7.8, sd * 16.0, 4.4, sd * (if (elder) 24.0 else 20.5), 5.4, wb, w))
                h.add(lq(sd * 10.0, 9.8, sd * 17.0, 9.4, sd * (if (elder) 25.0 else 21.5), 10.4, wb, w))
                h.add(lq(sd * 9.5, 11.6, sd * 15.5, 14.0, sd * (if (elder) 23.0 else 19.5), 15.6, wb, w))
                if (elder) h.add(lq(sd * 9.0, 13.0, sd * 14.0, 17.2, sd * 21.0, 20.2, wb, w))
                for ((dx, dy) in listOf(5.8 to 9.2, 7.2 to 10.8, 5.6 to 11.8)) h.add(el(sd * dx, dy, 0.55, 0.55, s("#8A6040")))
            }
        }

        when (cosmetic) {
            1 -> {
                h.add(fl("M-15.6,-7 C-17,-27 17,-27 15.6,-7 Q0,-10.2 -15.6,-7Z", grad("#EF5350", "#B71C1C")))
                h.add(ln("M-8,-19 Q-9.5,-14 -9,-9.2 M0,-22 L0,-9.6 M8,-19 Q9.5,-14 9,-9.2", s("#B71C1C", 140), 1.1))
                h.add(fl("M-17.2,-9.2 Q0,-13.4 17.2,-9.2 L16.6,-3.4 Q0,-7.6 -16.6,-3.4Z", s("#FFF3E0")))
                h.add(ln("M-14,-7.4 L-14,-5.2 M-8,-8.4 L-8,-6.2 M-2,-8.8 L-2,-6.6 M4,-8.8 L4,-6.6 M10,-8.4 L10,-6.2", s("#E8C9A0"), 1.1))
                h.add(el(0.0, -24.6, 4.4, 4.4, s("#FFF8EC")))
                h.add(el(-1.0, -25.6, 1.5, 1.2, s("#FFFFFF")))
            }
            2 -> {
                val lens = grad("#455A64", "#0E1418", 242, 242)
                h.add(ln("M-14.4,-2.4 L-20,-5.4 M14.4,-2.4 L20,-5.4", s("#10161A"), 1.4))
                h.add(el(-8.6, -1.2, 6.5, 5.0, lens, s("#10161A"), 1.4))
                h.add(el(8.6, -1.2, 6.5, 5.0, lens, s("#10161A"), 1.4))
                h.add(ln("M-2.2,-2.2 Q0,-3.8 2.2,-2.2", s("#10161A"), 1.5))
                h.add(ln("M-11.6,-3.4 L-9.2,-4.4 M5.6,-3.4 L8,-4.4", s("#FFFFFF", 170), 1.2))
            }
            3 -> {
                val bowC = grad("#F48FB1", "#C2185B")
                val bow = Cv().apply { rt = TransT(0f, 17.6f) }
                bow.add(shp(g("M0,0 L-9,-5.4 Q-10.4,0 -9,5.4Z"), bowC, s("#880E4F", 160), 0.6))
                bow.add(shp(g("M0,0 L9,-5.4 Q10.4,0 9,5.4Z"), bowC, s("#880E4F", 160), 0.6))
                bow.add(el(0.0, 0.0, 2.7, 3.2, grad("#F06292", "#AD1457"), s("#880E4F", 160), 0.6))
                h.add(bow)
            }
        }

        rigC.add(h)
    }

    private fun buildEye(e: EyeParts, x: Double, k: Double) {
        e.open.add(el(0.0, 0.0, 3.5 * k, 4.2 * k, eyeFill))
        e.open.add(el(1.25 * k, -1.6 * k, 1.35 * k, 1.35 * k, white))
        e.open.add(el(-1.3 * k, 1.7 * k, 0.68 * k, 0.68 * k, s("#FFFFFF", 190)))
        e.happy = shp(q(-3.9 * k, 2 * k, 0.0, -4.4 * k, 3.9 * k, 2 * k), null, dark, 1.9 * (if (compact) 1.15 else 1.0))
        e.sleep = shp(q(-3.9 * k, -0.8 * k, 0.0, 3.6 * k, 3.9 * k, -0.8 * k), null, dark, 1.7 * (if (compact) 1.15 else 1.0))
        e.root.add(e.open); e.root.add(e.happy!!); e.root.add(e.sleep!!)
        e.root.rt = GroupT(e.blink, e.look, TransT(x.toFloat(), -1.2f))
    }

    // =====================================================================================
    //  MOOD / WATER / LOOK / BLINK
    // =====================================================================================
    override fun changeMood(m: Mood) {
        mood = m
        setSleepFx(m == Mood.SLEEPING)
        val sl = m == Mood.SLEEPING; val hp = m == Mood.HAPPY; val th = m == Mood.THIRSTY; val vt = m == Mood.VERY_THIRSTY
        val open = !(sl || hp)
        for (e in listOf(eL, eR)) {
            e.open.visible = open; e.happy?.visible = hp; e.sleep?.visible = sl
            if (sl) { e.look.x.set(0f); e.look.y.set(0f) }
        }
        sadBrows.visible = vt
        mIdle.visible = m == Mood.IDLE; mHappy.visible = hp; mThirsty.visible = th
        mSad.visible = vt; mSleep.visible = sl
        blushC.opacity.set(if (hp) 1f else if (sl) 0.6f else if (m == Mood.IDLE) 0.45f else 0f)
        sweatC.visible = th || vt

        val ear = if (vt) -46f else if (th) -14f else if (sl) -7f else if (hp) 5f else 0f
        earL.angle.set(ear); earR.angle.set(-ear)
        headTilt.angle.set(if (sl) 7f else if (vt) -4f else 0f)
        headMove.y.set(if (vt) 2.2f else if (sl) 1.4f else 0f)
        for (fr in fur) {
            val b = if (vt) fr.pa else fr.n
            if (fr.stroke) fr.p.stroke = b else fr.p.fill = b
        }
        if (!compact) {
            cupGlass?.visible = !sl
            cupWater?.visible = m == Mood.IDLE || hp
            val p = if (sl) (if (stage == 0) 5.6f else 4.4f) else 0f
            pawL.x.set(p); pawR.x.set(-p)
        }
        if (idling) startIdle()
    }

    override fun setWater(fraction: Double, animate: Boolean) {
        val fv = fraction.coerceIn(0.0, 1.0)
        water = fv
        if (compact) return
        val surf = (100 - fv * 36).toFloat()
        tween(lvlBack, surf, animate)
        tween(lvlFront, surf + 1.5f, animate)
        tween(rise, (-fv * 7).toFloat(), animate)
        cracks?.opacity?.set((1 - fv * 5).toFloat().coerceIn(0f, 1f))
    }

    private fun tween(t: TransT, to: Float, animate: Boolean) {
        val from = t.y.value()
        t.y.begin(null)
        t.y.set(to)
        if (animate && abs(from - to) > 0.05f) {
            t.y.begin(DA(from, to, 700, CubicE(EM.INOUT)).apply { fillStop = true })
        }
    }

    override fun lookAt(dx: Double, dy: Double) {
        if (mood == Mood.SLEEPING) return
        val r = 2.5 / hs
        val x = (dx.coerceIn(-1.0, 1.0) * r).toFloat()
        val y = (dy.coerceIn(-1.0, 1.0) * r).toFloat()
        eL.look.x.set(x); eL.look.y.set(y); eR.look.x.set(x); eR.look.y.set(y)
    }

    override fun blink() {
        if (mood == Mood.SLEEPING || mood == Mood.HAPPY) return
        for (e in listOf(eL, eR)) e.blink.sy.begin(KA(listOf(KF(0.1f, 70), KF(1f, 160))).apply { fillStop = true })
    }

    // =====================================================================================
    //  ANIMATION
    // =====================================================================================
    override fun startIdle() {
        super.startIdle()
        if (compact) return
        val sl = mood == Mood.SLEEPING
        val a = if (sl) 2.5f else 4f
        rock.angle.begin(DA(-a, a, if (sl) 3400 else 2600, SineE(EM.INOUT)).apply { autoReverse = true; forever = true })
        waveBack.x.begin(DA(0f, (-wavePeriod).toFloat(), if (sl) 6200 else 4200).apply { forever = true })
        waveFront.x.begin(DA(-12f, (-12 - wavePeriod).toFloat(), if (sl) 4800 else 3100).apply { forever = true })
    }

    override fun stopIdle() {
        super.stopIdle()
        if (compact) return
        rock.angle.begin(null)
        waveBack.x.begin(null)
        waveFront.x.begin(null)
    }

    private fun kf(vararg k: Pair<Float, Long>) = KA(k.map { KF(it.first, it.second, SineE(EM.INOUT)) }).apply { fillStop = true }

    override fun playDrink() {
        super.playDrink()
        val t0 = headTilt.angle.value()
        headTilt.angle.begin(kf(t0 to 0L, (t0 - 9) to 320L, (t0 - 9) to 860L, t0 to 1250L))
        if (!compact && stage > 0 && mood != Mood.SLEEPING) {
            cupMove.y.begin(kf(0f to 0L, -10f to 320L, -10f to 860L, 0f to 1250L))
            cupRot.angle.begin(kf(0f to 0L, -22f to 320L, -22f to 860L, 0f to 1250L))
        }
        if (mood != Mood.HAPPY && mood != Mood.SLEEPING)
            for (e in listOf(eL, eR)) e.blink.sy.begin(kf(1f to 0L, 0.12f to 260L, 0.12f to 860L, 1f to 1150L))
    }
}
