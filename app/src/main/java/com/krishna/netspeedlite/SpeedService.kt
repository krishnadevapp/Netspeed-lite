package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Calendar
import java.util.Locale

class SpeedService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private val interval = 1000L

    // ⭐ NEW CHANNEL ID v5 to reset importance to a standard level
    private val channelId = "speed_channel_v5"
    private val notificationId = 1
    
    // ⭐ Fixed timestamp: marks this notification as "older" than new events (like Downloads)
    private val serviceStartTime = System.currentTimeMillis()

    private lateinit var prefs: SharedPreferences
    private lateinit var networkStatsManager: NetworkStatsManager

    override fun onBind(intent: Intent?): IBinder? = null

    // 🔁 UPDATE LOOP
    private val runnable = object : Runnable {
        override fun run() {
            updateNotificationData()
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        createNotificationChannel()

        lastRx = TrafficStats.getTotalRxBytes()
        lastTx = TrafficStats.getTotalTxBytes()

        // 🚨 MUST START FOREGROUND IMMEDIATELY (CRASH FIX)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Internet Speed")
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_speed) // ⚠️ ENSURE THIS EXISTS
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) 
            .setWhen(serviceStartTime) // ⭐ Use fixed time
            .setShowWhen(false)
            .build()

        startForeground(notificationId, notification)

        handler.post(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun updateNotificationData() {

        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        val rxDelta = rx - lastRx
        val txDelta = tx - lastTx
        val totalBytes = rxDelta + txDelta

        lastRx = rx
        lastTx = tx

        // 🔢 AUTO UNIT SWITCH
        val (speedVal, unitVal) =
            if (totalBytes >= 1_024_000) {
                Pair(String.format(Locale.US, "%.1f", totalBytes / 1_048_576f), "MB/s")
            } else {
                Pair((totalBytes / 1024).toString(), "KB/s")
            }

        // 📋 DETAILS TEXT
        val details = StringBuilder()

        // ALWAYS Show today's usage if permission granted
        if (hasUsageStatsPermission()) {
            val (mobileUsage, wifiUsage) = getTodayUsage()
            details.append("Mobile: ${formatUsage(mobileUsage)} | WiFi: ${formatUsage(wifiUsage)}")
        } else {
             details.append("Tap to grant permission for usage stats")
        }

        var speedTitle = "$speedVal $unitVal"

        if (prefs.getBoolean("show_up_down", false)) {
             speedTitle += "   ↓ ${formatSimple(rxDelta)}   ↑ ${formatSimple(txDelta)}"
        }

        if (prefs.getBoolean("show_wifi_signal", false)) {
            val signal = getWifiSignal()
            speedTitle += "   \uD83D\uDCF6 $signal%"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            notificationId,
            buildNotification(speedTitle, speedVal, unitVal, details.toString())
        )
    }

    private fun getTodayUsage(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val mobile = getUsage(ConnectivityManager.TYPE_MOBILE, startTime, endTime)
        val wifi = getUsage(ConnectivityManager.TYPE_WIFI, startTime, endTime)

        return Pair(mobile, wifi)
    }

    private fun getUsage(networkType: Int, startTime: Long, endTime: Long): Long {
        return try {
            val bucket = networkStatsManager.querySummaryForDevice(
                networkType,
                null,
                startTime,
                endTime
            )
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getWifiSignal(): Int {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
             // Manual calculation for granular result (0-100) instead of steps
             val rssi = wm.connectionInfo.rssi
             
             // Standard WiFi RSSI ranges:
             // > -50 dBm : 100%
             // < -100 dBm : 0%
             val minRssi = -100
             val maxRssi = -50
             
             when {
                 rssi <= minRssi -> 0
                 rssi >= maxRssi -> 100
                 else -> ((rssi - minRssi) * 100) / (maxRssi - minRssi)
             }
        } catch (e: Exception) {
            0
        }
    }

    private fun formatSimple(b: Long): String =
        if (b >= 1_024_000)
            String.format(Locale.US, "%.1f MB/s", b / 1_048_576f)
        else
            "${b / 1024} KB/s"

    private fun formatUsage(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1048576f) // Default to MB if small
        }
    }

    private fun buildNotification(
        title: String,
        speed: String,
        unit: String,
        details: String
    ): Notification {

        // This makes sure the "notification bubble" (badge count) is not shown or is 0
        // on some launchers that interpret the notification count.
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(createSpeedIcon(speed, unit))
            .setContentTitle(title)
            .setContentText(details)
            .setOngoing(true)
            .setSilent(true)
            .setNumber(0) // Explicitly set number to 0 to avoid badge counts
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // ⭐ Standard priority
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) 
            .setWhen(serviceStartTime) // ⭐ Fixed timestamp allows newer (downloads) to rank higher
            .setShowWhen(false)
            // ⭐ Removed setSortKey so it respects system sorting
            .build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 🖼️ DYNAMIC ICON (SAFE FOR 3–4 DIGITS)
     */
    private fun createSpeedIcon(speed: String, unit: String): IconCompat {

        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        // SPEED TEXT
        paint.textSize = when {
            speed.length >= 4 -> size * 0.52f
            speed.length == 3 -> size * 0.60f
            else -> size * 0.72f
        }
        canvas.drawText(speed, size / 2f, size * 0.58f, paint)

        // UNIT TEXT
        paint.textSize = size * 0.38f
        canvas.drawText(unit, size / 2f, size * 0.95f, paint)

        return IconCompat.createWithBitmap(bitmap)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Internet Speed",
                NotificationManager.IMPORTANCE_DEFAULT // ⭐ Standard Importance
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET 
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        super.onDestroy()
    }
}
