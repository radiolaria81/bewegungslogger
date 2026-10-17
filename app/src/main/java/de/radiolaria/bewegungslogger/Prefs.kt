package de.radiolaria.bewegungslogger

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Einstellungen (SharedPreferences). */
object Prefs {
    private const val NAME = "settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_WIFI = "stationary_wifi" // Einträge "bssid|ssid"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_STEPS_DAY = "steps_day"
    private const val KEY_STEPS_TODAY = "steps_today"

    /** Tagesgrenze für die Schrittanzeige: 4:00 Uhr Ortszeit. */
    private const val DAY_START_HOUR = 4

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun setupDone(ctx: Context) = sp(ctx).getBoolean(KEY_SETUP_DONE, false)
    fun setSetupDone(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_SETUP_DONE, v).apply()

    /** Kennung des "Tages" ab 4:00 Uhr, z. B. 2026-10-17 für 17.10. 4:00 bis 18.10. 3:59. */
    fun stepsDayKey(ms: Long): String {
        // Über die Ortszeit-Stunde statt "ms - 4 h", damit die Grenze auch an Tagen mit Zeitumstellung bei 4:00 liegt
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        if (c.get(Calendar.HOUR_OF_DAY) < DAY_START_HOUR) c.add(Calendar.DAY_OF_MONTH, -1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    /** Gespeicherte Tagesschritte; 0, wenn der gespeicherte Wert zu einem anderen Tag gehört. */
    fun stepsToday(ctx: Context, nowMs: Long): Long =
        if (sp(ctx).getString(KEY_STEPS_DAY, null) == stepsDayKey(nowMs)) sp(ctx).getLong(KEY_STEPS_TODAY, 0L) else 0L

    fun saveStepsToday(ctx: Context, dayKey: String, steps: Long) =
        sp(ctx).edit().putString(KEY_STEPS_DAY, dayKey).putLong(KEY_STEPS_TODAY, steps).apply()

    fun enabled(ctx: Context) = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun treeUri(ctx: Context): Uri? = sp(ctx).getString(KEY_TREE, null)?.let { Uri.parse(it) }
    fun setTreeUri(ctx: Context, uri: Uri) = sp(ctx).edit().putString(KEY_TREE, uri.toString()).apply()

    /** Ortsfeste WLANs als Map BSSID -> SSID. */
    fun stationaryWifis(ctx: Context): Map<String, String> =
        (sp(ctx).getStringSet(KEY_WIFI, emptySet()) ?: emptySet()).associate {
            val i = it.indexOf('|')
            if (i < 0) it to "" else it.substring(0, i) to it.substring(i + 1)
        }

    fun isStationary(ctx: Context, bssid: String?) =
        bssid != null && stationaryWifis(ctx).containsKey(bssid.lowercase())

    fun addStationary(ctx: Context, bssid: String, ssid: String?) {
        val m = stationaryWifis(ctx).toMutableMap()
        m[bssid.lowercase()] = ssid ?: ""
        save(ctx, m)
    }

    fun removeStationary(ctx: Context, bssid: String) {
        val m = stationaryWifis(ctx).toMutableMap()
        m.remove(bssid.lowercase())
        save(ctx, m)
    }

    private fun save(ctx: Context, m: Map<String, String>) =
        sp(ctx).edit().putStringSet(KEY_WIFI, m.map { "${it.key}|${it.value}" }.toSet()).apply()
}

/** Laufzeitstatus für die Anzeige in der Activity (vom Dienst geschrieben). */
object Live {
    @Volatile var running = false
    @Volatile var stationary = false
    @Volatile var gpsOn = false
    @Volatile var ssid: String? = null
    @Volatile var bssid: String? = null
    @Volatile var stepSensor = true
    @Volatile var stepsToday = 0L
    @Volatile var stepsDayKey = ""
    @Volatile var lastRecordMs = 0L
    @Volatile var lastExport = "–"
}
