package com.rsv.dynamicisland.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Animated pet for the settings screen.
 * [preview] = a fixed kind for the picker (tap selects it); null = the active pet (tap pokes).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PetView(ctx: Context, private val preview: PetKind? = null) : View(ctx) {
    var dancing = false
    var chosen = false
        set(v) { field = v; invalidate() }
    var onSelect: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var touchX = 0f
    private var touchY = 0f
    private var touchAt = 0L
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = 0xFF4FC3F7.toInt()
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF121A1F.toInt() }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B2F.toInt()
        textSize = 14 * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val box = RectF()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        touchX = e.x
        touchY = e.y
        touchAt = SystemClock.uptimeMillis()
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (preview != null) onSelect?.invoke() else PetEngine.poke()
        return true
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        if (preview != null) {
            box.set(cx - size / 2f + d, cy - size / 2f + d, cx + size / 2f - d, cy + size / 2f - d)
            c.drawRoundRect(box, 16 * d, 16 * d, bg)
            if (chosen) c.drawRoundRect(box, 16 * d, 16 * d, ring)
        }
        var lx = 0f
        var ly = 0f
        if (SystemClock.uptimeMillis() - touchAt < 2500) {
            val dx = touchX - cx
            val dy = touchY - cy
            val len = hypot(dx, dy).coerceAtLeast(1f)
            lx = dx / len
            ly = dy / len
        }
        val inset = if (preview != null) 0.86f else 1f
        if (preview != null) {
            PetEngine.draw(c, context, cx, cy, size * inset, lookX = lx, lookY = ly, dancing = dancing, kind = preview, slot = "prev-${preview.name}")
        } else {
            PetEngine.draw(c, context, cx, cy, size * inset, lookX = lx, lookY = ly, dancing = dancing, slot = "hero")
            if (PetEngine.pokeShowing()) {
                val line = PetEngine.pokeLine
                val w = bubbleText.measureText(line) + 24 * d
                val bx = cx + size * 0.22f
                val by = cy - size * 0.42f
                box.set(bx - w / 2f, by - 16 * d, bx + w / 2f, by + 16 * d)
                c.drawRoundRect(box, 16 * d, 16 * d, bubble)
                c.drawText(line, bx, by + 5 * d, bubbleText)
            }
        }
        postInvalidateOnAnimation()
    }
}
