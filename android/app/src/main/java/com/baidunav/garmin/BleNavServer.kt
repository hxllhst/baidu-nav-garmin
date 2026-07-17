package com.baidunav.garmin

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 手机作为 BLE 外设(GATT Server + 广播), 手表端数据字段作为 BLE 中心
 * 主动扫描/连接/订阅通知。全程不依赖 Garmin Connect。
 *
 * GATT 结构(UUID 必须与手表端 NavBle.mc 保持一致):
 *   Service  ba1d0001-...  导航服务
 *   Char     ba1d0002-...  导航数值(13 字节, 小端):
 *       [0] 协议版本=1
 *       [1] 转向代码 0-9
 *       [2] 有效位: bit0=stepDist bit1=remainDist bit2=remainTime
 *       [3-6]  距转向点距离 uint32(米)
 *       [7-10] 剩余总距离 uint32(米)
 *       [11-12] 剩余时间 uint16(分钟)
 *   Char     ba1d0003-...  道路名(UTF-8, ≤20 字节, BLE 单包限制)
 */
@SuppressLint("MissingPermission")
class BleNavServer private constructor(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("ba1d0001-5c3a-4e2b-9f8d-6a7c1e2f3a4b")
        val NAV_CHAR_UUID: UUID = UUID.fromString("ba1d0002-5c3a-4e2b-9f8d-6a7c1e2f3a4b")
        val ROAD_CHAR_UUID: UUID = UUID.fromString("ba1d0003-5c3a-4e2b-9f8d-6a7c1e2f3a4b")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile
        private var instance: BleNavServer? = null

        fun get(context: Context): BleNavServer =
            instance ?: synchronized(this) {
                instance ?: BleNavServer(context.applicationContext).also { instance = it }
            }
    }

    @Volatile
    var statusListener: ((String) -> Unit)? = null

    private fun log(msg: String) {
        statusListener?.invoke(msg)
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var navChar: BluetoothGattCharacteristic? = null
    private var roadChar: BluetoothGattCharacteristic? = null

    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()

    @Volatile
    private var advertising = false

    @Volatile
    private var running = false

    private var lastNavPayload: ByteArray = byteArrayOf()
    private var lastRoadPayload: ByteArray = byteArrayOf()
    private var lastSendAt = 0L
    private var lastNav: NavInfo? = null

    fun hasSubscriber(): Boolean = subscribers.isNotEmpty()
    fun isAdvertising(): Boolean = advertising

    fun start() {
        if (running) return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("蓝牙未开启, 请先打开手机蓝牙后点「重启蓝牙广播」")
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            log("此手机不支持 BLE 外设广播, 无法直连手表")
            return
        }
        advertiser = adv
        try {
            gattServer = mgr.openGattServer(ctx, gattCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            navChar = BluetoothGattCharacteristic(
                NAV_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            roadChar = BluetoothGattCharacteristic(
                ROAD_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            service.addCharacteristic(navChar)
            service.addCharacteristic(roadChar)
            gattServer?.addService(service)

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val scanResp = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            adv.startAdvertising(settings, data, scanResp, advCallback)
            running = true
        } catch (e: SecurityException) {
            log("缺少蓝牙权限, 请打开 App 授权后重试")
            stop()
        } catch (e: Exception) {
            log("蓝牙服务启动失败: ${e.message}")
            stop()
        }
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (e: Exception) {
        }
        try {
            gattServer?.close()
        } catch (e: Exception) {
        }
        gattServer = null
        subscribers.clear()
        advertising = false
        running = false
    }

    fun restart() {
        stop()
        start()
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            log("蓝牙广播已开启, 手表打开 BaiduNav 数据字段即可自动连接")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            log("蓝牙广播启动失败(code=$errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("手表已连接: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device.address)
                log("手表已断开: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                NAV_CHAR_UUID -> lastNavPayload
                ROAD_CHAR_UUID -> lastRoadPayload
                else -> byteArrayOf()
            }
            val part = if (offset >= value.size) byteArrayOf() else value.copyOfRange(offset, value.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enable = value != null && value.isNotEmpty() && value[0].toInt() != 0
                if (enable) {
                    subscribers[device.address] = device
                    log("手表已订阅导航数据")
                    // 立即补发一次当前状态, 手表打开字段即可看到数据
                    lastNav?.let { pushTo(device, it) }
                } else {
                    subscribers.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            val v = if (subscribers.containsKey(device.address)) {
                byteArrayOf(0x01, 0x00)
            } else {
                byteArrayOf(0x00, 0x00)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }
    }

    /**
     * 发送导航数据到所有已订阅手表。
     * 内容不变时最多每 5 秒发一次心跳; 内容变化时也限流到约 0.8 秒一次。
     */
    fun send(nav: NavInfo, force: Boolean = false) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (nav == lastNav && now - lastSendAt < 5000) return
            if (now - lastSendAt < 800) return
        }
        lastNav = nav
        lastSendAt = now

        val navPayload = buildNavPayload(nav)
        val roadPayload = utf8Truncate(nav.road, 20)
        val roadChanged = !roadPayload.contentEquals(lastRoadPayload)
        lastNavPayload = navPayload
        lastRoadPayload = roadPayload

        for (device in subscribers.values) {
            notifyChar(device, navChar, navPayload)
            if (roadChanged || force) {
                notifyChar(device, roadChar, roadPayload)
            }
        }
    }

    private fun pushTo(device: BluetoothDevice, nav: NavInfo) {
        notifyChar(device, navChar, buildNavPayload(nav))
        notifyChar(device, roadChar, utf8Truncate(nav.road, 20))
    }

    private fun buildNavPayload(nav: NavInfo): ByteArray {
        var flags = 0
        if (nav.stepDist >= 0) flags = flags or 0x01
        if (nav.remainDist >= 0) flags = flags or 0x02
        if (nav.remainTime >= 0) flags = flags or 0x04
        val buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1)
        buf.put(nav.turn.toByte())
        buf.put(flags.toByte())
        buf.putInt(if (nav.stepDist >= 0) nav.stepDist else 0)
        buf.putInt(if (nav.remainDist >= 0) nav.remainDist else 0)
        val minutes = if (nav.remainTime >= 0) (nav.remainTime / 60).coerceAtMost(0xFFFF) else 0
        buf.putShort(minutes.toShort())
        return buf.array()
    }

    private fun utf8Truncate(s: String, maxBytes: Int): ByteArray {
        var str = s
        var out = str.toByteArray(Charsets.UTF_8)
        while (out.size > maxBytes && str.isNotEmpty()) {
            str = str.dropLast(1)
            out = str.toByteArray(Charsets.UTF_8)
        }
        return out
    }

    private fun notifyChar(
        device: BluetoothDevice,
        ch: BluetoothGattCharacteristic?,
        payload: ByteArray
    ) {
        val gs = gattServer ?: return
        if (ch == null) return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gs.notifyCharacteristicChanged(device, ch, false, payload)
            } else {
                @Suppress("DEPRECATION")
                ch.value = payload
                @Suppress("DEPRECATION")
                gs.notifyCharacteristicChanged(device, ch, false)
            }
        } catch (e: Exception) {
            log("通知失败: ${e.message}")
        }
    }
}
