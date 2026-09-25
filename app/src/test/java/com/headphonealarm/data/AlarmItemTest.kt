package com.headphonealarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

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

    @Test
    fun daylightSavingGapDoesNotShiftFollowingDayAlarm() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            // 2026-03-08（周日）02:00 跳至 03:00；02:30 在当日不存在。
            val now = at(2026, Calendar.MARCH, 8, 4, 0)
            val monday = at(2026, Calendar.MARCH, 9, 2, 30)
            assertEquals(monday, alarm(2, 30).nextTriggerTime(now))
            assertEquals(monday, alarm(2, 30, setOf(1)).nextTriggerTime(now))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun timezoneChangeRecalculatesTheSameLocalAlarmTime() {
        val original = TimeZone.getDefault()
        val now = Instant.parse("2026-09-20T23:00:00Z").toEpochMilli()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utcTrigger = alarm(9, 30).nextTriggerTime(now)
            assertEquals(Instant.parse("2026-09-21T09:30:00Z").toEpochMilli(), utcTrigger)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val localTrigger = alarm(9, 30).nextTriggerTime(now)
            assertEquals(Instant.parse("2026-09-21T01:30:00Z").toEpochMilli(), localTrigger)
            assertNotEquals(utcTrigger, localTrigger)
        } finally {
            TimeZone.setDefault(original)
        }
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
    fun invalidRepeatDaysNeverIndexOutsideLabels() {
        assertEquals("重复日期异常", repeatTextOf(setOf(0, 8)))
        assertEquals("一 日", repeatTextOf(setOf(0, 1, 7, 8)))
        assertEquals("每天", repeatTextOf((0..8).toSet()))

        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 21, 7, 30),
            alarm(7, 30, setOf(0, 8)).nextTriggerTime(now)
        )
    }

    @Test
    fun timeTextFormatsWithLeadingZero() {
        assertEquals("07:05", alarm(7, 5).timeText())
        assertEquals("23:59", alarm(23, 59).timeText())
    }

    @Test
    fun consumedOneShotKeepsItsPendingSnoozeButNotTheNextRegularAlarm() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        val until = now + 5 * 60_000L
        val consumed = alarm(7, 0).copy(enabled = false, consumedByFire = true, snoozedUntil = until)
        assertEquals(until, consumed.activeSnoozeTime(now))
        assertEquals(until, consumed.nextScheduledTime(now))
        assertEquals(until, consumed.snoozeTriggerTime(now))
        assertNull(consumed.copy(snoozedUntil = null).nextScheduledTime(now))
    }

    @Test
    fun repeatedAlarmAndSnoozeKeepIndependentNextTimes() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        val regular = alarm(9, 0, setOf(7)).copy(enabled = true)
        val earlier = now + 5 * 60_000L
        val later = now + 2 * 60 * 60_000L
        assertEquals(earlier, regular.copy(snoozedUntil = earlier).nextScheduledTime(now))
        assertEquals(regular.nextTriggerTime(now), regular.copy(snoozedUntil = later).nextScheduledTime(now))
        assertEquals(regular.nextTriggerTime(now), regular.copy(snoozedUntil = null).nextScheduledTime(now))
    }

    @Test
    fun recoverRecentMissedSnoozeWithoutChangingBroadcastToken() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        val until = now - 2 * 60_000L
        val item = alarm(7, 0).copy(consumedByFire = true, snoozedUntil = until)
        assertEquals(until, item.activeSnoozeTime(now))
        assertEquals(now + 800L, item.snoozeTriggerTime(now))
        assertEquals(until, item.nextScheduledTime(now))
        assertNull(item.snoozeTriggerTime(until + AlarmItem.SNOOZE_RECOVERY_GRACE_MS + 1L))
    }

    @Test
    fun manualDisableMustInvalidateOldSnooze() {
        val now = at(2026, Calendar.SEPTEMBER, 20, 8, 0)
        val disabled = alarm(7, 0).copy(enabled = false, snoozedUntil = now + 60_000L)
        assertNull(disabled.activeSnoozeTime(now))
        assertNull(disabled.snoozeTriggerTime(now))
        assertNull(disabled.nextScheduledTime(now))
    }

    // endregion
}
