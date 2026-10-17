package de.radiolaria.bewegungslogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Startet die Aufzeichnung nach Neustart oder App-Update wieder, wenn sie vorher an war. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.enabled(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching { ctx.startForegroundService(Intent(ctx, LoggerService::class.java)) }
    }
}
