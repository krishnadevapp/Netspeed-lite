package com.krishna.netspeedlite

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.krishna.netspeedlite.databinding.ItemUsageRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageAdapter(private var usageList: List<DailyUsage> = emptyList()) :
    RecyclerView.Adapter<UsageAdapter.UsageViewHolder>() {

    class UsageViewHolder(val binding: ItemUsageRowBinding) : RecyclerView.ViewHolder(binding.root)

    // Cache SimpleDateFormat to avoid recreating on every bind
    private val dateFormat = SimpleDateFormat("dd MMM, yyyy", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val binding = ItemUsageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        // Bounds check to prevent IndexOutOfBoundsException
        if (position < 0 || position >= usageList.size) {
            return
        }

        try {
            val item = usageList[position]
            val context = holder.itemView.context

            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val showInMbOnly = prefs.getBoolean(Constants.PREF_UNIT_IN_MB, false)

            holder.binding.apply {
                tvDate.text = dateFormat.format(Date(item.date))
                tvMobile.text = formatData(item.mobileBytes, showInMbOnly)
                tvWifi.text = formatData(item.wifiBytes, showInMbOnly)
                tvTotal.text = formatData(item.totalBytes, showInMbOnly)
            }
        } catch (e: Exception) {
            android.util.Log.e("UsageAdapter", "Error binding view at position $position", e)
        }
    }

    override fun getItemCount() = usageList.size

    fun updateData(newList: List<DailyUsage>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = usageList.size
            override fun getNewListSize() = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return usageList[oldItemPosition].date == newList[newItemPosition].date
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return usageList[oldItemPosition] == newList[newItemPosition]
            }
        })
        
        usageList = newList.toList()
        diffResult.dispatchUpdatesTo(this)
    }

    private fun formatData(bytes: Long, showInMbOnly: Boolean): String {
        return try {
            if (showInMbOnly) {
                return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
            }

            when {
                bytes >= 1073741824 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824f)
                bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
                bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
                else -> "$bytes B"
            }
        } catch (e: Exception) {
            android.util.Log.e("UsageAdapter", "Error formatting data", e)
            "0 B"
        }
    }
}