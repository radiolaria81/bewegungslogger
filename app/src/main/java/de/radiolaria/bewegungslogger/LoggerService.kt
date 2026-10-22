package de.radiolaria.bewegungslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vordergrund-Dienst, läuft den ganzen Tag.
 *
 * - Schrittzähler: immer.
 * - GPS + Beschleunigungssensor ("Sitzung"): nur wenn
 *     KEIN ortsfestes WLAN verbunden  UND  Bewegung erkannt
 *   (Significant-Motion-Sensor oder Schritte).
 *   Sitzung endet, wenn ein ortsfestes WLAN auftaucht ODER STOP_AFTER_MS lang keine Bewegung.
 * - Pro Minute (bzw. pro Intervall, wenn das Gerät schläft) eine JSON-Zeile.
 *
 * Die gesamte Logik läuft auf einem eigenen Thread ("logger").
 */
class LoggerService : Service(), SensorEventListener2 {

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_RECHECK = "recheck"   // nach Änderung der WLAN-Liste
        const val ACTION_EXPORT = "export"
        const val ACTION_KALIB_START = "kalib_start"
        const val ACTION_KALIB_STOP = "kalib_stop"
        const val ACTION_MARKE = "marke"           // Extra EXTRA_LABEL
        const val EXTRA_LABEL = "label"

        private const val CHANNEL = "logger"
        private const val NOTIF_ID = 1

        private const val TICK_MS = 60_000L
        private const val STOP_AFTER_MS = 60_000L
        private const val GPS_INTERVAL_MS = 5_000L
        private const val ACC_PERIOD_US = 40_000            // 25 Hz
        private const val ACC_LATENCY_US = 10_000_000       // Batching 10 s
        private const val STEP_LATENCY_US = 30_000_000      // Batching 30 s
        private const val BARO_LATENCY_US = 30_000_000      // Batching 30 s
        private const val BARO_NACHLAUF_MS = 3 * 60_000L    // Barometer nach WLAN-Verbindung noch so lange an
        private const val STEPS_START = 15                  // so viele Schritte im Intervall starten eine Sitzung
        private const val STEPS_EVIDENCE = 30               // so viele Schritte pro Intervall gelten als Bewegung
        private const val MOVE_SPEED = 1.0f                 // m/s (GPS-Doppler)
        private const val MOVE_DIST = 100f                  // m Abstand zum letzten Ankerpunkt
        private const val MAX_ACC = 30f                     // m, schlechtere Fixes zählen nicht als Bewegung
        private const val FLUSH_TIMEOUT_MS = 3_000L
        private const val LEAVE_DELAY_MS = 60_000L         // so lange muss das ortsfeste WLAN weg sein
        private const val RECORD_VERSION = 1
        private const val KALIB_MAX_MS = 2 * 60 * 60_000L    // Kalibrierung endet spätestens nach 2 h
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private lateinit var recorder: Recorder
    private lateinit var wifi: WifiWatcher
    private var wakeLock: PowerManager.WakeLock? = null

    private var stepSensor: Sensor? = null
    private var sigSensor: Sensor? = null
    private var accSensor: Sensor? = null
    private var baroSensor: Sensor? = null
    private var baroOn = false
    private val baro = BaroStats()
    private var started = false
    private var kalib: Kalibrierung? = null
    private var kalibLock: PowerManager.WakeLock? = null
    private val kalibAuto = Runnable { kalibStop() }

    // ---- Zustand, nur auf dem logger-Thread ----
    private var stationary = false
    private var ssid: String? = null
    private var session = false
    private var sessionInInterval = false
    private var lastEvidence = 0L
    private var lastStepTotal = -1f
    private var steps = 0
    private var sigCount = 0
    private var intervalStart = 0L
    private var gps = JSONArray()
    private val accel = AccelStats()
    private var anchor: Location? = null
    private var flushPending = false
    private var exportNowRequested = false
    private var stepsRegistered = false

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private val tick = Runnable { onTick() }
    private val flushTimeout = Runnable {
        if (flushPending) {
            flushPending = false
            finishInterval(reschedule = true)
        }
    }

    private val sigListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            handler.post {
                sigCount++
                if (!stationary) {
                    evidence()
                    startSession()
                }
                armSig()
            }
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("logger").also { it.start() }
        handler = Handler(thread.looper)
        sm = getSystemService(SensorManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        recorder = Recorder(this)
        wifi = WifiWatcher(this, handler) { b, s -> onWifi(b, s) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECHECK -> if (started) handler.post { onWifi(Live.bssid, Live.ssid, force = true) }
            ACTION_EXPORT -> if (started) handler.post {
                // Laufendes Intervall sofort abschließen (Sensorpuffer leeren), dann Teildatei exportieren
                exportNowRequested = true
                handler.removeCallbacks(tick)
                if (!flushPending) onTick()
            }
            ACTION_KALIB_START -> if (started) handler.post { kalibStart() }
            ACTION_KALIB_STOP -> if (started) handler.post { kalibStop() }
            ACTION_MARKE -> if (started) {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "?"
                handler.post { marke(label) }
            }
        }
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Fehlende Standortberechtigung oder Start aus dem Hintergrund nicht erlaubt
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            Prefs.setEnabled(this, true)
            handler.post { startAll() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) {
            handler.post {
                finishInterval(reschedule = false)
                kalibStop(beenden = true)
                if (session) stopSession()
                handler.removeCallbacksAndMessages(null)
                sm.unregisterListener(this)
                sigSensor?.let { sm.cancelTriggerSensor(sigListener, it) }
                wifi.stop()
                Live.running = false
                Live.gpsOn = false
                thread.quitSafely()
            }
            started = false
        } else {
            thread.quitSafely()
        }
        super.onDestroy()
    }

    // ---------- Start ----------

    private fun startAll() {
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        sigSensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        baroSensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        Live.stepSensor = stepSensor != null
        Live.baroSensor = baroSensor != null
        // Nach Absturz liegengebliebene Kalibrier-Dateien zum Export geben
        File(filesDir, "kalib").listFiles()?.forEach { f -> runCatching { f.renameTo(File(recorder.dir, f.name)) } }

        val actGranted = checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        stepsRegistered = stepSensor != null && actGranted &&
            sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, STEP_LATENCY_US, handler)

        armSig()
        updateBaro()
        wifi.start()
        intervalStart = System.currentTimeMillis()
        Live.running = true
        export(intervalStart) // Liegengebliebenes nachholen
        scheduleTick()
    }

    /** Tageszähler der Anzeige um 4:00 Uhr auf 0 setzen. */
    private fun rollStepsDay(now: Long) {
        val key = Prefs.stepsDayKey(now)
        if (key != Live.stepsDayKey) {
            Live.stepsDayKey = key
            Live.stepsToday = Prefs.stepsToday(this, now) // gleicher Tag nach Neustart: weiterzählen, sonst 0
        }
    }

    private fun armSig() {
        sigSensor?.let { sm.requestTriggerSensor(sigListener, it) }
    }

    // ---------- Takt ----------

    private fun scheduleTick() {
        val now = System.currentTimeMillis()
        handler.postDelayed(tick, TICK_MS - now % TICK_MS)
    }

    private fun onTick() {
        if (stepsRegistered) {
            // Gepufferte Sensorwerte erst abholen, dann Intervall abschließen
            flushPending = true
            sm.flush(this)
            handler.postDelayed(flushTimeout, FLUSH_TIMEOUT_MS)
        } else {
            finishInterval(reschedule = true)
        }
    }

    override fun onFlushCompleted(sensor: Sensor) {
        if (sensor.type != Sensor.TYPE_STEP_COUNTER) return
        handler.post {
            if (flushPending) {
                flushPending = false
                handler.removeCallbacks(flushTimeout)
                finishInterval(reschedule = true)
            }
        }
    }

    private fun finishInterval(reschedule: Boolean) {
        val now = System.currentTimeMillis()
        if (!stationary && steps >= STEPS_EVIDENCE) evidence()

        val rec = JSONObject()
        rec.put("v", RECORD_VERSION)
        rec.put("start", isoFmt.format(Date(intervalStart)))
        rec.put("end", isoFmt.format(Date(now)))
        rec.put("start_ms", intervalStart)
        rec.put("end_ms", now)
        rec.put("steps", if (stepsRegistered) steps else JSONObject.NULL)
        rec.put("stationary_wifi", stationary)
        rec.put("gps_on", sessionInInterval || session)
        rec.put("sig_motion", sigCount)
        battery()?.let { rec.put("batt", it) }
        rec.put("gps", gps)
        baro.result()?.let { b ->  // Luftdruck (nur außerhalb ortsfester WLANs und kurz danach)
            rec.put("hpa", round(b.mean, 2))
            rec.put("hpa_end", round(b.last, 2))
            b.climbMax?.let { rec.put("steig_max", round(it, 2)) }
        }
        val a = accel.result()
        if (a != null) {
            val o = JSONObject()
            o.put("n", a.n)
            o.put("hz", round(a.rateHz, 1))
            o.put("mean", round(a.mean, 3))
            o.put("sd", round(a.sd, 3))
            if (a.fDomHz != null) o.put("f_dom", round(a.fDomHz, 2))
            if (a.peakRatio != null) o.put("peak_ratio", round(a.peakRatio, 3))
            rec.put("acc", o)
        } else {
            rec.put("acc", JSONObject.NULL)
        }
        runCatching { recorder.append(now, rec.toString()) }
        kalib?.let { k -> runCatching { k.flush() } }

        steps = 0
        sigCount = 0
        gps = JSONArray()
        accel.reset()
        baro.reset()
        intervalStart = now
        sessionInInterval = session
        Live.lastRecordMs = now
        rollStepsDay(now)
        Prefs.saveStepsToday(this, Live.stepsDayKey, Live.stepsToday)

        if (session && SystemClock.elapsedRealtime() - lastEvidence > STOP_AFTER_MS) stopSession()
        if (exportNowRequested) {
            exportNowRequested = false
            export(now, includeCurrent = true)
        } else {
            export(now)
        }
        updateNotification()
        if (reschedule) scheduleTick()
    }

    private fun export(now: Long, includeCurrent: Boolean = false) {
        val hm = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now))
        val r = runCatching { if (includeCurrent) recorder.exportNow(now) else recorder.exportCompleted(now) }
        when {
            r.isFailure -> Live.lastExport = "$hm Fehler: ${r.exceptionOrNull()?.message}"
            r.getOrNull() == -1 -> Live.lastExport = "kein Zielordner gewählt (${recorder.pendingCount(now)} Stunden warten)"
            (r.getOrNull() ?: 0) > 0 -> Live.lastExport = "$hm: ${r.getOrNull()} Datei(en) exportiert"
        }
    }

    // ---------- Sensoren ----------

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = e.values[0]
                kalib?.let { k -> runCatching { k.schritte(e.timestamp, total) } }
                if (lastStepTotal < 0) {
                    lastStepTotal = total // Bezugswert; Schritte davor sind unbekannt
                    return
                }
                var delta = total - lastStepTotal
                if (delta < 0) delta = total // Zähler nach Neustart zurückgesetzt
                lastStepTotal = total
                val d = delta.toInt()
                steps += d
                rollStepsDay(System.currentTimeMillis())
                Live.stepsToday += d
                if (!stationary && !session && steps >= STEPS_START) {
                    evidence()
                    startSession()
                }
            }
            Sensor.TYPE_ACCELEROMETER -> accel.add(e.timestamp, e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_PRESSURE -> {
                baro.add(e.timestamp, e.values[0])
                kalib?.let { k -> runCatching { k.druck(e.timestamp, e.values[0]) } }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ---------- WLAN ----------

    private fun onWifi(bssid: String?, newSsid: String?, force: Boolean = false) {
        Live.bssid = bssid
        Live.ssid = newSsid
        ssid = newSsid
        val st = Prefs.isStationary(this, bssid, newSsid)
        if (st) {
            handler.removeCallbacks(leaveStationary)
            setStationary(true, force)
        } else if (stationary && !force) {
            // Erst nach LEAVE_DELAY_MS ohne Rückkehr als "WLAN weg" werten (Zugangspunktwechsel, kurze Aussetzer)
            handler.removeCallbacks(leaveStationary)
            handler.postDelayed(leaveStationary, LEAVE_DELAY_MS)
        } else {
            setStationary(false, force)
        }
    }

    private val leaveStationary = Runnable {
        if (!Prefs.isStationary(this, Live.bssid, Live.ssid)) setStationary(false, false)
    }

    private fun setStationary(st: Boolean, force: Boolean) {
        if (st == stationary && !force) return
        stationary = st
        Live.stationary = st
        if (stationary && session) stopSession()
        updateBaro()
        updateNotification()
    }

    /**
     * Barometer läuft außerhalb ortsfester WLANs (Treppenhaus, Anstiege) und noch BARO_NACHLAUF_MS
     * nach dem Verbinden – das Heim-WLAN reicht oft schon in die oberen Stockwerke des Treppenhauses.
     */
    private fun updateBaro() {
        val s = baroSensor ?: return
        if (kalib != null) return  // Kalibrierung: Barometer läuft durchgehend mit voller Rate
        val want = !stationary
        if (want) {
            handler.removeCallbacks(baroStop)
            if (!baroOn) baroOn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, BARO_LATENCY_US, handler)
        } else if (baroOn) {
            handler.removeCallbacks(baroStop)
            handler.postDelayed(baroStop, BARO_NACHLAUF_MS)
        }
    }

    private val baroStop = Runnable {
        if (stationary && baroOn && kalib == null) {
            baroSensor?.let { sm.unregisterListener(this, it) }
            baroOn = false
        }
    }

    // ---------- Kalibrierung (rohe Luftdruckwerte) ----------

    private fun kalibStart() {
        if (kalib != null) return
        val s = baroSensor ?: return
        val now = System.currentTimeMillis()
        val offsetNs = now * 1_000_000L - SystemClock.elapsedRealtimeNanos()
        val k = runCatching { Kalibrierung(File(filesDir, "kalib"), recorder.dir, now, offsetNs) }.getOrNull() ?: return
        kalib = k
        kalibLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bewegungslogger:kalib")
            .also { it.acquire(KALIB_MAX_MS + 60_000L) }
        // Barometer neu anmelden: höchste Rate, ohne Pufferung
        handler.removeCallbacks(baroStop)
        if (baroOn) sm.unregisterListener(this, s)
        baroOn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
        Live.kalibSeit = now
        marke("Start")
        handler.postDelayed(kalibAuto, KALIB_MAX_MS)
        updateNotification()
    }

    private fun kalibStop(beenden: Boolean = false) {
        val k = kalib ?: return
        handler.removeCallbacks(kalibAuto)
        marke("Stopp")
        kalib = null
        runCatching { k.stop() }
        kalibLock?.let { if (it.isHeld) it.release() }
        kalibLock = null
        baroSensor?.let { if (baroOn) sm.unregisterListener(this, it) }
        baroOn = false
        Live.kalibSeit = 0L
        if (beenden) return  // Dienst endet: Datei liegt im Exportordner, wird beim nächsten Start exportiert
        updateBaro()  // normaler Betrieb
        export(System.currentTimeMillis())
        updateNotification()
    }

    private fun marke(label: String) {
        val k = kalib ?: return
        val now = System.currentTimeMillis()
        runCatching { k.marke(now, label) }
        Live.kalibMarke = label + " " + SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(now))
    }

    // ---------- Sitzung (GPS + Beschleunigung) ----------

    private fun evidence() {
        lastEvidence = SystemClock.elapsedRealtime()
    }

    private fun startSession() {
        if (session) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locListener, thread.looper)
        } catch (e: Exception) {
            return // keine Berechtigung oder kein GPS
        }
        session = true
        sessionInInterval = true
        anchor = null
        evidence()
        accSensor?.let { sm.registerListener(this, it, ACC_PERIOD_US, ACC_LATENCY_US, handler) }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bewegungslogger:session")
            .also { it.acquire(6 * 60 * 60_000L) }
        Live.gpsOn = true
        updateNotification()
    }

    private fun stopSession() {
        if (!session) return
        session = false
        runCatching { lm.removeUpdates(locListener) }
        accSensor?.let { sm.unregisterListener(this, it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        anchor = null
        Live.gpsOn = false
        updateNotification()
    }

    private fun onLocation(loc: Location) {
        val p = JSONObject()
        p.put("t", loc.time)
        p.put("lat", loc.latitude)
        p.put("lon", loc.longitude)
        if (loc.hasAccuracy()) p.put("acc", round(loc.accuracy.toDouble(), 1))
        if (loc.hasSpeed()) p.put("spd", round(loc.speed.toDouble(), 2))
        if (loc.hasAltitude()) p.put("alt", round(loc.altitude, 1))
        gps.put(p)

        if (loc.hasAccuracy() && loc.accuracy <= MAX_ACC) {
            if (loc.hasSpeed() && loc.speed >= MOVE_SPEED) evidence()
            val a = anchor
            if (a == null) {
                anchor = loc
            } else if (a.distanceTo(loc) >= MOVE_DIST) {
                evidence()
                anchor = loc
            }
        }
    }

    // ---------- Hilfen ----------

    private fun battery(): Int? =
        getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun round(v: Double, digits: Int): Double {
        if (v.isNaN() || v.isInfinite()) return 0.0
        var f = 1.0
        repeat(digits) { f *= 10 }
        return Math.round(v * f) / f
    }

    private fun buildNotification(): Notification {
        val text = when {
            !Live.running -> "Startet …"
            Live.kalibSeit > 0 -> "Kalibrierung läuft – Luftdruck-Rohdaten"
            Live.stationary -> "Ortsfestes WLAN – nur Schritte"
            Live.gpsOn -> "Unterwegs – GPS an"
            else -> "Unterwegs – GPS aus (keine Bewegung)"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bewegungslogger")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
