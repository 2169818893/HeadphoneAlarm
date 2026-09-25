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
    val autoStopMinutes: Int = 10,
    /** 已安排的贪睡目标时间；与普通重复闹钟可以同时存在。 */
    val snoozedUntil: Long? = null,
    /** 区分触发后自动关闭的一次性闹钟与用户手动关闭的闹钟。 */
    val consumedByFire: Boolean = false
) {

    /** 原始贪睡时刻，也是广播的匹配令牌；过期太久的记录不再补响。 */
    fun activeSnoozeTime(from: Long = System.currentTimeMillis()): Long? =
        snoozedUntil?.takeIf {
            (enabled || consumedByFire) && it > 0L && it >= from - SNOOZE_RECOVERY_GRACE_MS
        }

    /** 恢复过期不久的贪睡时，系统触发时间必须在未来，但令牌保持原始值。 */
    fun snoozeTriggerTime(from: Long = System.currentTimeMillis()): Long? =
        activeSnoozeTime(from)?.let { maxOf(it, from + 800L) }

    /** 用于列表展示；一次性闹钟触发并关闭后，仍可能有待执行的贪睡。 */
    fun nextScheduledTime(from: Long = System.currentTimeMillis()): Long? {
        val regular = if (enabled) nextTriggerTime(from) else null
        return listOfNotNull(regular, activeSnoozeTime(from)).minOrNull()
    }

    /** 计算下一次触发时间戳（毫秒） */
    fun nextTriggerTime(from: Long = System.currentTimeMillis()): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = from
            // 日期从中午推进，避免在夏令时缺失的时刻（如 02:30）上滚动日期，
            // 导致原定 02:30 的次日闹钟永久变成 03:30。
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (repeatDays.isEmpty()) {
            val today = base.clone() as Calendar
            today.set(Calendar.HOUR_OF_DAY, hour)
            today.set(Calendar.MINUTE, minute)
            if (today.timeInMillis > from) return today.timeInMillis
            base.add(Calendar.DAY_OF_YEAR, 1)
            base.set(Calendar.HOUR_OF_DAY, hour)
            base.set(Calendar.MINUTE, minute)
            return base.timeInMillis
        }

        // 最多向后查 7 天即可命中任一选中星期
        for (offset in 0..7) {
            val candidate = base.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, offset)
            val isoDay = candidate.get(Calendar.DAY_OF_WEEK).toIsoDay()
            candidate.set(Calendar.HOUR_OF_DAY, hour)
            candidate.set(Calendar.MINUTE, minute)
            if (isoDay in repeatDays && candidate.timeInMillis > from) {
                return candidate.timeInMillis
            }
        }
        // 损坏数据中没有合法星期时，仍返回未来的安全退路。
        base.add(Calendar.DAY_OF_YEAR, 1)
        base.set(Calendar.HOUR_OF_DAY, hour)
        base.set(Calendar.MINUTE, minute)
        return base.timeInMillis
    }

    /** 星期描述文本 */
    fun repeatText(): String = repeatTextOf(repeatDays)

    fun timeText(): String = "%02d:%02d".format(hour, minute)

    companion object {
        /** 重启后只补响短时间内错过的贪睡，避免数日后突然响起旧闹钟。 */
        const val SNOOZE_RECOVERY_GRACE_MS = 10 * 60_000L
        val DAY_SHORT = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val DAY_LABEL = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/** Calendar.DAY_OF_WEEK(1=周日) 转为 ISO 编号(1=周一..7=周日) */
fun Int.toIsoDay(): Int = if (this == Calendar.SUNDAY) 7 else this - 1

/** 把重复星期集合转成可读文本 */
fun repeatTextOf(days: Set<Int>): String {
    val validDays = days.filterTo(mutableSetOf()) { it in 1..7 }
    return when {
        days.isEmpty() -> "仅一次"
        validDays.isEmpty() -> "重复日期异常"
        validDays.size == 7 -> "每天"
        validDays == setOf(1, 2, 3, 4, 5) -> "工作日"
        validDays == setOf(6, 7) -> "周末"
        else -> validDays.sorted().joinToString(" ") { AlarmItem.DAY_SHORT[it - 1] }
    }
}
