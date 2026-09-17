package com.headphonealarm.util

import java.util.Calendar
import java.util.concurrent.TimeUnit

object TimeUtils {

    /** 距离目标时间的可读倒计时，如「2 小时 13 分后」 */
    fun countdownText(targetMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = targetMillis - now
        if (diff <= 0) return "即将响铃"
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60

        return buildString {
            if (days > 0) append("${days}天")
            if (days > 0 || hours > 0) append("${hours}小时")
            append("${minutes}分后")
        }
    }

    /** 目标日期的星期描述，如「今天 07:30」「明天 07:30」「周三 07:30」 */
    fun dayLabel(targetMillis: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val targetDay = target.clone() as Calendar
        targetDay.set(Calendar.HOUR_OF_DAY, 0)
        targetDay.set(Calendar.MINUTE, 0)
        targetDay.set(Calendar.SECOND, 0)
        targetDay.set(Calendar.MILLISECOND, 0)

        val days = ((targetDay.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        return when (days) {
            0 -> "今天"
            1 -> "明天"
            2 -> "后天"
            else -> AlarmDayName.of(target)
        }
    }

    /** 音量渐强时长的可读文本 */
    fun rampDurationText(seconds: Int): String = when {
        seconds <= 0 -> "关闭"
        seconds < 60 -> "$seconds 秒"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        else -> "${seconds / 60} 分 ${seconds % 60} 秒"
    }

    /** 完整描述，如「明天 07:30」 */
    fun describe(targetMillis: Long, now: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val time = "%02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        return "${dayLabel(targetMillis, now)} $time"
    }
}

private object AlarmDayName {
    private val NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    fun of(calendar: Calendar): String = NAMES[calendar.get(Calendar.DAY_OF_WEEK) - 1]
}
