package com.baidunav.garmin

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务:
 *  - 监听百度地图 (com.baidu.BaiduMap) 的窗口内容变化与通知
 *  - 提取文本 → NavParser 解析 → BleNavServer 通过蓝牙 GATT 通知手表
 */
class NavAccessibilityService : AccessibilityService() {

    companion object {
        const val BAIDU_PKG = "com.baidu.BaiduMap"
        private const val PARSE_INTERVAL_MS = 600L
    }

    private var lastParseAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        BleNavServer.get(this).start()
        NavRepo.log("无障碍服务已启动, 等待百度地图导航…")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != BAIDU_PKG) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val texts = mutableListOf<String>()
                (event.parcelableData as? Notification)?.extras?.let { ex ->
                    ex.getCharSequence(Notification.EXTRA_TITLE)?.let { texts.add(it.toString()) }
                    ex.getCharSequence(Notification.EXTRA_TEXT)?.let { texts.add(it.toString()) }
                    ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { texts.add(it.toString()) }
                    ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { texts.add(it.toString()) }
                }
                event.text?.forEach { t ->
                    if (!t.isNullOrBlank()) texts.add(t.toString())
                }
                if (texts.isNotEmpty()) handleTexts(texts)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastParseAt < PARSE_INTERVAL_MS) return
                lastParseAt = now
                val root = rootInActiveWindow ?: return
                val texts = mutableListOf<String>()
                collectTexts(root, texts, 0)
                if (texts.isNotEmpty()) handleTexts(texts)
            }
        }
    }

    private fun handleTexts(texts: List<String>) {
        val nav = NavParser.parse(texts) ?: return
        NavRepo.update(nav)
        BleNavServer.get(this).send(nav)
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 14 || out.size > 150) return
        node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        node.contentDescription?.let { if (it.isNotBlank()) out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }

    override fun onInterrupt() {
        // no-op
    }
}
