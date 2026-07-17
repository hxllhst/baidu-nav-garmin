package com.baidunav.garmin

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccStatus: TextView
    private lateinit var tvGarminStatus: TextView
    private lateinit var tvLog: TextView

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccStatus = findViewById(R.id.tvAccStatus)
        tvGarminStatus = findViewById(R.id.tvGarminStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            appendLog("正在重新连接手表…")
            GarminConnector.get(this).discoverDevices()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val test = NavInfo(Turn.LEFT, 350, "人民中路", 5200, 780)
            GarminConnector.get(this).send(test, force = true)
            appendLog("已发送测试指令: ${NavRepo.describe(test)}")
        }

        val connector = GarminConnector.get(this)
        connector.statusListener = { msg ->
            runOnUiThread {
                appendLog(msg)
                refreshGarminStatus()
            }
        }
        NavRepo.listener = { msg -> runOnUiThread { appendLog(msg) } }
        connector.initialize()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ok = isAccessibilityEnabled()
        tvAccStatus.text = if (ok) {
            "✅ 无障碍服务: 已开启"
        } else {
            "❌ 无障碍服务: 未开启(必须开启才能读取百度导航)"
        }
        refreshGarminStatus()
    }

    private fun refreshGarminStatus() {
        val connector = GarminConnector.get(this)
        tvGarminStatus.text = if (connector.hasTarget()) {
            "✅ 佳明手表: 已连接"
        } else {
            "⌛ 佳明手表: 未连接(需安装 Garmin Connect 并配对手表)"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${NavAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun appendLog(msg: String) {
        logLines.addLast("[${timeFmt.format(Date())}] $msg")
        while (logLines.size > 120) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
    }
}
