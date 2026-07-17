# 百度导航 → 佳明手表 (BaiduNav → Garmin, BLE 直连版)

把百度地图的实时导航提示(转向箭头、剩余距离、道路名)通过 **蓝牙 BLE 直连** 发送到佳明 Garmin 手表/码表的 **Connect IQ 数据字段** 上显示,并把导航剩余距离写入手表的活动记录(FIT 开发者字段,可在 Garmin Connect 中查看)。

**不需要安装 Garmin Connect App**,手机与手表也**不需要保持长连接**:手机端作为 BLE 外设持续低功耗广播,手表只在活动记录期间打开 BaiduNav 数据字段时才自动扫描、连接并接收数据,退出字段即断开。

支持 **GitHub Actions 一键云端编译**:Fork 本仓库即可产出 Android 安装包(APK)和手表安装包(.prg / .iq),无需本地搭建环境。

## 架构

```
百度地图导航
      │
Accessibility(无障碍服务读取导航文本)
      │
Android 伴侣 App(解析 → 结构化数据 → BLE GATT Server + 广播)
      │
Bluetooth LE(手表主动扫描/连接/订阅, 仅在数据字段打开期间)
      │
Garmin Connect IQ(Data Field 数据字段, Toybox.BluetoothLowEnergy)
      │
实时显示: ← 左转 / → 右转 / ↑ 直行 / 掉头 / 环岛
          剩余距离、道路名称
          活动记录中写入"导航剩余距离"字段
```

BLE 协议(两端必须一致,定义见 `BleNavServer.kt` / `NavBle.mc`):

| 项 | UUID | 内容 |
|---|---|---|
| 服务 | `ba1d0001-5c3a-4e2b-9f8d-6a7c1e2f3a4b` | 导航服务(随广播发出,手表按此识别手机) |
| 特征1 | `ba1d0002-…` | 导航数值,13 字节小端:版本、转向码、有效位、转向点距离 u32、剩余距离 u32、剩余分钟 u16 |
| 特征2 | `ba1d0003-…` | 道路名,UTF-8 ≤20 字节(BLE 单包限制,约 6 个汉字) |

## 仓库结构

```
├── android/                     # 手机端伴侣 App(Kotlin)
│   └── app/src/main/java/com/baidunav/garmin/
│       ├── NavAccessibilityService.kt   # 无障碍服务,监听百度地图
│       ├── NavParser.kt                 # 导航文案启发式解析
│       ├── BleNavServer.kt              # BLE GATT Server + 广播
│       └── MainActivity.kt              # 权限申请 + 状态页 + 测试发送
├── garmin/                      # 手表端数据字段(Monkey C)
│   ├── manifest.xml             # 支持机型 / 权限(BluetoothLowEnergy + FitContributor)
│   └── source/
│       ├── BaiduNavApp.mc       # 生命周期: 打开字段开始扫描, 退出即断开
│       ├── NavBle.mc            # BLE 中心: 扫描/连接/订阅/解码
│       └── BaiduNavView.mc      # 绘制箭头/距离/路名 + FIT 记录
└── .github/workflows/
    ├── android.yml              # 云端编译 APK
    └── connectiq.yml            # 云端编译 .iq / .prg
```

## 一、GitHub 云端编译(推荐)

1. **Fork 本仓库**(或推送到自己的新仓库)。
2. 配置 Secrets:仓库 → `Settings` → `Secrets and variables` → `Actions` → `New repository secret`

   | Secret | 是否必须 | 说明 |
   |---|---|---|
   | `GARMIN_USERNAME` | 编译手表端必须 | 你的 Garmin 账号邮箱,用于下载官方 Connect IQ 设备定义文件 |
   | `GARMIN_PASSWORD` | 编译手表端必须 | Garmin 账号密码(仅在你自己的 Actions 运行器中使用) |
   | `CIQ_DEVELOPER_KEY_B64` | 可选 | 固定的开发者签名密钥(base64)。不配置则每次构建自动生成临时密钥 |

   > 生成固定密钥(本地执行,然后把 base64 内容存入 Secret):
   > ```bash
   > openssl genrsa -out dk.pem 4096
   > openssl pkcs8 -topk8 -inform PEM -outform DER -in dk.pem -out developer_key.der -nocrypt
   > base64 -w0 developer_key.der   # macOS 用 base64 -i developer_key.der
   > ```

3. 打开仓库的 **Actions** 页,分别手动运行(或推送代码自动触发):
   - `Build Android APK` → 产物 `BaiduNavGarmin-debug-apk`(app-debug.apk)
   - `Build Connect IQ DataField` → 产物 `BaiduNav-ConnectIQ`(含商店格式 `BaiduNavDataField.iq` 和侧载用 `prg/BaiduNav-<设备>.prg`)
4. 在对应 workflow 运行页底部的 **Artifacts** 下载安装包。

> SDK 版本由 `connectiq.yml` 中 `CIQ_SDK_VERSION: '>= 7.1.0'` 控制(匹配 Garmin 当前提供的最新 SDK)。Garmin 官方下载列表只保留较新版本,**不要**改成 `^7.1.0` 这类只锁 7.x 的写法,列表中若已无 7.x 会报 `no SDKs matched`。

编译哪些型号的 `.prg` 由 `connectiq.yml` 中的 `SIDELOAD_DEVICES` 决定,按自己的手表型号增删即可(型号必须同时存在于 `garmin/manifest.xml` 的 products 列表)。

## 二、安装

### 手机端(Android)
1. 安装 `app-debug.apk`,打开后按提示授予 **蓝牙(附近的设备)权限**。
2. 点「开启无障碍服务」→ 在系统无障碍设置中启用「百度导航→佳明 桥接服务」。
3. 国产 ROM(MIUI / ColorOS / HarmonyOS 等)请给本 App 关闭电池优化、允许后台运行,否则无障碍服务和蓝牙广播可能被杀。
4. **无需安装 Garmin Connect**,也无需在系统蓝牙设置中配对手表。

### 手表端(Garmin)
侧载方式:手表用 USB 连接电脑 → 把对应型号的 `BaiduNav-<设备>.prg` 拷贝到手表的 `GARMIN/APPS/` 目录 → 断开重启。
然后在手表上:活动设置 → 数据屏幕 → 添加数据字段 → Connect IQ → **BaiduNav / 百度导航**。建议放在单字段或双字段的大屏布局中,显示效果最好。

## 三、使用

1. 手机打开本 App,确认显示「📡 蓝牙广播中」,并已开启无障碍服务(两个 ✅)。
2. 手表进入活动界面(跑步/骑行等),切到 BaiduNav 数据字段:字段先显示「正在搜索手机蓝牙…」,找到手机后自动连接并变为「已连接,等待导航…」。可先在手机上点「发送测试指令」验证手表能收到"← 350m 人民中路"。
3. 手机上用百度地图正常发起导航(建议保持百度地图前台并开启导航通知)。
4. 手表实时显示:转向箭头 + 距转向点距离 + 即将进入的道路名 + 底部剩余里程/时间;超过 60 秒未收到新数据自动置灰。
5. 手表开始记录活动后,"导航剩余距离"会作为开发者字段写入 FIT,同步后可在 Garmin Connect 的活动图表中查看。
6. 退出数据字段或结束活动,手表即断开蓝牙,平时两者互不占用连接。

## 四、本地编译(可选)

- **Android**:Android Studio 打开 `android/` 目录直接构建;或 `cd android && gradle assembleDebug`。
- **手表端**:VS Code 安装 Garmin 官方 *Monkey C* 扩展 → 打开 `garmin/` 目录 → `Monkey C: Build for Device`。也可用命令行:
  ```bash
  monkeyc -f garmin/monkey.jungle -y developer_key.der -d fenix7 -r -o BaiduNav-fenix7.prg
  ```

## 五、常见问题

- **手表一直显示"正在搜索手机蓝牙"?** 依次检查:手机 App 显示「蓝牙广播中」、已授予蓝牙权限、手机蓝牙已打开、两者距离足够近;个别老手机不支持 BLE 外设广播(App 会提示),这种手机无法使用本方案。
- **手表型号是否支持?** 手表端使用 `Toybox.BluetoothLowEnergy`(CIQ 3.1+)。如果某个型号在 CI 编译时报 `BluetoothLowEnergy` 权限不可用或 "unknown device",直接从 `garmin/manifest.xml` 和 workflow 的 `SIDELOAD_DEVICES` 中删除该型号即可。
- **中文路名显示为方框?** 手表固件需自带中文字体(国行/亚太版固件正常;部分海外版固件无中文字体,箭头和距离不受影响)。路名受 BLE 单包 20 字节限制,超长会截断为前 6 个汉字左右。
- **百度地图更新后解析不到?** 界面文案属于第三方应用、随版本变化。解析逻辑集中在 `NavParser.kt`,按新文案补充关键词/正则即可,无需改动其他部分。
- **`sdk set` 报 no SDKs matched?** 见上文说明,保持 `CIQ_SDK_VERSION: '>= 7.1.0'`;workflow 会先执行 `sdk list` 打印当前可用版本,便于排查。
- **想上架 Connect IQ 商店?** 使用产物中的 `.iq` 文件到 [Garmin 开发者后台](https://developer.garmin.com/) 提交,并配置固定的 `CIQ_DEVELOPER_KEY_B64`。

## 说明与声明

- 本项目与百度、Garmin 均无关联;"百度地图""Garmin"为其各自所有者的商标。
- 无障碍服务仅监听 `com.baidu.BaiduMap` 包名的窗口与通知文本;数据仅通过 BLE 在你的手机与手表之间点对点传输,不上传任何服务器。
- 仅供个人学习与自用,请遵守相关服务条款;驾驶/骑行时请以实际路况为准,注意安全。

License: MIT
