package com.baidunav.garmin

import android.content.Context
import android.os.SystemClock
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过 Connect IQ Mobile SDK(经 Garmin Connect App 的蓝牙通道)
 * 把导航数据发送到手表端数据字段。
 */
class GarminConnector private constructor(private val appContext: Context) {

    companion object {
        /** 必须与 garmin/manifest.xml 中 iq:application 的 id 一致 */
        const val CIQ_APP_ID = "c3b5a1d47e924f08b6d2a90c51e7f3ab"

        @Volatile
        private var instance: GarminConnector? = null

        fun get(context: Context): GarminConnector =
            instance ?: synchronized(this) {
                instance ?: GarminConnector(context.applicationContext).also { instance = it }
            }
    }

    private val connectIQ: ConnectIQ =
        ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
    private val ciqApp = IQApp(CIQ_APP_ID)

    @Volatile
    private var sdkReady = false
    private val connectedDevices = ConcurrentHashMap<Long, IQDevice>()

    private var lastSendAt = 0L
    private var lastPayload: Map<String, Any>? = null

    /** UI 日志回调 */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    private fun log(msg: String) {
        statusListener?.invoke(msg)
    }

    fun initialize() {
        if (sdkReady) {
            discoverDevices()
            return
        }
        try {
            connectIQ.initialize(appContext, true, object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() {
                    sdkReady = true
                    log("Garmin SDK 已就绪")
                    discoverDevices()
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    sdkReady = false
                    log("Garmin SDK 初始化失败: $status (请确认已安装 Garmin Connect)")
                }

                override fun onSdkShutDown() {
                    sdkReady = false
                    connectedDevices.clear()
                }
            })
        } catch (e: Exception) {
            log("初始化异常: ${e.message}")
        }
    }

    fun discoverDevices() {
        if (!sdkReady) {
            initialize()
            return
        }
        try {
            val known: List<IQDevice> = connectIQ.knownDevices ?: emptyList()
            if (known.isEmpty()) {
                log("未在 Garmin Connect 中找到已配对手表")
            }
            for (device in known) {
                connectIQ.registerForDeviceEvents(device) { d, status ->
                    log("${d.friendlyName ?: "手表"}: $status")
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                        connectedDevices[d.deviceIdentifier] = d
                        checkAppInstalled(d)
                    } else {
                        connectedDevices.remove(d.deviceIdentifier)
                    }
                }
            }
            val connectedNow: List<IQDevice> = try {
                connectIQ.connectedDevices ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            for (d in connectedNow) {
                connectedDevices[d.deviceIdentifier] = d
                log("已连接: ${d.friendlyName ?: d.deviceIdentifier}")
                checkAppInstalled(d)
            }
        } catch (e: InvalidStateException) {
            log("SDK 状态异常, 请点击重新连接")
        } catch (e: ServiceUnavailableException) {
            log("无法连接 Garmin Connect 服务")
        } catch (e: Exception) {
            log("查找设备失败: ${e.message}")
        }
    }

    private fun checkAppInstalled(device: IQDevice) {
        try {
            connectIQ.getApplicationInfo(CIQ_APP_ID, device,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(app: IQApp?) {
                        log("手表端 BaiduNav 数据字段已安装")
                    }

                    override fun onApplicationNotInstalled(applicationId: String?) {
                        log("提示: 手表尚未安装 BaiduNav 数据字段(仍会尝试发送)")
                    }
                })
        } catch (e: Exception) {
            // 查询失败不影响发送
        }
    }

    fun hasTarget(): Boolean = sdkReady && connectedDevices.isNotEmpty()

    /**
     * 发送导航数据到所有已连接手表。
     * 内容不变时最多每 5 秒发一次心跳; 内容变化时也限流到约 0.8 秒一次。
     */
    fun send(nav: NavInfo, force: Boolean = false) {
        if (!sdkReady || connectedDevices.isEmpty()) return

        val payload: Map<String, Any> = mapOf(
            "t" to nav.turn,
            "d" to nav.stepDist,
            "r" to nav.road,
            "rd" to nav.remainDist,
            "rt" to nav.remainTime
        )
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (payload == lastPayload && now - lastSendAt < 5000) return
            if (now - lastSendAt < 800) return
        }
        lastPayload = payload
        lastSendAt = now

        for (device in connectedDevices.values) {
            try {
                connectIQ.sendMessage(device, ciqApp, payload) { _, _, status ->
                    if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                        log("发送失败: $status")
                    }
                }
            } catch (e: Exception) {
                log("发送异常: ${e.message}")
            }
        }
    }
}
