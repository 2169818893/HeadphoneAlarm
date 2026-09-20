package com.headphonealarm.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * [TimeUtils] 展示文本的单元测试。
 * 基准日 2026-09-20 为周日。
 */
class TimeUtilsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // region countdownText

    @Test
    fun countdownAlreadyPassed() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 10, 0)
        assertEquals("即将响铃", TimeUtils.countdownText(now - 1, now))
        assertEquals("即将响铃", TimeUtils.countdownText(now, now))
    }

    @Test
    fun countdownMinutes() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 10, 0)
        assertEquals("5分后", TimeUtils.countdownText(now + 5 * 60_000L, now))
    }

    @Test
    fun countdownHoursAndMinutes() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 10, 0)
        assertEquals("1小时30分后", TimeUtils.countdownText(now + 90 * 60_000L, now))
    }

    @Test
    fun countdownDaysHoursMinutes() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 10, 0)
        assertEquals("1天1小时0分后", TimeUtils.countdownText(now + 25 * 3_600_000L, now))
    }

    // endregion

    // region dayLabel

    @Test
    fun dayLabelTodayTomorrowDayAfter() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        assertEquals("今天", TimeUtils.dayLabel(now + 3_600_000L, now))
        assertEquals("明天", TimeUtils.dayLabel(now + 86_400_000L, now))
        assertEquals("后天", TimeUtils.dayLabel(now + 2 * 86_400_000L, now))
    }

    @Test
    fun dayLabelWeekdayAfter3Days() {
        // 周日 + 3 天 = 周三
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        assertEquals("周三", TimeUtils.dayLabel(now + 3 * 86_400_000L, now))
    }

    // endregion

    // region rampDurationText

    @Test
    fun rampDurationVariants() {
        assertEquals("关闭", TimeUtils.rampDurationText(0))
        assertEquals("45 秒", TimeUtils.rampDurationText(45))
        assertEquals("1 分钟", TimeUtils.rampDurationText(60))
        assertEquals("1 分 30 秒", TimeUtils.rampDurationText(90))
    }

    // endregion
}
