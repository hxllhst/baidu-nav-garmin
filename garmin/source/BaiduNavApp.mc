import Toybox.Application;
import Toybox.Lang;
import Toybox.System;

// ============================================================
// 保存手机端最近一次推送的导航数据
// 转向代码: 0直行 1左转 2右转 3左前方 4右前方 5靠左 6靠右
//           7掉头 8环岛 9到达目的地
// ============================================================
module NavData {
    var turn = 0;
    var stepDist = -1;    // 距下一转向点(米), -1 未知
    var roadName = "";
    var remainDist = -1;  // 剩余总距离(米), -1 未知
    var remainTime = -1;  // 剩余时间(秒), -1 未知
    var lastUpdate = 0;   // System.getTimer() 毫秒
    var hasData = false;
    var bleState = 0;     // 0 未启动  1 扫描中  2 已连接手机
}

class BaiduNavApp extends Application.AppBase {

    hidden var mBle = null;

    function initialize() {
        AppBase.initialize();
    }

    // 数据字段只在活动界面显示期间运行:
    // onStart 时开始扫描手机, onStop(退出字段/活动)时断开并停止扫描,
    // 因此手机与手表之间不需要保持长连接。
    function onStart(state) {
        mBle = new NavBleDelegate();
        mBle.open();
    }

    function onStop(state) {
        if (mBle != null) {
            mBle.close();
            mBle = null;
        }
        NavData.bleState = 0;
    }

    function getInitialView() {
        return [new BaiduNavView()];
    }
}
