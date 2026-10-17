package de.radiolaria.bewegungslogger

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sammelt Beschleunigungswerte eines Intervalls und liefert Kennwerte:
 * Mittelwert und Standardabweichung des Betrags sowie die vorherrschende
 * Frequenz im Bereich 0,5–5 Hz (Schritt- bzw. Tritt-Rhythmus) mit ihrem
 * Leistungsanteil. Reines Kotlin, keine Android-Abhängigkeit.
 */
class AccelStats(initialCapacity: Int = 2048) {

    data class Result(
        val n: Int,
        val rateHz: Double,
        val mean: Double,
        val sd: Double,
        val fDomHz: Double?,
        val peakRatio: Double?,
    )

    private var t = LongArray(initialCapacity)
    private var mag = FloatArray(initialCapacity)
    var n = 0
        private set

    fun add(tNs: Long, x: Float, y: Float, z: Float) {
        if (n == t.size) {
            t = t.copyOf(n * 2)
            mag = mag.copyOf(n * 2)
        }
        t[n] = tNs
        mag[n] = sqrt(x * x + y * y + z * z)
        n++
    }

    fun reset() {
        n = 0
    }

    fun result(window: Int = WINDOW): Result? {
        if (n < 2) return null
        val dur = (t[n - 1] - t[0]) / 1e9
        if (dur <= 0) return null
        val fs = (n - 1) / dur

        var sum = 0.0
        for (i in 0 until n) sum += mag[i]
        val mean = sum / n
        var sq = 0.0
        for (i in 0 until n) {
            val d = mag[i] - mean
            sq += d * d
        }
        val sd = sqrt(sq / n)

        if (n < window) return Result(n, fs, mean, sd, null, null)

        val half = window / 2
        val power = DoubleArray(half + 1)
        val cosT = DoubleArray(window) { cos(2 * PI * it / window) }
        val sinT = DoubleArray(window) { sin(2 * PI * it / window) }
        val hann = DoubleArray(window) { 0.5 - 0.5 * cos(2 * PI * it / (window - 1)) }
        val buf = DoubleArray(window)
        var start = 0
        while (start + window <= n) {
            var m = 0.0
            for (i in 0 until window) m += mag[start + i]
            m /= window
            for (i in 0 until window) buf[i] = (mag[start + i] - m) * hann[i]
            for (k in 1..half) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until window) {
                    val idx = (k * i) % window
                    re += buf[i] * cosT[idx]
                    im -= buf[i] * sinT[idx]
                }
                power[k] += re * re + im * im
            }
            start += window / 2
        }

        val kMin = ceil(F_MIN * window / fs).toInt().coerceAtLeast(1)
        val kMax = floor(F_MAX * window / fs).toInt().coerceAtMost(half)
        if (kMin > kMax) return Result(n, fs, mean, sd, null, null)

        var best = kMin
        for (k in kMin..kMax) if (power[k] > power[best]) best = k
        var total = 0.0
        for (k in 1..half) total += power[k]
        var peak = 0.0
        for (k in best - 1..best + 1) if (k in 1..half) peak += power[k]

        return Result(n, fs, mean, sd, best * fs / window, if (total > 0) peak / total else null)
    }

    companion object {
        const val WINDOW = 256      // bei 25 Hz ca. 10 s
        const val F_MIN = 0.5
        const val F_MAX = 5.0
    }
}
