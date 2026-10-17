package de.radiolaria.bewegungslogger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher
import android.widget.ImageView
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
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    // Farben (hell/dunkel über values/values-night)
    private val cBg by lazy { getColor(R.color.bg) }
    private val cCard by lazy { getColor(R.color.card) }
    private val cText by lazy { getColor(R.color.text) }
    private val cSub by lazy { getColor(R.color.text_sub) }
    private val cDivider by lazy { getColor(R.color.divider) }
    private val cPrimary by lazy { getColor(R.color.primary) }
    private val cOnPrimary by lazy { getColor(R.color.on_primary) }
    private val cPrimaryC by lazy { getColor(R.color.primary_container) }
    private val cOnPrimaryC by lazy { getColor(R.color.on_primary_container) }
    private val cWarn by lazy { getColor(R.color.warn) }
    private val cWarnC by lazy { getColor(R.color.warn_container) }
    private val cDanger by lazy { getColor(R.color.danger) }
    private val cDangerC by lazy { getColor(R.color.danger_container) }
    private val cIdle by lazy { getColor(R.color.idle) }
    private val cRipple by lazy { getColor(R.color.ripple) }

    // Views, die beim Aktualisieren geändert werden
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var versionText: TextView
    private lateinit var stateDot: View
    private lateinit var stateTitle: TextView
    private lateinit var stateSub: TextView
    private lateinit var stepsValue: TextView
    private lateinit var lastValue: TextView
    private lateinit var wifiSub: TextView
    private lateinit var exportSub: TextView
    private lateinit var banner: TextView
    private lateinit var mainView: LinearLayout
    private lateinit var setupView: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var gear: TextView
    private var setupMode = false
    private lateinit var toggle: TextView
    private lateinit var setupRows: List<SetupRow>
    private lateinit var wifiList: LinearLayout

    private class SetupRow(val badge: TextView, val title: TextView, val sub: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(header())

        // ---- Hauptansicht: Start/Stopp, WLAN hinzufügen, Logdatei erstellen ----
        mainView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = text("", 14f, cWarn, bold = true).apply {
            background = ripple(rounded(cWarnC, 16), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { showSetup(true) }
        }
        mainView.addView(banner, lp(bottom = 12))
        mainView.addView(statusCard())
        toggle = pillButton("") { onToggle() }
        mainView.addView(toggle, lp(top = 4, bottom = 16))
        mainView.addView(card().apply {
            val (r1, s1) = actionRowWithSub("Aktuelles WLAN als ortsfest eintragen") { addCurrentWifi() }
            wifiSub = s1
            addView(r1)
            addView(divider())
            val (r2, s2) = actionRowWithSub("Logdatei jetzt erstellen") {
                if (Live.running) startService(Intent(this@MainActivity, LoggerService::class.java).setAction(LoggerService.ACTION_EXPORT))
                else toast("Aufzeichnung läuft nicht.")
            }
            exportSub = s2
            addView(r2)
        })
        content.addView(mainView)

        // ---- Einrichtung ----
        setupView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupView.addView(hint("Einmalig nötig. Antippen, was noch nicht erledigt ist."))
        setupView.addView(setupCard())
        setupView.addView(sectionTitle("Ortsfeste WLANs"))
        setupView.addView(hint("Mit einem dieser WLANs verbunden: GPS bleibt aus, es werden nur Schritte gezählt. Zum Entfernen antippen."))
        setupView.addView(wifiCard())
        val done = pillButton("Fertig") { finishSetup() }
        stylePill(done, cPrimary, cOnPrimary)
        setupView.addView(done, lp(top = 8, bottom = 8))
        content.addView(setupView)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        setContentView(scroll)
        setupWindow() // erst nach setContentView: vorher gibt es keinen DecorView (NPE auf Android 15)
        applyInsets(scroll)
        registerBack()
        showSetup(!Prefs.setupDone(this))
    }

    // ---------- Ansichten umschalten ----------

    private fun showSetup(on: Boolean) {
        setupMode = on
        mainView.visibility = if (on) View.GONE else View.VISIBLE
        setupView.visibility = if (on) View.VISIBLE else View.GONE
        headerTitle.text = if (on) "Einrichtung" else "Bewegungslogger"
        gear.visibility = if (on) View.GONE else View.VISIBLE
        scroll.scrollTo(0, 0)
        renderWifiList()
        refresh()
    }

    private fun finishSetup() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Ohne Standortberechtigung kann nicht aufgezeichnet werden (Schritt 1).")
            return
        }
        Prefs.setSetupDone(this, true)
        showSetup(false)
    }

    private fun onBack() {
        if (setupMode && Prefs.setupDone(this)) showSetup(false) else finish()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBack()
    }

    override fun onResume() {
        super.onResume()
        main.post(poll)
        renderWifiList()
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    // ---------- Fenster / Ränder ----------

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.setBackgroundDrawable(ColorDrawable(cBg))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val light = if (isNight()) 0 else
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!isNight()) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    /** Inhalt um Status-/Navigationsleiste (und Kamera-Aussparung) einrücken. */
    @Suppress("DEPRECATION")
    private fun applyInsets(scroll: View) {
        val pad = dp(20)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
                r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
            }
            content.setPadding(pad + l, dp(12) + t, pad + r, pad + b)
            insets
        }
        scroll.requestApplyInsets()
    }

    // ---------- Bausteine ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(top: Int = 0, bottom: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ripple(content: Drawable?, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(cRipple), content, rounded(0xFFFFFFFF.toInt(), radiusDp))

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.1f)
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(20))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerTitle = text("Bewegungslogger", 22f, cText, bold = true)
        col.addView(headerTitle)
        versionText = text("", 13f, cSub)
        col.addView(versionText)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gear = text("⚙", 24f, cSub).apply {
            gravity = Gravity.CENTER
            background = ripple(null, 24)
            isClickable = true
            contentDescription = "Einrichtung"
            setOnClickListener { showSetup(true) }
        }
        row.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)))
        return row
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 20)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = lp(bottom = 12)
    }

    private fun sectionTitle(s: String) = text(s, 14f, cPrimary, bold = true).apply {
        setPadding(dp(4), dp(8), 0, dp(8))
    }

    private fun hint(s: String) = text(s, 13f, cSub).apply {
        setPadding(dp(4), 0, dp(4), dp(10))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(cDivider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(16); marginEnd = dp(16)
        }
    }

    private fun pillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun stylePill(v: TextView, bg: Int, fg: Int) {
        v.background = ripple(rounded(bg, 28), 28)
        v.setTextColor(fg)
    }

    private fun statusCard(): View {
        val c = card()
        c.setPadding(dp(20), dp(18), dp(20), dp(18))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stateDot = View(this)
        top.addView(stateDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        stateTitle = text("", 18f, cText, bold = true)
        top.addView(stateTitle)
        c.addView(top)
        stateSub = text("", 14f, cSub).apply { setPadding(dp(22), dp(2), 0, dp(14)) }
        c.addView(stateSub)

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(label: String): TextView {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cBg, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val v = text("–", 22f, cText, bold = true)
            col.addView(v)
            col.addView(text(label, 12f, cSub))
            stats.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (stats.childCount > 0) marginStart = dp(10)
            })
            return v
        }
        stepsValue = stat("Schritte seit Start")
        lastValue = stat("Letzter Datensatz")
        c.addView(stats)
        return c
    }

    private fun setupCard(): View {
        val c = card()
        val items = listOf(
            "Berechtigungen" to { requestBasics() },
            "Standort „Immer zulassen“" to { requestBackground() },
            "Zielordner" to { pickFolder() },
            "Akku-Optimierung aus" to {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                Unit
            },
        )
        val rows = ArrayList<SetupRow>()
        items.forEachIndexed { i, (title, action) ->
            if (i > 0) c.addView(divider())
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = ripple(null, 20)
                isClickable = true
                setOnClickListener { action() }
            }
            val badge = text("${i + 1}", 14f, cOnPrimaryC, bold = true).apply { gravity = Gravity.CENTER }
            r.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val t = text(title, 16f, cText)
            val s = text("", 13f, cSub)
            col.addView(t); col.addView(s)
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(text("›", 22f, cSub))
            c.addView(r)
            rows += SetupRow(badge, t, s)
        }
        setupRows = rows
        return c
    }

    private fun wifiCard(): View {
        val c = card()
        wifiList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(wifiList)
        return c
    }

    private fun actionRowWithSub(label: String, onClick: () -> Unit): Pair<View, TextView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ripple(null, 20)
            isClickable = true
            setOnClickListener { onClick() }
        }
        col.addView(text(label, 16f, cPrimary, bold = true))
        val sub = text("", 13f, cSub)
        col.addView(sub)
        return col to sub
    }

    // ---------- Status ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun setBadge(row: SetupRow, number: Int, ok: Boolean) {
        row.badge.text = if (ok) "✓" else "$number"
        row.badge.background = oval(if (ok) cPrimaryC else cWarnC)
        row.badge.setTextColor(if (ok) cOnPrimaryC else cWarn)
    }

    private fun folderName(): String? {
        val uri = Prefs.treeUri(this) ?: return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return "gewählt"
        // z. B. "primary:Nextcloud/Bewegung" -> "Nextcloud/Bewegung"
        return id.substringAfter(':').ifEmpty { "Hauptspeicher" }
    }

    private fun refresh() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        versionText.text = "Version $version"

        // Zustand
        val (dot, title, sub) = when {
            !Live.running -> Triple(cIdle, "Aufzeichnung aus", "Zum Starten unten tippen.")
            Live.stationary -> Triple(cPrimary, "Läuft", "Ortsfestes WLAN – nur Schritte, GPS aus")
            Live.gpsOn -> Triple(cWarn, "Läuft · unterwegs", "GPS an")
            else -> Triple(cPrimary, "Läuft · unterwegs", "GPS aus – keine Bewegung")
        }
        stateDot.background = oval(dot)
        stateTitle.text = title
        stateSub.text = sub
        val hm = SimpleDateFormat("HH:mm", Locale.GERMANY)
        stepsValue.text = if (!Live.running) "–" else if (!Live.stepSensor) "kein Sensor" else
            String.format(Locale.GERMANY, "%,d", Live.stepsSinceStart)
        lastValue.text = if (Live.lastRecordMs > 0) hm.format(Date(Live.lastRecordMs)) else "–"
        val wifiName = Live.ssid ?: Live.bssid
        wifiSub.text = when {
            !Live.running -> "Erst Aufzeichnung starten"
            wifiName == null -> "Kein WLAN verbunden"
            Live.stationary -> "$wifiName – bereits eingetragen"
            else -> "Verbunden: $wifiName"
        }
        exportSub.text = if (Live.running) "Zuletzt: ${Live.lastExport}" else "Erst Aufzeichnung starten"

        if (Live.running) stylePill(toggle, cDangerC, cDanger) else stylePill(toggle, cPrimary, cOnPrimary)
        toggle.text = if (Live.running) "Aufzeichnung stoppen" else "Aufzeichnung starten"

        // Einrichtung
        val missing = buildList {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("Standort")
            if (!granted(Manifest.permission.ACTIVITY_RECOGNITION)) add("Körperliche Aktivität")
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add("Benachrichtigungen")
        }
        setBadge(setupRows[0], 1, missing.isEmpty())
        setupRows[0].sub.text = if (missing.isEmpty()) "Standort, Aktivität, Benachrichtigungen erteilt"
        else "Fehlt: " + missing.joinToString(", ")

        val bg = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        setBadge(setupRows[1], 2, bg)
        setupRows[1].sub.text = if (bg) "Erteilt" else "Damit die Aufzeichnung im Hintergrund weiterläuft"

        val folder = folderName()
        setBadge(setupRows[2], 3, folder != null)
        setupRows[2].sub.text = folder ?: "Ordner wählen, den die Nextcloud-App hochlädt"

        val batt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        setBadge(setupRows[3], 4, batt)
        setupRows[3].sub.text = if (batt) "Erledigt" else "Antippen und für Bewegungslogger ausschalten"

        // Warnhinweis auf der Hauptansicht, wenn nachträglich etwas entzogen wurde
        val open = buildList {
            if (missing.isNotEmpty()) add(missing.joinToString(", "))
            if (!bg) add("Standort „Immer“")
            if (folder == null) add("Zielordner")
            if (!batt) add("Akku-Optimierung")
        }
        banner.visibility = if (open.isEmpty()) View.GONE else View.VISIBLE
        banner.text = "⚠  Einrichtung unvollständig: ${open.joinToString(", ")} – antippen"
    }

    // ---------- Aufzeichnung ----------

    private fun onToggle() {
        if (Live.running) {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
            main.postDelayed({ refresh() }, 500)
            return
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Erst Schritt 1: Berechtigungen erteilen.")
            return
        }
        startForegroundService(Intent(this, LoggerService::class.java))
        main.postDelayed({ refresh() }, 500)
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
            wifiList.addView(text("Noch keins eingetragen.", 14f, cSub).apply {
                setPadding(dp(16), dp(14), dp(16), dp(6))
            })
            return
        }
        for ((bssid, name) in m.entries.sortedBy { it.value }) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(12), dp(12))
                background = ripple(null, 20)
                isClickable = true
                setOnClickListener { confirmRemove(bssid, name) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(name.ifEmpty { "Unbekanntes WLAN" }, 16f, cText))
            col.addView(text(bssid, 12f, cSub))
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(text("✕", 16f, cSub).apply { setPadding(dp(12), dp(4), dp(4), dp(4)) })
            wifiList.addView(r)
            wifiList.addView(divider())
        }
    }

    private fun confirmRemove(bssid: String, name: String) {
        AlertDialog.Builder(this)
            .setMessage("${name.ifEmpty { bssid }} entfernen?")
            .setPositiveButton("Entfernen") { _, _ ->
                Prefs.removeStationary(this, bssid)
                if (Live.running) startService(
                    Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_RECHECK)
                )
                renderWifiList()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
