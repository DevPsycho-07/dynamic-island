package com.rsv.dynamicisland.pet

import android.graphics.Path
import com.rsv.dynamicisland.Mood
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/** Port of Windows Pets/AxolotlSkin.cs. Pink axolotl in a glass tank. Baby / Teen / Adult / Royal. */
class AxolotlSkin : PetSkin() {
    override val kind = PetKind.AXOLOTL

    // ---------- art assets ----------
    private fun hb(h: String, alpha: Double = 1.0) = SolidB(hex(h, round(alpha * 255).toInt()))
    private fun grad(top: String, bottom: String, alpha: Double = 1.0): LinB {
        val al = round(alpha * 255).toInt()
        return LinB.angle(hex(top, al), hex(bottom, al), 90.0)
    }

    private val white = hb("#FFFFFF")
    private val whiteSoft = hb("#FFFFFF", 0.8)
    private val whiteFaint = hb("#FFFFFF", 0.22)
    private val whiteHi = hb("#FFFFFF", 0.38)
    private val ink = hb("#2E1A36")
    private val browInk = hb("#6E4A5F")
    private val eyeFill = RadB(listOf(Stop(hex("#5E3C6C"), 0f), Stop(hex("#1B0E21"), 1f)), .5f, .5f, .4f, .3f, .62f, .62f)
    private val blushFill = hb("#FF4F8C")
    private val mouthFill = hb("#B2324F")
    private val tongueFill = hb("#FF8AA6")
    private val dropFill = hb("#8FDBFF")
    private val goldFill = grad("#FFE98A", "#FFB52E")
    private val goldLine = hb("#E58F1F")
    private val gemRed = hb("#FF4F78")
    private val gemBlue = hb("#4FC3F7")
    private val bowFill = grad("#C7B2FF", "#8F6CF2")
    private val bowDark = hb("#6D4BD1")
    private val leaf = hb("#6FD08A")
    private val halo = hb("#FF9AD0", 0.30)
    private val star = hb("#FFF4B0")

    private val mouthGeo = arrayOf(
        g("M-4.4,0 Q-2.2,3.6 0,1.4 Q2.2,3.6 4.4,0"),                       // idle (cat smile)
        g("M-5.8,-0.6 Q0,0.5 5.8,-0.6 Q5,6.8 0,6.8 Q-5,6.8 -5.8,-0.6 Z"), // happy (open)
        g("M-3.8,1.8 Q0,-0.7 3.8,1.8"),                                     // thirsty
        g("M-5.2,3.2 Q0,-3.2 5.2,3.2"),                                     // very thirsty
        g("M-2.6,0 Q0,2.3 2.6,0"),                                          // sleeping
    )
    private val dropGeo = g("M0,-5 C-1,-1.5 -3.4,0.8 -3.4,3 A3.4,3.4 0 0 0 3.4,3 C3.4,0.8 1,-1.5 0,-5 Z")
    private val starGeo = g("M0,-1 L0.22,-0.22 1,0 0.22,0.22 0,1 -0.22,0.22 -1,0 -0.22,-0.22 Z")
    private val tongueGeo = g("M-2.7,4.6 Q0,3.2 2.7,4.6 Q2.4,6.7 0,6.7 Q-2.4,6.7 -2.7,4.6 Z")
    private val tailBaby = g("M62,85 C68,83 73,85 73,88.5 C72,92 66,92.5 62,90.5 Z")
    private val tailTeen = g("M74,77 C82,78 87,73 88,64 C93,72 92,86 76,90.5 Z")
    private val tailAdult = g("M79,74.5 C87,75 91,68 91,56 C96,66 96,85 80,91.5 Z")
    private val crownGeo = g("M-13,4.5 L-14.5,-12 L-7,-6 L0,-16.5 L7,-6 L14.5,-12 L13,4.5 Q0,-0.5 -13,4.5 Z")
    private val bowL = g("M0,0 C-4,-7.5 -13,-6.5 -13,0 C-13,6.5 -4,7.5 0,0 Z")
    private val bowR = g("M0,0 C4,-7.5 13,-6.5 13,0 C13,6.5 4,7.5 0,0 Z")
    private val bowTailL = g("M-1,2 L-6.5,10 L-2.2,8.4 Z")
    private val bowTailR = g("M1,2 L6.5,10 L2.2,8.4 Z")

    private fun el(cx: Double, cy: Double, rx: Double, ry: Double, fill: Br?, stroke: Br? = null, sw: Double = 0.0): EllipseE =
        at(ellipse(rx * 2, ry * 2, fill, stroke, sw), cx - rx, cy - ry)

    private fun pa(geo: Path, fill: Br?, stroke: Br? = null, sw: Double = 0.0) = pathE(geo, fill, stroke, sw, round = true)
    private fun ln(x1: Double, y1: Double, x2: Double, y2: Double, th: Double, stroke: Br) = lineE(x1, y1, x2, y2, th, stroke)
    private fun qCurve(x1: Double, y1: Double, cx: Double, cy: Double, x2: Double, y2: Double) =
        Geo.quad(x1.toFloat(), y1.toFloat(), cx.toFloat(), cy.toFloat(), x2.toFloat(), y2.toFloat())

    private fun waveGeo(closed: Boolean): Path {
        val half = 12.5f
        val amp = 3.2f
        val n = 12
        val p = Path()
        p.moveTo(0f, 0f)
        for (i in 0 until n) p.quadTo(half * i + half / 2, if (i % 2 == 0) -amp else amp, half * (i + 1), 0f)
        if (closed) {
            p.lineTo(half * n, 120f)
            p.lineTo(0f, 120f)
            p.close()
        }
        return p
    }

    private class Pal(val top: String, val bot: String, val line: String, val gill: String, val tip: String)

    private fun pal(m: Mood, royal: Boolean): Pal = when (m) {
        Mood.HAPPY -> if (royal) Pal("#FFCBDF", "#FF9FC9", "#EE78A6", "#FF58A8", "#FFC3E1") else Pal("#FFC0D8", "#FF9AC2", "#EC76A4", "#EA4A8E", "#FF8DBA")
        Mood.THIRSTY -> Pal("#F1CBD9", "#DFA3BC", "#C48AA3", "#B9618C", "#D68CAD")
        Mood.VERY_THIRSTY -> Pal("#CEC1C9", "#AB9AA6", "#968391", "#85647A", "#A3869A")
        Mood.SLEEPING -> if (royal) Pal("#F2BFD6", "#EA97BD", "#D571A0", "#EE4B93", "#F5A6CB") else Pal("#F6B3CE", "#EE8DB6", "#DB6C98", "#D43F7C", "#F27BAB")
        else -> if (royal) Pal("#FFC6DB", "#FF9CC6", "#EE78A6", "#FF4FA0", "#FFC0DF") else Pal("#FFB8D3", "#FF93BC", "#EA6F9C", "#E0407F", "#FF80B0")
    }

    // ---------- live state ----------
    private class GillRef(val droop: RotT, val wave: RotT, val side: Int)

    private val gills = ArrayList<GillRef>()
    private val eyeScale = ArrayList<ScaleT>()
    private val eyeMove = ArrayList<TransT>()
    private lateinit var sTop: Stop
    private lateinit var sBot: Stop
    private lateinit var skin: LinB
    private val outline = SolidB(0xFFFFFFFF.toInt())
    private val gill = SolidB(0xFFFFFFFF.toInt())
    private val tip = SolidB(0xFFFFFFFF.toInt())
    private var stageTf = ScaleT()
    private var sleepScale = ScaleT()
    private var sleepMove = TransT()
    private var tailRot: RotT? = null
    private var eyesOpen: Cv? = null
    private var eyesClosed: Cv? = null
    private var brows: Cv? = null
    private var drop: Cv? = null
    private var burst: Cv? = null
    private var waterC: Cv? = null
    private var burstMove: TransT? = null
    private var waveMove: TransT? = null
    private lateinit var mouth: PathE
    private lateinit var tongue: PathE
    private val cheeks = ArrayList<El>()
    private var ready = false

    // =====================================================================
    // Scene: glass tank + water
    // =====================================================================
    override fun buildScene(scene: Cv) {
        waterC = null; waveMove = null
        if (compact) return

        scene.add(pa(Geo.rect(4f, 8f, 92f, 89f, 13f, 13f), grad("#FFFFFF", "#9FE3FF", 0.06), hb("#FFFFFF", 0.42), 1.6))

        val host = Cv().apply { clip = Geo.rect(5.5f, 9.5f, 89f, 86f, 12f, 12f) }
        val w = Cv()
        waterC = w
        val wm = TransT()
        waveMove = wm
        val waveC = Cv().apply { rt = wm }
        val fillBrush = LinB(
            listOf(Stop(argb(0x926FD6FA), 0f), Stop(argb(0x8A3AA0E0), 0.55f), Stop(argb(0x962478C4), 1f)),
            0f, 0f, 0f, 1f
        )
        waveC.add(pa(waveGeo(true), fillBrush))
        waveC.add(pa(waveGeo(false), null, hb("#E6F8FF", 0.7), 1.4))
        w.add(waveC)
        for ((x, y, r) in listOf(Triple(15.0, 14.0, 2.0), Triple(17.5, 27.0, 1.2), Triple(84.0, 22.0, 1.6), Triple(88.0, 40.0, 1.0)))
            w.add(el(x, y, r, r, hb("#FFFFFF", 0.14), hb("#FFFFFF", 0.6), 0.8))
        host.add(w)

        host.add(at(rectE(89, 5, grad("#E8CC9A", "#CDAA74", 0.9)), 5.5, 90.8))
        for (pb in listOf(
            arrayOf(14.0, 91.4, 3.4, 2.1, "#B8A8B8"), arrayOf(20.0, 92.6, 2.2, 1.5, "#E9B79A"), arrayOf(83.0, 91.6, 3.0, 2.0, "#A9B8C8"),
            arrayOf(89.0, 92.7, 2.0, 1.4, "#D9C2E0"), arrayOf(74.0, 93.2, 1.6, 1.0, "#E9B79A")
        )) host.add(el(pb[0] as Double, pb[1] as Double, pb[2] as Double, pb[3] as Double, hb(pb[4] as String, 0.95)))
        scene.add(host)

        scene.add(pa(qCurve(11.0, 24.0, 10.2, 31.0, 11.0, 40.0), null, hb("#FFFFFF", 0.38), 1.8))
        scene.add(el(11.0, 46.0, 1.0, 1.0, hb("#FFFFFF", 0.38)))
    }

    override fun setWater(fraction: Double, animate: Boolean) {
        water = fraction.coerceIn(0.0, 1.0)
        val w = waterC ?: return
        val y = (95.5 - (8 + water * 66)).toFloat()
        if (animate) {
            w.top.begin(DA(null, y, 750, CubicE(EM.INOUT)))
        } else {
            w.top.begin(null)
            w.top.set(y)
        }
    }

    // =====================================================================
    // Body
    // =====================================================================
    override fun buildBody(body: Cv) {
        ready = false
        gills.clear(); eyeScale.clear(); eyeMove.clear(); cheeks.clear()
        tailRot = null; burst = null; burstMove = null

        sTop = Stop(0xFFFFFFFF.toInt(), 0f)
        sBot = Stop(0xFFFFFFFF.toInt(), 1f)
        skin = LinB(listOf(sTop, sBot), 0f, 0f, 0f, 1f)

        val st = stage
        val royal = st == 3
        val hx: Double; val hy: Double; val hrx: Double; val hry: Double; val gl: Double; val gth: Double
        var sc = 1.0
        when {
            compact -> { hx = 50.0; hy = 59.0; hrx = 29.0; hry = 24.0; gl = 17 + st * 1.2; gth = 6.4 }
            st == 0 -> { hx = 50.0; hy = 71.0; hrx = 23.0; hry = 19.0; gl = 8.5; gth = 3.5 }
            st == 1 -> { hx = 44.0; hy = 66.0; hrx = 20.0; hry = 16.5; gl = 12.0; gth = 3.2 }
            else -> { hx = 42.0; hy = 62.0; hrx = 19.5; hry = 16.5; gl = if (st == 2) 15.0 else 16.5; gth = 3.3; if (royal) sc = 1.05 }
        }
        val ow = if (compact) 2.2 else 1.2

        stageTf = ScaleT(sc.toFloat(), sc.toFloat(), 50f, 92f)
        sleepScale = ScaleT(1f, 1f, 50f, 92f)
        sleepMove = TransT()
        val inner = Cv().apply { rt = GroupT(stageTf, sleepScale, sleepMove) }
        body.add(inner)

        // ---- tail, body, legs
        if (!compact) {
            val tail = Cv()
            tail.add(pa(if (st == 0) tailBaby else if (st == 1) tailTeen else tailAdult, skin, outline, ow))
            val piv = if (st == 0) floatArrayOf(64f, 88f) else if (st == 1) floatArrayOf(77f, 84f) else floatArrayOf(80f, 83f)
            val tr = RotT(0f, piv[0], piv[1])
            tailRot = tr
            tail.rt = tr
            inner.add(tail)

            val bx: Double; val by: Double; val brx: Double; val bry: Double
            when (st) {
                0 -> { bx = 50.0; by = 86.0; brx = 14.0; bry = 8.0 }
                1 -> { bx = 58.0; by = 83.0; brx = 25.0; bry = 9.5 }
                else -> { bx = 60.0; by = 81.0; brx = 27.0; bry = 11.0 }
            }
            inner.add(el(bx, by, brx, bry, skin, outline, ow))
            inner.add(el(bx, by + bry * 0.4, brx * 0.7, bry * 0.4, whiteFaint))

            val legs = when (st) {
                0 -> listOf(doubleArrayOf(39.0, 91.5, 5.6, 3.2), doubleArrayOf(61.0, 91.5, 5.6, 3.2))
                1 -> listOf(doubleArrayOf(36.0, 91.0, 4.8, 3.0), doubleArrayOf(51.0, 91.4, 4.8, 3.0), doubleArrayOf(70.0, 90.6, 4.8, 3.0))
                else -> listOf(doubleArrayOf(35.0, 91.0, 5.8, 3.6), doubleArrayOf(50.0, 91.6, 5.8, 3.6), doubleArrayOf(70.0, 91.0, 6.0, 3.6), doubleArrayOf(83.0, 89.6, 5.4, 3.4))
            }
            for (l in legs) inner.add(el(l[0], l[1], l[2], l[3], skin, outline, ow))
        }

        // ---- gills (behind head)
        val phi = doubleArrayOf(52.0, 22.0, -8.0)
        val ang = doubleArrayOf(64.0, 30.0, -2.0)
        val lf = doubleArrayOf(1.0, 0.9, 0.78)
        for (s in intArrayOf(-1, 1)) for (i in 0 until 3) {
            val p = phi[i] * PI / 180
            addGill(inner, hx + s * hrx * 0.88 * cos(p), hy - hry * 0.88 * sin(p), s, ang[i], gl * lf[i], gth, royal)
        }

        // ---- head
        inner.add(el(hx, hy, hrx, hry, skin, outline, ow))
        val shine = el(hx - hrx * 0.38, hy - hry * 0.58, hrx * 0.3, hry * 0.13, whiteHi)
        shine.rt = RotT(-16f, (hrx * 0.3).toFloat(), (hry * 0.13).toFloat())
        inner.add(shine)

        // ---- face
        val er = hrx * (if (st == 0 || compact) 0.29 else 0.25)
        val eoff = hrx * 0.5
        val ey = hy + hry * 0.05
        val mk = (hrx / 20.0 * (if (compact) 1.25 else 1.0)).toFloat()

        for (s in intArrayOf(-1, 1)) {
            val ch = el(hx + s * hrx * 0.74, hy + hry * 0.46, er * 0.85, er * 0.5, blushFill)
            ch.opacity.set(0f)
            cheeks.add(ch); inner.add(ch)
        }

        val eo = Cv(); val ec2 = Cv(); val bw = Cv().apply { visible = false }
        eyesOpen = eo; eyesClosed = ec2; brows = bw
        for (s in intArrayOf(-1, 1)) {
            val ex = hx + s * eoff
            val sct = ScaleT(1f, 1f, ex.toFloat(), ey.toFloat())
            val mv = TransT()
            val ec = Cv().apply { rt = GroupT(sct, mv) }
            ec.add(el(ex, ey, er, er * 1.12, eyeFill))
            ec.add(el(ex - er * 0.32, ey - er * 0.38, er * 0.36, er * 0.36, white))
            ec.add(el(ex + er * 0.4, ey + er * 0.42, er * 0.17, er * 0.17, whiteSoft))
            eo.add(ec)
            eyeScale.add(sct); eyeMove.add(mv)

            ec2.add(pa(qCurve(ex - er * 0.95, ey, ex, ey + er * 1.0, ex + er * 0.95, ey), null, ink, if (compact) 2.8 else 1.8))
            if (!compact) bw.add(ln(ex + s * er * 1.1, ey - er * 1.55, ex - s * er * 1.0, ey - er * 2.1, 1.6, browInk))
        }
        ec2.visible = false
        inner.add(eo); inner.add(ec2); inner.add(bw)

        val my = hy + hry * 0.52
        mouth = PathE(null).apply {
            stroke = ink; sw = 1.7f; roundCap = true; roundJoin = true
            rt = ScaleT(mk, mk)
        }
        tongue = PathE(tongueGeo).apply { fill = tongueFill; rt = ScaleT(mk, mk); visible = false }
        inner.add(at(mouth, hx, my))
        inner.add(at(tongue, hx, my))

        // sweat drop (thirsty)
        val dk = if (compact) 1.9f else (1.15 * hrx / 20).toFloat()
        val d = Cv().apply { visible = false }
        drop = d
        val dp = pa(dropGeo, dropFill).apply { rt = ScaleT(dk, dk) }
        d.add(at(dp, hx + hrx * 0.84, hy - hry * 0.62))
        inner.add(d)

        // royal sparkle spots
        if (royal) {
            val s1 = if (compact) 4.2f else 2.6f
            inner.add(at(pa(starGeo, star).apply { rt = ScaleT(s1, s1) }, hx - hrx * 0.55, hy - hry * 0.2))
            inner.add(at(pa(starGeo, star).apply { rt = ScaleT(s1 * 0.8f, s1 * 0.8f) }, hx + hrx * 0.62, hy - hry * 0.38))
            if (!compact) {
                inner.add(at(pa(starGeo, star).apply { rt = ScaleT(2.4f, 2.4f) }, 64, 76))
                inner.add(at(pa(starGeo, star).apply { rt = ScaleT(1.7f, 1.7f) }, 76, 83))
                inner.add(at(pa(starGeo, star).apply { rt = ScaleT(1.5f, 1.5f) }, 52, 85))
            }
        }

        addCosmetic(inner, hx, hy, hrx, hry)

        // drink bubbles (live in Fx so the body squash does not distort them)
        if (!compact) {
            val bm = TransT()
            burstMove = bm
            val b = Cv().apply { opacity.set(0f); rt = bm }
            burst = b
            for ((dx, dy, r) in listOf(Triple(-6.0, 0.0, 2.4), Triple(3.0, -5.0, 1.8), Triple(9.0, 2.0, 2.0), Triple(-1.0, -11.0, 1.4)))
                b.add(el(hx + dx, hy + hry * 0.2 + dy, r, r, hb("#FFFFFF", 0.15), hb("#FFFFFF", 0.85), 0.9))
            fx.add(b)
        }

        ready = true
    }

    private fun addGill(host: Cv, ax: Double, ay: Double, side: Int, angDeg: Double, len: Double, th: Double, royal: Boolean) {
        val a = angDeg * PI / 180
        val dx = side * cos(a)
        val dy = -sin(a)
        val ex = ax + dx * len
        val ey = ay + dy * len
        val c = Cv()
        if (royal) c.add(ln(ax, ay, ex, ey, th + (if (compact) 5.0 else 4.2), halo))
        c.add(ln(ax, ay, ex, ey, th, gill))
        if (!compact && len >= 10) {
            for (t in doubleArrayOf(0.58)) for (fs in intArrayOf(-1, 1)) {
                val px = ax + dx * len * t
                val py = ay + dy * len * t
                val th2 = fs * 0.95
                val fx2 = dx * cos(th2) - dy * sin(th2)
                val fy2 = dx * sin(th2) + dy * cos(th2)
                val fl = len * 0.4
                c.add(ln(px, py, px + fx2 * fl, py + fy2 * fl, th * 0.78, gill))
                c.add(el(px + fx2 * fl, py + fy2 * fl, th * 0.62, th * 0.62, tip))
            }
        }
        c.add(el(ex, ey, th * 0.95, th * 0.95, tip))
        val droop = RotT(0f, ax.toFloat(), ay.toFloat())
        val wave = RotT(0f, ax.toFloat(), ay.toFloat())
        c.rt = GroupT(droop, wave)
        host.add(c)
        gills.add(GillRef(droop, wave, side))
    }

    // ---------- cosmetics ----------
    private fun addCosmetic(inner: Cv, hx: Double, hy: Double, hrx: Double, hry: Double) {
        if (cosmetic == 0) return
        val cs = hrx / 18.5
        when (cosmetic) {
            2 -> {
                val c = Cv()
                c.add(pa(crownGeo, goldFill, goldLine, 1.0))
                for ((x, y) in listOf(-14.5 to -12.0, 0.0 to -16.5, 14.5 to -12.0)) c.add(el(x, y, 1.9, 1.9, goldFill, goldLine, 0.7))
                c.add(el(0.0, -4.6, 2.2, 2.2, gemRed))
                c.add(el(-7.5, -1.8, 1.6, 1.6, gemBlue))
                c.add(el(7.5, -1.8, 1.6, 1.6, gemBlue))
                place(c, hx, hy - hry + 1.2, cs, 0.0)
                inner.add(c)
            }
            1 -> {
                val t = 52 * PI / 180
                val c = Cv()
                c.add(pa(bowTailL, bowFill, bowDark, 0.6))
                c.add(pa(bowTailR, bowFill, bowDark, 0.6))
                c.add(pa(bowL, bowFill, bowDark, 0.9))
                c.add(pa(bowR, bowFill, bowDark, 0.9))
                c.add(el(0.0, 0.0, 2.7, 2.7, bowDark))
                c.add(el(-0.6, -0.7, 0.9, 0.9, whiteSoft))
                place(c, hx + hrx * 0.74 * cos(t) / 0.78 * 0.9, hy - hry * 0.95 * sin(t) * 0.97, cs * 0.95, 18.0)
                inner.add(c)
            }
            else -> {
                val pet = arrayOf("#FFFFFF", "#DDBEFF", "#FFC9AA", "#FFE98A", "#FFFFFF")
                val ts = doubleArrayOf(158.0, 126.0, 94.0, 62.0, 30.0)
                val c = Cv()
                for (i in 0 until 4) {
                    val m = (ts[i] + ts[i + 1]) / 2 * PI / 180
                    val lf = el(0.0, 0.0, 3.8 * cs, 1.9 * cs, leaf)
                    lf.rt = RotT(((ts[i] + ts[i + 1]) / 2 - 90 + 90).toFloat(), (3.8 * cs).toFloat(), (1.9 * cs).toFloat())
                    at(lf, hx + hrx * 0.98 * cos(m) - 3.8 * cs, hy - hry * 1.0 * sin(m) - 1.9 * cs)
                    c.add(lf)
                }
                val fr = max(3.4, hrx * 0.17)
                for (i in 0 until 5) {
                    val t = ts[i] * PI / 180
                    val fx2 = hx + hrx * 0.98 * cos(t)
                    val fy2 = hy - hry * 1.0 * sin(t)
                    val pb = hb(pet[i])
                    for (k in 0 until 5) {
                        val pa2 = k * 2 * PI / 5 - PI / 2
                        c.add(el(fx2 + cos(pa2) * fr * 0.62, fy2 + sin(pa2) * fr * 0.62, fr * 0.52, fr * 0.52, pb, hb("#E58AB0", 0.5), 0.5))
                    }
                    c.add(el(fx2, fy2, fr * 0.42, fr * 0.42, hb(if (i == 3) "#FF9F43" else "#FFC83D")))
                }
                inner.add(c)
            }
        }
    }

    private fun place(c: Cv, x: Double, y: Double, scale: Double, rot: Double) {
        c.rt = GroupT(ScaleT(scale.toFloat(), scale.toFloat()), RotT(rot.toFloat()), TransT(x.toFloat(), y.toFloat()))
    }

    // =====================================================================
    // Mood / eyes
    // =====================================================================
    override fun changeMood(m: Mood) {
        mood = m
        if (!ready) return
        setSleepFx(m == Mood.SLEEPING)

        val p = pal(m, stage == 3)
        sTop.color.base = hex(p.top); sBot.color.base = hex(p.bot)
        outline.color.base = hex(p.line); gill.color.base = hex(p.gill); tip.color.base = hex(p.tip)

        val sleeping = m == Mood.SLEEPING
        eyesOpen!!.visible = !sleeping
        eyesClosed!!.visible = sleeping
        brows!!.visible = m == Mood.VERY_THIRSTY && !compact
        drop!!.visible = m == Mood.THIRSTY || m == Mood.VERY_THIRSTY

        val eyeY = when (m) { Mood.THIRSTY -> 0.86f; Mood.VERY_THIRSTY -> 0.68f; else -> 1f }
        for (s in eyeScale) { s.sy.begin(null); s.sy.set(eyeY) }
        if (sleeping) for (t in eyeMove) { t.x.set(0f); t.y.set(0f) }

        mouth.data = mouthGeo[m.ordinal]
        mouth.fill = if (m == Mood.HAPPY) mouthFill else null
        tongue.visible = m == Mood.HAPPY

        val blush = when (m) { Mood.HAPPY -> 0.8f; Mood.IDLE -> 0.3f; Mood.SLEEPING -> 0.35f; else -> 0f }
        for (c in cheeks) c.opacity.set(blush)

        val droop = when (m) { Mood.HAPPY -> -6f; Mood.THIRSTY -> 20f; Mood.VERY_THIRSTY -> 42f; Mood.SLEEPING -> 14f; else -> 0f }
        for (gr in gills) gr.droop.angle.set(gr.side * droop)

        sleepScale.sy.set(if (sleeping) 0.93f else 1f)
        sleepScale.sx.set(if (sleeping) 1.03f else 1f)
        sleepMove.y.set(if (sleeping) 2.5f else if (m == Mood.VERY_THIRSTY) 1.5f else 0f)

        if (idling) startIdle()
    }

    override fun lookAt(dx: Double, dy: Double) {
        if (!ready || mood == Mood.SLEEPING) return
        val x = (dx.coerceIn(-1.0, 1.0) * 2.5).toFloat()
        val y = (dy.coerceIn(-1.0, 1.0) * 2.0).toFloat()
        for (t in eyeMove) { t.x.set(x); t.y.set(y) }
    }

    override fun blink() {
        if (!ready || mood == Mood.SLEEPING) return
        val rest = if (eyeScale.isNotEmpty()) eyeScale[0].sy.base else 1f
        for (s in eyeScale) s.sy.begin(KA(listOf(KF(0.08f, 70), KF(rest, 170))).apply { fillStop = true })
    }

    // =====================================================================
    // Idle: gentle gill wave, tail sway, water ripple
    // =====================================================================
    override fun startIdle() {
        super.startIdle()
        if (!ready) return
        val amp = if (mood == Mood.SLEEPING) 2.5f else if (mood == Mood.VERY_THIRSTY) 3f else 7f
        for ((i, gr) in gills.withIndex()) {
            gr.wave.angle.begin(DA(-amp * gr.side, amp * gr.side, 1100L + (i % 3) * 170, SineE(EM.INOUT)).apply {
                autoReverse = true; forever = true; beginMs = (i % 3) * 140L
            })
        }
        tailRot?.angle?.begin(DA(-4.5f, 4.5f, 1700, SineE(EM.INOUT)).apply { autoReverse = true; forever = true })
        waveMove?.x?.begin(DA(0f, -25f, 3200).apply { forever = true })
    }

    override fun stopIdle() {
        super.stopIdle()
        for (gr in gills) { gr.wave.angle.begin(null); gr.wave.angle.set(0f) }
        tailRot?.let { it.angle.begin(null); it.angle.set(0f) }
        waveMove?.let { it.x.begin(null); it.x.set(0f) }
    }

    override fun playDrink() {
        super.playDrink()
        val b = burst ?: return
        val bm = burstMove ?: return
        b.opacity.begin(KA(listOf(KF(1f, 100), KF(1f, 650), KF(0f, 950))))
        bm.y.begin(DA(4f, -16f, 950, QuadE(EM.OUT)))
    }
}
