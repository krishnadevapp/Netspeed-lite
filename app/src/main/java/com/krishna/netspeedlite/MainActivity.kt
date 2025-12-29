package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.krishna.netspeedlite.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var networkStatsManager: NetworkStatsManager
    private lateinit var prefs: SharedPreferences
    private lateinit var usageAdapter: UsageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        // Force Dark Mode always
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // UI Styling
        window.statusBarColor = ContextCompat.getColor(this, R.color.toolbarGrey)
        setSupportActionBar(binding.topAppBar)

        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        setupPermissions()
        setupUI()

        // Initial background data load
        refreshData()

        val showSpeed = prefs.getBoolean("show_speed", true)
        if (showSpeed) {
            startSpeedService()
        }
    }

    private fun startSpeedService() {
        val intent = Intent(this, SpeedService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasUsageStatsPermission()) {
            binding.btnPermission.visibility = View.GONE
            refreshData()
        } else {
            binding.btnPermission.visibility = View.VISIBLE
        }
    }

    private fun setupPermissions() {
        binding.btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
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

    private fun setupUI() {
        usageAdapter = UsageAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = usageAdapter
            isNestedScrollingEnabled = false // Better scrolling if inside a NestedScrollView
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val switchShowSpeed = dialogView.findViewById<MaterialSwitch>(R.id.switchShowSpeed)
        val switchShowUpDown = dialogView.findViewById<MaterialSwitch>(R.id.switchShowUpDown)
        val switchShowWifiSignal = dialogView.findViewById<MaterialSwitch>(R.id.switchShowWifiSignal)
        val btnResetData = dialogView.findViewById<TextView>(R.id.btnResetData)
        val btnStopExit = dialogView.findViewById<TextView>(R.id.btnStopExit)

        switchShowSpeed.isChecked = prefs.getBoolean("show_speed", true)
        switchShowUpDown.isChecked = prefs.getBoolean("show_up_down", false)
        switchShowWifiSignal.isChecked = prefs.getBoolean("show_wifi_signal", false)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()

        switchShowSpeed.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_speed", isChecked).apply()
            if (isChecked) startSpeedService() else stopService(Intent(this, SpeedService::class.java))
        }

        switchShowUpDown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_up_down", isChecked).apply()
            // Restart service to apply change
            if (switchShowSpeed.isChecked) startSpeedService()
        }

        switchShowWifiSignal.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_wifi_signal", isChecked).apply()
            // Restart service to apply change
            if (switchShowSpeed.isChecked) startSpeedService()
        }

        btnResetData.setOnClickListener {
            prefs.edit().putLong("reset_timestamp", System.currentTimeMillis()).apply()
            Toast.makeText(this, "Data Usage Reset", Toast.LENGTH_SHORT).show()
            refreshData()
            dialog.dismiss()
        }

        btnStopExit.setOnClickListener {
            stopService(Intent(this, SpeedService::class.java))
            finishAffinity()
        }
    }

    private fun refreshData() {
        if (!hasUsageStatsPermission()) return

        // Run heavy queries in a background coroutine
        lifecycleScope.launch(Dispatchers.IO) {
            val usageList = ArrayList<DailyUsage>()
            val calendar = Calendar.getInstance()
            val resetTimestamp = prefs.getLong("reset_timestamp", 0L)

            var last7DaysMobile = 0L
            var last7DaysWifi = 0L
            var last30DaysMobile = 0L
            var last30DaysWifi = 0L

            for (i in 0 until 30) {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                val startTime = calendar.timeInMillis

                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                val endTime = calendar.timeInMillis

                val queryStartTime = if (startTime < resetTimestamp) resetTimestamp else startTime

                var mobile = 0L
                var wifi = 0L

                if (endTime > resetTimestamp) {
                    mobile = getUsage(ConnectivityManager.TYPE_MOBILE, queryStartTime, endTime)
                    wifi = getUsage(ConnectivityManager.TYPE_WIFI, queryStartTime, endTime)
                }

                usageList.add(DailyUsage(startTime, mobile, wifi, mobile + wifi))

                last30DaysMobile += mobile
                last30DaysWifi += wifi
                if (i < 7) {
                    last7DaysMobile += mobile
                    last7DaysWifi += wifi
                }

                // Move to previous day
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }

            // Switch back to Main thread to update UI
            withContext(Dispatchers.Main) {
                usageAdapter.updateData(usageList)
                updateUIStats(last7DaysMobile, last7DaysWifi, last30DaysMobile, last30DaysWifi)
            }
        }
    }

    private fun updateUIStats(m7: Long, w7: Long, m30: Long, w30: Long) {
        binding.apply {
            tv7DaysMobile.text = formatData(m7)
            tv7DaysWifi.text = formatData(w7)
            tv7DaysTotal.text = formatData(m7 + w7)

            tv30DaysMobile.text = formatData(m30)
            tv30DaysWifi.text = formatData(w30)
            tv30DaysTotal.text = formatData(m30 + w30)

            tvTotalMobile.text = formatData(m30)
            tvTotalWifi.text = formatData(w30)
            tvGrandTotal.text = formatData(m30 + w30)
        }
    }

    private fun getUsage(networkType: Int, startTime: Long, endTime: Long): Long {
        return try {
            val bucket = networkStatsManager.querySummaryForDevice(
                networkType,
                null, // Passing null is correct for modern API device-wide queries
                startTime,
                endTime
            )
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatData(bytes: Long): String {
        val showInMbOnly = prefs.getBoolean("unit_in_mb", false)
        if (showInMbOnly) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
        }

        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }
}