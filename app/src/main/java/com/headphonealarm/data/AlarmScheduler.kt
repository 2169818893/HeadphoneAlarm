package com.headphonealarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.headphonealarm.receiver.AlarmReceiver
import com.headphonealarm.ui.MainActivity
import kotlinx.coroutines.flow.first

/**
 * 负责把闹钟写入系统 AlarmManager。
 *
 * 优先使用 [AlarmManager.setAlarmClock]，它是最可靠的精确闹钟接口，
 * 并且会在系统状态栏显示下一次闹钟图标；无精确闹钟权限时退化为普通精确闹钟。
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /** 为单条闹钟安排下一次触发 */
    fun schedule(alarm: AlarmItem): Boolean {
        if (!alarm.enabled) {
            return cancel(alarm)
        }
        val triggerAt = alarm.nextTriggerTime()
        return setExact(triggerAt, triggerPendingIntent(alarm, snooze = false))
    }

    /** 使用持久化的绝对时间注册贪睡，开机恢复不能从恢复时刻重新计时。 */
    fun scheduleSnooze(alarm: AlarmItem, triggerAt: Long, snoozeToken: Long = triggerAt): Boolean =
        setExact(triggerAt, triggerPendingIntent(alarm, snooze = true, snoozeAt = snoozeToken))

    /** 立即触发（调试 / 预览用） */
    fun triggerNow(alarm: AlarmItem): Boolean =
        setExact(System.currentTimeMillis() + 800L, triggerPendingIntent(alarm, snooze = false))

    fun cancel(alarm: AlarmItem): Boolean {
        val regularCancelled = cancelRegular(alarm)
        val snoozeCancelled = cancelSnooze(alarm)
        return regularCancelled && snoozeCancelled
    }

    fun cancelSnooze(alarm: AlarmItem): Boolean = runCatching {
        alarmManager.cancel(triggerPendingIntent(alarm, snooze = true))
    }.onFailure { Log.e(TAG, "贪睡取消失败 id=${alarm.id}", it) }.isSuccess

    /** 取消全部已注册闹钟并依据当前列表重建 */
    suspend fun rescheduleAll(repository: AlarmRepository): Set<Long> {
        val failed = mutableSetOf<Long>()
        repository.alarms.first().forEach { alarm ->
            // 列表恢复/开机重排只处理普通闹钟：一次性闹钟触发后会显示关闭，
            // 但用户刚安排的贪睡仍应保留，不能因为回到主页就抹掉。
            val cancelled = cancelRegular(alarm)
            val scheduled = !alarm.enabled || schedule(alarm)
            if (!cancelled || !scheduled) failed.add(alarm.id)
        }
        return failed
    }

    /** 所有已启用闹钟中最早的一次触发时间 */
    fun nextTriggerAmong(alarms: List<AlarmItem>): Long? =
        alarms.filter { it.enabled }.minOfOrNull { it.nextTriggerTime() }

    private fun setExact(triggerAt: Long, pendingIntent: PendingIntent): Boolean {
        return runCatching {
            val exact = canScheduleExact()
            if (exact) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent()),
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            Log.i(TAG, "闹钟已注册 exact=$exact triggerAt=$triggerAt (${java.util.Date(triggerAt)})")
        }.onFailure {
            Log.e(TAG, "闹钟注册失败 triggerAt=$triggerAt", it)
        }.isSuccess
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        // AlarmClockInfo 的 showIntent 会在尚未触发时由系统闹钟图标调用。
        // 此时没有响铃服务，打开响铃页只会显示“闹钟已结束”。
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 使用 data URI + requestCode 双保险，保证每条闹钟拥有独立的 PendingIntent */
    fun cancelRegular(alarm: AlarmItem): Boolean = runCatching {
        alarmManager.cancel(triggerPendingIntent(alarm, snooze = false))
    }.onFailure { Log.e(TAG, "闹钟取消失败 id=${alarm.id}", it) }.isSuccess

    private fun triggerPendingIntent(alarm: AlarmItem, snooze: Boolean, snoozeAt: Long = 0L): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            // 保留普通闹钟的旧 PendingIntent 身份，升级后仍可取消旧闹钟。
            action = if (snooze) AlarmReceiver.ACTION_SNOOZE_FIRE else AlarmReceiver.ACTION_FIRE
            data = Uri.parse("hpalarm://alarm/${alarm.id}")
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            if (snooze && snoozeAt > 0L) putExtra(AlarmReceiver.EXTRA_SNOOZE_AT, snoozeAt)
        }
        return PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val TAG = "AlarmScheduler"
        const val SHOW_REQUEST_CODE = 0x5A11
    }
}
