package com.krishna.netspeedlite

/**
 * Data class representing network usage for a specific day.
 * @property date The timestamp (in milliseconds) for the start of the day.
 * @property mobileBytes Total mobile data (upload + download) in bytes.
 * @property wifiBytes Total Wi-Fi data (upload + download) in bytes.
 * @property totalBytes Sum of mobile and Wi-Fi data in bytes.
 */
data class DailyUsage(
    val date: Long,
    val mobileBytes: Long,
    val wifiBytes: Long,
    val totalBytes: Long
)