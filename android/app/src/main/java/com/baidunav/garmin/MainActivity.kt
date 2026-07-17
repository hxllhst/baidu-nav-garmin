package com.baidunav.garmin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_BLE = 1001
    }

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
            appendLog("正在重启蓝牙广播…")
            if (ensureBlePermissions()) {
                BleNavServer.get(this).restart()
            }
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val test = NavInfo(Turn.LEFT, 350, "人民中路", 5200, 780)
            BleNavServer.get(this).send(test, force = true)
            appendLog("已发送测试指令: ${NavRepo.describe(test)}")
        }

        val server = BleNavServer.get(this)
        server.statusListener = { msg ->
            runOnUiThread {
                appendLog(msg)
                refreshBleStatus()
            }
        }
        NavRepo.listener = { msg -> runOnUiThread { appendLog(msg) } }

        if (ensureBlePermissions()) {
            server.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun ensureBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLE)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            BleNavServer.get(this).start()
        } else {
            appendLog("蓝牙权限被拒绝, 无法向手表发送数据")
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val ok = isAccessibilityEnabled()
        tvAccStatus.text = if (ok) {
            "✅ 无障碍服务: 已开启"
        } else {
            "❌ 无障碍服务: 未开启(必须开启才能读取百度导航)"
        }
        refreshBleStatus()
    }

    private fun refreshBleStatus() {
        val server = BleNavServer.get(this)
        tvGarminStatus.text = when {
            server.hasSubscriber() -> "✅ 蓝牙: 手表已连接并订阅导航数据"
            server.isAdvertising() -> "📡 蓝牙: 广播中, 等待手表连接(手表打开 BaiduNav 数据字段即自动连接)"
            else -> "❌ 蓝牙: 广播未启动(检查蓝牙开关与权限, 点「重启蓝牙广播」)"
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
