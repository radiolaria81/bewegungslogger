package de.radiolaria.bewegungslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaroStatsTest {

    /** Druck zu Höhe h (Umkehrung der Höhenformel). */
    private fun hpaAt(h: Double) = 1013.25 * Math.pow(1.0 - h / 44330.0, 5.255)

    private fun run(speedMs: Double, seconds: Int): BaroStats.Result {
        val b = BaroStats()
        // 30 s ruhig, dann Fahrt mit konstanter Geschwindigkeit, 5 Messungen pro Sekunde
        var h = 0.0
        for (i in 0 until (30 + seconds) * 5) {
            val s = i / 5.0
            if (s >= 30) h += speedMs / 5
            b.add((s * 1e9).toLong(), hpaAt(h).toFloat())
        }
        return b.result()!!
    }

    @Test fun lift() {
        val r = run(1.0, 25)
        assertNotNull(r.climbMax)
        assertEquals(1.0, r.climbMax!!, 0.05)
    }

    @Test fun stairs() {
        val r = run(0.2, 120)
        assertEquals(0.2, r.climbMax!!, 0.03)
    }

    @Test fun lastValue() {
        val r = run(0.2, 120)
        assertEquals(hpaAt(24.0), r.last, 0.01)
        assertTrue(r.mean > r.last)
    }

    @Test fun gapResetsWindow() {
        val b = BaroStats()
        b.add(0L, hpaAt(0.0).toFloat())
        b.add(60_000_000_000L, hpaAt(30.0).toFloat())  // 60 s Lücke: kein Anstieg ableitbar
        assertEquals(null, b.result()!!.climbMax)
    }
}
