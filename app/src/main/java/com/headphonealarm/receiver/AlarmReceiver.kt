package com.headphonealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.headphonealarm.AlarmApp
import com.headphonealarm.service.AlarmRingService
import com.headphonealarm.ui.ring.AlarmRingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 系统闹钟触发入口。立即拉起服务；服务读取并验证闹钟后负责更新重复状态。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE && intent.action != ACTION_SNOOZE_FIRE) return
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) return

        val appContext = context.applicationContext
        Log.i(TAG, "闹钟触发 id=$alarmId")

        // 第一步就持有桥接唤醒锁：前台服务只保证进程不被杀/不被冻结，但**不阻止 CPU 息屏后休眠**。
        // 服务侧的唤醒锁是在「异步读取闹钟数据之后」才获取的，若这段窗口内 CPU 挂起，
        // 播放管线会卡在启动播放器之前——表现为「后台到点不响、切回前台点亮屏幕才响」。
        // 这里同步持锁桥接「闹钟触发 → 服务接管播放」，带超时自动释放，避免耗电泄漏。
        acquireBridgeWakeLock(appContext)

        // 关键顺序：先「同步」拉起响铃服务与全屏界面，再做异步记账。
        // 小米/HyperOS 等 ROM 会在广播的异步阶段冻结进程，若把 startForegroundService
        // 放在 DataStore 读取之后，服务可能永远起不来——表现为「到点毫无反应」。
        val isSnooze = intent.action == ACTION_SNOOZE_FIRE
        val snoozeAt = intent.getLongExtra(EXTRA_SNOOZE_AT, 0L)
        if (!AlarmRingService.start(appContext, alarmId, isSnooze, snoozeAt)) {
            // Neither service-start API was accepted. The service cannot consume this fire,
            // so record it here to disable a one-shot / reschedule a repeat / consume a
            // snooze token. Keep the broadcast alive briefly for the DataStore write, but
            // let the application-owned operation finish even if the receiver times out.
            val pending = goAsync()
            val accounting = AlarmApp.from(appContext).prepareQueuedFire(alarmId, isSnooze, snoozeAt)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    withTimeout(ACCOUNTING_TIMEOUT_MS) { accounting.await() }
                } catch (error: Exception) {
                    Log.e(TAG, "响铃服务启动失败，触发记账未及时完成 id=$alarmId", error)
                } finally {
                    pending.finish()
                }
            }
            // Without a service, an empty full-screen ring page would never finish loading.
            return
        }
        // 兜底：直接拉起全屏响铃界面。精确闹钟豁免「后台启动 Activity」限制，
        // 万一前台服务的 fullScreenIntent 被 ROM 拦截，界面仍有机会显示。
        AlarmRingActivity.launchFromAlarm(appContext, alarmId)

    }

    /**
     * 桥接唤醒锁：覆盖从闹钟触发到响铃服务自行持锁之间的窗口。
     * 使用超时自动释放（[BRIDGE_WAKELOCK_MS]），即便服务因异常未接管也不会长期耗电。
     */
    private fun acquireBridgeWakeLock(context: Context) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BRIDGE_WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(BRIDGE_WAKELOCK_MS)
            }
        }.onFailure { Log.e(TAG, "桥接唤醒锁获取失败", it) }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.headphonealarm.action.ALARM_FIRE"
        const val ACTION_SNOOZE_FIRE = "com.headphonealarm.action.ALARM_SNOOZE_FIRE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_SNOOZE_AT = "extra_snooze_at"
        private const val BRIDGE_WAKELOCK_TAG = "HeadphoneAlarm:alarm-fire-bridge"
        // 桥接时长：足够冷启动读盘 + 启动播放器；服务接管后由服务自己的唤醒锁续期
        private const val BRIDGE_WAKELOCK_MS = 60 * 1000L
        private const val ACCOUNTING_TIMEOUT_MS = 8_000L
    }
}
