package com.headphonealarm.data

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * 无可用耳机时的行为策略。
 */
@Serializable
enum class NoHeadphoneAction {
    /** 静默等待耳机接入，接入后立即播放（绝不外放） */
    WAIT,

    /** 仅震动提醒，不播放任何声音 */
    VIBRATE_ONLY,

    /** 允许回落到扬声器外放 */
    SPEAKER
}

/**
 * 单条闹钟配置。
 *
 * [repeatDays] 使用 ISO 星期编号：1=周一 ... 7=周日；空集合表示「仅响一次」。
 * [ringtoneUri] 为空时使用系统默认闹钟铃声。
 */
@Serializable
data class AlarmItem(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = false,
    val repeatDays: Set<Int> = emptySet(),
    val ringtoneUri: String? = null,
    val ringtoneName: String = "",
    /** 仅通过耳机输出，杜绝外放 */
    val headphoneOnly: Boolean = true,
    val noHeadphoneAction: NoHeadphoneAction = NoHeadphoneAction.WAIT,
    val vibrate: Boolean = true,
    /** 音量渐强时长（秒），0 表示直接播放到目标音量 */
    val volumeRampSeconds: Int = 30,
    /** 渐强起点音量百分比 1~30，从「刚好能听见」开始最护耳 */
    val rampStartPercent: Int = 5,
    /** 音量上限百分比 20~100，默认不完全拉满以保护听力 */
    val maxVolumePercent: Int = 85,
    val snoozeMinutes: Int = 5,
    /** 响铃最长持续时间（分钟），超时自动关闭 */
    val autoStopMinutes: Int = 10
) {

    /** 计算下一次触发时间戳（毫秒） */
    fun nextTriggerTime(from: Long = System.currentTimeMillis()): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (repeatDays.isEmpty()) {
            if (base.timeInMillis <= from) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }

        // 最多向后查 7 天即可命中任一选中星期
        for (offset in 0..7) {
            val candidate = base.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, offset)
            val isoDay = candidate.get(Calendar.DAY_OF_WEEK).toIsoDay()
            if (isoDay in repeatDays && candidate.timeInMillis > from) {
                return candidate.timeInMillis
            }
        }
        return base.timeInMillis
    }

    /** 星期描述文本 */
    fun repeatText(): String = repeatTextOf(repeatDays)

    fun timeText(): String = "%02d:%02d".format(hour, minute)

    companion object {
        val DAY_SHORT = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val DAY_LABEL = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/** Calendar.DAY_OF_WEEK(1=周日) 转为 ISO 编号(1=周一..7=周日) */
fun Int.toIsoDay(): Int = if (this == Calendar.SUNDAY) 7 else this - 1

/** 把重复星期集合转成可读文本 */
fun repeatTextOf(days: Set<Int>): String = when {
    days.isEmpty() -> "仅一次"
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日"
    days == setOf(6, 7) -> "周末"
    else -> days.sorted().joinToString(" ") { AlarmItem.DAY_SHORT[it - 1] }
}
