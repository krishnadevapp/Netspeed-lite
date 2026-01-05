package com.krishna.netspeedlite

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar

object NetworkUsageHelper {

    // 🛡️ Sanity Limit: If a single bucket claims > 100 TB, it's a glitch.
    private const val SANITY_THRESHOLD = 100L * 1024 * 1024 * 1024 * 1024

    fun getUsageForDate(context: Context, timestamp: Long): Pair<Long, Long> {
        val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return Pair(0L, 0L)

        // 1. Calculate Start and End of the given day
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endTime = calendar.timeInMillis

        // 2. Get Data
        val mobile = getSafeUsage(statsManager, ConnectivityManager.TYPE_MOBILE, startTime, endTime)
        val wifi = getSafeUsage(statsManager, ConnectivityManager.TYPE_WIFI, startTime, endTime)

        return Pair(mobile, wifi)
    }

    private fun getSafeUsage(manager: NetworkStatsManager, networkType: Int, start: Long, end: Long): Long {
        var totalBytes = 0L
        var networkStats: NetworkStats? = null
        try {
            // Use querySummary to iterate over buckets (Fixes 0 Data bug)
            networkStats = manager.querySummary(networkType, null, start, end)
            
            // Fix: Add null check for querySummary result
            if (networkStats == null) {
                android.util.Log.w("NetworkUsageHelper", "querySummary returned null for networkType: $networkType")
                return 0L
            }
            
            val bucket = NetworkStats.Bucket()

            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                val bytes = bucket.rxBytes + bucket.txBytes

                // 🛡️ FIX: Filter out negative values or impossible spikes (Garbage Data)
                if (bytes < 0) continue
                if (bytes > SANITY_THRESHOLD) continue

                totalBytes += bytes
            }
        } catch (e: Exception) {
            android.util.Log.e("NetworkUsageHelper", "Error getting safe usage", e)
        } finally {
            networkStats?.close()
        }
        return totalBytes
    }
}