package com.rsv.dynamicisland.pet

import android.graphics.Color
import com.rsv.dynamicisland.Mood
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Port of Windows Pets/PlantSkin.cs. Potted plant with a kawaii face on the pot. The face carries
 * the moods, the leaves droop / perk. Scene = pot + face + soil (moisture). Body = the plant.
 */
class PlantSkin : PetSkin() {
    override val kind = PetKind.PLANT

    // ---------- shared brushes ----------
    private fun b(r: Int, g: Int, bl: Int, a: Int = 255) = SolidB(Color.argb(a, r, g, bl))
    private fun vGrad(top: Int, bot: Int) = LinB.angle(rgb(top), rgb(bot), 90.0)
    private fun aGrad(c0: Int, c1: Int, angle: Double) = LinB.angle(rgb(c0), rgb(c1), angle)

    private val ink = b(0x3A, 0x1F, 0x18)
    private val eyeFill = vGrad(0x4D2E24, 0x1B0E0A)
    private val white = b(255, 255, 255)
    private val hiWhite = b(255, 255, 255, 230)
    private val blushBrush = b(0xFF, 0x6E, 0x93)
    private val dropFill = vGrad(0xB4EAFF, 0x35A4EC)
    private val veinBrush = b(255, 255, 255, 105)
    private val shade = b(0, 0, 0, 40)
    private val mouthIn = b(0x56, 0x22, 0x1C)
    private val tongueB = b(0xFF, 0x8F, 0xA6)
    private val soilSpeck = b(0, 0, 0, 38)
    private val glint = b(255, 255, 255, 75)
    private val crackBrush = b(0x8A, 0x66, 0x3C)

    private fun leafPath(l: Double, w: Double) =
        "M0,0 C${f(-w)},${f(-l * .18)} ${f(-w * 1.05)},${f(-l * .72)} 0,${f(-l)} C${f(w * 1.05)},${f(-l * .72)} ${f(w)},${f(-l * .18)} 0,0 Z"

    private fun fade(e: El?, to: Double, anim: Boolean, ms: Long = 450) {
        if (e == null) return
        if (anim) e.opacity.begin(DA(null, to.toFloat(), ms))
        else { e.opacity.begin(null); e.opacity.set(to.toFloat()) }
    }

    private fun hue(c: ColorProp, to: Int, anim: Boolean, ms: Long = 450) {
        if (anim) c.begin(CA(to, ms)) else { c.begin(null); c.base = to }
    }

    private fun ang(r: RotT, to: Double, anim: Boolean, ms: Long = 380) {
        if (anim) r.angle.begin(DA(null, to.toFloat(), ms, CubicE(EM.OUT)))
        else { r.angle.begin(null); r.angle.set(to.toFloat()) }
    }

    private fun vis(l: List<El>, on: Boolean) { for (e in l) e.visible = on }
    private fun vis(e: El?, on: Boolean) { e?.visible = on }

    // ---------- state ----------
    private class LeafInfo {
        val pose = RotT()
        val sway = RotT()
        var ang = 0.0
        var dir = 1
        var flower = false
        var s0: Stop? = null
        var s1: Stop? = null
        var c0 = 0
        var c1 = 0
        var fScale: ScaleT? = null
    }

    private val leaves = ArrayList<LeafInfo>()
    private val dropsL = ArrayList<El>()
    private val openEyes = ArrayList<El>()
    private val happyEyes = ArrayList<El>()
    private val sleepEyes = ArrayList<El>()
    private val browsL = ArrayList<El>()
    private val tears = ArrayList<El>()
    private val blushL = ArrayList<El>()
    private var mIdle: El? = null
    private var mHappy: El? = null
    private var mFlat: El? = null
    private var mSad: El? = null
    private var mSleep: El? = null
    private var sweat: El? = null
    private var cracksE: El? = null
    private var sheen: El? = null

    private var plant: Cv? = null
    private val sprinkle = ArrayList<Pair<El, TransT>>()
    private var grow: ScaleT? = null
    private var sceneScale: ScaleT? = null
    private var blinkT: ScaleT? = null
    private var eyeMood: ScaleT? = null
    private var swayT: RotT? = null
    private var kick: RotT? = null
    private var lean: RotT? = null
    private var sceneMove: TransT? = null
    private var look: TransT? = null
    private var stemBrush: SolidB? = null
    private var soilBrush: LinB? = null
    private var soil0: Stop? = null
    private var soil1: Stop? = null
    private var snapMood = false

    // =====================================================================================
    //  SCENE : pot, soil, face
    // =====================================================================================
    override fun buildScene(scene: Cv) {
        snapMood = true
        val sm = TransT()
        val ss = ScaleT(1f, 1f, 50f, 92f)
        sceneMove = sm; sceneScale = ss
        scene.rt = GroupT(ss, sm)

        val s0 = Stop(rgb(0xE9D3A6), 0f)
        val s1 = Stop(rgb(0xCDAE7E), 1f)
        soil0 = s0; soil1 = s1
        soilBrush = LinB(listOf(s0, s1), 0.5f, 0f, 0.5f, 1f)

        openEyes.clear(); happyEyes.clear(); sleepEyes.clear(); browsL.clear(); tears.clear(); blushL.clear()
        cracksE = null; sheen = null

        if (compact) buildPotCompact(scene) else buildPot(scene)
    }

    private class P5(val bt: Int, val bb: Int, val rt: Int, val rb: Int, val lt: Int)

    private fun pal(): P5 = when (cosmetic) {
        1 -> P5(0x72C0F8, 0x2B69BF, 0x8FD0FF, 0x4A8FE0, 0xC4E8FF)
        3 -> P5(0xFFDA6A, 0xC48808, 0xFFE27A, 0xE8A91C, 0xFFF8D2)
        else -> P5(0xE0794F, 0xB5532E, 0xEE8A60, 0xD0653A, 0xF8A57F)
    }

    private val starD = "M0,-1 L0.25,-0.25 1,0 0.25,0.25 0,1 -0.25,0.25 -1,0 -0.25,-0.25 Z"

    private fun buildPot(c: Cv) {
        val p = pal()
        c.add(pathE(g("M23,70 L77,70 L72,94 Q71.4,98.5 66.5,98.5 L33.5,98.5 Q28.6,98.5 28,94 Z"), vGrad(p.bt, p.bb)))
        c.add(PathE(g("M29.2,76 Q28.4,85 31.4,93")).apply { stroke = if (cosmetic == 3) b(255, 255, 255, 120) else glint; sw = 3.2f; roundCap = true })
        c.add(PathE(g("M71.4,77 Q72.4,86 70,94")).apply { stroke = shade; sw = 3.4f; roundCap = true })
        c.add(pathE(g("M23.4,70.5 L76.6,70.5 L75.8,75.4 Q50,78.4 24.2,75.4 Z"), b(0, 0, 0, 44)))
        if (cosmetic == 2) {
            c.add(pathE(g("M24,71.2 L76,71.2 L75.3,75.2 L24.7,75.2 Z"), vGrad(0xFF86AA, 0xE0467A)))
            c.add(PathE(g("M24.4,72.3 L75.6,72.3")).apply { stroke = b(255, 255, 255, 110); sw = 0.9f })
        }
        c.add(at(rectE(64, 9.6, vGrad(p.rt, p.rb), 3.8, 3.8), 18, 62))
        c.add(at(ellipse(64, 12.4, vGrad(p.lt, p.rt)), 18, 55.8))
        c.add(at(ellipse(55, 8.6, soilBrush), 22.5, 57.7))
        c.add(PathE(g("M23.4,61 A26.6,4.1 0 0 1 76.6,61")).apply { stroke = b(0, 0, 0, 55); sw = 1.3f })
        val specks = Cv()
        for ((x, y, w) in listOf(Triple(33.0, 62.6, 2.6), Triple(44.0, 60.0, 2.0), Triple(57.0, 64.0, 2.6), Triple(65.0, 60.8, 2.0), Triple(70.5, 63.4, 2.4), Triple(39.5, 65.0, 2.0)))
            specks.add(at(ellipse(w, w * .5, soilSpeck), x - w / 2, y))
        c.add(specks)
        val cr = PathE(g("M31.5,62.3 L36,61 L38.6,63.4 M40,64.8 L43.5,63.8 M56,59.5 L59,61.2 L63.5,60.6 M66,63.8 L70,62.4 M47.5,65.6 L50.5,64.8")).apply {
            stroke = crackBrush; sw = 0.95f; roundJoin = true; roundCap = true; opacity.set(0f)
        }
        cracksE = cr; c.add(cr)
        val sh = Cv().apply { opacity.set(0f) }
        sh.add(at(ellipse(17, 1.9, glint), 31, 58.9))
        sh.add(at(ellipse(6, 1.5, glint), 60, 60.2))
        sheen = sh; c.add(sh)

        if (cosmetic == 1) {
            for ((x, len) in listOf(25.0 to 5.2, 35.2 to 3.0, 64.8 to 3.6, 73.5 to 5.6))
                c.add(pathE(g("M${f(x - 1.7)},71 L${f(x - 1.7)},${f(71 + len)} A1.7,1.7 0 0 0 ${f(x + 1.7)},${f(71 + len)} L${f(x + 1.7)},71 Z"), b(0xD6, 0xEF, 0xFF, 215)))
            c.add(at(ellipse(3.4, 3.4, b(255, 255, 255, 140)), 60, 56.6))
        } else if (cosmetic == 3) {
            c.add(at(pathE(g(starD), white).apply { rt = ScaleT(3.6f, 3.6f) }, 28.5, 66))
            c.add(at(pathE(g(starD), b(255, 255, 255, 230)).apply { rt = ScaleT(2.2f, 2.2f) }, 71.5, 77))
        }

        buildFace(c, 11.0, 80.5, 89.0, 17.0, 87.4, 1.0, 1.0, 1.9, 1.9, 71.5, 75.5, 1.1)

        if (cosmetic == 2) c.add(bow(27.0, 72.0, 0.82))
    }

    private fun buildPotCompact(c: Cv) {
        val p = pal()
        c.add(pathE(g("M17,64 L83,64 L77.5,93 Q76.8,98.5 70.5,98.5 L29.5,98.5 Q23.2,98.5 22.5,93 Z"), vGrad(p.bt, p.bb)))
        c.add(PathE(g("M24,69 Q23.6,80 26.6,91")).apply { stroke = if (cosmetic == 3) b(255, 255, 255, 130) else glint; sw = 5f; roundCap = true })
        if (cosmetic == 2) c.add(pathE(g("M17.5,64 L82.5,64 L81.7,70.5 L18.3,70.5 Z"), vGrad(0xFF86AA, 0xE0467A)))
        c.add(at(rectE(76, 11, vGrad(p.rt, p.rb), 5, 5), 12, 54))
        c.add(at(ellipse(76, 15, vGrad(p.lt, p.rt)), 12, 46.5))
        c.add(at(ellipse(64, 10.5, soilBrush), 18, 48.8))
        if (cosmetic == 3) c.add(at(pathE(g(starD), white).apply { rt = ScaleT(6f, 6f) }, 76, 58))

        buildFace(c, 13.0, 77.0, 87.5, 22.0, 86.5, 1.65, 1.35, 3.4, 4.2, 80.0, 70.0, 1.8)

        if (cosmetic == 2) c.add(bow(24.0, 66.0, 1.35))
    }

    private fun bow(x: Double, y: Double, s: Double): Cv {
        val bw = Cv().apply { rt = ScaleT(s.toFloat(), s.toFloat()) }
        val fill = vGrad(0xFF8EB0, 0xE0467A)
        bw.add(pathE(g("M-0.8,3 L-6.4,11.6 L-2.8,10.4 L-0.4,13 Z"), vGrad(0xF0507F, 0xC93468)))
        bw.add(pathE(g("M0.8,3 L6.4,11.6 L2.8,10.4 L0.4,13 Z"), vGrad(0xF0507F, 0xC93468)))
        bw.add(pathE(g("M0,0 C-5,-7.5 -13,-5.5 -12,0 C-13,5.5 -5,7.5 0,0 Z"), fill))
        bw.add(pathE(g("M0,0 C5,-7.5 13,-5.5 12,0 C13,5.5 5,7.5 0,0 Z"), fill))
        bw.add(at(ellipse(5.6, 6, vGrad(0xFF6C96, 0xD23A6C)), -2.8, -3))
        bw.add(at(ellipse(2, 1.4, hiWhite), -1.7, -2.3))
        val host = Cv()
        host.add(bw)
        return at(host, x, y)
    }

    // ---------- face ----------
    private fun makeDrop(s: Double): Cv {
        val d = Cv().apply { rt = ScaleT(s.toFloat(), s.toFloat()) }
        d.add(pathE(g("M0,-4.4 C-0.6,-2.4 -3.1,-0.9 -3.1,1.5 A3.1,3.1 0 0 0 3.1,1.5 C3.1,-0.9 0.6,-2.4 0,-4.4 Z"), dropFill))
        d.add(at(ellipse(1.3, 1.9, hiWhite), -1.9, -0.2))
        return d
    }

    private fun buildFace(c: Cv, ex: Double, ey: Double, my: Double, bx: Double, by: Double, ke: Double, km: Double,
                          swe: Double, swm: Double, sxp: Double, syp: Double, ss: Double) {
        val lk = TransT(); val bt = ScaleT(1f, 1f); val em = ScaleT(1f, 1f)
        look = lk; blinkT = bt; eyeMood = em
        val bk = if (compact) 1.5 else 1.0

        for (side in intArrayOf(-1, 1)) {
            val bl = ellipse(9.4 * bk, 5.4 * bk, blushBrush).apply { opacity.set(0.5f) }
            c.add(at(bl, 50 + side * bx - 4.7 * bk, by - 2.7 * bk))
            blushL.add(bl)
        }

        for (side in intArrayOf(-1, 1)) {
            val box = Cv()
            val open = Cv()
            open.add(at(ellipse(7.6, 9.4, eyeFill), -3.8, -4.7))
            open.add(at(ellipse(3.1, 3.1, white), -2.9, -3.8))
            open.add(at(ellipse(1.5, 1.5, hiWhite), 0.9, 1.4))
            open.rt = GroupT(em, bt, ScaleT(ke.toFloat(), ke.toFloat()), lk)
            box.add(open); openEyes.add(open)

            val happy = arcPath("M-4.2,1.8 Q0,-4.4 4.2,1.8", swe, ke); box.add(happy); happyEyes.add(happy)
            val sleep = arcPath("M-4.4,-0.8 Q0,3.8 4.4,-0.8", swe, ke); box.add(sleep); sleepEyes.add(sleep)
            val brow = arcPath(if (side < 0) "M-4.6,-5.8 L3.4,-8.2" else "M-3.4,-8.2 L4.6,-5.8", swe, ke); box.add(brow); browsL.add(brow)
            val tear = makeDrop(0.95 * ke); at(tear, side * 5.6 * ke, 6.6 * ke); tear.visible = false; box.add(tear); tears.add(tear)
            c.add(at(box, 50 + side * ex, ey))
        }

        val mouth = Cv().apply { rt = ScaleT(km.toFloat(), km.toFloat()) }
        val mi = strokeP("M-3.8,0 Q0,3.8 3.8,0", swm)
        val mf = strokeP("M-3.2,0.8 L3.2,0.8", swm)
        val ms = strokeP("M-4.2,2.8 Q0,-2.4 4.2,2.8", swm)
        val hm = Cv()
        hm.add(pathE(g("M-5.2,-1.2 Q0,-2 5.2,-1.2 Q4.8,6.8 0,6.8 Q-4.8,6.8 -5.2,-1.2 Z"), mouthIn))
        hm.add(at(ellipse(5.4, 2.8, tongueB), -2.7, 3.1))
        val msl = at(ellipse(3.6, 4.2, mouthIn), -1.8, -0.6)
        mIdle = mi; mFlat = mf; mSad = ms; mHappy = hm; mSleep = msl
        for (m in listOf(mi, mf, ms, hm, msl)) { m.visible = false; mouth.add(m) }
        c.add(at(mouth, 50, my))

        val sw = makeDrop(ss); at(sw, sxp, syp); sw.visible = false; sweat = sw; c.add(sw)
    }

    private fun arcPath(d: String, sw: Double, k: Double) = PathE(g(d)).apply {
        stroke = ink; this.sw = sw.toFloat(); roundCap = true
        rt = ScaleT(k.toFloat(), k.toFloat()); visible = false
    }

    private fun strokeP(d: String, sw: Double) = PathE(g(d)).apply { stroke = ink; this.sw = sw.toFloat(); roundCap = true }

    // =====================================================================================
    //  BODY : the plant
    // =====================================================================================
    override fun buildBody(body: Cv) {
        leaves.clear(); dropsL.clear(); sprinkle.clear()
        val s = if (compact) 54f else 62f
        stemBrush = SolidB(rgb(0x3BAA62))
        val gr = ScaleT(1f, 1f, 50f, s); val sw = RotT(0f, 50f, s); val kk = RotT(0f, 50f, s); val ln = RotT(0f, 50f, s)
        grow = gr; swayT = sw; kick = kk; lean = ln
        val pl = Cv().apply { rt = GroupT(gr, sw, kk) }
        plant = pl
        val stem = Cv().apply { rt = ln }
        pl.add(stem)

        if (compact) buildPlantCompact(stem) else buildPlant(stem)
        body.add(pl)

        val rx = if (compact) (if (stage >= 2) 11.0 else 8.0) else (if (stage >= 2) 10.0 else 6.5)
        val ry = if (compact) 3.4 else 2.5
        body.add(at(ellipse(rx * 2, ry * 2, soilBrush), 50 - rx, s + 0.5 - ry))

        if (!compact) {
            val sp = Cv()
            fx.kids.add(0, sp)
            for (i in 0 until 7) {
                val d = makeDrop(1.05)
                val tt = TransT()
                d.opacity.set(0f)
                d.rt = GroupT(ScaleT(1.05f, 1.05f), tt)
                sp.add(at(d, 28 + i * 7.4, 4))
                sprinkle.add(d to tt)
            }
        }
    }

    private fun stemPath(d: String, w: Double) = PathE(g(d)).apply { stroke = stemBrush; sw = w.toFloat(); roundCap = true }

    private fun newHost(li: LeafInfo, x: Double, y: Double, angle: Double): Cv {
        li.pose.angle.set(angle.toFloat())
        return at(Cv().apply { rt = GroupT(li.pose, li.sway) }, x, y)
    }

    private fun addLeaf(parent: Cv, x: Double, y: Double, angle: Double, l: Double, w: Double, dark: Double, drop: Boolean) {
        val li = LeafInfo().apply { ang = angle; dir = if (angle < 0) -1 else 1 }
        li.c0 = mix(rgb(0x2E9E5B), rgb(0x1A6A40), dark)
        li.c1 = mix(rgb(0x7BD389), rgb(0x45AD6B), dark)
        val s0 = Stop(li.c0, 0f); val s1 = Stop(li.c1, 1f)
        li.s0 = s0; li.s1 = s1
        val br = LinB(listOf(s0, s1), 0.35f, 1f, 0.65f, 0f)
        val host = newHost(li, x, y, angle)
        host.add(pathE(g(leafPath(l, w)), br))
        if (!compact)
            host.add(PathE(g("M0,${f(-l * .1)} Q${f(w * .1)},${f(-l * .5)} 0,${f(-l * .84)}")).apply { stroke = veinBrush; sw = 0.9f; roundCap = true })
        if (drop && !compact) {
            val d = makeDrop(0.8); d.opacity.set(0f)
            host.add(at(d, -li.dir * w * 0.2, -l * 0.56))
            dropsL.add(d)
        }
        parent.add(host)
        leaves.add(li)
    }

    private fun addFlower(parent: Cv, x: Double, y: Double, angle: Double, l: Double, hi: Int, lo: Int, r: Double) {
        val li = LeafInfo().apply { ang = angle; dir = if (angle < 0) -1 else 1; flower = true }
        val host = newHost(li, x, y, angle)
        host.add(stemPath("M0,0 Q${f(l * .07)},${f(-l * .5)} 0,${f(-l)}", if (compact) 4.6 else 2.3))
        val fs = ScaleT(1f, 1f)
        li.fScale = fs
        val fg = Cv().apply { rt = fs }
        val petal = aGrad(lo, hi, 270.0)
        val pw = 0.78 * r; val ph = 1.06 * r; val d = 0.6 * r
        for (k in 0 until 5)
            fg.add(at(ellipse(pw, ph, petal).apply { rt = RotT((k * 72).toFloat(), (pw / 2).toFloat(), (d + ph / 2).toFloat()) }, -pw / 2, -d - ph / 2))
        val cd = 0.62 * r
        fg.add(at(ellipse(cd, cd, vGrad(0xFFE88E, 0xF3A100)), -cd / 2, -cd / 2))
        if (!compact) fg.add(at(ellipse(cd * .32, cd * .26, hiWhite), -cd * .3, -cd * .32))
        at(fg, 0, -l)
        host.add(fg)
        parent.add(host)
        leaves.add(li)
    }

    private fun addBud(parent: Cv, x: Double, y: Double, angle: Double, l: Double) {
        val li = LeafInfo().apply { ang = angle; dir = if (angle < 0) -1 else 1; flower = true }
        val host = newHost(li, x, y, angle)
        host.add(stemPath("M0,0 Q${f(l * .05)},${f(-l * .5)} 0,${f(-l + 1)}", 2.2))
        val fs = ScaleT(1f, 1f)
        li.fScale = fs
        val gC = Cv().apply { rt = fs }
        gC.add(pathE(g("M0,-10 C3.6,-5.6 3.8,-1.2 0,0.8 C-3.8,-1.2 -3.6,-5.6 0,-10 Z"), vGrad(0xFFC0D2, 0xFF7FA6)))
        gC.add(pathE(g("M-4.2,-3.4 Q0,3.4 4.2,-3.4 Q0,-1.4 -4.2,-3.4 Z"), vGrad(0x6DCB87, 0x2E9E5B)))
        at(gC, 0, -l)
        host.add(gC)
        parent.add(host)
        leaves.add(li)
    }

    private fun buildPlant(stem: Cv) {
        when (stage) {
            0 -> {
                stem.add(stemPath("M50,62 L50,47", 3.8))
                addLeaf(stem, 50.0, 48.5, -62.0, 22.0, 12.5, 0.0, true)
                addLeaf(stem, 50.0, 48.5, 62.0, 22.0, 12.5, 0.0, true)
            }
            1 -> {
                stem.add(stemPath("M50,62 C47.5,54 52.5,44 50,33.5", 3.6))
                addLeaf(stem, 49.4, 51.0, -66.0, 21.0, 11.0, 0.12, true)
                addLeaf(stem, 49.4, 51.0, 66.0, 21.0, 11.0, 0.12, false)
                addLeaf(stem, 50.6, 39.0, -38.0, 20.0, 10.5, 0.0, false)
                addLeaf(stem, 50.6, 39.0, 38.0, 20.0, 10.5, 0.0, true)
                stem.add(at(ellipse(5.4, 5.4, stemBrush), 47.3, 31.4))
            }
            2 -> bushLeaves(stem, 1.0)
            else -> {
                bushLeaves(stem, 0.74)
                addFlower(stem, 50.0, 62.0, -32.0, 38.0, 0xFFD3E0, 0xFF6F9B, 8.6)
                addFlower(stem, 50.0, 62.0, 30.0, 36.0, 0xFFF3B0, 0xFFC247, 8.2)
                addFlower(stem, 50.0, 62.0, 3.0, 47.0, 0xFFC4D6, 0xFF7FA6, 8.8)
                addBud(stem, 50.0, 62.0, 60.0, 27.0)
                addLeaf(stem, 46.5, 62.0, -42.0, 20.0, 9.0, 0.0, true)
                addLeaf(stem, 53.5, 62.0, 44.0, 20.0, 9.0, 0.0, true)
            }
        }
    }

    private fun bushLeaves(s: Cv, k: Double) {
        val w = 1.28
        addLeaf(s, 46.0, 62.0, -54.0, 25 * k, 10 * k * w, .5, false)
        addLeaf(s, 54.0, 62.0, 54.0, 25 * k, 10 * k * w, .5, false)
        addLeaf(s, 48.0, 62.0, -24.0, 31 * k, 11 * k * w, .42, false)
        addLeaf(s, 52.0, 62.0, 24.0, 31 * k, 11 * k * w, .42, true)
        if (k > 0.9) addLeaf(s, 50.0, 62.0, 2.0, 34.0, 11 * w, .46, false)
        if (k > 0.9) {
            addLeaf(s, 44.5, 62.0, -76.0, 22.0, 9.5 * w, 0.02, true)
            addLeaf(s, 55.5, 62.0, 76.0, 22.0, 9.5 * w, 0.02, false)
            addLeaf(s, 46.0, 62.0, -46.0, 27.0, 10.5 * w, 0.04, false)
            addLeaf(s, 54.0, 62.0, 46.0, 27.0, 10.5 * w, 0.04, true)
            addLeaf(s, 49.0, 62.0, -14.0, 28.0, 10.5 * w, 0.0, true)
            addLeaf(s, 51.0, 62.0, 16.0, 23.0, 9.5 * w, 0.08, false)
        }
    }

    private fun buildPlantCompact(stem: Cv) {
        val s = 54.0
        when (stage) {
            0 -> {
                stem.add(stemPath("M50,${f(s)} L50,43", 6.0))
                addLeaf(stem, 50.0, 45.0, -58.0, 25.0, 15.0, 0.0, false)
                addLeaf(stem, 50.0, 45.0, 58.0, 25.0, 15.0, 0.0, false)
            }
            1 -> {
                stem.add(stemPath("M50,${f(s)} C47,46 53,38 50,31", 6.0))
                addLeaf(stem, 49.0, 46.0, -64.0, 25.0, 14.0, .1, false)
                addLeaf(stem, 49.0, 46.0, 64.0, 25.0, 14.0, .1, false)
                addLeaf(stem, 50.5, 35.0, -35.0, 23.0, 13.0, 0.0, false)
                addLeaf(stem, 50.5, 35.0, 35.0, 23.0, 13.0, 0.0, false)
            }
            2 -> {
                addLeaf(stem, 45.0, s, -68.0, 25.0, 12.0, .35, false)
                addLeaf(stem, 55.0, s, 68.0, 25.0, 12.0, .35, false)
                addLeaf(stem, 47.0, s, -34.0, 32.0, 13.0, .1, false)
                addLeaf(stem, 53.0, s, 34.0, 32.0, 13.0, .1, false)
                addLeaf(stem, 50.0, s, 0.0, 38.0, 13.0, 0.0, false)
            }
            else -> {
                addLeaf(stem, 45.0, s, -70.0, 22.0, 11.0, .35, false)
                addLeaf(stem, 55.0, s, 70.0, 22.0, 11.0, .35, false)
                addLeaf(stem, 47.0, s, -38.0, 27.0, 12.0, .1, false)
                addLeaf(stem, 53.0, s, 38.0, 27.0, 12.0, .1, false)
                addFlower(stem, 50.0, s, 0.0, 31.0, 0xFFD3E0, 0xFF6F9B, 10.0)
            }
        }
    }

    // =====================================================================================
    //  MOOD / WATER / EYES
    // =====================================================================================
    private fun tint0(m: Mood, c: Int): Int = when (m) {
        Mood.THIRSTY -> mix(c, rgb(0xA9B545), 0.45)
        Mood.VERY_THIRSTY -> mix(c, rgb(0x8A6A2E), 0.62)
        Mood.SLEEPING -> mix(c, rgb(0x1B4A3A), 0.2)
        Mood.HAPPY -> mix(c, rgb(0xFFFFFF), 0.04)
        else -> c
    }

    private fun tint1(m: Mood, c: Int): Int = when (m) {
        Mood.THIRSTY -> mix(c, rgb(0xE3DB6E), 0.5)
        Mood.VERY_THIRSTY -> mix(c, rgb(0xCDA64E), 0.66)
        Mood.SLEEPING -> mix(c, rgb(0x1B4A3A), 0.2)
        Mood.HAPPY -> mix(c, rgb(0xFFFFFF), 0.1)
        else -> c
    }

    override fun changeMood(m: Mood) {
        mood = m
        if (plant == null) return
        setSleepFx(m == Mood.SLEEPING)
        val anim = !snapMood
        snapMood = false

        val delta = when (m) { Mood.HAPPY -> -10.0; Mood.THIRSTY -> 22.0; Mood.VERY_THIRSTY -> 42.0; else -> 0.0 }
        val fold = if (m == Mood.SLEEPING) 0.55 else 1.0
        val leanA = when (m) { Mood.THIRSTY -> 7.0; Mood.VERY_THIRSTY -> 15.0; Mood.SLEEPING -> 3.0; else -> 0.0 }
        val fsv = when (m) { Mood.HAPPY -> 1.12f; Mood.THIRSTY -> 0.9f; Mood.VERY_THIRSTY -> 0.72f; Mood.SLEEPING -> 0.82f; else -> 1f }
        for (l in leaves) {
            var a = l.ang * fold
            a += if (m == Mood.HAPPY) l.dir * delta * min(1.0, abs(l.ang) / 25) else l.dir * delta * (if (l.flower) 0.72 else 1.0)
            ang(l.pose, a.coerceIn(-106.0, 106.0), anim)
            val s0 = l.s0
            val s1 = l.s1
            if (s0 != null && s1 != null) { hue(s0.color, tint0(m, l.c0), anim); hue(s1.color, tint1(m, l.c1), anim) }
            l.fScale?.let { fsT ->
                if (anim) {
                    fsT.sx.begin(DA(null, fsv, 380)); fsT.sy.begin(DA(null, fsv, 380))
                } else {
                    fsT.sx.begin(null); fsT.sy.begin(null); fsT.sx.set(fsv); fsT.sy.set(fsv)
                }
            }
        }
        ang(lean!!, leanA, anim)
        hue(stemBrush!!.color, when (m) {
            Mood.THIRSTY -> rgb(0x8DB04A); Mood.VERY_THIRSTY -> rgb(0xA58A3E); Mood.SLEEPING -> rgb(0x2F8F58); else -> rgb(0x3BAA62)
        }, anim)

        val happy = m == Mood.HAPPY; val sleep = m == Mood.SLEEPING; val very = m == Mood.VERY_THIRSTY; val thirsty = m == Mood.THIRSTY
        vis(openEyes, !happy && !sleep); vis(happyEyes, happy); vis(sleepEyes, sleep); vis(browsL, very); vis(tears, very)
        vis(mIdle, m == Mood.IDLE); vis(mHappy, happy); vis(mFlat, thirsty); vis(mSad, very); vis(mSleep, sleep)
        vis(sweat, thirsty)
        eyeMood!!.sx.set(if (very) 1.1f else if (thirsty) 0.95f else 1f)
        eyeMood!!.sy.set(if (very) 1.1f else if (thirsty) 0.74f else 1f)
        val bo = when (m) { Mood.HAPPY -> 0.95; Mood.IDLE -> 0.55; Mood.SLEEPING -> 0.5; Mood.THIRSTY -> 0.22; else -> 0.1 }
        for (bl in blushL) fade(bl, bo, anim, 300)
        if (sleep) { look!!.x.set(0f); look!!.y.set(0f) }

        if (idling) startIdle()
    }

    override fun setWater(fraction: Double, animate: Boolean) {
        water = fraction.coerceIn(0.0, 1.0)
        val s0 = soil0 ?: return
        val t = water
        hue(s0.color, mix(rgb(0xE9D3A6), rgb(0x6A4428), t), animate, 650)
        hue(soil1!!.color, mix(rgb(0xCDAE7E), rgb(0x3D2514), t), animate, 650)
        fade(cracksE, ((0.5 - t) / 0.35).coerceIn(0.0, 1.0), animate, 650)
        fade(sheen, ((t - 0.55) / 0.35).coerceIn(0.0, 1.0) * 0.95, animate, 650)
        val dv = ((t - 0.7) / 0.1).coerceIn(0.0, 1.0)
        for (d in dropsL) fade(d, dv, animate, 500)
    }

    override fun lookAt(dx: Double, dy: Double) {
        val lk = look ?: return
        if (mood == Mood.SLEEPING) return
        val k = if (compact) 2.6 else 2.0
        lk.x.set((dx.coerceIn(-1.0, 1.0) * k).toFloat())
        lk.y.set((dy.coerceIn(-1.0, 1.0) * k * 0.8).toFloat())
    }

    override fun blink() {
        val bt = blinkT ?: return
        if (mood == Mood.SLEEPING || mood == Mood.HAPPY) return
        bt.sy.begin(KA(listOf(KF(0.08f, 70), KF(1f, 190))))
    }

    // =====================================================================================
    //  ANIMATION (pot + plant move together; leaves do their own motion)
    // =====================================================================================
    override fun startIdle() {
        super.startIdle()
        val sm = sceneMove ?: return
        val sw = swayT ?: return
        val sl = mood == Mood.SLEEPING
        sm.y.begin(DA(0f, if (sl) -1.2f else -3f, if (sl) 2200 else 1300, SineE()).apply { autoReverse = true; forever = true })
        val a = if (sl) 0.8f else 2.2f
        sw.angle.begin(DA(-a, a, if (sl) 3200 else 2100, SineE()).apply { autoReverse = true; forever = true })
        for ((i, l) in leaves.withIndex()) {
            val bb = ((if (sl) 0.9 else 3.0) * (if (l.flower) 1.3 else 1.0)).toFloat()
            l.sway.angle.begin(DA(-bb, bb, (if (sl) 2600L else 1350L) + (i % 5) * 170L, SineE()).apply {
                autoReverse = true; forever = true; beginMs = i * 60L
            })
        }
    }

    override fun stopIdle() {
        super.stopIdle()
        val sm = sceneMove ?: return
        val sw = swayT ?: return
        sm.y.begin(null); sm.y.set(0f)
        sw.angle.begin(null); sw.angle.set(0f)
        for (l in leaves) { l.sway.angle.begin(null); l.sway.angle.set(0f) }
    }

    private fun sceneSquash(sx: Float, sy: Float, ms: Int) {
        val ss = sceneScale ?: return
        ss.sx.begin(KA(listOf(KF(sx, (ms * 0.18).toLong()), KF(1f, ms.toLong(), ElasticE(2, 5.0)))))
        ss.sy.begin(KA(listOf(KF(sy, (ms * 0.18).toLong()), KF(1f, ms.toLong(), ElasticE(2, 5.0)))))
    }

    private fun sceneHop(h: Float) {
        val sm = sceneMove ?: return
        sm.y.begin(KA(listOf(KF(-h, 220, QuadE(EM.OUT)), KF(0f, 560, BounceE(2, 3.0, EM.OUT)))))
    }

    private fun shake(amp: Float) {
        val k = kick ?: return
        val v = floatArrayOf(-amp, amp * .8f, -amp * .5f, amp * .25f, 0f)
        val t = longArrayOf(90, 230, 360, 470, 600)
        k.angle.begin(KA(List(v.size) { KF(v[it], t[it]) }))
    }

    private fun pulse(peak: Float, ms: Int) {
        val gr = grow ?: return
        gr.sx.begin(KA(listOf(KF(peak, (ms * 0.35).toLong(), CubicE(EM.OUT)), KF(1f, ms.toLong(), ElasticE(1, 4.0)))))
        gr.sy.begin(KA(listOf(KF(peak, (ms * 0.35).toLong(), CubicE(EM.OUT)), KF(1f, ms.toLong(), ElasticE(1, 4.0)))))
    }

    override fun playDrink() {
        super.playDrink()
        sceneSquash(1.12f, 0.88f, 420)
        pulse(1.1f, 700)
        for ((i, pr) in sprinkle.withIndex()) {
            val (e, tt) = pr
            tt.y.begin(DA(0f, 40f + (i % 3) * 4, 520, QuadE(EM.IN)).apply { beginMs = i * 65L })
            e.opacity.begin(KA(listOf(KF(1f, 60), KF(1f, 400), KF(0f, 520))).apply { beginMs = i * 65L })
        }
    }

    override fun playCelebrate() {
        super.playCelebrate()
        sceneHop(16f)
        shake(7f)
    }

    override fun playGoal() {
        super.playGoal()
        sceneHop(20f)
        shake(9f)
    }

    override fun playEvolve() {
        super.playCelebrate()
        sceneHop(16f)
        pulse(1.22f, 900)
        shake(10f)
    }

    override fun playPoke() {
        squash(1.14f, 0.84f, 600)
        sceneSquash(1.14f, 0.84f, 600)
        shake(11f)
        blink()
    }

    override fun dance(peak: Double) {
        val ss = sceneScale ?: return
        val k = kick ?: return
        if (mood == Mood.SLEEPING) return
        val p = peak.coerceIn(0.0, 1.0)
        for (s in listOf(bodyScale, ss)) {
            s.sx.begin(null); s.sy.begin(null)
            s.sy.set((1 - p * 0.09).toFloat())
            s.sx.set((1 + p * 0.06).toFloat())
        }
        k.angle.begin(null)
        k.angle.set((sin(Clock.now() / 260.0) * 7 * min(1.0, p * 2)).toFloat())
    }

    override fun stopDance() {
        bodyScale.sx.set(1f); bodyScale.sy.set(1f)
        sceneScale?.let { it.sx.set(1f); it.sy.set(1f) }
        kick?.let { it.angle.begin(null); it.angle.set(0f) }
    }
}
