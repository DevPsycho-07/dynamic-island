package com.rsv.dynamicisland.pet

import android.graphics.Path
import android.graphics.Region
import com.rsv.dynamicisland.Mood

/**
 * Port of Windows Pets/DropletSkin.cs. Evolving water droplet: glass body with real water inside
 * (clipped, wavy surface). Droplet -> Pup (feet, arms) -> Sprite (crest) -> Spirit (aura, cloud).
 */
class DropletSkin : PetSkin() {
    override val kind = PetKind.DROPLET

    // ---------- palette / helpers ----------
    private fun sb(v: Long) = SolidB(argb(v))
    private fun lin(top: Long, bot: Long, x2: Float = 0f, y2: Float = 1f) = LinB.two(argb(top), argb(bot), 0f, 0f, x2, y2)

    private val ink = sb(0xFF0B2A50)
    private val white = sb(0xFFFFFFFF)

    // tone 0 = normal, 1 = thirsty (paler), 2 = very thirsty (desaturated)
    private val glassFill = arrayOf(lin(0xFFE2F8FF, 0xFFA2DAF6), lin(0xFFE2F8FF, 0xFFA8DDF6), lin(0xFFEAF0F3, 0xFFB8C8D1))
    private val waterF = arrayOf(lin(0xFF8FE3FF, 0xFF1E8FE6), lin(0xFFC2EEFF, 0xFF6DBBEE), lin(0xFFC9D6DC, 0xFF7D95A3))
    private val waterB = arrayOf(lin(0x99C4F4FF, 0x995CC0F5), lin(0x99DFF7FF, 0x999AD3F2), lin(0x99E1E8EB, 0x99A5B6BF))
    private val limb = arrayOf(lin(0xFF86D8FB, 0xFF35A0EA), lin(0xFFB0E4FB, 0xFF70BAEA), lin(0xFFBBC9D0, 0xFF7E95A2))
    private val crestB = arrayOf(lin(0xFFC4F4FF, 0xFF3AA8F0), lin(0xFFDDF7FF, 0xFF7CC4F0), lin(0xFFDDE6EA, 0xFF93A8B4))
    private val rimB = lin(0xF0FFFFFF, 0xC8BFEFFF)
    private val rimDull = lin(0xC8FFFFFF, 0x9AD2E4EE)

    // ---------- per-stage layout ----------
    private val bodyData = arrayOf(
        "M50,12 C56,28 80,46 80,68 C80,82 67,92 50,92 C33,92 20,82 20,68 C20,46 44,28 50,12 Z",
        "M50,24 C55,36 79,50 79,68 C79,81 66,89 50,89 C34,89 21,81 21,68 C21,50 45,36 50,24 Z",
        "M50,18 C55,32 76,46 76,66 C76,80 64,89 50,89 C36,89 24,80 24,66 C24,46 45,32 50,18 Z",
        "M50,24 C56,37 78,49 78,64 C78,78 66,87 50,87 C34,87 22,78 22,64 C22,49 44,37 50,24 Z",
    )
    private val bodyGeo = Array(4) { g(bodyData[it]) }
    private val bodyRegion = arrayOfNulls<Region>(4)
    private val topY = doubleArrayOf(12.0, 24.0, 18.0, 24.0)
    private val botY = doubleArrayOf(92.0, 89.0, 89.0, 87.0)
    private val eyeYs = doubleArrayOf(65.0, 62.0, 57.0, 55.0)
    private val eyeDx = doubleArrayOf(11.0, 12.0, 11.0, 11.0)
    private val eyeRx = doubleArrayOf(4.8, 5.4, 5.0, 5.0)
    private val eyeRy = doubleArrayOf(5.8, 6.4, 6.2, 6.0)
    private val mouthY = doubleArrayOf(75.0, 73.0, 68.0, 65.0)
    private val hatY = doubleArrayOf(45.0, 46.0, 40.0, 42.0)
    private val puddleRx = doubleArrayOf(30.0, 32.0, 34.0, 0.0)

    private val mouthGeo = arrayOf(
        g("M-4.5,0 Q0,4.5 4.5,0"),                    // idle
        g("M-7,-1 Q0,1 7,-1 Q7,9 0,9 Q-7,9 -7,-1 Z"), // happy
        g("M-4.5,1 Q0,0.4 4.5,1"),                    // thirsty
        g("M-5.5,3.5 Q0,-2.5 5.5,3.5"),               // very thirsty
        g("M-3,0 Q0,3 3,0"),                          // sleeping
    )
    private val starGeo = g("M0,-1 L0.24,-0.24 1,0 0.24,0.24 0,1 -0.24,0.24 -1,0 -0.24,-0.24 Z")
    private val dropGeo = g("M0,-5 C1.6,-2.6 4,0 4,3 A4,4 0 0 1 -4,3 C-4,0 -1.6,-2.6 0,-5 Z")
    private val crestGeo = g("M44,29 C42,17 48,6 60,5 C70,4 77,11 74,18 C72,23 64,24 62,19 C61,15.5 65,13.5 67.5,15 C66,10.5 59,10.5 57,15.5 C55.5,20 58,25 57,29 Z")
    private val period = 40.0

    private fun wave(amp: Double, x0: Double, x1: Double, depth: Double, up: Boolean): Path {
        val p = Path()
        p.moveTo(x0.toFloat(), 0f)
        val half = period / 2
        var x = x0
        var dir = up
        while (x < x1 - 0.01) {
            p.quadTo((x + half / 2).toFloat(), (if (dir) -2 * amp else 2 * amp).toFloat(), (x + half).toFloat(), 0f)
            x += half; dir = !dir
        }
        p.lineTo(x.toFloat(), depth.toFloat())
        p.lineTo(x0.toFloat(), depth.toFloat())
        p.close()
        return p
    }

    private val waveFront = wave(2.1, -80.0, 180.0, 95.0, true)
    private val waveBack = wave(2.7, -60.0, 160.0, 95.0, false)
    private val waveFrontC = wave(3.4, -80.0, 180.0, 95.0, true)
    private val waveBackC = wave(3.9, -60.0, 160.0, 95.0, false)

    /** Leftmost / rightmost x inside the body at height y (Geometry.FillContains scan). */
    private fun span(s: Int, y: Double): DoubleArray {
        val r = bodyRegion[s] ?: Geo.region(bodyGeo[s]).also { bodyRegion[s] = it }
        var l = 50.0; var rr = 50.0; var found = false
        var x = 0.0
        while (x <= 100.0) {
            if (r.contains((x * 10).toInt(), (y * 10).toInt())) { if (!found) { l = x; found = true }; rr = x }
            x += 0.5
        }
        return doubleArrayOf(l, rr)
    }

    private class Eye(val c: Cv, val open: Cv, val closed: PathE, val star: PathE, val s: ScaleT, val t: TransT)

    // ---------- live state ----------
    private var root: Cv? = null
    private var fit = ScaleT(1f, 1f, 50f, 92f)
    private var fitBase = 1.0
    private var glass: PathE? = null
    private var wBack: PathE? = null
    private var wFront: PathE? = null
    private var rim: PathE? = null
    private var crest: PathE? = null
    private val limbs = ArrayList<ShapeE>()
    private val arms = ArrayList<Pair<RotT, Boolean>>()
    private var lvl = TransT()
    private var wxF = TransT()
    private var wxB = TransT()
    private val eyes = ArrayList<Eye>()
    private val blush = ArrayList<El>()
    private var cracks: Cv? = null
    private var browsC: Cv? = null
    private var mouth: PathE? = null
    private var tongue: EllipseE? = null
    private var sweat: PathE? = null
    private var puddle: EllipseE? = null
    private var puddleScale: ScaleT? = null
    private var auraRot: RotT? = null
    private var cloudT: TransT? = null
    private val drops = ArrayList<Triple<TransT, PathE, Long>>()
    private var eyeBaseX = 1f
    private var eyeBaseY = 1f

    // ---------- scene ----------
    override fun buildScene(scene: Cv) {
        val s = stage
        val k = if (compact) 1.3 else 1.0
        puddle = null; puddleScale = null
        if (s < 3) {
            val rx = puddleRx[s]
            val ry = 4.4 * k
            val rg = RadB(listOf(Stop(argb(0x8C8CDCFF), 0f), Stop(argb(0x554FB8F5), 0.65f), Stop(argb(0x002A8FE0), 1f)))
            val ps = ScaleT(1f, 1f)
            val e = at(ellipse(rx * 2, ry * 2, rg), 50 - rx, 94 - ry).apply { rt = ps; hasOrigin = true; originX = .5f; originY = .5f }
            puddle = e; puddleScale = ps
            scene.add(e)
        } else {
            val rg = RadB(listOf(Stop(argb(0x707FDBFF), 0f), Stop(argb(0x307FDBFF), 0.6f), Stop(argb(0x007FDBFF), 1f)))
            scene.add(at(ellipse(54, 9, rg), 23, 90))
        }
    }

    // ---------- body ----------
    override fun buildBody(body: Cv) {
        val s = stage
        val c = compact
        limbs.clear(); arms.clear(); eyes.clear(); blush.clear(); drops.clear()
        auraRot = null; cloudT = null
        lvl = TransT(); wxF = TransT(); wxB = TransT()
        val geo = bodyGeo[s]

        var topMost = topY[s]
        if (cosmetic == 1 && s != 2) topMost -= 6.5
        if (s == 2) topMost = 4.0
        if (s == 3 && !c) topMost = 3.0
        fitBase = if (c) (88.0 / (92 - topMost)).coerceIn(1.0, 1.18) else 1.0
        fit = ScaleT(fitBase.toFloat(), fitBase.toFloat(), 50f, 92f)
        val r = Cv().apply { rt = fit }
        root = r
        body.add(r)

        // aura (Spirit)
        if (s == 3 && !c) {
            r.add(at(ellipse(84, 84, sb(0x2E7FDBFF)), 8, 14))
            r.add(at(ellipse(68, 68, sb(0x387FDBFF)), 16, 22))
            r.add(at(ellipse(52, 52, sb(0x40B6EBFF)), 24, 30))
            val ar = RotT(0f, 50f, 56f)
            auraRot = ar
            val spin = Cv().apply { rt = ar }
            spin.add(at(ellipse(88, 88, null, sb(0x9CBDEFFF), 1.5).apply { dash = floatArrayOf(1.2f, 3.2f); roundCap = true }, 6, 12))
            for ((x, y, sz) in listOf(Triple(50f, 12f, 3.4f), Triple(94f, 56f, 2.6f), Triple(50f, 100f, 3.0f), Triple(6f, 56f, 3.0f), Triple(81f, 25f, 2.2f)))
                spin.add(at(pathE(starGeo, sb(0xE6FFFFFF)).apply { rt = ScaleT(sz, sz) }, x, y))
            r.add(spin)
        }

        if (s == 3 && c) r.add(at(ellipse(76, 76, sb(0x4A7FDBFF)), 12, 14))

        // feet
        if (!c && (s == 1 || s == 2)) {
            val fw = if (s == 1) 15.0 else 16.0
            val fh = 8.2
            val fy = if (s == 1) 88.2 else 88.6
            val fxo = if (s == 1) 13.0 else 12.0
            for (cx in doubleArrayOf(50 - fxo - 1, 50 + fxo + 1))
                addLimb(r, EllipseE(fw.toFloat(), fh.toFloat()).apply { sw = 1.1f }, cx - fw / 2, fy - fh / 2 + 1.2)
        }

        // arms
        if (!c && s >= 1) {
            val aw = if (s == 1) 10.0 else 8.8
            val ah = if (s == 1) 12.0 else if (s == 2) 17.0 else 15.5
            val sy = if (s == 1) 64.0 else if (s == 2) 62.0 else 58.0
            val lx = if (s == 1) 22.5 else if (s == 2) 25.5 else 25.0
            for (left in booleanArrayOf(true, false)) {
                val sx = if (left) lx else 100 - lx
                val rt0 = RotT(0f, (aw / 2).toFloat(), 2.5f)
                val arm = EllipseE(aw.toFloat(), ah.toFloat()).apply { sw = 1.1f; rt = rt0 }
                addLimb(r, arm, sx - aw / 2, sy - 2.5)
                arms.add(rt0 to left)
            }
        }

        // glass
        val gl = PathE(geo)
        glass = gl
        r.add(gl)

        // water (clipped to the body)
        val waterCv = Cv().apply { clip = geo }
        val lvlHost = Cv().apply { rt = lvl }
        val wb = PathE(if (c) waveBackC else waveBack).apply { rt = wxB }
        val wf = PathE(if (c) waveFrontC else waveFront).apply { rt = wxF }
        if (!c) { wf.stroke = sb(0xC8FFFFFF); wf.sw = 1.5f; wf.roundJoin = true }
        wBack = wb; wFront = wf
        lvlHost.add(wb); lvlHost.add(wf)
        if (!c) {
            val bx = if (s == 2) 62.0 else 64.0
            lvlHost.add(at(ellipse(5.5, 5.5, sb(0x5CFFFFFF)), 33, 17))
            lvlHost.add(at(ellipse(3.4, 3.4, sb(0x66FFFFFF)), bx, 29))
            lvlHost.add(at(ellipse(2.4, 2.4, sb(0x55FFFFFF)), 44, 38))
        }
        waterCv.add(lvlHost)
        r.add(waterCv)

        // cracks (very thirsty)
        cracks = null
        if (!c) {
            val cr = Cv().apply { clip = geo; visible = false }
            val y0 = topY[s] + (botY[s] - topY[s]) * 0.42
            val a = span(s, y0)
            val b = span(s, y0 + 14)
            val l0 = a[0]; val r0 = a[1]; val r1 = b[1]
            val cb = sb(0xFF3F5A6B)
            cr.add(PathE(g("M${f(l0 - 1)},${f(y0)} L${f(l0 + 5)},${f(y0 + 3.5)} L${f(l0 + 3)},${f(y0 + 7.5)} L${f(l0 + 9)},${f(y0 + 11.5)}")).apply { stroke = cb; sw = 1.6f; roundJoin = true })
            cr.add(PathE(g("M${f(r1 + 1)},${f(y0 + 14)} L${f(r1 - 5)},${f(y0 + 17)} L${f(r1 - 3)},${f(y0 + 21)} L${f(r1 - 8.5)},${f(y0 + 25)}")).apply { stroke = cb; sw = 1.6f; roundJoin = true })
            cr.add(PathE(g("M${f(r0 - 1)},${f(y0 - 8)} L${f(r0 - 5)},${f(y0 - 5)}")).apply { stroke = cb; sw = 1.5f; roundCap = true })
            cracks = cr
            r.add(cr)
        }

        // glass outline + speculars
        val rm = PathE(geo).apply { sw = if (c) 3.2f else 2.1f; roundJoin = true }
        rim = rm
        r.add(rm)
        run {
            val h = botY[s] - topY[s]
            val ya = topY[s] + h * 0.44
            val yb = topY[s] + h * 0.70
            val ym = (ya + yb) / 2
            val la = span(s, ya)[0]; val lb = span(s, yb)[0]; val lm = span(s, ym)[0]
            r.add(PathE(g("M${f(la + 6.5)},${f(ya)} Q${f(lm + 3.2)},${f(ym)} ${f(lb + 5.2)},${f(yb)}")).apply {
                stroke = sb(if (c) 0xC8FFFFFF else 0xB4FFFFFF); sw = if (c) 4.2f else 3.2f; roundCap = true
            })
            val ld = span(s, ya - 7)[0]
            if (!c) r.add(at(ellipse(3.6, 3.6, sb(0xB4FFFFFF)), ld + 8.5, ya - 10))
        }

        buildFace(r)
        buildCosmetic(r)

        // crest (Sprite)
        crest = null
        if (s == 2) {
            val cp = PathE(crestGeo).apply { stroke = sb(0xE6FFFFFF); sw = if (c) 2.4f else 1.6f; roundJoin = true }
            crest = cp
            r.add(cp)
        }

        // rain cloud (Spirit)
        if (s == 3 && !c) {
            val ct = TransT()
            cloudT = ct
            val cloud = Cv().apply { rt = ct }
            val gg = Path().apply {
                fillType = Path.FillType.WINDING
                addPath(Geo.ellipse(41f, 12f, 7f, 6f))
                addPath(Geo.ellipse(50f, 8.6f, 9f, 7.4f))
                addPath(Geo.ellipse(60f, 11f, 7.6f, 6.4f))
                addPath(Geo.rect(35f, 11f, 32f, 7f, 3.5f, 3.5f))
            }
            for ((x, y, dl) in listOf(Triple(42f, 23f, 0L), Triple(60f, 23f, 450L), Triple(67f, 21f, 900L))) {
                val tt = TransT()
                val dp = pathE(dropGeo, sb(0xFF8FE0FF)).apply { opacity.set(0.9f); rt = GroupT(ScaleT(0.55f, 0.55f), tt) }
                cloud.add(at(dp, x, y))
                drops.add(Triple(tt, dp, dl))
            }
            cloud.add(pathE(gg, lin(0xFFFFFFFF, 0xFFB5D0EA)))
            cloud.add(at(ellipse(6, 3.4, sb(0xB0FFFFFF)), 45, 4.4))
            r.add(cloud)
        }

        if (idling) startAnims()
    }

    private fun addLimb(r: Cv, e: ShapeE, x: Double, y: Double) {
        e.stroke = sb(0xA0FFFFFF)
        limbs.add(e)
        r.add(at(e, x, y))
    }

    private fun buildFace(r: Cv) {
        val s = stage
        val c = compact
        val kk = if (c) 1.7f else 1f
        var ey = eyeYs[s]
        var dx = eyeDx[s]
        val rx = eyeRx[s] * (if (c) 1.35 else 1.12)
        val ry = eyeRy[s] * (if (c) 1.35 else 1.12)
        if (c) { ey += 1; dx += 1.5 }

        if (!c) for (sgn in doubleArrayOf(-1.0, 1.0)) {
            val b = at(ellipse(10, 5.4, sb(0xB4FF8FB1)).apply { visible = false }, 50 + sgn * (dx + 6.5) - 5, ey + ry * 0.9)
            blush.add(b); r.add(b)
        }

        for (sgn in doubleArrayOf(-1.0, 1.0)) {
            val sc = ScaleT(1f, 1f)
            val tr = TransT()
            val ec = Cv().apply { rt = GroupT(sc, tr) }
            at(ec, 50 + sgn * dx, ey)
            val open = Cv()
            open.add(at(ellipse(rx * 2, ry * 2, ink), -rx, -ry))
            if (!c) open.add(at(ellipse(rx * 1.05, ry * 0.8, sb(0xA03A86D6)), -rx * 0.52, ry * 0.12))
            open.add(at(ellipse(rx * (if (c) 0.9 else 0.85), ry * (if (c) 0.75 else 0.7), white), -rx * 0.58, -ry * 0.66))
            if (!c) open.add(at(ellipse(rx * 0.38, ry * 0.32, white), rx * 0.2, ry * 0.3))
            val closed = PathE(g("M${f(-rx * 0.95)},${f(-ry * 0.1)} Q0,${f(ry * 0.85)} ${f(rx * 0.95)},${f(-ry * 0.1)}")).apply {
                stroke = ink; sw = 2.3f * kk; roundCap = true; visible = false
            }
            val st = pathE(starGeo, white).apply { visible = false; rt = ScaleT(3.3f, 3.3f) }
            at(st, rx * 0.5, -ry * 0.55)
            ec.add(open); ec.add(closed)
            if (!c) ec.add(st)
            eyes.add(Eye(ec, open, closed, st, sc, tr))
            r.add(ec)
        }

        browsC = null
        if (!c) {
            val bw = Cv().apply { visible = false }
            val by = ey - ry - 3.4
            bw.add(PathE(g("M${f(50 - dx - 4.6)},${f(by + 1.6)} L${f(50 - dx + 4.4)},${f(by - 2.2)}")).apply { stroke = ink; sw = 1.9f; roundCap = true })
            bw.add(PathE(g("M${f(50 + dx + 4.6)},${f(by + 1.6)} L${f(50 + dx - 4.4)},${f(by - 2.2)}")).apply { stroke = ink; sw = 1.9f; roundCap = true })
            browsC = bw
            r.add(bw)
        }

        val mh = Cv()
        at(mh, 50, mouthY[s] + (if (c) 1 else 0))
        if (c) mh.rt = ScaleT(1.15f, 1.15f)
        val mo = PathE(null).apply { stroke = ink; sw = 2.2f * kk; roundCap = true; roundJoin = true }
        val tg = at(ellipse(6.4, 4.2, sb(0xFFFF7D9A)).apply { visible = false }, -3.2, 4.6)
        mouth = mo; tongue = tg
        mh.add(mo); mh.add(tg)
        r.add(mh)

        val swt = PathE(dropGeo).apply {
            fill = sb(0xFFD4F4FF); stroke = sb(0xFF4FB0F0); sw = if (c) 1.6f else 1f
            visible = false; rt = ScaleT(if (c) 1.5f else 1.05f, if (c) 1.5f else 1.05f)
        }
        at(swt, 50 + dx + rx + 4.5 + (if (c) 1 else 0), ey - ry - 3 + (if (c) 2 else 0))
        sweat = swt
        r.add(swt)
    }

    private fun buildCosmetic(r: Cv) {
        val s = stage
        val c = compact
        when (cosmetic) {
            1 -> {
                val hy = hatY[s]
                val cl = Cv().apply { clip = bodyGeo[s] }
                cl.add(at(rectE(100, hy - topY[s] + 10, lin(0xFFFF8A78, 0xFFE0454C)), 0, topY[s] - 10))
                if (!c) cl.add(PathE(g("M44,${f(hy - 8)} Q46,${f(hy - 18)} 50,${f(topY[s] + 8)}")).apply { stroke = sb(0x66FFFFFF); sw = 2.4f; roundCap = true })
                r.add(cl)
                val sp = span(s, hy)
                val l = sp[0]; val rr = sp[1]
                r.add(at(rectE(rr - l + 6, if (c) 10 else 9, lin(0xFFFFF1D6, 0xFFF3CB8E), 4.5, 4.5, sb(0x66B06A2A), if (c) 0 else 0.8), l - 3, hy - 4.5))
                if (!c) {
                    var x = l + 3
                    while (x < rr - 2) { r.add(at(rectE(1.3, 6, sb(0x55B06A2A)), x, hy - 3)); x += 5.2 }
                }
                if (s != 2) r.add(at(ellipse(11, 11, lin(0xFFFFFFFF, 0xFFFFE3B0), sb(0x55B06A2A), if (c) 0 else 0.8), 44.5, topY[s] - 7))
            }
            2 -> {
                val sy = mouthY[s] + (if (c) 6.5 else 6.0)
                val bh = if (c) 10.0 else 8.6
                val cl = Cv().apply { clip = bodyGeo[s] }
                cl.add(at(rectE(100, bh, lin(0xFFFFB547, 0xFFEE8420)), 0, sy))
                if (!c) {
                    var x = -2.0
                    while (x < 100) { cl.add(at(rectE(3.2, bh, sb(0x55FFFFFF)).apply { rt = SkewT(-18f, 0f) }, x, sy)); x += 9 }
                }
                r.add(cl)
                val rr = span(s, sy + 3)[1]
                r.add(at(rectE(if (c) 10 else 8.5, if (c) 16 else 15, lin(0xFFFFB547, 0xFFE87A1C), 3.2, 3.2, sb(0x66B8570A), if (c) 0 else 0.8).apply {
                    rt = RotT(-14f, 4f, 0f)
                }, rr - 8, sy + 3.2))
                if (!c) r.add(at(rectE(5, 1.6, sb(0x77FFFFFF)).apply { rt = RotT(-14f, 0f, 0f) }, rr - 5.4, sy + 9.5))
            }
            3 -> {
                val ey = eyeYs[s] + (if (c) 1 else 0)
                val dx = eyeDx[s] + (if (c) 1.5 else 0.0)
                val w = eyeRx[s] * (if (c) 1.3 else 1.0) * 2 + 7.5
                val h = eyeRy[s] * (if (c) 1.3 else 1.0) * 2 + 2.5
                val lens = lin(0xFF2A3A5E, 0xFF0C1427)
                r.add(at(rectE(2 * dx - w + 3, if (c) 3.4 else 2.4, sb(0xFF0C1427)), 50 - dx + w / 2 - 1.5, ey - h / 2 + 2.2))
                for (sgn in doubleArrayOf(-1.0, 1.0)) {
                    val cx = 50 + sgn * dx
                    r.add(at(rectE(w, h, lens, 4.2, 4.2, sb(0xFF0A1020), if (c) 1.4 else 1.1), cx - w / 2, ey - h / 2))
                    r.add(PathE(g("M${f(cx - w * 0.28)},${f(ey - h * 0.12)} L${f(cx + w * 0.1)},${f(ey - h * 0.38)}")).apply {
                        stroke = sb(0xA0FFFFFF); sw = if (c) 2.6f else 1.9f; roundCap = true
                    })
                }
            }
        }
    }

    // ---------- mood ----------
    override fun changeMood(m: Mood) {
        mood = m
        setSleepFx(m == Mood.SLEEPING)
        if (root == null || glass == null || mouth == null) return
        val sleeping = m == Mood.SLEEPING
        val happy = m == Mood.HAPPY
        val thirsty = m == Mood.THIRSTY
        val very = m == Mood.VERY_THIRSTY
        val tone = if (very) 2 else if (thirsty) 1 else 0

        glass!!.fill = glassFill[tone]
        wFront!!.fill = waterF[tone]
        wBack!!.fill = waterB[tone]
        rim!!.stroke = if (very) rimDull else rimB
        crest?.fill = crestB[tone]
        for (l in limbs) l.fill = limb[tone]

        eyeBaseX = if (happy) 1.1f else 1f
        eyeBaseY = if (happy) 1.1f else if (thirsty) 0.82f else if (very) 0.9f else 1f
        for (e in eyes) {
            e.s.sy.begin(null)
            e.s.sx.set(eyeBaseX); e.s.sy.set(eyeBaseY)
            e.open.visible = !sleeping
            e.closed.visible = sleeping
            e.star.visible = happy
            if (sleeping) { e.t.x.set(0f); e.t.y.set(0f) }
        }

        val mi = if (happy) 1 else if (thirsty) 2 else if (very) 3 else if (sleeping) 4 else 0
        mouth!!.data = mouthGeo[mi]
        mouth!!.fill = if (happy) ink else null
        tongue!!.visible = happy && !compact
        for (b in blush) {
            val o = if (happy) 1f else if (sleeping) 0.6f else if (m == Mood.IDLE) 0.4f else 0f
            b.opacity.set(o)
            b.visible = o > 0
        }
        sweat!!.visible = thirsty || very
        cracks?.visible = very
        browsC?.visible = very

        val a = if (happy) 138f else if (very) 2f else if (thirsty) 8f else if (sleeping) 12f else 18f
        for ((rt0, left) in arms) rt0.angle.set(if (left) a else -a)

        val ms = if (very) 0.92 else 1.0
        fit.sx.set((fitBase * ms).toFloat()); fit.sy.set((fitBase * ms).toFloat())
    }

    // ---------- water ----------
    override fun setWater(fraction: Double, animate: Boolean) {
        water = fraction.coerceIn(0.0, 1.0)
        if (root == null) return
        val s = stage
        val top = (botY[s] - (0.13 + 0.82 * water) * (botY[s] - topY[s])).toFloat()
        puddleScale?.let { val k = (0.78 + 0.3 * water).toFloat(); it.sx.set(k); it.sy.set(k) }
        lvl.y.begin(null)
        if (animate) {
            lvl.y.begin(DA(null, top, 750, QuadE(EM.OUT)))
            lvl.y.set(top)
        } else lvl.y.set(top)
    }

    override fun lookAt(dx: Double, dy: Double) {
        if (mood == Mood.SLEEPING) return
        val x = (dx.coerceIn(-1.0, 1.0) * 2.5).toFloat()
        val y = (dy.coerceIn(-1.0, 1.0) * 2.5).toFloat()
        for (e in eyes) { e.t.x.set(x); e.t.y.set(y) }
    }

    override fun blink() {
        if (mood == Mood.SLEEPING) return
        for (e in eyes) e.s.sy.begin(KA(listOf(KF(0.08f, 70), KF(eyeBaseY, 150))))
    }

    // ---------- idle ----------
    override fun startIdle() {
        super.startIdle()
        if (stage == 3 && mood != Mood.SLEEPING) {
            bodyMove.y.begin(DA(0f, -5f, 1800, SineE()).apply { autoReverse = true; forever = true })
        }
        startAnims()
    }

    override fun stopIdle() {
        super.stopIdle()
        stopAnims()
    }

    private fun startAnims() {
        wxF.x.begin(DA(0f, (-period).toFloat(), 2600).apply { forever = true })
        wxB.x.begin(DA(0f, period.toFloat(), 3700).apply { forever = true })
        auraRot?.angle?.begin(DA(0f, 360f, 24000).apply { forever = true })
        cloudT?.x?.begin(DA(-4f, 4f, 3400, SineE()).apply { autoReverse = true; forever = true })
        for ((t, p, delay) in drops) {
            t.y.begin(DA(0f, 9f, 1300).apply { forever = true; beginMs = delay })
            p.opacity.begin(KA(listOf(KF(0.95f, 0), KF(0.9f, (1300 * 0.55).toLong()), KF(0f, 1300)), 1300).apply { forever = true; beginMs = delay })
        }
    }

    private fun stopAnims() {
        wxF.x.begin(null)
        wxB.x.begin(null)
        auraRot?.angle?.begin(null)
        cloudT?.x?.begin(null)
        for ((t, p, _) in drops) { t.y.begin(null); p.opacity.begin(null) }
    }
}
