import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

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
}

class BaiduNavApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        // 注册接收手机伴侣 App 消息 (经 Garmin Connect / 蓝牙)
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
    }

    function onStop(state) {
    }

    hidden function getNum(d, key, def) {
        var v = d.get(key);
        if (v instanceof Lang.Number) {
            return v;
        }
        if (v instanceof Lang.Long || v instanceof Lang.Float || v instanceof Lang.Double) {
            return v.toNumber();
        }
        return def;
    }

    function onPhoneMessage(msg) {
        var data = msg.data;
        if (!(data instanceof Lang.Dictionary)) {
            return;
        }
        NavData.turn = getNum(data, "t", NavData.turn);
        NavData.stepDist = getNum(data, "d", -1);
        NavData.remainDist = getNum(data, "rd", NavData.remainDist);
        NavData.remainTime = getNum(data, "rt", NavData.remainTime);
        var r = data.get("r");
        if (r instanceof Lang.String) {
            NavData.roadName = r;
        }
        NavData.lastUpdate = System.getTimer();
        NavData.hasData = true;
    }

    function getInitialView() {
        return [new BaiduNavView()];
    }
}
