package com.anonchat.app.util

import java.text.SimpleDateFormat
import java.util.*

object TimestampConverter {

    fun toTime(timestamp: Long): String {
        val format = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return format.format(cal.time)
    }

    fun toDate(timestamp: Long): String {
        val format = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return format.format(cal.time)
    }

    fun toDateTime(timestamp: Long): String {
        val format = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return format.format(cal.time)
    }

    fun toRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> toDate(timestamp)
        }
    }

    fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
                cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
    }

    fun isToday(timestamp: Long): Boolean {
        return isSameDay(timestamp, System.currentTimeMillis())
    }

    fun isYesterday(timestamp: Long): Boolean {
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                cal.get(Calendar.MONTH) == yesterday.get(Calendar.MONTH) &&
                cal.get(Calendar.DAY_OF_MONTH) == yesterday.get(Calendar.DAY_OF_MONTH)
    }
}
