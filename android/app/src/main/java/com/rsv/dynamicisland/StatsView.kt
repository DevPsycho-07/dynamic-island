package com.rsv.dynamicisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Calendar
import kotlin.math.max

/** Last 7 days of hydration as bars, with a dashed goal line. Today is the rightmost bar. */
class StatsView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var days: List<Triple<String, Int, Int>> = emptyList()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goalLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0xFFFFFFFF.toInt()
        alpha = 120
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9A9A.toInt()
        textSize = 11 * d
        textAlign = Paint.Align.CENTER
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 10 * d
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private val names = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun update(data: List<Triple<String, Int, Int>>) {
        days = data
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (150 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val top = 18 * d
        val bottom = height - 22 * d
        val maxV = max(days.maxOf { max(it.second, it.third) }, 1).toFloat() * 1.1f
        val slot = width / days.size.toFloat()
        val bw = slot * 0.5f
        for ((i, day) in days.withIndex()) {
            val (key, ml, goal) = day
            val x = slot * i + slot / 2f
            val h = (bottom - top) * (ml / maxV)
            bar.color = when {
                ml >= goal -> 0xFF30D158.toInt()
                i == days.size - 1 -> 0xFF4FC3F7.toInt()
                else -> 0xFF2C6E8F.toInt()
            }
            r.set(x - bw / 2f, bottom - max(h, 2 * d), x + bw / 2f, bottom)
            c.drawRoundRect(r, 5 * d, 5 * d, bar)
            if (ml > 0) c.drawText(Water.liters(ml), x, bottom - h - 5 * d, value)
            val cal = Calendar.getInstance()
            try {
                val p = key.split("-").map { it.toInt() }
                cal.set(p[0], p[1] - 1, p[2])
            } catch (_: Exception) { }
            c.drawText(if (i == days.size - 1) "Today" else names[cal.get(Calendar.DAY_OF_WEEK) - 1], x, height - 6 * d, label)
        }
        val goal = days.last().third
        val gy = bottom - (bottom - top) * (goal / maxV)
        c.drawLine(0f, gy, width.toFloat(), gy, goalLine)
    }
}
