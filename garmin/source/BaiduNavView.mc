import Toybox.FitContributor;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.WatchUi;

class BaiduNavView extends WatchUi.DataField {

    // 超过 60 秒未收到手机数据则置灰显示
    hidden var STALE_MS = 60000;

    hidden var mRemainField;

    // 单位坐标系箭头(y 向上为负), 绘制时按尺寸缩放/旋转
    hidden var mHead = [[0.0, -1.0], [-0.62, -0.18], [0.62, -0.18]];
    hidden var mShaft = [[-0.24, -0.25], [0.24, -0.25], [0.24, 0.95], [-0.24, 0.95]];

    function initialize() {
        DataField.initialize();
        // 把"剩余距离"写入活动 FIT 文件 (Garmin Connect 中可查看图表)
        mRemainField = createField("baidu_nav_remaining", 0,
            FitContributor.DATA_TYPE_UINT32,
            { :mesgType => FitContributor.MESG_TYPE_RECORD, :units => "m" });
    }

    function compute(info) {
        if (NavData.hasData && NavData.remainDist >= 0) {
            mRemainField.setData(NavData.remainDist);
        }
    }

    function onUpdate(dc) {
        var bg = getBackgroundColor();
        var fg = (bg == Graphics.COLOR_WHITE) ? Graphics.COLOR_BLACK : Graphics.COLOR_WHITE;
        dc.setColor(fg, bg);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();

        if (!NavData.hasData) {
            var rid = (NavData.bleState == 2) ? Rez.Strings.waiting : Rez.Strings.scanning;
            dc.setColor(fg, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_XTINY,
                WatchUi.loadResource(rid),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var stale = (System.getTimer() - NavData.lastUpdate) > STALE_MS;
        var color = stale ? Graphics.COLOR_LT_GRAY : fg;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);

        var distStr = formatDist(NavData.stepDist);

        if (h < 60) {
            // 小尺寸字段: 仅箭头 + 距离
            var s = h * 0.36;
            drawTurnIcon(dc, (h * 0.5).toNumber(), (h * 0.5).toNumber(), s, NavData.turn);
            var font = pickFont(dc, distStr, w - h - 4, h - 2);
            dc.drawText(h + (w - h) / 2, h / 2, font, distStr,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        // 大尺寸字段: 上方箭头+距离, 中间路名, 底部剩余里程/时间
        var s2 = h * 0.20;
        drawTurnIcon(dc, (w * 0.22).toNumber(), (h * 0.28).toNumber(), s2, NavData.turn);

        var font2 = pickFont(dc, distStr, (w * 0.55).toNumber(), (h * 0.42).toNumber());
        dc.drawText((w * 0.64).toNumber(), (h * 0.28).toNumber(), font2, distStr,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        if (!NavData.roadName.equals("")) {
            var rf = Graphics.FONT_SMALL;
            if (dc.getTextWidthInPixels(NavData.roadName, rf) > w - 8) {
                rf = Graphics.FONT_XTINY;
            }
            dc.drawText(w / 2, (h * 0.62).toNumber(), rf, NavData.roadName,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        var bottom = "";
        if (NavData.remainDist >= 0) {
            bottom = formatDist(NavData.remainDist);
        }
        if (NavData.remainTime >= 0) {
            if (!bottom.equals("")) {
                bottom = bottom + "  ";
            }
            bottom = bottom + (NavData.remainTime / 60).toString() + "min";
        }
        if (!bottom.equals("")) {
            dc.drawText(w / 2, (h * 0.86).toNumber(), Graphics.FONT_XTINY, bottom,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // ---------------- 工具函数 ----------------

    hidden function formatDist(m) {
        if (m < 0) {
            return "--";
        }
        if (m >= 1000) {
            return (m / 1000.0).format("%.1f") + "km";
        }
        return m.toString() + "m";
    }

    hidden function pickFont(dc, text, maxW, maxH) {
        var fonts = [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM,
                     Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY];
        for (var i = 0; i < fonts.size(); i++) {
            var f = fonts[i];
            if (dc.getTextWidthInPixels(text, f) <= maxW && dc.getFontHeight(f) <= maxH) {
                return f;
            }
        }
        return Graphics.FONT_XTINY;
    }

    hidden function drawTurnIcon(dc, cx, cy, s, turn) {
        if (turn == 1) {                       // 左转
            drawRotArrow(dc, cx, cy, s, -90);
        } else if (turn == 2) {                // 右转
            drawRotArrow(dc, cx, cy, s, 90);
        } else if (turn == 3 || turn == 5) {   // 左前方 / 靠左
            drawRotArrow(dc, cx, cy, s, -40);
        } else if (turn == 4 || turn == 6) {   // 右前方 / 靠右
            drawRotArrow(dc, cx, cy, s, 40);
        } else if (turn == 7) {                // 掉头
            drawUturn(dc, cx, cy, s);
        } else if (turn == 8) {                // 环岛
            drawRoundabout(dc, cx, cy, s);
        } else if (turn == 9) {                // 到达
            drawArrive(dc, cx, cy, s);
        } else {                               // 直行
            drawRotArrow(dc, cx, cy, s, 0);
        }
    }

    hidden function drawRotArrow(dc, cx, cy, s, deg) {
        var rad = deg * Math.PI / 180.0;
        var c = Math.cos(rad);
        var sn = Math.sin(rad);
        dc.fillPolygon(transformPts(mHead, cx, cy, s, c, sn));
        dc.fillPolygon(transformPts(mShaft, cx, cy, s, c, sn));
    }

    hidden function transformPts(pts, cx, cy, s, c, sn) {
        var out = new [pts.size()];
        for (var i = 0; i < pts.size(); i++) {
            var p = pts[i] as Array<Float>;
            var x = p[0];
            var y = p[1];
            out[i] = [(cx + s * (x * c - y * sn)).toNumber(),
                      (cy + s * (x * sn + y * c)).toNumber()];
        }
        return out;
    }

    hidden function drawUturn(dc, cx, cy, s) {
        var pen = (s * 0.34).toNumber();
        if (pen < 2) { pen = 2; }
        dc.setPenWidth(pen);
        var r = (s * 0.55).toNumber();
        if (r < 3) { r = 3; }
        var acy = (cy - s * 0.30).toNumber();
        dc.drawArc(cx, acy, r, Graphics.ARC_COUNTER_CLOCKWISE, 0, 180);
        dc.drawLine(cx + r, acy, cx + r, (cy + s * 0.55).toNumber());
        dc.drawLine(cx - r, acy, cx - r, (cy + s * 0.15).toNumber());
        dc.fillPolygon([
            [(cx - r - s * 0.42).toNumber(), (cy + s * 0.15).toNumber()],
            [(cx - r + s * 0.42).toNumber(), (cy + s * 0.15).toNumber()],
            [(cx - r).toNumber(), (cy + s * 0.95).toNumber()]
        ]);
        dc.setPenWidth(1);
    }

    hidden function drawRoundabout(dc, cx, cy, s) {
        var pen = (s * 0.28).toNumber();
        if (pen < 2) { pen = 2; }
        dc.setPenWidth(pen);
        var r = (s * 0.52).toNumber();
        if (r < 3) { r = 3; }
        dc.drawCircle(cx, (cy + s * 0.25).toNumber(), r);
        dc.setPenWidth(1);
        dc.fillPolygon([
            [cx, (cy - s).toNumber()],
            [(cx - s * 0.45).toNumber(), (cy - s * 0.25).toNumber()],
            [(cx + s * 0.45).toNumber(), (cy - s * 0.25).toNumber()]
        ]);
    }

    hidden function drawArrive(dc, cx, cy, s) {
        dc.fillRectangle((cx - s * 0.45).toNumber(), (cy - s).toNumber(),
            (s * 0.16).toNumber() + 1, (s * 2).toNumber());
        dc.fillPolygon([
            [(cx - s * 0.30).toNumber(), (cy - s).toNumber()],
            [(cx + s * 0.80).toNumber(), (cy - s * 0.55).toNumber()],
            [(cx - s * 0.30).toNumber(), (cy - s * 0.10).toNumber()]
        ]);
    }
}
