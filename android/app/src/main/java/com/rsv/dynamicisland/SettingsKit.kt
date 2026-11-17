package com.rsv.dynamicisland

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.min

/** Colours shared by the settings screens (match the Windows settings window). */
object Ui {
    const val BG = 0xFF000000.toInt()
    const val NAV = 0xFF131417.toInt()
    const val CARD = 0x14FFFFFF
    const val DIVIDER = 0x12FFFFFF
    const val ACCENT = 0xFF4FC3F7.toInt()
    const val DIM = 0xFF8A8F98.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val CHIP = 0xFF26272B.toInt()
}

/** Custom-drawn bottom navigation: dark bar, water-blue pill sliding under the selected tab. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class NavBar(ctx: Context, private val labels: List<String>, private val onSelect: (Int) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    var selected = 0
        private set
    private var pillX = -1f
    private val bg = Paint().apply { color = Ui.NAV }
    private val line = Paint().apply { color = 0x22FFFFFF }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334FC3F7 }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val p = Path()
    private val r = RectF()

    fun select(i: Int, notify: Boolean = true) {
        if (i !in labels.indices) return
        selected = i
        invalidate()
        if (notify) onSelect(i)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (66 * d).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / labels.size.toFloat())).toInt().coerceIn(0, labels.size - 1)
            if (i != selected) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                select(i)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawRect(0f, 0f, width.toFloat(), d, line)
        val iw = width / labels.size.toFloat()
        val target = (selected + 0.5f) * iw
        if (pillX < 0) pillX = target
        pillX += (target - pillX) * 0.28f
        if (abs(target - pillX) < 0.5f) pillX = target else postInvalidateOnAnimation()
        r.set(pillX - 30 * d, 8 * d, pillX + 30 * d, 38 * d)
        c.drawRoundRect(r, 15 * d, 15 * d, pill)
        for (i in labels.indices) {
            val cx = (i + 0.5f) * iw
            val on = i == selected
            val col = if (on) Ui.ACCENT else Ui.DIM
            drawIcon(c, i, cx, 23 * d, 20 * d, col)
            text.color = if (on) Color.WHITE else Ui.DIM
            text.textSize = 11 * d
            c.drawText(labels[i], cx, 56 * d, text)
        }
    }

    private fun drawIcon(c: Canvas, i: Int, cx: Float, cy: Float, s: Float, col: Int) {
        icon.color = col
        icon.strokeWidth = 1.8f * d
        icon.style = Paint.Style.STROKE
        val h = s / 2
        p.reset()
        when (i) {
            0 -> { // home
                p.moveTo(cx - h * 0.85f, cy - h * 0.05f)
                p.lineTo(cx, cy - h * 0.85f)
                p.lineTo(cx + h * 0.85f, cy - h * 0.05f)
                p.moveTo(cx - h * 0.62f, cy - h * 0.25f)
                p.lineTo(cx - h * 0.62f, cy + h * 0.8f)
                p.lineTo(cx + h * 0.62f, cy + h * 0.8f)
                p.lineTo(cx + h * 0.62f, cy - h * 0.25f)
                c.drawPath(p, icon)
            }
            1 -> { // water drop
                p.moveTo(cx, cy - h * 0.95f)
                p.cubicTo(cx + h * 0.3f, cy - h * 0.45f, cx + h * 0.75f, cy - h * 0.05f, cx + h * 0.75f, cy + h * 0.3f)
                p.cubicTo(cx + h * 0.75f, cy + h * 0.75f, cx + h * 0.4f, cy + h * 0.95f, cx, cy + h * 0.95f)
                p.cubicTo(cx - h * 0.4f, cy + h * 0.95f, cx - h * 0.75f, cy + h * 0.75f, cx - h * 0.75f, cy + h * 0.3f)
                p.cubicTo(cx - h * 0.75f, cy - h * 0.05f, cx - h * 0.3f, cy - h * 0.45f, cx, cy - h * 0.95f)
                c.drawPath(p, icon)
            }
            2 -> { // island pill
                r.set(cx - h * 0.95f, cy - h * 0.4f, cx + h * 0.95f, cy + h * 0.4f)
                icon.style = Paint.Style.FILL
                c.drawRoundRect(r, h * 0.4f, h * 0.4f, icon)
                icon.color = Ui.NAV
                c.drawCircle(cx + h * 0.45f, cy, h * 0.16f, icon)
            }
            3 -> { // sync arrows
                r.set(cx - h * 0.75f, cy - h * 0.75f, cx + h * 0.75f, cy + h * 0.75f)
                c.drawArc(r, 200f, 140f, false, icon)
                c.drawArc(r, 20f, 140f, false, icon)
                c.drawLine(cx + h * 0.62f, cy - h * 0.55f, cx + h * 0.72f, cy - h * 0.12f, icon)
                c.drawLine(cx + h * 0.72f, cy - h * 0.12f, cx + h * 0.3f, cy - h * 0.18f, icon)
                c.drawLine(cx - h * 0.62f, cy + h * 0.55f, cx - h * 0.72f, cy + h * 0.12f, icon)
                c.drawLine(cx - h * 0.72f, cy + h * 0.12f, cx - h * 0.3f, cy + h * 0.18f, icon)
            }
            else -> { // setup: sliders
                for ((k, y) in floatArrayOf(-0.55f, 0f, 0.55f).withIndex()) {
                    c.drawLine(cx - h * 0.85f, cy + y * h, cx + h * 0.85f, cy + y * h, icon)
                    val kx = cx + floatArrayOf(-0.35f, 0.4f, -0.05f)[k] * h
                    icon.style = Paint.Style.FILL
                    c.drawCircle(kx, cy + y * h, h * 0.18f, icon)
                    icon.style = Paint.Style.STROKE
                }
            }
        }
    }
}

/** iOS-style toggle switch. */
@SuppressLint("ClickableViewAccessibility")
class ToggleView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var onChange: ((Boolean) -> Unit)? = null
    var checked = false
        private set
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(2 * resources.displayMetrics.density, 0f, d, 0x40000000) }
    private val r = RectF()

    init {
        isClickable = true
        setOnClickListener { set(!checked, animate = true, notify = true) }
    }

    fun set(v: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (v != checked || !animate) {
            checked = v
            anim?.cancel()
            if (animate) {
                anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
                    duration = 180
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                    start()
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            } else {
                pos = if (v) 1f else 0f
                invalidate()
            }
        }
        if (notify) onChange?.invoke(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((51 * d).toInt(), (31 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        track.color = mixColor(0xFF39393D.toInt(), Ui.ACCENT, pos)
        c.drawRoundRect(r, height / 2f, height / 2f, track)
        val kr = height / 2f - 2 * d
        val x = kr + 2 * d + (width - 2 * kr - 4 * d) * pos
        c.drawCircle(x, height / 2f, kr, knob)
    }

    private fun mixColor(a: Int, b: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}

/** Little helper to keep a value inside min..max on a step grid. */
fun stepValue(v: Int, delta: Int, lo: Int, hi: Int): Int = min(hi, maxOf(lo, v + delta))
