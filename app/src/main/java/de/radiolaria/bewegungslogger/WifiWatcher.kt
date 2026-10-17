package de.radiolaria.bewegungslogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler

/**
 * Meldet das verbundene WLAN (BSSID, SSID) bzw. null, wenn keins verbunden ist.
 * BSSID/SSID liefert Android nur mit Standortberechtigung (und eingeschaltetem Standort).
 */
class WifiWatcher(
    private val ctx: Context,
    private val handler: Handler,
    private val onChange: (bssid: String?, ssid: String?) -> Unit,
) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastBssid: String? = "init"

    fun start() {
        if (callback != null) return
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wi = caps.transportInfo as? WifiInfo
                    report(wi?.bssid, wi?.ssid)
                }
                override fun onLost(network: Network) = report(null, null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = legacy()
                override fun onLost(network: Network) = report(null, null)
            }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, cb, handler)
        callback = cb
        // Ohne WLAN kommt kein Callback: Anfangszustand "kein WLAN" melden
        handler.post { if (lastBssid == "init") report(null, null) }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        lastBssid = "init"
    }

    @Suppress("DEPRECATION")
    private fun legacy() {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val wi = wm.connectionInfo
        report(wi?.bssid, wi?.ssid)
    }

    private fun report(bssidRaw: String?, ssidRaw: String?) {
        // 02:00:00:00:00:00 = von Android verborgen (fehlende Berechtigung)
        val bssid = bssidRaw?.lowercase()?.takeIf { it != "02:00:00:00:00:00" }
        val ssid = ssidRaw?.removeSurrounding("\"")?.takeIf { bssid != null && it != "<unknown ssid>" }
        if (bssid == lastBssid) return
        lastBssid = bssid
        onChange(bssid, ssid)
    }
}
