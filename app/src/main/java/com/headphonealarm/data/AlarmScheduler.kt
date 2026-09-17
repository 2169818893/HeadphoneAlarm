package com.headphonealarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.headphonealarm.receiver.AlarmReceiver
import com.headphonealarm.ui.ring.AlarmRingActivity
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
    fun schedule(alarm: AlarmItem) {
        if (!alarm.enabled) {
            cancel(alarm)
            return
        }
        val triggerAt = alarm.nextTriggerTime()
        setExact(triggerAt, triggerPendingIntent(alarm))
    }

    /** 贪睡：在指定分钟后再次触发 */
    fun scheduleSnooze(alarm: AlarmItem, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        setExact(triggerAt, triggerPendingIntent(alarm))
    }

    /** 立即触发（调试 / 预览用） */
    fun triggerNow(alarm: AlarmItem) {
        setExact(System.currentTimeMillis() + 800L, triggerPendingIntent(alarm))
    }

    fun cancel(alarm: AlarmItem) {
        alarmManager.cancel(triggerPendingIntent(alarm))
    }

    /** 取消全部已注册闹钟并依据当前列表重建 */
    suspend fun rescheduleAll(repository: AlarmRepository) {
        repository.alarms.first().forEach { alarm ->
            cancel(alarm)
            if (alarm.enabled) schedule(alarm)
        }
    }

    /** 所有已启用闹钟中最早的一次触发时间 */
    fun nextTriggerAmong(alarms: List<AlarmItem>): Long? =
        alarms.filter { it.enabled }.minOfOrNull { it.nextTriggerTime() }

    private fun setExact(triggerAt: Long, pendingIntent: PendingIntent) {
        val exact = canScheduleExact()
        runCatching {
            if (exact) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent()),
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onSuccess {
            Log.i(TAG, "闹钟已注册 exact=$exact triggerAt=$triggerAt (${java.util.Date(triggerAt)})")
        }.onFailure {
            // 之前用 runCatching 静默吞掉了异常，注册失败时用户永远不知道。
            Log.e(TAG, "闹钟注册失败 exact=$exact triggerAt=$triggerAt", it)
        }
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        Intent(context, AlarmRingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 使用 data URI + requestCode 双保险，保证每条闹钟拥有独立的 PendingIntent */
    private fun triggerPendingIntent(alarm: AlarmItem): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = Uri.parse("hpalarm://alarm/${alarm.id}")
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
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
