package de.radiolaria.bewegungslogger

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kalibrier-Aufzeichnung: rohe Luftdruckwerte, Schrittzählerstände und Markierungen als CSV.
 *
 *   t_ms,typ,wert
 *   1792650000123,p,1007.8312     Luftdruck in hPa
 *   1792650000456,s,123456        Schrittzähler (Gesamtstand seit Gerätestart)
 *   1792650001000,m,Treppe        Markierung (Treppe, Fahrstuhl, Rad, Stand, Ende)
 *
 * Die Datei entsteht in [arbeitsDir] und wird bei [stop] nach [zielDir] verschoben (von dort exportiert).
 * Reines Kotlin, keine Android-Abhängigkeit.
 *
 * @param offsetNs Sensorzeit (ns) + offsetNs = Weltzeit (ns)
 */
class Kalibrierung(
    private val arbeitsDir: File,
    private val zielDir: File,
    startMs: Long,
    private val offsetNs: Long,
) {
    val name: String = "baro_roh_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startMs)) + ".csv"
    private val datei = File(arbeitsDir.apply { mkdirs() }, name)
    private val out: BufferedWriter = datei.bufferedWriter()
    var zeilen = 0L
        private set

    init {
        out.write("t_ms,typ,wert\n")
    }

    private fun ms(sensorNs: Long) = (sensorNs + offsetNs) / 1_000_000

    fun druck(sensorNs: Long, hpa: Float) = zeile(ms(sensorNs), "p", String.format(Locale.US, "%.4f", hpa))
    fun schritte(sensorNs: Long, gesamt: Float) = zeile(ms(sensorNs), "s", gesamt.toLong().toString())
    fun marke(wallMs: Long, label: String) = zeile(wallMs, "m", label.replace(',', ' ').replace('\n', ' '))

    private fun zeile(t: Long, typ: String, wert: String) {
        out.write("$t,$typ,$wert\n")
        zeilen++
    }

    fun flush() = out.flush()

    /** Schließt die Datei und verschiebt sie zum Export. @return Zieldatei */
    fun stop(): File {
        out.flush()
        out.close()
        val ziel = File(zielDir.apply { mkdirs() }, name)
        if (!datei.renameTo(ziel)) {
            datei.copyTo(ziel, overwrite = true)
            datei.delete()
        }
        return ziel
    }
}
