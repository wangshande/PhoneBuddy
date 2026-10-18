package com.deskbuddy

import kotlin.math.log10
import kotlin.math.sqrt

/** 将真实 PCM 音量映射到显示范围；静音严格为零。 */
object AudioLevel {
    fun fromPcm(pcm: ShortArray, count: Int): Float {
        val n = count.coerceIn(0, pcm.size)
        if (n == 0) return 0f
        var energy = 0.0
        for (i in 0 until n) { val value = pcm[i].toDouble(); energy += value * value }
        val rms = sqrt(energy / n) / 32768.0
        if (rms < 0.001) return 0f
        return ((20.0 * log10(rms) + 55.0) / 45.0).toFloat().coerceIn(0f, 1f)
    }
}
