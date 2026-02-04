package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

class SpeedService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = -1L
    private var lastTx = -1L
    // Fix VPN Double-Counting: Track mobile separate from total (which might include VPN tun)
    private var lastMobileRx = -1L
    private var lastMobileTx = -1L
    private var tickCount = 0

    private var lastNetworkStatsUpdate = 0L
    
    // Fix Race Condition: Track day locally to ensure reset even if MainActivity updates Prefs first
    private var lastDayTracker: String = ""
    
    // Fix Jitter: Track exact time of last update for precise speed calculation
    private var lastUpdateTimestamp: Long = 0L

    // Fix Battery Drain: Hybrid Usage Interpolation
    // We only query the heavy system DB once every 60s (Baseline).
    // In between, we add the live 'delta' bytes to the baseline for smooth 1s updates.
    private var lastUsageDbQueryTime: Long = 0L
    private var cachedMobileBaseline: Long = 0L
    private var cachedWifiBaseline: Long = 0L
    // Accumulators reset every time we refresh the baseline
    private var sessionMobileAccumulator: Long = 0L
    private var sessionWifiAccumulator: Long = 0L

    // Optimization: Cache last notification content to avoid redundant updates
    private var lastNotificationContent: String = ""

    // Fix Race Condition: Mutex to ensure only one update runs at a time
    private val updateMutex = Mutex()
    
    // Fix Concurrent Alerts: Atomic lock for alert checking
    private val isCheckingAlerts = AtomicBoolean(false)

    // ... (Rest of variables) ...

    // ... (Inside updateNotificationDataSuspend) ...
        


    private val channelId = Constants.SPEED_CHANNEL_ID
    private val alertChannelId = Constants.ALERT_CHANNEL_ID
    private val notificationId = Constants.NOTIFICATION_ID

    private val serviceStartTime = System.currentTimeMillis()

    private lateinit var prefs: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    // ===== PROFESSIONAL FIX: DENSITY-AWARE SIZING =====
    // Fixes Pixelation: Generates exact 1:1 pixels for the device.
    // S24 Ultra (3.0x/4.0x) gets high res (72px/96px).
    // Older phones (1.5x) get native res (36px).
    // NO DOWNSCALING = NO PIXELATION.
    private val optimalIconSize: Int by lazy {
        val density = resources?.displayMetrics?.density ?: 2.0f
        // Standard Android Status Bar Height is 24dp
        (24 * density).toInt().coerceAtLeast(36) // Minimum safety
    }

    // Icon cache for performance - using LinkedHashMap for LRU behavior
    // Fix: Limit cache size to prevent unbounded growth
    private val iconCache = object : LinkedHashMap<String, IconCompat>(Constants.MAX_ICON_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IconCompat>?): Boolean {
            return size > Constants.MAX_ICON_CACHE_SIZE
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val runnable = object : Runnable {
        override fun run() {
            // Update notification UI only if screen is on to save battery
            if (isScreenOn) {
                serviceScope.launch {
                    try {
                        updateNotificationDataSuspend()
                    } catch (e: Exception) {
                        // Ignore errors during notification update
                    }
                }
            }

            // checkDataAlerts runs every 5th tick.
            // If screen is ON (interval 1s), checks every 5s.
            // If screen is OFF (interval 10s, see below), checks every 50s.
            // This might be too slow for high speed downloads.
            // Let's ensure we check frequently enough.
            if (isScreenOn) {
                 // FIX: Check alerts every 30 seconds (30 ticks) instead of 5 minutes
                 // This ensures reasonably quick detection while not being too battery intensive
                 tickCount++
                 if (tickCount % 30 == 0) {
                     checkDataAlerts()
                 }
                 
                 if (tickCount > 1_000_000) tickCount = 0 // Prevent overflow
                 handler.postDelayed(this, Constants.UPDATE_INTERVAL_MS)
            } else {
                // Screen OFF: Check alerts every 10 seconds directly
                checkDataAlerts()
                handler.postDelayed(this, 10000L) 
            }
        }
    }

    // --- Screen State Handling ---
    private var isScreenOn = true
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    // Don't stop updates entirely, just slow them down (handled in runnable)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    
                    // BUG FIX: Synchronization
                    // Move logic to background thread and lock mutex to prevent race with updateNotificationDataSuspend
                    serviceScope.launch {
                        updateMutex.lock()
                        try {
                            // BUG FIX: Force baseline refresh on screen wake-up
                            // This prevents stale "4GB" data from showing after overnight sleep
                            lastUsageDbQueryTime = 0L // Force immediate DB query
                            
                            // BUG FIX: Reset accumulators to prevent stale data accumulation
                            // Usage tracked while screen was off may be inaccurate
                            sessionMobileAccumulator = 0L
                            sessionWifiAccumulator = 0L
                            
                            // BUG FIX: Capture usage that happened while screen was OFF (Sleep Data)
                            // Before we reset the baselines to 'current', we must record the difference
                            // between 'current' and the old 'last' values.
                            val currentRx = TrafficStats.getTotalRxBytes()
                            val currentTx = TrafficStats.getTotalTxBytes()
                            val currentMobileRx = TrafficStats.getMobileRxBytes()
                            val currentMobileTx = TrafficStats.getMobileTxBytes()

                            if (lastRx != -1L && currentRx != -1L && lastTx != -1L && currentTx != -1L) {
                                try {
                                    val sleepRxDelta = if (currentRx >= lastRx) currentRx - lastRx else 0L
                                    val sleepTxDelta = if (currentTx >= lastTx) currentTx - lastTx else 0L
                                    // Sanity check: If sleep contained massive spike (>2GB), ignore it to be safe
                                    // But realistic sleep usage (background updates) should be counted.
                                    if (sleepRxDelta < 2_000_000_000L && sleepTxDelta < 2_000_000_000L) {
                                        val totalSleepBytes = sleepRxDelta + sleepTxDelta
                                        
                                        // Calculate Mobile Sleep Delta
                                        val sleepMobileRxDelta = if (currentMobileRx >= lastMobileRx && 
                                            lastMobileRx != -1L && currentMobileRx != -1L) currentMobileRx - lastMobileRx else 0L
                                        val sleepMobileTxDelta = if (currentMobileTx >= lastMobileTx && 
                                            lastMobileTx != -1L && currentMobileTx != -1L) currentMobileTx - lastMobileTx else 0L
                                        
                                        val totalSleepMobile = if (sleepMobileRxDelta < 2_000_000_000L && sleepMobileTxDelta < 2_000_000_000L) {
                                            sleepMobileRxDelta + sleepMobileTxDelta
                                        } else 0L
                                        
                                        // Calculate WiFi Sleep Delta (Total - Mobile)
                                        // Handle VPN heuristic if needed, but for sleep data we can keep it simple:
                                        // WiFi = Total - Mobile (safely)
                                        val totalSleepWifi = if (totalSleepBytes > totalSleepMobile) totalSleepBytes - totalSleepMobile else 0L

                                        // Save Sleep Data if we are in Manual Mode (No Permission)
                                        if (!hasUsageStatsPermission() && (totalSleepMobile > 0 || totalSleepWifi > 0)) {
                                            val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                                            trackManualUsage(totalSleepMobile, totalSleepWifi, todayKey)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Safely ignore errors during awake calculation
                                }
                            }

                            // BUG FIX: Reset TrafficStats baselines to prevent speed spikes
                            // After long sleep, lastRx/lastTx are very old - resync to current values
                            lastRx = if (currentRx == -1L) lastRx else currentRx
                            lastTx = if (currentTx == -1L) lastTx else currentTx
                            
                            lastMobileRx = if (currentMobileRx == -1L) lastMobileRx else currentMobileRx
                            lastMobileTx = if (currentMobileTx == -1L) lastMobileTx else currentMobileTx
                            
                            // Reset timestamp for accurate speed calculation
                            lastUpdateTimestamp = System.currentTimeMillis()
                            
                            // Clear notification cache to force immediate update
                            lastNotificationContent = ""
                            
                            // Post startUpdates back to main thread (handler is attached to Main Looper)
                            withContext(Dispatchers.Main) {
                                startUpdates() 
                            }
                        } finally {
                            updateMutex.unlock()
                        }
                    }
                }
            }
        }
    }

    // Listener for immediate response to settings changes
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Constants.PREF_SHOW_UP_DOWN,
            Constants.PREF_SHOW_WIFI_SIGNAL,
            Constants.PREF_SHOW_SPEED -> {
                // Immediately refresh notification when display settings change
                serviceScope.launch {
                    try {
                        lastNotificationContent = "" // Clear cache to force update
                        updateNotificationDataSuspend()
                    } catch (e: Exception) {
                        // Ignore errors
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)

        // Fix: Correctly initialize screen state to avoid unnecessary work if started in background
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        isScreenOn = powerManager?.isInteractive ?: true

        createNotificationChannel()
        createAlertChannel()

        // Initialize with current stats, handling -1 (unavailable)
        val initialRx = TrafficStats.getTotalRxBytes()
        val initialTx = TrafficStats.getTotalTxBytes()
        lastRx = if (initialRx == -1L) 0L else initialRx
        lastTx = if (initialTx == -1L) 0L else initialTx

        // Initialize Mobile stats
        val initialMobileRx = TrafficStats.getMobileRxBytes()
        val initialMobileTx = TrafficStats.getMobileTxBytes()
        lastMobileRx = if (initialMobileRx == -1L) 0L else initialMobileRx
        lastMobileTx = if (initialMobileTx == -1L) 0L else initialMobileTx

        val notification = buildNotification("Initializing...", "0", "KB/s", "Starting...")

        try {
            // Pass foregroundServiceType to support Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Use UPSIDE_DOWN_CAKE for API 34
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                android.util.Log.e("SpeedService", "BG launch restricted, scheduling Worker fallback", e)
                // BUG FIX: Do NOT call stopSelf(). Instead, schedule a Worker to retry shortly.
                // The Worker has privileges to start the service even from background.
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<SpeedServiceWorker>()
                    .setInitialDelay(5, java.util.concurrent.TimeUnit.SECONDS) // Short delay to let system settle
                    .build()
                androidx.work.WorkManager.getInstance(applicationContext).enqueue(workRequest)
            } else {
                // Log other exceptions but don't crash if possible
                android.util.Log.e("SpeedService", "Error starting foreground service", e)
                // For other critial errors, we might still have to stop, but let's try to stay alive if it's just a notification error
                // stopSelf() // Removed to attempt persistence
            }
        }

        // Register Screen On/Off receiver
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error registering screen receiver", e)
        }

        // Register SharedPreferences listener for immediate toggle response
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Initialize tracker with current date to avoid immediate reset on start
        lastDayTracker = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            ""
        }

        startUpdates()
    }

    private fun startUpdates() {
        handler.removeCallbacks(runnable) // Prevent duplicates
        handler.post(runnable)
    }

    private fun stopUpdates() {
        handler.removeCallbacks(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun checkDataAlerts() {
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        if (!isAlertEnabled) {
            return
        }
        
        // Fix Concurrent Alerts: Only allow one check at a time
        if (isCheckingAlerts.compareAndSet(false, true)) {
            serviceScope.launch {
                try {
                    val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
                    if (limitMb <= 0f) {
                        return@launch
                    }

                    val todayStr = try {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    } catch (e: Exception) {
                        ""
                    }
                    
                    if (todayStr.isEmpty()) {
                        return@launch
                    }

                    val lastChecked = prefs.getString(Constants.PREF_LAST_ALERT_DATE, "")

                    // UNIFIED DAY RESET: Single atomic check for new day
                    // Fixes race condition between lastDayTracker and lastChecked
                    if (todayStr != lastChecked || todayStr != lastDayTracker) {
                        prefs.edit {
                            putString(Constants.PREF_LAST_ALERT_DATE, todayStr)
                            putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                            putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                        }
                        lastDayTracker = todayStr
                    }

                    val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                    val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)

                    if (alert80 && alert100) {
                        // Both alerts already triggered today
                        return@launch
                    }

                    val limitBytes = (limitMb.toDouble() * 1024.0 * 1024.0).toLong()
                    if (limitBytes <= 0) {
                        return@launch
                    }

                    // BUG FIX: Use the corrected function that queries midnight to NOW
                    // instead of midnight to 23:59:59 (future time)
                    val hasPermission = hasUsageStatsPermission()
                    val mobileUsage = if (hasPermission) {
                        // FIXED: Use getTodayMobileUsageUntilNow() - queries correct time range
                        NetworkUsageHelper.getTodayMobileUsageUntilNow(applicationContext)
                    } else {
                        // Fallback to manual tracking (less accurate but still works)
                        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                        prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
                    }

                    // BUG FIX: Removed double-counting accumulator logic
                    // The system stats from NetworkStatsManager is the source of truth.
                    
                    val percentage = if (limitBytes > 0) {
                        (mobileUsage.toDouble() / limitBytes.toDouble()) * 100
                    } else {
                        0.0
                    }

                    if (percentage >= 100 && !alert100) {
                        sendAlertNotification(
                            "Daily data limit reached!", 
                            "You've used ${formatUsage(mobileUsage)} of your ${formatUsage(limitBytes)} daily limit."
                        )
                        prefs.edit { putBoolean(Constants.PREF_ALERT_100_TRIGGERED, true) }
                    } else if (percentage >= 80 && !alert80 && !alert100) {
                        // BUG FIX: Show actual percentage instead of static "80%"
                        val actualPercentage = percentage.toInt()
                        sendAlertNotification(
                            "Data usage warning", 
                            "You've used ${formatUsage(mobileUsage)} ($actualPercentage%) of your ${formatUsage(limitBytes)} daily limit."
                        )
                        prefs.edit { putBoolean(Constants.PREF_ALERT_80_TRIGGERED, true) }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SpeedService", "Error in checkDataAlerts", e)
                } finally {
                    isCheckingAlerts.set(false)
                }
            }
        }
    }

    private fun sendAlertNotification(title: String, message: String) {
        try {
            // Ensure the alert channel exists
            createAlertChannel()
            
            val notification = NotificationCompat.Builder(this, alertChannelId)
                .setSmallIcon(R.drawable.ic_speed)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSortKey("B_ALERT") // Ensure it comes AFTER the speed notification
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(getOpenAppIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
                
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager == null) {
                android.util.Log.e("SpeedService", "NotificationManager is null, cannot send alert")
                return
            }
            
            // Check if notification channel is enabled (Android 8+)
            // Check if notification channel is enabled (Android 8+)
            val channel = manager.getNotificationChannel(alertChannelId)
            if (channel == null) {
                android.util.Log.e("SpeedService", "Alert notification channel not found, recreating...")
                createAlertChannel()
            } else if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                android.util.Log.w("SpeedService", "Alert notification channel is disabled by user")
            }
            
            // Use unique ID to force new notification each time
            val uniqueId = (System.currentTimeMillis() % 100000).toInt() + 100
            manager.notify(uniqueId, notification)

        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Failed to send alert notification", e)
        }
    }

    // Refactored to be a suspend function to run network operations on a background thread
    private suspend fun updateNotificationDataSuspend() {
        // Fix Race Condition: Drop this update if another is already in progress.
        // This prevents stacking of updates which causes double-counting of data usage.
        if (!updateMutex.tryLock()) return
        
        try {
            withContext(Dispatchers.IO) {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        // Detect TrafficStats reset (e.g. reboot, airplane mode toggle, or overflow)
        // If current stats are LESS than previous, it means the counter reset.
        // In that case, we can't calculate a delta for *this* second, but we MUST
        // update lastRx/lastTx to the new lower value so the *next* second is correct.
        val rxDelta = if (rx == -1L || lastRx == -1L) {
            0L
        } else if (rx < lastRx) {
            // Counter reset: Don't show confusing large negative/positive spike.
            // Just treat this tick as 0 and re-sync baseline.
            0L
        } else {
            rx - lastRx
        }

        val txDelta = if (tx == -1L || lastTx == -1L) {
            0L
        } else if (tx < lastTx) {
            0L
        } else {
            tx - lastTx
        }

        // Sanity Check: If delta is impossible (> 2GB/s), ignore it.
        // This prevents massive spikes if stats suddenly jump (e.g. 0 -> 26GB) due to OS glitch
        val saneRxDelta = if (rxDelta > 2_000_000_000L) 0L else rxDelta
        val saneTxDelta = if (txDelta > 2_000_000_000L) 0L else txDelta
        val totalBytes = saneRxDelta + saneTxDelta

        // Always update baseline for next tick (unless unavailable)
        // This is crucial: if rx < lastRx (reset), we MUST update lastRx to the new smaller rx
        if (rx != -1L) lastRx = rx
        if (tx != -1L) lastTx = tx

        // Real-Time Alert Triggering
        // Fix for VPN: Calculate Mobile-Specific Delta to avoid double-counting (Physical + Tun)
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()
        
        val mobileRxDelta = if (mobileRx == -1L || lastMobileRx == -1L) {
             0L
        } else if (mobileRx < lastMobileRx) {
             0L 
        } else {
             mobileRx - lastMobileRx
        }
        
        val mobileTxDelta = if (mobileTx == -1L || lastMobileTx == -1L) {
             0L
        } else if (mobileTx < lastMobileTx) {
             0L
        } else {
             mobileTx - lastMobileTx
        }

        // Sanity Check for Mobile
        val saneMobileRxDelta = if (mobileRxDelta > 2_000_000_000L) 0L else mobileRxDelta
        val saneMobileTxDelta = if (mobileTxDelta > 2_000_000_000L) 0L else mobileTxDelta
        val totalMobileBytes = saneMobileRxDelta + saneMobileTxDelta
        
        // Update baseline
        if (mobileRx != -1L) lastMobileRx = mobileRx
        if (mobileTx != -1L) lastMobileTx = mobileTx

        // Check Active Network Type
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        
        val isMobile = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // --- VPN DOUBLE VISON FIX ---
        // 1. Mobile Data: TrafficStats.getMobileRx/TxBytes IS the physical interface. 
        //    It naturally excludes the VPN 'tun' interface overhead. 
        //    So simply using 'mobileRxDelta' instead of 'totalBytes' fixes Mobile+VPN.
        
        // 2. Wifi: We only have 'Total'. Total = Wifi + VPN(Tun).
        //    If VPN is on, traffic looks doubled. We assume Full Tunnel and divide by 2.
        
        val displayRx: Long
        val displayTx: Long
        
        if (isMobile) {
            // Precise Mobile Speed (ignores VPN overhead)
            displayRx = saneMobileRxDelta
            displayTx = saneMobileTxDelta
        } else {
            // Wifi / Ethernet
            // Calculate Non-Mobile Traffic
            var wifiRx = if (saneRxDelta > saneMobileRxDelta) saneRxDelta - saneMobileRxDelta else 0L
            var wifiTx = if (saneTxDelta > saneMobileTxDelta) saneTxDelta - saneMobileTxDelta else 0L
            
            if (isVpn) {
                // Heuristic: Remove double counting (Physical + Tun) -> Divide by 2
                wifiRx /= 2
                wifiTx /= 2
            }
            displayRx = wifiRx
            displayTx = wifiTx
        }
        
        val displayBytes = displayRx + displayTx

        // NOTE: Real-time alert triggering moved AFTER the hybrid usage calculation below
        // to use the interpolated baseline+session value instead of a separate accumulator

        val details = StringBuilder()
        
        // --- HYBRID USAGE LOGIC (BATTERY OPTIMIZATION) ---
        // Requirement: Update text every second.
        // Constraint: NetworkUsageHelper queries are heavy (IPC + Disk).
        // Solution: Query DB every 60s. Interpolate in between.
        
        val usageQueryInterval = 60_000L // 60 seconds
        val timeSinceLastQuery = System.currentTimeMillis() - lastUsageDbQueryTime
        
        // BUG FIX #1: Reset session accumulators at midnight
        // Check if day has changed to prevent stale accumulator data after midnight
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (todayStr != lastDayTracker) {
            sessionMobileAccumulator = 0L
            sessionWifiAccumulator = 0L
            cachedMobileBaseline = 0L
            cachedWifiBaseline = 0L
            lastUsageDbQueryTime = 0L // Force refresh on next iteration
            lastDayTracker = todayStr
        }
        
        if (timeSinceLastQuery > usageQueryInterval || lastUsageDbQueryTime == 0L) {
             // 1. REFRESH BASELINE (Heavy Operation)
             if (hasUsageStatsPermission()) {
                 // BUG FIX #3: Use getTodayUsageUntilNow() instead of getUsageForDate()
                 // This queries from midnight to NOW, not midnight to 23:59:59 (future)
                 val (mobile, wifi) = NetworkUsageHelper.getTodayUsageUntilNow(applicationContext)
                 cachedMobileBaseline = mobile
                 cachedWifiBaseline = wifi
                 lastUsageDbQueryTime = System.currentTimeMillis()
                 
                 // Reset accumulators as they are now effectively baked into the new baseline
                 sessionMobileAccumulator = 0L
                 sessionWifiAccumulator = 0L
             }
        } else {
             // 2. INTERPOLATE (Light Operation)
             // Add this second's usage to the running session accumulator
             if (isMobile) {
                // totalMobileBytes already handles VPN correction (physical interface check)
                sessionMobileAccumulator += totalMobileBytes
             } else if (isWifi) {
                 // displayRx/Tx already handles VPN correction (heuristic)
                 sessionWifiAccumulator += (displayRx + displayTx)
             }
        }
        
        // 3. DISPLAY (Baseline + Accumulator)
        if (hasUsageStatsPermission()) {
            val displayMobile = cachedMobileBaseline + sessionMobileAccumulator
            val displayWifi = cachedWifiBaseline + sessionWifiAccumulator
            
            details.append("Mobile: ${formatUsage(displayMobile)}")
            
            // Show percentage if limit is set
            val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
            if (limitMb > 0f) {
                 // Percentage display removed as per request
            }
            
            details.append(" | WiFi: ${formatUsage(displayWifi)}")
            
            // REAL-TIME ALERT CHECK using interpolated displayMobile
            // This triggers alerts immediately when thresholds are crossed during active downloads
            // Uses the same value displayed in notification for consistency (no double-counting)
            val limitBytes = if (limitMb > 0f) (limitMb * 1024 * 1024).toLong() else 0L
            if (limitBytes > 0) {
                val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
                if (isAlertEnabled) {
                    val estPercentage = (displayMobile.toDouble() / limitBytes.toDouble()) * 100
                    val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                    val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                    
                    if ((estPercentage >= 80 && !alert80) || (estPercentage >= 100 && !alert100)) {
                        // Threshold crossed - force a check with the accurate system stats
                        checkDataAlerts()
                    }
                }
            }
        } else {
              // Fallback: Use manually tracked data
              val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
              val mobile = prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
              val wifi = prefs.getLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, 0L)
              details.append("Mobile: ${formatUsage(mobile)} | WiFi: ${formatUsage(wifi)}")
              
              // BUG FIX #2: Track using VPN-corrected displayBytes instead of raw deltas
              // The raw rxDelta+txDelta includes VPN double-counting, displayBytes is corrected
              if (displayBytes > 0) {
                   val mobileBytesToAdd = if (isMobile) displayBytes else 0L
                   val wifiBytesToAdd = if (isWifi) displayBytes else 0L
                   trackManualUsage(mobileBytesToAdd, wifiBytesToAdd, todayKey)
              }
              
              // FIX: Real-time alert check for fallback path (no permission)
              val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
              val limitBytes = if (limitMb > 0f) (limitMb * 1024 * 1024).toLong() else 0L
              if (limitBytes > 0 && mobile > 0) {
                  val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
                  if (isAlertEnabled) {
                      val estPercentage = (mobile.toDouble() / limitBytes.toDouble()) * 100
                      val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                      val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                      
                      if ((estPercentage >= 80 && !alert80) || (estPercentage >= 100 && !alert100)) {
                          checkDataAlerts()
                      }
                  }
              }
        }

        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        
        // --- PRECISION TIMING FIX ---
        val currentTime = System.currentTimeMillis()
        val timeDelta = if (lastUpdateTimestamp == 0L) {
            1000L // Default for first run
        } else {
            currentTime - lastUpdateTimestamp
        }
        lastUpdateTimestamp = currentTime
        
        // Prevent Divide-By-Zero or extreme glitches
        val safeTimeDelta = if (timeDelta < 100) 1000L else timeDelta
        
        // Calculate Speed using Actual Time: (Bytes * 1000) / TimeMs
        // This fixes the "Jitter" bug where 1.2s delay caused 20% speed over-reporting
        val calculatedSpeedBytes = (displayBytes * 1000) / safeTimeDelta
        
        // Optimization: Generate a content key to check if update is needed
        val (speedVal, unitVal) = formatSpeed(calculatedSpeedBytes)
        val contentKey = if (showSpeed) {
            // Simple RX/TX also needs to be normalized to "per second"
            val simpleRx = formatSimple((displayRx * 1000) / safeTimeDelta)
            val simpleTx = formatSimple((displayTx * 1000) / safeTimeDelta)
            val wifiSigVal = getWifiSignal()
            val wifiSignal = if (prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false) && wifiSigVal >= 0) {
                 wifiSigVal.toString() 
            } else {
                 ""
            }
            "$speedVal|$unitVal|$simpleRx|$simpleTx|$wifiSignal|$details"
        } else {
            "HIDDEN|$details"
        }

        // Skip update if content hasn't changed (Battery Optimization)
        if (contentKey == lastNotificationContent) {
            return@withContext
        }
        lastNotificationContent = contentKey

        val notification = if (showSpeed) {
            var speedTitle = "$speedVal $unitVal"
            if (prefs.getBoolean(Constants.PREF_SHOW_UP_DOWN, false)) {
                 val normRx = (displayRx * 1000) / safeTimeDelta
                 val normTx = (displayTx * 1000) / safeTimeDelta
                 speedTitle += "   ↓ ${formatSimple(normRx)}   ↑ ${formatSimple(normTx)}"
            }
            if (prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false)) {
                val signal = getWifiSignal()
                if (signal >= 0) {
                    speedTitle += "   WiFi: $signal%"
                }
            }
            buildNotification(speedTitle, speedVal, unitVal, details.toString())
        } else {
            // When speed is hidden, use the MONITOR channel with MIN importance (collapsed)
            // This satisfies the foreground service requirement while minimizing visibility
            NotificationCompat.Builder(this@SpeedService, Constants.MONITOR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_speed)
                .setContentTitle("Data monitoring enabled")
                .setContentText(details.toString())
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_MIN) // Minimized
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(getOpenAppIntent())
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lockscreen if possible
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        // Post notification update back to the Main thread (or directly if NotificationManager is thread-safe, which it is)
        // Post notification update back to the Main thread (or directly if NotificationManager is thread-safe, which it is)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager != null) {
            try {
                manager.notify(notificationId, notification)
            } catch (e: Exception) {
                // Catch sporadic SecurityException or "TransactionTooLargeException" on some devices
                android.util.Log.e("SpeedService", "Error updating notification", e)
            }
        }
    } 
    } finally {
        updateMutex.unlock()
    }
  }

    // --- Helpers ---

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getWifiSignal(): Int {
        try {
            val minRssi = -100
            val maxRssi = -55
            var rssi = -127

            // CRITICAL FIX FOR ANDROID 14:
            // "NetworkCapabilities" Signal Strength is cached/stale and often returns -127 if not updated.
            // "WifiManager.getConnectionInfo()" triggers a fresh(er) lookup but requires ACCESS_FINE_LOCATION on Android 10+.
            
            // We now have ACCESS_FINE_LOCATION in manifest, so we prefer WifiManager.
            
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                // Suppress deprecation: Standard way to get RSSI is still this or ScanResults (which are slower).
                // Android recommended usage for foreground apps is still fine, especially for Signal Strength.
                @Suppress("DEPRECATION")
                val connectionInfo = wm.connectionInfo
                if (connectionInfo != null) {
                    // This will return -127 if permission is missing, but with permission it works.
                    rssi = connectionInfo.rssi
                }
            }

            // Fallback: ConnectivityManager (Less reliable for real-time RSSI, often returns intervals)
            if (rssi == -127 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val activeNetwork = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(activeNetwork)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val transportInfo = caps.transportInfo
                        if (transportInfo is android.net.wifi.WifiInfo) {
                            rssi = transportInfo.rssi
                        }
                    }
                }
            }

            // If still invalid, return -1 (Hidden) instead of 0 (Confusion)
            if (rssi == -127) return -1

            return when {
                rssi <= minRssi -> 0
                rssi >= maxRssi -> 100
                else -> ((rssi - minRssi) * 100) / (maxRssi - minRssi)
            }
        } catch (e: Exception) {
            // Permission might be denied at runtime security exception
            return -1
        }
    }

    private fun formatSpeed(bytes: Long): Pair<String, String> {
        return if (bytes >= 1_024_000) {
            val mb = bytes / 1_048_576f
            Pair(String.format(Locale.US, if (mb >= 10) "%.0f" else "%.1f", mb), "MB/s")
        } else {
            Pair((bytes / 1024).toString(), "KB/s")
        }
    }

    private fun formatSimple(b: Long): String =
        if (b >= 1_024_000) String.format(Locale.US, "%.1f MB/s", b / 1_048_576f) else "${b / 1024} KB/s"

    private fun formatUsage(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun buildNotification(title: String, speed: String, unit: String, details: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(createSpeedIcon(speed, unit))
            .setContentTitle(title)
            .setContentText(details)
            .setOngoing(true)
            .setAutoCancel(false)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSortKey("A_SPEED") // Force Sort to Top behavior
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(serviceStartTime)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun trackManualUsage(mobileBytes: Long, wifiBytes: Long, todayKey: String) {
        try {
            if (mobileBytes > 0) {
                synchronized(prefs) {
                    val current = prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
                    prefs.edit { putLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, current + mobileBytes) }
                }
            }
            
            if (wifiBytes > 0) {
                synchronized(prefs) {
                    val current = prefs.getLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, 0L)
                    prefs.edit { putLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, current + wifiBytes) }
                }
            }
        } catch (e: Exception) {
            // Ignore errors in tracking
        }
    }

    private fun createSpeedIcon(speed: String, unit: String): IconCompat {
        // Check cache
        val cacheKey = "$speed|$unit"
        synchronized(iconCache) {
            iconCache[cacheKey]?.let { return it }
        }

        val size = optimalIconSize

        try {
            // Stacked Layout: Speed (Top) / Unit (Bottom)
            // Using "sans-serif-medium" for system status bar consistency

            
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                // Perfect Balance: "sans-serif-medium" matches the Status Bar Clock
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isFilterBitmap = true
                isDither = true
                
                // Polish: Subtle Shadow (instead of lightning) for contrast & anti-aliasing help
                // Polish: Subtle Shadow (instead of lightning) for contrast & anti-aliasing help
                setShadowLayer(2f, 0f, 1f, "#40000000".toColorInt())
            }

            // Uniform Reference Scaling: Scale based on "888" to ensure consistent height/thickness

            
            // 1. Calculate Standardized Font Size using Reference "888"
            paint.textSize = size * 1.0f 
            val refRect = android.graphics.Rect()
            paint.getTextBounds("888", 0, 3, refRect)
            
            // Fixed Target: 56% of Canvas
            val targetVisualHeight = size * 0.56f
            val heightScale = targetVisualHeight / refRect.height().toFloat()
            val masterTextSize = paint.textSize * heightScale
            
            // Apply Master Size
            paint.textSize = masterTextSize
            
            // 2. Safety Check (Horizontal)
            // Only shrink if THIS specific number is too wide (e.g. "999")
            val textWidth = paint.measureText(speed)
            val maxSpeedWidth = size * 0.96f
            if (textWidth > maxSpeedWidth) {
                paint.textScaleX = maxSpeedWidth / textWidth
            }
            
            // 3. Draw Speed
            // Vertical Alignment: Align based on reference top to keep baseline stable

            val reMeasuredRefRect = android.graphics.Rect()
            paint.getTextBounds("888", 0, 3, reMeasuredRefRect)
            
            // Align Top of "888" to top of canvas
            val speedY = -reMeasuredRefRect.top.toFloat() + (size * 0.00f) // Keep tight top
            canvas.drawText(speed, size / 2f, speedY, paint)

            // 4. Draw Unit (With Safety Scaling)
            paint.textScaleX = 1.0f 
            paint.textSize = size * 0.45f // Slightly reduced from 0.48f to prevent vertical overflow
            
            val unitRect = android.graphics.Rect()
            paint.getTextBounds(unit, 0, unit.length, unitRect)
            
            // Safety Check: Scale down if unit text is too wide
            val unitRefWidth = paint.measureText(unit)
            val maxUnitWidth = size * 0.96f
            if (unitRefWidth > maxUnitWidth) {
                 paint.textScaleX = maxUnitWidth / unitRefWidth
            }
            
            // Restore tiny padding to prevent anti-aliasing clipping at the bottom
            val unitY = size.toFloat() - unitRect.bottom - (size * 0.02f) 
            canvas.drawText(unit, size / 2f, unitY, paint)

            val iconCompat = IconCompat.createWithBitmap(bitmap)
            synchronized(iconCache) {
                iconCache[cacheKey] = iconCompat
            }
            
            return iconCompat
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error creating speed icon", e)
            // Fallback to static icon
            return IconCompat.createWithResource(this, R.drawable.ic_speed)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        
        // Channel 1: Internet Speed (Low Importance - shows icon)
        val speedChannel = NotificationChannel(channelId, "Internet Speed", NotificationManager.IMPORTANCE_LOW)
        speedChannel.setSound(null, null)
        speedChannel.enableVibration(false)
        speedChannel.setShowBadge(false)
        speedChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager?.createNotificationChannel(speedChannel)
        
        // Channel 2: Data Monitor (Min Importance - collapsed, no icon)
        val monitorChannel = NotificationChannel(Constants.MONITOR_CHANNEL_ID, "Data Monitor", NotificationManager.IMPORTANCE_MIN)
        monitorChannel.setSound(null, null)
        monitorChannel.enableVibration(false)
        monitorChannel.setShowBadge(false)
        monitorChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager?.createNotificationChannel(monitorChannel)
    }

    private fun createAlertChannel() {
        val channel = NotificationChannel(alertChannelId, "Data Usage Alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.enableVibration(true)
        channel.setVibrationPattern(longArrayOf(0, 500, 200, 500))
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        serviceScope.cancel()

        // Unregister receiver to prevent leaks
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        // Unregister SharedPreferences listener
        if (::prefs.isInitialized) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Restart service if it was killed system-side but user wants it on
        if (::prefs.isInitialized) {
            try {
                val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
                val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
                // Safety Check: Only restart if the service has lived for at least 2 seconds.
                // This prevents an infinite crash loop if the service crashes immediately upon startup.
                val livedLongEnough = (System.currentTimeMillis() - serviceStartTime) > 2000

                // Restart if either speed display OR alerts are enabled
                if ((showSpeed || isAlertEnabled) && livedLongEnough) {
                    // Send broadcast to restart service
                    val restartIntent = Intent(applicationContext, BootReceiver::class.java)
                    restartIntent.action = "com.krishna.netspeedlite.RESTART_SERVICE"
                    sendBroadcast(restartIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("SpeedService", "Error in restart logic", e)
            }
        }

        super.onDestroy()
    }
}