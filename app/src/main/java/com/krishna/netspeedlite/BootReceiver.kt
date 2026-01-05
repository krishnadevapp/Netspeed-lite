package com.krishna.netspeedlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)

        if (showSpeed && (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == "android.intent.action.QUICKBOOT_POWERON")) {

            val serviceIntent = Intent(context, SpeedService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 12+ may throw ForegroundServiceStartNotAllowedException
                    // when started from broadcast receiver - catch and handle gracefully
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // On Android 12+, use WorkManager or schedule for later
                        // For now, just try to start and catch exception
                        try {
                            context.startForegroundService(serviceIntent)
                        } catch (e: Exception) {
                            // Service cannot start from broadcast receiver on Android 12+
                            // User will need to manually start the service
                            android.util.Log.w("BootReceiver", "Cannot start service from boot receiver on Android 12+", e)
                        }
                    } else {
                        context.startForegroundService(serviceIntent)
                    }
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Log but don't crash - service will start when user opens app
                android.util.Log.e("BootReceiver", "Failed to start service on boot", e)
            }
        }
    }
}