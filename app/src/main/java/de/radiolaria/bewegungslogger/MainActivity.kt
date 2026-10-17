package de.radiolaria.bewegungslogger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQ_PERM = 1
        const val REQ_BG = 2
        const val REQ_TREE = 3
        const val POLL_MS = 2_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var wifiList: LinearLayout
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            col.addView(this, lp())
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, pad, 0, pad / 4)
            col.addView(this, lp())
        }

        status = TextView(this).apply { textSize = 14f }
        col.addView(status, lp())
        toggle = button("") { onToggle() }

        label("Einrichtung")
        button("1. Berechtigungen erteilen") { requestBasics() }
        button("2. Standort „Immer zulassen“") { requestBackground() }
        button("3. Zielordner wählen (Nextcloud-Sync)") { pickFolder() }
        button("4. Akku-Optimierung ausschalten") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        label("Ortsfeste WLANs (dort kein GPS)")
        button("Aktuelles WLAN hinzufügen") { addCurrentWifi() }
        wifiList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(wifiList, lp())

        label("Export")
        button("Abgeschlossene Stunden jetzt exportieren") {
            if (Live.running) startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_EXPORT))
            else toast("Aufzeichnung läuft nicht.")
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onResume() {
        super.onResume()
        main.post(poll)
        renderWifiList()
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    // ---------- Status ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        toggle.text = if (Live.running) "Aufzeichnung stoppen" else "Aufzeichnung starten"
        val ok = "✓"
        val no = "✗"
        val pm = getSystemService(PowerManager::class.java)
        val notifOk = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val hm = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
        val wifiText = Live.ssid?.let { "$it (${Live.bssid})" } ?: Live.bssid ?: "keins / unbekannt"
        status.text = buildString {
            appendLine(if (Live.running) "● Aufzeichnung läuft" else "○ Aufzeichnung aus")
            if (Live.running) {
                appendLine("WLAN: $wifiText" + if (Live.stationary) "  → ortsfest" else "")
                appendLine("GPS: " + if (Live.gpsOn) "an" else "aus")
                appendLine("Schritte seit Start: ${Live.stepsSinceStart}" + if (!Live.stepSensor) " (kein Schrittzähler!)" else "")
                if (Live.lastRecordMs > 0) appendLine("Letzter Datensatz: ${hm.format(Date(Live.lastRecordMs))}")
                appendLine("Export: ${Live.lastExport}")
            }
            appendLine()
            appendLine("${if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) ok else no} Standort genau")
            appendLine("${if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) ok else no} Standort immer")
            appendLine("${if (granted(Manifest.permission.ACTIVITY_RECOGNITION)) ok else no} Körperliche Aktivität")
            appendLine("${if (notifOk) ok else no} Benachrichtigungen")
            appendLine("${if (Prefs.treeUri(this@MainActivity) != null) ok else no} Zielordner")
            append("${if (pm.isIgnoringBatteryOptimizations(packageName)) ok else no} Akku-Optimierung aus")
        }
    }

    // ---------- Aufzeichnung ----------

    private fun onToggle() {
        if (Live.running) {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
            return
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Erst Schritt 1: Berechtigungen erteilen.")
            return
        }
        startForegroundService(Intent(this, LoggerService::class.java))
    }

    // ---------- Berechtigungen ----------

    private fun requestBasics() {
        val need = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(need.filter { !granted(it) }.toTypedArray().ifEmpty { return toast("Schon erteilt.") }, REQ_PERM)
    }

    private fun requestBackground() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return toast("Erst Schritt 1.")
        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return toast("Schon erteilt.")
        // Ab Android 11 führt das in die Einstellungen; dort „Immer zulassen“ wählen.
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- Ordner ----------

    private fun pickFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.setTreeUri(this, uri)
            toast("Ordner gespeichert.")
        } catch (e: Exception) {
            toast("Ordner nicht nutzbar: ${e.message}")
        }
        refresh()
    }

    // ---------- WLANs ----------

    private fun addCurrentWifi() {
        val b = Live.bssid
        if (!Live.running) return toast("Erst Aufzeichnung starten, dann ist das WLAN sichtbar.")
        if (b == null) return toast("Kein WLAN erkannt (Standort an? Berechtigung erteilt?).")
        Prefs.addStationary(this, b, Live.ssid)
        startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_RECHECK))
        renderWifiList()
        toast("${Live.ssid ?: b} hinzugefügt.")
    }

    private fun renderWifiList() {
        wifiList.removeAllViews()
        val m = Prefs.stationaryWifis(this)
        if (m.isEmpty()) {
            wifiList.addView(TextView(this).apply { text = "noch keins" }, lp())
            return
        }
        for ((bssid, name) in m.entries.sortedBy { it.value }) {
            wifiList.addView(Button(this).apply {
                text = "${name.ifEmpty { "?" }}  ($bssid)  ✕"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("${name.ifEmpty { bssid }} entfernen?")
                        .setPositiveButton("Entfernen") { _, _ ->
                            Prefs.removeStationary(this@MainActivity, bssid)
                            if (Live.running) startService(
                                Intent(this@MainActivity, LoggerService::class.java).setAction(LoggerService.ACTION_RECHECK)
                            )
                            renderWifiList()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }, lp())
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
