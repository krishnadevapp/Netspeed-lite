package com.krishna.netspeedlite

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.krishna.netspeedlite.databinding.ItemUsageRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageAdapter(private var usageList: List<DailyUsage> = emptyList()) :
    RecyclerView.Adapter<UsageAdapter.UsageViewHolder>() {

    class UsageViewHolder(val binding: ItemUsageRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val binding = ItemUsageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val item = usageList[position]
        val context = holder.itemView.context

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val showInMbOnly = prefs.getBoolean("unit_in_mb", false)

        val dateFormat = SimpleDateFormat("dd MMM, yyyy", Locale.US)

        holder.binding.apply {
            tvDate.text = dateFormat.format(Date(item.date))
            tvMobile.text = formatData(item.mobileBytes, showInMbOnly)
            tvWifi.text = formatData(item.wifiBytes, showInMbOnly)
            tvTotal.text = formatData(item.totalBytes, showInMbOnly)
        }
    }

    override fun getItemCount() = usageList.size

    fun updateData(newList: List<DailyUsage>) {
        usageList = newList
        notifyDataSetChanged()
    }

    private fun formatData(bytes: Long, showInMbOnly: Boolean): String {
        if (showInMbOnly) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
        }

        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }
}