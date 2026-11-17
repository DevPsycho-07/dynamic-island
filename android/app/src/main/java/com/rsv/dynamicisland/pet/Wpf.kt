package com.rsv.dynamicisland.pet

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * A tiny retained-mode layer that mirrors the bits of WPF the Windows pet skins use, so the
 * skins can be ported 1:1: Canvas / Path / Ellipse / Rectangle / Line / TextBlock elements,
 * Solid / Linear / Radial brushes, Scale / Rotate / Translate / Skew transforms, Canvas.Left/Top,
 * Opacity, Visibility, Clip, plus WPF-style animations (DoubleAnimation, key frames, color
 * animation, BeginTime, AutoReverse, RepeatBehavior.Forever, FillBehavior.Stop, Completed) and
 * the WPF easing functions. Values are evaluated lazily at draw time from the uptime clock.
 */

internal object Clock {
    fun now(): Long = SystemClock.uptimeMillis()
}

// =====================================================================================
// Easing (WPF EasingFunctionBase: default mode EaseOut)
// =====================================================================================

enum class EM { IN, OUT, INOUT }

abstract class Ease(private val mode: EM) {
    protected abstract fun inn(t: Double): Double
    fun f(t: Double): Double = when (mode) {
        EM.IN -> inn(t)
        EM.OUT -> 1.0 - inn(1.0 - t)
        EM.INOUT -> if (t < 0.5) inn(t * 2) * 0.5 else (1.0 - inn((1.0 - t) * 2)) * 0.5 + 0.5
    }
}

class SineE(m: EM = EM.OUT) : Ease(m) { override fun inn(t: Double) = 1.0 - sin((1.0 - t) * PI / 2) }
class CubicE(m: EM = EM.OUT) : Ease(m) { override fun inn(t: Double) = t * t * t }
class QuadE(m: EM = EM.OUT) : Ease(m) { override fun inn(t: Double) = t * t }

class ElasticE(private val osc: Int = 3, private val spring: Double = 3.0, m: EM = EM.OUT) : Ease(m) {
    override fun inn(t: Double): Double {
        val o = max(0, osc).toDouble()
        val s = max(0.0, spring)
        val expo = if (s == 0.0) t else (exp(s * t) - 1.0) / (exp(s) - 1.0)
        return expo * sin((PI * 2.0 * o + PI * 0.5) * t)
    }
}

class BounceE(private val bounces: Int = 3, private val bounciness: Double = 2.0, m: EM = EM.OUT) : Ease(m) {
    override fun inn(t: Double): Double {
        val b = max(0, bounces).toDouble()
        var k = bounciness
        if (k <= 1.0) k = 1.001
        val pow = k.pow(b)
        val oneMinus = 1.0 - k
        val sumOfUnits = (1.0 - pow) / oneMinus + pow * 0.5
        val unitAtT = t * sumOfUnits
        val bounceAtT = ln(-unitAtT * (1.0 - k) + 1.0) / ln(k)
        val start = floor(bounceAtT)
        val end = start + 1.0
        val startTime = (1.0 - k.pow(start)) / (oneMinus * sumOfUnits)
        val endTime = (1.0 - k.pow(end)) / (oneMinus * sumOfUnits)
        val midTime = (startTime + endTime) * 0.5
        val rel = t - midTime
        val radius = midTime - startTime
        val amp = (1.0 / k).pow(b - start)
        return (-amp / (radius * radius)) * (rel - radius) * (rel + radius)
    }
}

// =====================================================================================
// Timelines
// =====================================================================================

abstract class Tl {
    var beginMs = 0L
    var forever = false
    var autoReverse = false
    var fillStop = false
    var onDone: (() -> Unit)? = null
    internal var start = 0L
    internal var finished = false
    internal var fired = false
    abstract val durMs: Long

    /** ms into the current forward pass, or null before BeginTime. Sets [finished]. */
    internal fun local(now: Long): Double? {
        val e = now - start
        if (e < 0) return null
        val d = max(1.0, durMs.toDouble())
        val span = if (autoReverse) 2 * d else d
        if (!forever && e >= span) {
            finished = true
            return if (autoReverse) 0.0 else d
        }
        val m = e % span
        return if (autoReverse && m > d) span - m else m
    }
}

/** DoubleAnimation. [from] null = current value when it starts. */
class DA(val from: Float?, val to: Float, override val durMs: Long, val ease: Ease? = null) : Tl()

class KF(val v: Float, val at: Long, val ease: Ease? = null)

/** DoubleAnimationUsingKeyFrames (linear frames when ease == null). */
class KA(val frames: List<KF>, dur: Long? = null) : Tl() {
    override val durMs: Long = dur ?: (frames.maxOfOrNull { it.at } ?: 0L)
}

/** ColorAnimation(to, duration) from the current colour. */
class CA(val to: Int, override val durMs: Long) : Tl()

class Prop(var base: Float) {
    private var a: Tl? = null
    private var from0 = 0f

    fun begin(t: Tl?) {
        val now = Clock.now()
        if (t == null) { a = null; return }
        from0 = value(now)
        t.start = now + t.beginMs
        t.finished = false
        t.fired = false
        a = t
    }

    fun set(v: Float) { base = v }

    fun value(now: Long = Clock.now()): Float {
        val t = a ?: return base
        val loc = t.local(now)
        val v: Float? = when {
            loc == null -> null
            t is DA -> {
                val f = t.from ?: from0
                val p = (loc / max(1.0, t.durMs.toDouble())).coerceIn(0.0, 1.0)
                val e = t.ease?.f(p) ?: p
                (f + (t.to - f) * e).toFloat()
            }
            t is KA -> keyValue(t, loc)
            else -> null
        }
        if (t.finished && !t.fired) {
            t.fired = true
            if (t.fillStop) a = null
            t.onDone?.invoke()
            if (t.fillStop) return base
        }
        return v ?: base
    }

    private fun keyValue(t: KA, loc: Double): Float {
        var prevV = from0
        var prevT = 0.0
        for (k in t.frames) {
            val at = k.at.toDouble()
            if (loc <= at) {
                if (at <= prevT) return k.v
                val p = ((loc - prevT) / (at - prevT)).coerceIn(0.0, 1.0)
                val e = k.ease?.f(p) ?: p
                return (prevV + (k.v - prevV) * e).toFloat()
            }
            prevV = k.v
            prevT = at
        }
        return prevV
    }
}

class ColorProp(var base: Int) {
    private var a: CA? = null
    private var from0 = 0

    fun begin(t: CA?) {
        val now = Clock.now()
        if (t == null) { a = null; return }
        from0 = value(now)
        t.start = now + t.beginMs
        t.finished = false
        a = t
    }

    fun value(now: Long = Clock.now()): Int {
        val t = a ?: return base
        val loc = t.local(now) ?: return base
        val p = (loc / max(1.0, t.durMs.toDouble())).toFloat().coerceIn(0f, 1f)
        return lerpColor(from0, t.to, p)
    }
}

fun lerpColor(a: Int, b: Int, t: Float): Int = Color.argb(
    (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t + 0.5f).toInt(),
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t + 0.5f).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t + 0.5f).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t + 0.5f).toInt(),
)

// =====================================================================================
// Transforms (TransformGroup applies children in order)
// =====================================================================================

abstract class Tf {
    abstract fun apply(m: Matrix, now: Long)
}

class ScaleT(sx: Float = 1f, sy: Float = 1f, private val cx: Float = 0f, private val cy: Float = 0f) : Tf() {
    val sx = Prop(sx)
    val sy = Prop(sy)
    override fun apply(m: Matrix, now: Long) { m.postScale(sx.value(now), sy.value(now), cx, cy) }
}

class RotT(angle: Float = 0f, private val cx: Float = 0f, private val cy: Float = 0f) : Tf() {
    val angle = Prop(angle)
    override fun apply(m: Matrix, now: Long) { m.postRotate(angle.value(now), cx, cy) }
}

class TransT(x: Float = 0f, y: Float = 0f) : Tf() {
    val x = Prop(x)
    val y = Prop(y)
    override fun apply(m: Matrix, now: Long) { m.postTranslate(x.value(now), y.value(now)) }
}

class SkewT(private val ax: Float, private val ay: Float) : Tf() {
    override fun apply(m: Matrix, now: Long) {
        m.postSkew(tan(Math.toRadians(ax.toDouble())).toFloat(), tan(Math.toRadians(ay.toDouble())).toFloat())
    }
}

class GroupT(vararg val children: Tf) : Tf() {
    override fun apply(m: Matrix, now: Long) { for (c in children) c.apply(m, now) }
}

// =====================================================================================
// Brushes (relative to the shape's bounding box, like WPF's default mapping)
// =====================================================================================

abstract class Br {
    abstract fun setup(p: Paint, b: RectF, now: Long, alpha: Float)
}

class SolidB(color: Int) : Br() {
    val color = ColorProp(color)
    override fun setup(p: Paint, b: RectF, now: Long, alpha: Float) {
        p.shader = null
        val c = color.value(now)
        p.color = c
        p.alpha = (Color.alpha(c) * alpha + 0.5f).toInt().coerceIn(0, 255)
    }
}

class Stop(color: Int, val offset: Float) {
    val color = ColorProp(color)
}

private class ShaderCache {
    private val keys = arrayOfNulls<FloatArray>(4)
    private val vals = arrayOfNulls<Shader>(4)
    private var next = 0
    fun get(k: FloatArray): Shader? {
        for (i in keys.indices) if (keys[i]?.contentEquals(k) == true) return vals[i]
        return null
    }
    fun put(k: FloatArray, s: Shader) {
        keys[next] = k; vals[next] = s; next = (next + 1) % keys.size
    }
}

class LinB(val stops: List<Stop>, private val x0: Float = 0f, private val y0: Float = 0f,
           private val x1: Float = 0f, private val y1: Float = 1f) : Br() {
    private val cache = ShaderCache()

    override fun setup(p: Paint, b: RectF, now: Long, alpha: Float) {
        val n = stops.size
        val k = FloatArray(4 + n)
        k[0] = b.left; k[1] = b.top; k[2] = b.right; k[3] = b.bottom
        val cols = IntArray(n) { stops[it].color.value(now) }
        for (i in 0 until n) k[4 + i] = Float.fromBits(cols[i])
        var s = cache.get(k)
        if (s == null) {
            val pos = FloatArray(n) { stops[it].offset }
            s = LinearGradient(
                b.left + x0 * b.width(), b.top + y0 * b.height(), b.left + x1 * b.width(), b.top + y1 * b.height(),
                cols, pos, Shader.TileMode.CLAMP
            )
            cache.put(k, s)
        }
        p.shader = s
        p.color = Color.BLACK
        p.alpha = (255 * alpha + 0.5f).toInt().coerceIn(0, 255)
    }

    companion object {
        /** new LinearGradientBrush(c0, c1, angle) - EndPoint = (cos a, sin a). */
        fun angle(c0: Int, c1: Int, deg: Double) = LinB(
            listOf(Stop(c0, 0f), Stop(c1, 1f)), 0f, 0f, cos(Math.toRadians(deg)).toFloat(), sin(Math.toRadians(deg)).toFloat()
        )
        fun two(c0: Int, c1: Int, x0: Float = 0f, y0: Float = 0f, x1: Float = 0f, y1: Float = 1f) =
            LinB(listOf(Stop(c0, 0f), Stop(c1, 1f)), x0, y0, x1, y1)
    }
}

/** RadialGradientBrush. The gradient origin offset is approximated by centring on it. */
class RadB(val stops: List<Stop>, private val cx: Float = .5f, private val cy: Float = .5f,
           private val ox: Float = .5f, private val oy: Float = .5f,
           private val rx: Float = .5f, private val ry: Float = .5f) : Br() {
    private val cache = ShaderCache()
    private val lm = Matrix()

    override fun setup(p: Paint, b: RectF, now: Long, alpha: Float) {
        val n = stops.size
        val k = FloatArray(4 + n)
        k[0] = b.left; k[1] = b.top; k[2] = b.right; k[3] = b.bottom
        val cols = IntArray(n) { stops[it].color.value(now) }
        for (i in 0 until n) k[4 + i] = Float.fromBits(cols[i])
        var s = cache.get(k)
        if (s == null) {
            val w = max(b.width(), 0.001f)
            val h = max(b.height(), 0.001f)
            val gx = b.left + ox * w
            val gy = b.top + oy * h
            val shift = hypot((ox - cx) * w, (oy - cy) * h)
            val r = max(0.01f, rx * w + shift)
            val pos = FloatArray(n) { stops[it].offset }
            val rg = RadialGradient(gx, gy, r, cols, pos, Shader.TileMode.CLAMP)
            lm.reset()
            lm.setScale(1f, (ry * h + shift) / r, gx, gy)
            rg.setLocalMatrix(lm)
            s = rg
            cache.put(k, s)
        }
        p.shader = s
        p.color = Color.BLACK
        p.alpha = (255 * alpha + 0.5f).toInt().coerceIn(0, 255)
    }
}

// =====================================================================================
// Elements
// =====================================================================================

private val tmpM = Matrix()

abstract class El {
    val left = Prop(0f)
    val top = Prop(0f)
    val opacity = Prop(1f)
    var visible = true
    var rt: Tf? = null
    var clip: Path? = null
    /** RenderTransformOrigin (relative to the element's own size), or null for (0,0). */
    var originX = 0f
    var originY = 0f
    var hasOrigin = false

    open fun sizeW() = 0f
    open fun sizeH() = 0f

    fun render(c: Canvas, now: Long, alpha: Float) {
        if (!visible) return
        val a = alpha * opacity.value(now)
        if (a <= 0.002f) return
        c.save()
        c.translate(left.value(now), top.value(now))
        val t = rt
        if (t != null) {
            tmpM.reset()
            if (hasOrigin) {
                val ox = originX * sizeW()
                val oy = originY * sizeH()
                tmpM.postTranslate(-ox, -oy)
                t.apply(tmpM, now)
                tmpM.postTranslate(ox, oy)
            } else {
                t.apply(tmpM, now)
            }
            c.concat(tmpM)
        }
        clip?.let { c.clipPath(it) }
        paint(c, now, a)
        c.restore()
    }

    protected abstract fun paint(c: Canvas, now: Long, a: Float)
}

/** Canvas: a group. */
class Cv : El() {
    val kids = ArrayList<El>()
    fun add(e: El): Cv { kids.add(e); return this }
    override fun paint(c: Canvas, now: Long, a: Float) { for (k in kids) k.render(c, now, a) }
}

abstract class ShapeE : El() {
    var fill: Br? = null
    var stroke: Br? = null
    var sw = 0f
    var roundCap = false
    var roundJoin = false
    var dash: FloatArray? = null

    protected val bnd = RectF()
    protected abstract fun bounds(out: RectF)
    protected abstract fun drawShape(c: Canvas, p: Paint)

    override fun paint(c: Canvas, now: Long, a: Float) {
        val f = fill
        val s = stroke
        if (f == null && (s == null || sw <= 0f)) return
        bounds(bnd)
        if (f != null) {
            FP.style = Paint.Style.FILL
            f.setup(FP, bnd, now, a)
            drawShape(c, FP)
        }
        if (s != null && sw > 0f) {
            SP.style = Paint.Style.STROKE
            SP.strokeWidth = sw
            SP.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
            SP.strokeJoin = if (roundJoin) Paint.Join.ROUND else Paint.Join.MITER
            SP.pathEffect = dash?.let { DashPathEffect(FloatArray(it.size) { i -> it[i] * sw }, 0f) }
            s.setup(SP, bnd, now, a)
            drawShape(c, SP)
            SP.pathEffect = null
        }
    }

    companion object {
        internal val FP = Paint(Paint.ANTI_ALIAS_FLAG)
        internal val SP = Paint(Paint.ANTI_ALIAS_FLAG)
    }
}

/** Path element with Data. */
class PathE(var data: Path?) : ShapeE() {
    override fun bounds(out: RectF) {
        val d = data
        if (d == null) out.setEmpty() else @Suppress("DEPRECATION") d.computeBounds(out, true)
    }
    override fun drawShape(c: Canvas, p: Paint) { data?.let { c.drawPath(it, p) } }
}

/** Ellipse element (Width x Height at Left/Top). Stroke is drawn inside, like WPF. */
class EllipseE(var w: Float, var h: Float) : ShapeE() {
    override fun sizeW() = w
    override fun sizeH() = h
    override fun bounds(out: RectF) {
        val i = if (stroke != null) sw / 2f else 0f
        out.set(i, i, w - i, h - i)
    }
    override fun drawShape(c: Canvas, p: Paint) { c.drawOval(bnd, p) }
}

/** Rectangle element with RadiusX/Y. Stroke drawn inside, like WPF. */
class RectE(var w: Float, var h: Float, var rx: Float = 0f, var ry: Float = 0f) : ShapeE() {
    override fun sizeW() = w
    override fun sizeH() = h
    override fun bounds(out: RectF) {
        val i = if (stroke != null) sw / 2f else 0f
        out.set(i, i, w - i, h - i)
    }
    override fun drawShape(c: Canvas, p: Paint) {
        if (rx > 0f || ry > 0f) c.drawRoundRect(bnd, rx, ry, p) else c.drawRect(bnd, p)
    }
}

class LineE(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : ShapeE() {
    override fun bounds(out: RectF) { out.set(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)) }
    override fun drawShape(c: Canvas, p: Paint) { c.drawLine(x1, y1, x2, y2, p) }
}

class TextE(val text: String, val size: Float, val brush: Br, val bold: Boolean = true) : El() {
    override fun paint(c: Canvas, now: Long, a: Float) {
        TP.textSize = size
        TP.isFakeBoldText = bold
        brush.setup(TP, RectF(0f, 0f, size, size), now, a)
        c.drawText(text, 0f, size * 1.0f, TP)
    }

    companion object { private val TP = Paint(Paint.ANTI_ALIAS_FLAG) }
}

// =====================================================================================
// Geometry: WPF / SVG path mini-language (M L H V C S Q T A Z, relative forms, F0/F1)
// =====================================================================================

object Geo {
    private val cache = HashMap<String, Path>()

    /** Parsed and cached. Don't mutate the returned path. */
    fun g(d: String): Path = synchronized(cache) { cache.getOrPut(d) { parse(d) } }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): Path =
        Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }

    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f, ry: Float = 0f): Path =
        Path().apply { addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW) }

    fun quad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float): Path =
        Path().apply { moveTo(x0, y0); quadTo(cx, cy, x1, y1) }

    /** Path.FillContains equivalent for small sweeps (100-unit space, 0.1 resolution). */
    fun region(p: Path): Region {
        val m = Matrix().apply { setScale(10f, 10f) }
        val q = Path(p).apply { transform(m) }
        return Region().apply { setPath(q, Region(-2000, -2000, 3000, 3000)) }
    }

    private fun parse(d: String): Path {
        val p = Path()
        p.fillType = Path.FillType.EVEN_ODD // WPF default unless F1
        val toks = tokenize(d)
        var i = 0
        var cmd = 'M'
        var cx = 0f; var cy = 0f        // current point
        var sx = 0f; var sy = 0f        // subpath start
        var lcx = 0f; var lcy = 0f      // last control point
        var lastCmd = ' '

        fun num(): Float = (toks[i++] as Float)
        fun hasNum() = i < toks.size && toks[i] is Float

        while (i < toks.size) {
            val t = toks[i]
            if (t is Char) {
                cmd = t; i++
                if (cmd == 'F' || cmd == 'f') {
                    val rule = num()
                    p.fillType = if (rule == 1f) Path.FillType.WINDING else Path.FillType.EVEN_ODD
                    continue
                }
                if (cmd == 'Z' || cmd == 'z') {
                    p.close(); cx = sx; cy = sy; lastCmd = 'Z'
                    continue
                }
            } else if (cmd == 'Z' || cmd == 'z') {
                i++; continue
            }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    p.moveTo(x, y); cx = x; cy = y; sx = x; sy = y
                    cmd = if (rel) 'l' else 'L'
                    lastCmd = 'M'
                }
                'L' -> {
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    p.lineTo(x, y); cx = x; cy = y; lastCmd = 'L'
                }
                'H' -> {
                    var x = num(); if (rel) x += cx
                    p.lineTo(x, cy); cx = x; lastCmd = 'L'
                }
                'V' -> {
                    var y = num(); if (rel) y += cy
                    p.lineTo(cx, y); cy = y; lastCmd = 'L'
                }
                'C' -> {
                    var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var x = num(); var y = num()
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                    p.cubicTo(x1, y1, x2, y2, x, y)
                    lcx = x2; lcy = y2; cx = x; cy = y; lastCmd = 'C'
                }
                'S' -> {
                    var x2 = num(); var y2 = num(); var x = num(); var y = num()
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }
                    val x1 = if (lastCmd == 'C') 2 * cx - lcx else cx
                    val y1 = if (lastCmd == 'C') 2 * cy - lcy else cy
                    p.cubicTo(x1, y1, x2, y2, x, y)
                    lcx = x2; lcy = y2; cx = x; cy = y; lastCmd = 'C'
                }
                'Q' -> {
                    var x1 = num(); var y1 = num(); var x = num(); var y = num()
                    if (rel) { x1 += cx; y1 += cy; x += cx; y += cy }
                    p.quadTo(x1, y1, x, y)
                    lcx = x1; lcy = y1; cx = x; cy = y; lastCmd = 'Q'
                }
                'T' -> {
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    val x1 = if (lastCmd == 'Q') 2 * cx - lcx else cx
                    val y1 = if (lastCmd == 'Q') 2 * cy - lcy else cy
                    p.quadTo(x1, y1, x, y)
                    lcx = x1; lcy = y1; cx = x; cy = y; lastCmd = 'Q'
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    arc(p, cx, cy, rx, ry, rot, large, sweep, x, y)
                    cx = x; cy = y; lastCmd = 'A'
                }
                else -> i++
            }
            if (!hasNum() && i < toks.size && toks[i] !is Char) i++
        }
        return p
    }

    private fun tokenize(d: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        val n = d.length
        while (i < n) {
            val ch = d[i]
            when {
                ch.isLetter() && ch != 'e' && ch != 'E' -> { out.add(ch); i++ }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val st = i
                    if (ch == '-' || ch == '+') i++
                    var dot = false
                    while (i < n) {
                        val c = d[i]
                        if (c.isDigit()) i++
                        else if (c == '.' && !dot) { dot = true; i++ }
                        else if ((c == 'e' || c == 'E') && i + 1 < n && (d[i + 1].isDigit() || d[i + 1] == '-' || d[i + 1] == '+')) {
                            i += 2
                            while (i < n && d[i].isDigit()) i++
                            break
                        } else break
                    }
                    out.add(d.substring(st, i).toFloat())
                }
                else -> i++
            }
        }
        return out
    }

    /** Elliptical arc (SVG endpoint parameterisation) as cubic Béziers. */
    private fun arc(p: Path, x0: Float, y0: Float, rx0: Float, ry0: Float, rotDeg: Float,
                    large: Boolean, sweep: Boolean, x: Float, y: Float) {
        if (x0 == x && y0 == y) return
        var rx = abs(rx0.toDouble())
        var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x, y); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cp * dx2 + sp * dy2
        val y1p = -sp * dx2 + cp * dy2
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { rx *= sqrt(lam); ry *= sqrt(lam) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = (if (large == sweep) -1.0 else 1.0) * sqrt(max(0.0, num / den))
        val cxp = coef * rx * y1p / ry
        val cyp = coef * -ry * x1p / rx
        val ccx = cp * cxp - sp * cyp + (x0 + x) / 2.0
        val ccy = sp * cxp + cp * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val th1 = ang(1.0, 0.0, ux, uy)
        var dth = ang(ux, uy, vx, vy)
        if (!sweep && dth > 0) dth -= 2 * PI
        if (sweep && dth < 0) dth += 2 * PI
        val segs = max(1, ceil(abs(dth) / (PI / 2)).toInt())
        val delta = dth / segs
        val t = 4.0 / 3.0 * tan(delta / 4)
        var a1 = th1
        for (s in 0 until segs) {
            val a2 = a1 + delta
            val c1 = cos(a1); val s1 = sin(a1); val c2 = cos(a2); val s2 = sin(a2)
            val p1x = ccx + rx * c1 * cp - ry * s1 * sp; val p1y = ccy + rx * c1 * sp + ry * s1 * cp
            val p2x = ccx + rx * c2 * cp - ry * s2 * sp; val p2y = ccy + rx * c2 * sp + ry * s2 * cp
            val d1x = -rx * s1 * cp - ry * c1 * sp; val d1y = -rx * s1 * sp + ry * c1 * cp
            val d2x = -rx * s2 * cp - ry * c2 * sp; val d2y = -rx * s2 * sp + ry * c2 * cp
            p.cubicTo(
                (p1x + t * d1x).toFloat(), (p1y + t * d1y).toFloat(),
                (p2x - t * d2x).toFloat(), (p2y - t * d2y).toFloat(),
                p2x.toFloat(), p2y.toFloat()
            )
            a1 = a2
        }
    }
}

// =====================================================================================
// Small builders shared by the skins (mirror the C# helpers)
// =====================================================================================

/** "#RRGGBB" with optional alpha 0..1 / 0..255. */
fun hex(h: String, a: Int = 255): Int {
    val c = Color.parseColor(h)
    return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}

fun argb(v: Long): Int = v.toInt()

fun rgb(v: Int, a: Int = 255): Int = Color.argb(a, (v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)

fun mix(a: Int, b: Int, t: Double): Int = Color.argb(
    255,
    Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

fun <T : El> at(e: T, x: Number, y: Number): T {
    e.left.set(x.toFloat()); e.top.set(y.toFloat()); return e
}

fun ellipse(w: Number, h: Number, fill: Br?, stroke: Br? = null, sw: Number = 0): EllipseE =
    EllipseE(w.toFloat(), h.toFloat()).apply { this.fill = fill; this.stroke = stroke; this.sw = sw.toFloat() }

fun rectE(w: Number, h: Number, fill: Br?, rx: Number = 0, ry: Number = rx, stroke: Br? = null, sw: Number = 0): RectE =
    RectE(w.toFloat(), h.toFloat(), rx.toFloat(), ry.toFloat()).apply { this.fill = fill; this.stroke = stroke; this.sw = sw.toFloat() }

fun pathE(data: Path?, fill: Br? = null, stroke: Br? = null, sw: Number = 0, round: Boolean = false): PathE =
    PathE(data).apply {
        this.fill = fill; this.stroke = stroke; this.sw = sw.toFloat()
        if (round) { roundCap = true; roundJoin = true }
    }

fun lineE(x1: Number, y1: Number, x2: Number, y2: Number, th: Number, stroke: Br): LineE =
    LineE(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()).apply {
        this.stroke = stroke; sw = th.toFloat(); roundCap = true
    }

fun f(v: Double): String = if (v == Math.floor(v) && abs(v) < 1e7) v.toLong().toString() else "%.3f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
