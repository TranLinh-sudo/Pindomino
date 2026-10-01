package com.example.batterylogger

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class BatteryMonitorService : Service() {

    private val CHANNEL_ID = "BatteryMonitorChannel"
    private val NOTIF_ID = 1001
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Đang giám sát pin & ứng dụng..."))

        runnable = object : Runnable {
            override fun run() {
                logBatteryData()
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(runnable)
    }

    private fun logBatteryData() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val voltageMv = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = (level * 100 / scale.toFloat())

        val currentNowMicroA = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentNowMa = currentNowMicroA / 1000.0

        val voltageV = voltageMv / 1000.0
        val currentA = Math.abs(currentNowMa) / 1000.0
        val powerWatts = voltageV * currentA

        val currentApp = getForegroundApp()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val modeText = if (isCharging) "CHARGING" else "DISCHARGING"

        val logLine = String.format(
            Locale.US,
            "%s | Mode: %s | App: %s | Battery: %.1f%% | Voltage: %.2fV | Current: %.1fmA | Power: %.2fW\n",
            timestamp, modeText, currentApp, batteryPct, voltageV, currentNowMa, powerWatts
        )

        Log.d("BatteryLogger", logLine.trim())
        saveLogToFile(logLine)

        val notifText = if (isCharging) {
            String.format("Đang sạc: %.2f W (%.1f mA)", powerWatts, currentNowMa)
        } else {
            String.format("App: %s | Xả: %.1f mA (%.2f W)", currentApp, currentNowMa, powerWatts)
        }
        updateNotification(notifText)
    }

    private fun getForegroundApp(): String {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

        var foregroundApp = "Unknown / Home"
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foregroundApp = event.packageName
            }
        }
        return foregroundApp
    }

    private fun saveLogToFile(data: String) {
        try {
            val file = File(getExternalFilesDir(null), "battery_usage_log.txt")
            val writer = FileWriter(file, true)
            writer.append(data)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e("BatteryLogger", "Error writing log: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }
}

cat << 'EOF' > app/src/main/java/com/example/batterylogger/BatteryLogAnalyzer.kt
package com.example.batterylogger

import java.io.File
import java.util.Locale

data class AppBatteryReport(
    val packageName: String,
    val avgPowerWatts: Double,
    val avgCurrentMa: Double,
    val totalBatteryDropPct: Double,
    val sampleCount: Int,
    val totalTimeSeconds: Long
)

object BatteryLogAnalyzer {

    private val LOG_PATTERN = Regex(
        """^([^|]+)\s*\|\s*Mode:\s*(\w+)\s*\|\s*App:\s*([^|]+)\s*\|\s*Battery:\s*([\d.]+)%\s*\|\s*Voltage:\s*([\d.]+)V\s*\|\s*Current:\s*([-\d.]+)mA\s*\|\s*Power:\s*([\d.]+)W"""
    )

    fun analyzeLogFile(logFile: File, sampleIntervalSeconds: Long = 5): List<AppBatteryReport> {
        if (!logFile.exists()) return emptyList()

        class TempStats {
            var totalPowerWatts = 0.0
            var totalCurrentMa = 0.0
            var sampleCount = 0
            var totalBatteryDropPct = 0.0
        }

        val statsMap = mutableMapOf<String, TempStats>()
        var lastBatteryPct: Double? = null

        logFile.useLines { lines ->
            for (line in lines) {
                val matchResult = LOG_PATTERN.find(line.trim()) ?: continue

                val mode = matchResult.groupValues[2]
                val appName = matchResult.groupValues[3].trim()
                val batteryPct = matchResult.groupValues[4].toDoubleOrNull() ?: continue
                val currentMa = Math.abs(matchResult.groupValues[6].toDoubleOrNull() ?: 0.0)
                val powerWatts = matchResult.groupValues[7].toDoubleOrNull() ?: 0.0

                if (mode.equals("DISCHARGING", ignoreCase = true)) {
                    val stats = statsMap.getOrPut(appName) { TempStats() }
                    stats.totalPowerWatts += powerWatts
                    stats.totalCurrentMa += currentMa
                    stats.sampleCount++

                    if (lastBatteryPct != null) {
                        val drop = lastBatteryPct - batteryPct
                        if (drop > 0) {
                            stats.totalBatteryDropPct += drop
                        }
                    }
                    lastBatteryPct = batteryPct
                } else {
                    lastBatteryPct = null
                }
            }
        }

        return statsMap.map { (packageName, stats) ->
            val count = stats.sampleCount
            AppBatteryReport(
                packageName = packageName,
                avgPowerWatts = if (count > 0) stats.totalPowerWatts / count else 0.0,
                avgCurrentMa = if (count > 0) stats.totalCurrentMa / count else 0.0,
                totalBatteryDropPct = stats.totalBatteryDropPct,
                sampleCount = count,
                totalTimeSeconds = count * sampleIntervalSeconds
            )
        }.sortedByDescending { it.avgPowerWatts }
    }

    fun generateReportString(reports: List<AppBatteryReport>): String {
        if (reports.isEmpty()) return "Không có dữ liệu xả pin hợp lệ."

        val builder = StringBuilder()
        builder.appendLine("================ BÁO CÁO TIÊU THỤ PIN ================")

        for (report in reports) {
            builder.appendLine("Ứng dụng: ${report.packageName}")
            builder.appendLine(
                String.format(
                    Locale.US,
                    "  - Công suất TB: %.2f W (%.1f mA)",
                    report.avgPowerWatts,
                    report.avgCurrentMa
                )
            )
            builder.appendLine(
                String.format(
                    Locale.US,
                    "  - Tổng % pin đã tụt: %.1f%%",
                    report.totalBatteryDropPct
                )
            )
            builder.appendLine("  - Thời gian theo dõi: ${report.totalTimeSeconds} giây (${report.sampleCount} mẫu)")
            builder.appendLine("-----------------------------------------------------")
        }

        return builder.toString()
    }
}
