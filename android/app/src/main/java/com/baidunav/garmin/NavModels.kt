package com.baidunav.garmin

/** 转向代码, 与手表端 NavData 注释保持一致 */
object Turn {
    const val STRAIGHT = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val SLIGHT_LEFT = 3
    const val SLIGHT_RIGHT = 4
    const val KEEP_LEFT = 5
    const val KEEP_RIGHT = 6
    const val UTURN = 7
    const val ROUNDABOUT = 8
    const val ARRIVE = 9
}

data class NavInfo(
    val turn: Int,
    val stepDist: Int,    // 距下一转向点(米), -1 未知
    val road: String,
    val remainDist: Int,  // 剩余总距离(米), -1 未知
    val remainTime: Int   // 剩余时间(秒), -1 未知
)

/** 最新导航状态 + 界面日志转发 */
object NavRepo {
    @Volatile
    var lastNav: NavInfo? = null

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun update(nav: NavInfo) {
        if (nav == lastNav) return
        lastNav = nav
        log("→ ${describe(nav)}")
    }

    fun log(msg: String) {
        listener?.invoke(msg)
    }

    fun describe(nav: NavInfo): String {
        val turnStr = when (nav.turn) {
            Turn.LEFT -> "左转"
            Turn.RIGHT -> "右转"
            Turn.SLIGHT_LEFT -> "左前方"
            Turn.SLIGHT_RIGHT -> "右前方"
            Turn.KEEP_LEFT -> "靠左"
            Turn.KEEP_RIGHT -> "靠右"
            Turn.UTURN -> "掉头"
            Turn.ROUNDABOUT -> "环岛"
            Turn.ARRIVE -> "到达"
            else -> "直行"
        }
        val d = if (nav.stepDist >= 0) "${nav.stepDist}米后" else ""
        val rem = if (nav.remainDist >= 0) {
            " | 剩余" + String.format("%.1f", nav.remainDist / 1000.0) + "km"
        } else ""
        return "$d$turnStr ${nav.road}$rem"
    }
}
