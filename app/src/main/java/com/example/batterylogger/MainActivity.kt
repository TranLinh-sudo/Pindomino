package com.example.batterylogger

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvReport: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        tvStatus = TextView(this).apply {
            text = "Trạng thái: Chưa kiểm tra quyền"
            textSize = 15f
            setPadding(0, 0, 0, 20)
        }

        val btnUsageAccess = Button(this).apply {
            text = "1. Cấp quyền truy cập Sử dụng"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        val btnStart = Button(this).apply {
            text = "2. Bắt đầu Theo dõi"
            setOnClickListener {
                if (hasUsageStatsPermission()) {
                    val intent = Intent(this@MainActivity, BatteryMonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    tvStatus.text = "Trạng thái: Dịch vụ đang chạy ngầm..."
                } else {
                    Toast.makeText(this@MainActivity, "Vui lòng cấp quyền Usage Access trước!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "3. Dừng Theo dõi"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BatteryMonitorService::class.java))
                tvStatus.text = "Trạng thái: Đã dừng dịch vụ."
            }
        }

        val btnAnalyze = Button(this).apply {
            text = "4. Xem Báo Cáo Pin"
            setOnClickListener {
                loadAndDisplayReport()
            }
        }

        tvReport = TextView(this).apply {
            text = "Nhấn '4. Xem Báo Cáo Pin' để phân tích nhật ký tiêu thụ."
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }

        val scrollView = ScrollView(this).apply {
            addView(tvReport)
        }

        rootLayout.addView(btnUsageAccess)
        rootLayout.addView(btnStart)
        rootLayout.addView(btnStop)
        rootLayout.addView(btnAnalyze)
        rootLayout.addView(tvStatus)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    private fun loadAndDisplayReport() {
        val file = File(getExternalFilesDir(null), "battery_usage_log.txt")
        if (!file.exists()) {
            tvReport.text = "Chưa tìm thấy file nhật ký. Vui lòng bật theo dõi trước."
            return
        }

        val reports = BatteryLogAnalyzer.analyzeLogFile(file, sampleIntervalSeconds = 5)
        val reportText = BatteryLogAnalyzer.generateReportString(reports)
        tvReport.text = reportText
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
}
