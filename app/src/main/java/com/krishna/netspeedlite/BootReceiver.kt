package com.krishna.netspeedlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)

        if ((showSpeed || isAlertEnabled) && (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
                    intent.action == "com.krishna.netspeedlite.RESTART_SERVICE")) {

            val serviceIntent = Intent(context, SpeedService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        context.startForegroundService(serviceIntent)
                    } catch (e: Exception) {
                        // Android 12+ (API 31+) restricts starting foreground services from the background.
                        // BOOT_COMPLETED is an exception, but RESTART_SERVICE is not.
                        // If we catch ForegroundServiceStartNotAllowedException (or generic Exception here),
                        // we log it and avoid crashing. The service will restart when the user opens the app.
                        android.util.Log.w("BootReceiver", "BG launch restricted (Android 12+): ${e.message}")
                    }
                } else {
                    context.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Failed to start service", e)
            }
        }
    }
}