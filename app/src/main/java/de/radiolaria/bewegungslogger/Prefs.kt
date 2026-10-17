package de.radiolaria.bewegungslogger

import android.content.Context
import android.net.Uri

/** Einstellungen (SharedPreferences). */
object Prefs {
    private const val NAME = "settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_WIFI = "stationary_wifi" // Einträge "bssid|ssid"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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
    @Volatile var stepsSinceStart = 0L
    @Volatile var lastRecordMs = 0L
    @Volatile var lastExport = "–"
}
