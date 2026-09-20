package com.headphonealarm.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * [AlarmItem.nextTriggerTime] 触发时间计算的单元测试。
 *
 * 测试基准日：2026-09-20 为周日（ISO=7），
 * 依次为 周一 09-21、周二 09-22、周三 09-23、周四 09-24、周五 09-25、周六 09-26。
 */
class AlarmItemTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun alarm(
        hour: Int,
        minute: Int,
        repeatDays: Set<Int> = emptySet()
    ) = AlarmItem(id = 1L, hour = hour, minute = minute, repeatDays = repeatDays)

    // region 一次性闹钟

    @Test
    fun oneShotTodayFuture() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0) // 周日 08:00
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 20, 9, 30),
            alarm(9, 30).nextTriggerTime(now)
        )
    }

    @Test
    fun oneShotAlreadyPassedRollsToTomorrow() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 10, 0) // 周日 10:00
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 21, 9, 30), // 周一
            alarm(9, 30).nextTriggerTime(now)
        )
    }

    @Test
    fun oneShotExactNowRollsToTomorrow() {
        // 边界：触发时刻 == 当前时刻（<= 判定已过）
        val now = at(2026, Calendar.SEPTEMBER, 20, 9, 30)
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 21, 9, 30),
            alarm(9, 30).nextTriggerTime(now)
        )
    }

    // endregion

    // region 重复规则

    @Test
    fun weekdayAlarmFromSundayFiresMonday() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 12, 0) // 周日
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 21, 7, 0), // 周一
            alarm(7, 0, repeatDays = setOf(1, 2, 3, 4, 5)).nextTriggerTime(now)
        )
    }

    @Test
    fun weekdayAlarmTodayBeforeTimeFiresToday() {
        val now = at(2026, Calendar.SEPTEMBER, 22, 6, 0) // 周二 06:00
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 22, 7, 0), // 当天
            alarm(7, 0, repeatDays = setOf(1, 2, 3, 4, 5)).nextTriggerTime(now)
        )
    }

    @Test
    fun weekendAlarmFromSundayFiresNextSaturday() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 12, 0) // 周日 12:00
        // 周日 08:00 已过，周末集合下一个命中是周六 09-26
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 26, 8, 0),
            alarm(8, 0, repeatDays = setOf(6, 7)).nextTriggerTime(now)
        )
    }

    @Test
    fun dailyAlarmAlwaysTomorrowIfPassed() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 23, 0) // 周日 23:00
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 21, 22, 0), // 周一
            alarm(22, 0, repeatDays = (1..7).toSet()).nextTriggerTime(now)
        )
    }

    @Test
    fun fridayNightRollsOverWeekendToMonday() {
        val now = at(2026, Calendar.SEPTEMBER, 25, 23, 0) // 周五 23:00
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 28, 7, 0), // 跨周末到下周一
            alarm(7, 0, repeatDays = setOf(1, 2, 3, 4, 5)).nextTriggerTime(now)
        )
    }

    // endregion

    // region 文本展示

    @Test
    fun repeatTextVariants() {
        assertEquals("仅一次", repeatTextOf(emptySet()))
        assertEquals("每天", repeatTextOf((1..7).toSet()))
        assertEquals("工作日", repeatTextOf(setOf(1, 2, 3, 4, 5)))
        assertEquals("周末", repeatTextOf(setOf(6, 7)))
        assertEquals("一 三 五", repeatTextOf(setOf(5, 3, 1)))
    }

    @Test
    fun timeTextFormatsWithLeadingZero() {
        assertEquals("07:05", alarm(7, 5).timeText())
        assertEquals("23:59", alarm(23, 59).timeText())
    }

    // endregion
}
