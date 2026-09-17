package com.headphonealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.headphonealarm.AlarmApp
import com.headphonealarm.service.AlarmRingService
import com.headphonealarm.ui.ring.AlarmRingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统闹钟触发入口。只做两件事：更新重复状态 + 拉起响铃前台服务。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) return

        val appContext = context.applicationContext
        Log.i(TAG, "闹钟触发 id=$alarmId")

        // 关键顺序：先「同步」拉起响铃服务与全屏界面，再做异步记账。
        // 小米/HyperOS 等 ROM 会在广播的异步阶段冻结进程，若把 startForegroundService
        // 放在 DataStore 读取之后，服务可能永远起不来——表现为「到点毫无反应」。
        AlarmRingService.start(appContext, alarmId)
        // 兜底：直接拉起全屏响铃界面。精确闹钟豁免「后台启动 Activity」限制，
        // 万一前台服务的 fullScreenIntent 被 ROM 拦截，界面仍有机会显示。
        AlarmRingActivity.launchFromAlarm(appContext, alarmId)

        val app = AlarmApp.from(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val alarm = app.alarmRepository.getById(alarmId) ?: return@launch
                if (alarm.repeatDays.isEmpty()) {
                    // 一次性闹钟：响铃后自动关闭开关
                    app.alarmRepository.setEnabled(alarm.id, false)
                } else {
                    // 重复闹钟：立即排下一次，避免用户长时间未关闭导致漏响
                    app.alarmScheduler.schedule(alarm)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "闹钟记账失败 id=$alarmId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.headphonealarm.action.ALARM_FIRE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
    }
}
