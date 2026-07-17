import Toybox.BluetoothLowEnergy;
import Toybox.Lang;
import Toybox.StringUtil;
import Toybox.System;

// ============================================================
// 手表作为 BLE 中心设备:
//   扫描广播中携带导航服务 UUID 的手机 → 连接 → 订阅两条特征通知
// UUID / 数据格式必须与 Android 端 BleNavServer.kt 保持一致。
//
// 导航数值特征 (13 字节, 小端):
//   [0] 协议版本  [1] 转向代码  [2] 有效位(bit0 stepDist / bit1 remainDist / bit2 remainTime)
//   [3-6] 距转向点距离 uint32 米  [7-10] 剩余距离 uint32 米  [11-12] 剩余时间 uint16 分钟
// 道路名特征: UTF-8 字符串 (≤20 字节)
// ============================================================
class NavBleDelegate extends BluetoothLowEnergy.BleDelegate {

    hidden var mSvcUuid;
    hidden var mNavUuid;
    hidden var mRoadUuid;
    hidden var mDevice = null;
    hidden var mSubscribeStep = 0;

    function initialize() {
        BleDelegate.initialize();
        mSvcUuid = BluetoothLowEnergy.stringToUuid("ba1d0001-5c3a-4e2b-9f8d-6a7c1e2f3a4b");
        mNavUuid = BluetoothLowEnergy.stringToUuid("ba1d0002-5c3a-4e2b-9f8d-6a7c1e2f3a4b");
        mRoadUuid = BluetoothLowEnergy.stringToUuid("ba1d0003-5c3a-4e2b-9f8d-6a7c1e2f3a4b");
    }

    function open() {
        BluetoothLowEnergy.setDelegate(self);
        try {
            BluetoothLowEnergy.registerProfile({
                :uuid => mSvcUuid,
                :characteristics => [
                    { :uuid => mNavUuid, :descriptors => [BluetoothLowEnergy.cccdUuid()] },
                    { :uuid => mRoadUuid, :descriptors => [BluetoothLowEnergy.cccdUuid()] }
                ]
            });
        } catch (ex) {
            // Profile 可能已在之前的运行中注册过, 直接开始扫描
            startScan();
        }
    }

    function close() {
        try {
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
        } catch (ex) {
        }
        if (mDevice != null) {
            try {
                BluetoothLowEnergy.unpairDevice(mDevice);
            } catch (ex) {
            }
            mDevice = null;
        }
    }

    hidden function startScan() {
        NavData.bleState = 1;
        try {
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_SCANNING);
        } catch (ex) {
        }
    }

    // ---------------- BleDelegate 回调 ----------------

    function onProfileRegister(uuid, status) {
        startScan();
    }

    // 修复：使用 try-catch 替代 has() 检查
    function onScanResults(scanResults) {
        // 检查 scanResults 是否有效
        if (scanResults == null) {
            return;
        }
        
        for (var r = scanResults.next(); r != null; r = scanResults.next()) {
            // 尝试将结果转换为 ScanResult
            var scanResult = null;
            try {
                // 使用类型判断
                if (r instanceof BluetoothLowEnergy.ScanResult) {
                    scanResult = r;
                } else {
                    // 如果不是 ScanResult，跳过
                    continue;
                }
            } catch (ex) {
                // 类型转换失败，跳过
                continue;
            }
            
            // 获取服务 UUIDs - 使用 try-catch 处理可能不存在的方法
            var uuids = null;
            try {
                // 直接尝试调用，如果不存在会抛出异常
                uuids = scanResult.getServiceUuids();
            } catch (ex) {
                // 如果 getServiceUuids 不可用，尝试从广告数据获取
                try {
                    var advData = scanResult.getAdvertisingData();
                    if (advData != null) {
                        // 检查是否有 getServiceUuids 方法
                        try {
                            uuids = advData.getServiceUuids();
                        } catch (e) {
                            // 广告数据也没有，继续
                        }
                    }
                } catch (e) {
                    // 广告数据获取失败
                }
            }
            
            // 如果获取不到 UUIDs，跳过
            if (uuids == null) {
                continue;
            }
            
            // 遍历 UUIDs 查找匹配的服务
            var found = false;
            for (var u = uuids.next(); u != null; u = uuids.next()) {
                if (u.equals(mSvcUuid)) {
                    found = true;
                    break;
                }
            }
            
            if (found) {
                try {
                    BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
                    mDevice = BluetoothLowEnergy.pairDevice(scanResult);
                    return;
                } catch (ex) {
                    startScan();
                    return;
                }
            }
        }
    }

    function onConnectedStateChanged(device, state) {
        if (state == BluetoothLowEnergy.CONNECTION_STATE_CONNECTED) {
            NavData.bleState = 2;
            mDevice = device;
            mSubscribeStep = 0;
            writeCccd();
        } else {
            // 断开: 清理并重新扫描
            if (mDevice != null) {
                try {
                    BluetoothLowEnergy.unpairDevice(mDevice);
                } catch (ex) {
                }
                mDevice = null;
            }
            startScan();
        }
    }

    // GATT 同时只能有一个未完成请求: 先订阅导航数值, 回调后再订阅道路名
    hidden function writeCccd() {
        if (mDevice == null) {
            return;
        }
        var svc = mDevice.getService(mSvcUuid);
        if (svc == null) {
            return;
        }
        var chUuid = (mSubscribeStep == 0) ? mNavUuid : mRoadUuid;
        var ch = svc.getCharacteristic(chUuid);
        if (ch == null) {
            return;
        }
        var cccd = ch.getDescriptor(BluetoothLowEnergy.cccdUuid());
        if (cccd == null) {
            return;
        }
        try {
            cccd.requestWrite([0x01, 0x00]b);
        } catch (ex) {
        }
    }

    function onDescriptorWrite(descriptor, status) {
        if (mSubscribeStep == 0) {
            mSubscribeStep = 1;
            writeCccd();
        }
    }

    function onCharacteristicChanged(characteristic, value) {
        var u = characteristic.getUuid();
        if (u.equals(mNavUuid)) {
            decodeNav(value);
        } else if (u.equals(mRoadUuid)) {
            decodeRoad(value);
        }
    }

    // ---------------- 解码 ----------------

    hidden function decodeNav(b) {
        if (b == null || b.size() < 13) {
            return;
        }
        var flags = b[2];
        NavData.turn = b[1];
        NavData.stepDist = ((flags & 0x01) != 0) ? u32(b, 3) : -1;
        NavData.remainDist = ((flags & 0x02) != 0) ? u32(b, 7) : -1;
        NavData.remainTime = ((flags & 0x04) != 0) ? (u16(b, 11) * 60) : -1;
        NavData.lastUpdate = System.getTimer();
        NavData.hasData = true;
    }

    hidden function decodeRoad(b) {
        if (b == null) {
            return;
        }
        if (b.size() == 0) {
            NavData.roadName = "";
            return;
        }
        var s = StringUtil.convertEncodedString(b, {
            :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :toRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        if (s instanceof Lang.String) {
            NavData.roadName = s;
        }
        NavData.lastUpdate = System.getTimer();
        NavData.hasData = true;
    }

    hidden function u32(b, off) {
        return b[off] + (b[off + 1] << 8) + (b[off + 2] << 16) + (b[off + 3] << 24);
    }

    hidden function u16(b, off) {
        return b[off] + (b[off + 1] << 8);
    }
}
