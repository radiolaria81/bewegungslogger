package de.radiolaria.bewegungslogger

import kotlin.math.pow

/**
 * Luftdruck eines Intervalls: Mittel, letzter Wert und schnellster Anstieg.
 *
 * Der schnellste Anstieg (m/s) wird über ein Fenster von 10 s bestimmt: Mittel der
 * Messwerte der letzten 2 s gegen das Mittel der Werte von vor 10–12 s. So lassen sich
 * Fahrstuhl (Nenngeschwindigkeit Wohnhaus meist ≥ 0,63 m/s) und Treppe (deutlich langsamer)
 * unterscheiden. Die Messwerte der letzten Sekunden bleiben über die Intervallgrenze
 * hinweg erhalten, damit ein Anstieg an der Minutengrenze nicht verloren geht.
 * Reines Kotlin, keine Android-Abhängigkeit.
 */
class BaroStats {

    data class Result(val mean: Double, val last: Double, val climbMax: Double?)

    private var sum = 0.0
    private var n = 0
    private var last = 0.0
    private var climbMax: Double? = null

    // Verlauf der letzten WINDOW_NS + AVG_NS
    private val t = ArrayDeque<Long>()
    private val p = ArrayDeque<Double>()

    fun add(tNs: Long, hpa: Float) {
        val v = hpa.toDouble()
        sum += v; n++; last = v
        if (t.isNotEmpty() && tNs - t.last() > GAP_NS) { t.clear(); p.clear() }  // Sensor war aus
        t.addLast(tNs); p.addLast(v)
        while (t.isNotEmpty() && tNs - t.first() > WINDOW_NS + AVG_NS) { t.removeFirst(); p.removeFirst() }

        var sNow = 0.0; var tNow = 0.0; var cNow = 0
        var sOld = 0.0; var tOld = 0.0; var cOld = 0
        for (i in t.indices) {
            val age = tNs - t[i]
            if (age <= AVG_NS) { sNow += p[i]; tNow += t[i].toDouble(); cNow++ }
            else if (age >= WINDOW_NS) { sOld += p[i]; tOld += t[i].toDouble(); cOld++ }
        }
        if (cNow == 0 || cOld == 0) return
        val dtS = (tNow / cNow - tOld / cOld) / 1e9
        if (dtS <= 0) return
        val rate = (heightM(sNow / cNow) - heightM(sOld / cOld)) / dtS
        if (climbMax == null || rate > climbMax!!) climbMax = rate
    }

    fun result(): Result? = if (n == 0) null else Result(sum / n, last, climbMax)

    /** Neues Intervall; der Verlauf der letzten Sekunden bleibt. */
    fun reset() {
        sum = 0.0; n = 0; climbMax = null
    }

    companion object {
        const val WINDOW_NS = 10_000_000_000L
        const val AVG_NS = 2_000_000_000L
        const val GAP_NS = 5_000_000_000L

        /** Internationale Höhenformel (Standardatmosphäre). */
        fun heightM(hpa: Double) = 44330.0 * (1.0 - (hpa / 1013.25).pow(1.0 / 5.255))
    }
}
