package de.radiolaria.bewegungslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KalibrierungTest {

    @Test
    fun schreibtCsvUndVerschiebtBeimStopp() {
        val tmp = Files.createTempDirectory("kalib").toFile()
        val arbeit = File(tmp, "kalib")
        val ziel = File(tmp, "pending")
        val startMs = 1_792_650_000_000L
        // Sensorzeit 5 s = Weltzeit startMs + 5 s
        val offsetNs = startMs * 1_000_000L - 0L
        val k = Kalibrierung(arbeit, ziel, startMs, offsetNs)
        k.marke(startMs, "Treppe")
        k.druck(5_000_000_000L, 1007.8312f)
        k.schritte(5_500_000_000L, 1234f)
        k.marke(startMs + 6000, "a,b")
        assertEquals(4L, k.zeilen)
        val f = k.stop()
        assertEquals(ziel, f.parentFile)
        assertFalse(File(arbeit, k.name).exists())
        assertTrue(k.name.startsWith("baro_roh_") && k.name.endsWith(".csv"))
        val z = f.readLines()
        assertEquals("t_ms,typ,wert", z[0])
        assertEquals("$startMs,m,Treppe", z[1])
        assertEquals("${startMs + 5000},p,1007.8312", z[2])
        assertEquals("${startMs + 5500},s,1234", z[3])
        assertEquals("${startMs + 6000},m,a b", z[4])
        tmp.deleteRecursively()
    }
}
