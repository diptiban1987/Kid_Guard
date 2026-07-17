package com.parentalcontrol.app.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Browser
import android.provider.CallLog
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkVersion: Int
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val provider: String,
    val timestamp: Long
)

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val temperature: Float
)

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int
)

data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val duration: Long,
    val date: Long,
    val type: Int
)

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val isSystemApp: Boolean = false
)

data class ScreenTimeData(
    val totalMinutes: Int,
    val unlocks: Int,
    val date: String,
    val appUsage: Map<String, Long> = emptyMap()
)

data class WebHistoryEntry(
    val url: String,
    val title: String,
    val browser: String,
    val visitCount: Int,
    val timestamp: Long
)

class Collectors {

    fun collectDeviceInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            deviceId = Build.DEVICE,
            deviceName = Build.DISPLAY,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT
        )
    }

    fun collectLocation(context: Context): LocationData? {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var bestLocation: Location? = null

            for (provider in locationManager.getProviders(true)) {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null && (bestLocation == null ||
                            location.accuracy < bestLocation.accuracy ||
                            location.time > bestLocation.time)
                ) {
                    bestLocation = location
                }
            }

            bestLocation?.let { loc ->
                return LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    provider = loc.provider ?: "unknown",
                    timestamp = loc.time
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        return null
    }

    fun collectBatteryInfo(context: Context): BatteryInfo {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temperature = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0f

        return BatteryInfo(level = batteryPct, isCharging = isCharging, temperature = temperature)
    }

    fun collectSmsMessages(context: Context): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null, null, null,
                "${Telephony.Sms.DATE} DESC LIMIT 100"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(Telephony.Sms._ID)
                val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
                val typeCol = c.getColumnIndex(Telephony.Sms.TYPE)

                while (c.moveToNext()) {
                    messages.add(SmsMessage(
                        id = c.getLong(idCol),
                        address = c.getString(addrCol) ?: "",
                        body = c.getString(bodyCol)?.take(500) ?: "",
                        date = c.getLong(dateCol),
                        type = c.getInt(typeCol)
                    ))
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        return messages
    }

    fun collectCallLogs(context: Context): List<CallLogEntry> {
        val logs = mutableListOf<CallLogEntry>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(CallLog.Calls._ID)
                val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val durCol = c.getColumnIndex(CallLog.Calls.DURATION)
                val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                var count = 0

                while (c.moveToNext() && count < 100) {
                    count++
                    logs.add(CallLogEntry(
                        id = c.getLong(idCol),
                        number = c.getString(numCol) ?: "",
                        name = if (nameCol >= 0) c.getString(nameCol) else null,
                        duration = c.getLong(durCol),
                        date = c.getLong(dateCol),
                        type = c.getInt(typeCol)
                    ))
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        return logs
    }

    fun collectInstalledApps(context: Context): List<InstalledApp> {
        val apps = mutableListOf<InstalledApp>()
        try {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledPackages(0)

            for (pkg in packages) {
                if (pkg.applicationInfo != null) {
                    val isSystemApp = (pkg.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    apps.add(InstalledApp(
                        packageName = pkg.packageName,
                        appName = packageManager.getApplicationLabel(pkg.applicationInfo).toString(),
                        versionName = pkg.versionName,
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pkg.longVersionCode
                        } else {
                            pkg.versionCode.toLong()
                        },
                        firstInstallTime = pkg.firstInstallTime,
                        lastUpdateTime = pkg.lastUpdateTime,
                        isSystemApp = isSystemApp
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return apps
    }

    fun collectScreenTime(context: Context): ScreenTimeData? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 24 * 60 * 60 * 1000
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime
                )
                var totalMinutes = 0
                val appUsage = mutableMapOf<String, Long>()
                val packageManager = context.packageManager

                stats?.forEach { usageStats ->
                    val timeInForeground = usageStats.totalTimeInForeground / 60000
                    if (usageStats.packageName != context.packageName && timeInForeground > 0) {
                        totalMinutes += timeInForeground.toInt()
                        appUsage[usageStats.packageName] = timeInForeground
                    }
                }

                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                ScreenTimeData(
                    totalMinutes = totalMinutes,
                    unlocks = stats?.size ?: 0,
                    date = today,
                    appUsage = appUsage
                )
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun collectWebHistory(context: Context): List<WebHistoryEntry> {
        val entries = mutableListOf<WebHistoryEntry>()

        // Method 1: Try Chrome's browsing history content provider
        try {
            val chromeHistoryUri = android.net.Uri.parse("content://com.android.chrome.browser/history")
            val cursor = context.contentResolver.query(
                chromeHistoryUri,
                arrayOf("url", "title", "visits", "date", "favicon"),
                null, null, "date DESC"
            )
            cursor?.use { c ->
                val urlCol = c.getColumnIndex("url")
                val titleCol = c.getColumnIndex("title")
                val visitsCol = c.getColumnIndex("visits")
                val dateCol = c.getColumnIndex("date")
                var count = 0
                while (c.moveToNext() && count < 30) {
                    val url = c.getString(urlCol) ?: continue
                    if (url.isNotBlank() && url.startsWith("http")) {
                        entries.add(WebHistoryEntry(
                            url = url,
                            title = c.getString(titleCol) ?: "",
                            browser = "Chrome",
                            visitCount = c.getInt(visitsCol),
                            timestamp = c.getLong(dateCol)
                        ))
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            // Chrome history provider not available
        }

        // Method 2: Try Firefox/Edge/Brave history providers
        if (entries.isEmpty()) {
            val historyProviders = mapOf(
                "content://org.mozilla.firefox.db.history" to "Firefox",
                "content://com.microsoft.emmx.history" to "Edge",
                "content://com.brave.browser.history" to "Brave"
            )
            for ((uri, browserName) in historyProviders) {
                try {
                    val cursor = context.contentResolver.query(
                        android.net.Uri.parse(uri),
                        arrayOf("url", "title", "visits", "date"),
                        null, null, "date DESC"
                    )
                    cursor?.use { c ->
                        val urlCol = c.getColumnIndex("url")
                        val titleCol = c.getColumnIndex("title")
                        val visitsCol = c.getColumnIndex("visits")
                        val dateCol = c.getColumnIndex("date")
                        var count = 0
                        while (c.moveToNext() && count < 30) {
                            val url = c.getString(urlCol) ?: continue
                            if (url.isNotBlank() && url.startsWith("http")) {
                                entries.add(WebHistoryEntry(
                                    url = url,
                                    title = c.getString(titleCol) ?: "",
                                    browser = browserName,
                                    visitCount = c.getInt(visitsCol),
                                    timestamp = c.getLong(dateCol)
                                ))
                                count++
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        entries.sortByDescending { it.timestamp }
        return entries
    }

    fun collectForegroundApp(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val currentTime = System.currentTimeMillis()
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    currentTime - 1000 * 60,
                    currentTime
                )
                stats?.sortedByDescending { it.lastTimeUsed }?.firstOrNull()?.packageName
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
