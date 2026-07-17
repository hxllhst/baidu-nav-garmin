package com.baidunav.garmin

/**
 * 从百度地图界面 / 通知文本中解析导航信息。
 *
 * 百度地图属于第三方应用, 其界面文案随版本变化。
 * 本解析器基于常见文案模式(如 "沿人民路行驶500米后左转进入解放路"、
 * "剩余5.2公里 12分钟")做启发式匹配, 若某版本解析失败,
 * 只需在 turnRules / 正则中补充关键词即可。
 */
object NavParser {

    private var last: NavInfo? = null

    // 顺序重要: 更具体的关键词放前面
    private val turnRules: List<Pair<String, Int>> = listOf(
        "掉头" to Turn.UTURN,
        "调头" to Turn.UTURN,
        "环岛" to Turn.ROUNDABOUT,
        "环形" to Turn.ROUNDABOUT,
        "到达目的地" to Turn.ARRIVE,
        "到达终点" to Turn.ARRIVE,
        "左前方" to Turn.SLIGHT_LEFT,
        "稍向左" to Turn.SLIGHT_LEFT,
        "右前方" to Turn.SLIGHT_RIGHT,
        "稍向右" to Turn.SLIGHT_RIGHT,
        "靠左" to Turn.KEEP_LEFT,
        "靠右" to Turn.KEEP_RIGHT,
        "左转" to Turn.LEFT,
        "向左转" to Turn.LEFT,
        "右转" to Turn.RIGHT,
        "向右转" to Turn.RIGHT,
        "直行" to Turn.STRAIGHT
    )

    private val kmRegex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(?:公里|千米|km|KM)""")
    private val mRegex = Regex("""([0-9]+)\s*米""")
    private val remainKmRegex = Regex("""剩余[^0-9]{0,6}([0-9]+(?:\.[0-9]+)?)\s*公里""")
    private val remainMRegex = Regex("""剩余[^0-9]{0,6}([0-9]+)\s*米""")
    private val hourMinRegex = Regex("""([0-9]+)\s*小时\s*([0-9]+)?\s*分""")
    private val minRegex = Regex("""([0-9]+)\s*分钟""")
    private val roadRegex =
        Regex("""(?:进入|驶入|沿)([\u4e00-\u9fffA-Za-z0-9·\-]{2,20}?(?:路|街|道|巷|桥|隧道|高速|高架|环线|大道|线|口))""")
    private val roadLooseRegex = Regex("""(?:进入|驶入)([\u4e00-\u9fffA-Za-z0-9·\-]{2,14})""")

    fun parse(texts: List<String>): NavInfo? {
        var turn: Int? = null
        var stepDist: Int? = null
        var road: String? = null
        var remainDist: Int? = null
        var remainTime: Int? = null

        for (t in texts) {
            // ---- 剩余总里程 ----
            if (remainDist == null && t.contains("剩余")) {
                val km = remainKmRegex.find(t)
                if (km != null) {
                    remainDist = (km.groupValues[1].toDouble() * 1000).toInt()
                } else {
                    val m = remainMRegex.find(t)
                    if (m != null) remainDist = m.groupValues[1].toInt()
                }
            }
            // ---- 剩余时间 ----
            if (remainTime == null && t.contains("剩余")) {
                val hm = hourMinRegex.find(t)
                if (hm != null) {
                    val hh = hm.groupValues[1].toInt()
                    val mm = hm.groupValues[2].toIntOrNull() ?: 0
                    remainTime = (hh * 60 + mm) * 60
                } else {
                    val m = minRegex.find(t)
                    if (m != null) remainTime = m.groupValues[1].toInt() * 60
                }
            }
            // ---- 转向 + 转向距离 ----
            if (turn == null) {
                for ((kw, code) in turnRules) {
                    if (t.contains(kw)) {
                        turn = code
                        if (stepDist == null && !t.contains("剩余")) {
                            stepDist = parseDistMeters(t)
                        }
                        break
                    }
                }
            }
            // ---- 道路名 ----
            if (road == null) {
                val r = roadRegex.find(t) ?: roadLooseRegex.find(t)
                if (r != null) road = r.groupValues[1].trim().take(20)
            }
        }

        // 兜底: 界面上独立的大字号距离(如 "500米" / "1.2公里")
        if (stepDist == null) {
            for (t in texts) {
                if (t.length <= 8 && !t.contains("剩余")) {
                    val d = parseDistMeters(t)
                    if (d != null) {
                        stepDist = d
                        break
                    }
                }
            }
        }

        if (turn == null && stepDist == null && remainDist == null) return null

        // 与上一次结果合并, 弥补单次事件文本不完整的问题
        val prev = last
        val info = NavInfo(
            turn = turn ?: prev?.turn ?: Turn.STRAIGHT,
            stepDist = stepDist ?: prev?.stepDist ?: -1,
            road = road ?: prev?.road ?: "",
            remainDist = remainDist ?: prev?.remainDist ?: -1,
            remainTime = remainTime ?: prev?.remainTime ?: -1
        )
        last = info
        return info
    }

    fun parseDistMeters(s: String): Int? {
        val km = kmRegex.find(s)
        if (km != null) return (km.groupValues[1].toDouble() * 1000).toInt()
        val m = mRegex.find(s)
        if (m != null) return m.groupValues[1].toInt()
        return null
    }

    fun reset() {
        last = null
    }
}
