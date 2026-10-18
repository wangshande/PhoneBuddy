package com.deskbuddy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/** 最近 24 个真实收音值；暂停/静音不显示假声波。 */
class VoiceWaveView(context: Context) : View(context) {
    private val samples = FloatArray(24)
    private val display = FloatArray(24)
    private var cursor = 0
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ACCENT
        strokeCap = Paint.Cap.ROUND
    }
    var active = false
        set(value) {
            field = value
            if (!value) { samples.fill(0f); display.fill(0f) }
            alpha = if (value) 1f else 0.25f
            contentDescription = if (value) "正在收音，声波随真实音量变化" else "麦克风未开启"
            invalidate()
        }
    fun push(level: Float) {
        if (!active) return
        samples[cursor] = level.coerceIn(0f, 1f)
        cursor = (cursor + 1) % samples.size
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val gap = width.toFloat() / samples.size
        pen.strokeWidth = minOf(context.dp(3).toFloat(), gap * 0.45f)
        for (i in samples.indices) {
            val target = samples[(cursor + i) % samples.size]
            display[i] += (target - display[i]) * 0.45f
            val amplitude = context.dp(1) + display[i] * height * 0.42f
            val x = (i + 0.5f) * gap
            canvas.drawLine(x, height/2f-amplitude, x, height/2f+amplitude, pen)
        }
        if (active && display.indices.any { abs(display[it] - samples[(cursor+it)%samples.size]) > 0.01f }) {
            postInvalidateOnAnimation()
        }
    }
}
