package de.radiolaria.bewegungslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AccelStatsTest {

    /** 60 s bei 25 Hz: Schwerkraft + Sinus mit Frequenz f + Rauschen. */
    private fun simulate(f: Double, amp: Double, noise: Double): AccelStats.Result {
        val rnd = Random(1)
        val s = AccelStats()
        val fs = 25.0
        for (i in 0 until (60 * fs).toInt()) {
            val t = i / fs
            val z = 9.81 + amp * sin(2 * PI * f * t) + rnd.nextDouble(-noise, noise)
            s.add((t * 1e9).toLong(), 0f, 0f, z.toFloat())
        }
        return s.result()!!
    }

    @Test
    fun findetSchrittfrequenz() {
        val r = simulate(1.8, 3.0, 0.5)
        assertEquals(1.8, r.fDomHz!!, 0.1)
        assertTrue("Peak deutlich: ${r.peakRatio}", r.peakRatio!! > 0.5)
        assertEquals(25.0, r.rateHz, 0.01)
    }

    @Test
    fun findetTrittfrequenz() {
        val r = simulate(1.25, 1.0, 0.3)
        assertEquals(1.25, r.fDomHz!!, 0.1)
    }

    @Test
    fun rauschenHatKeinenDeutlichenPeak() {
        val r = simulate(1.0, 0.0, 1.0)
        assertTrue("Peak-Anteil bei Rauschen: ${r.peakRatio}", r.peakRatio!! < 0.2)
    }

    @Test
    fun zuWenigeWerteKeineFrequenz() {
        val s = AccelStats()
        for (i in 0 until 100) s.add(i * 40_000_000L, 0f, 0f, 9.81f)
        val r = s.result()!!
        assertNull(r.fDomHz)
        assertEquals(9.81, r.mean, 1e-4)
    }
}
