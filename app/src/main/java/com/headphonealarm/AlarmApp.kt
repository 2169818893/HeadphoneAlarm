package com.headphonealarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.headphonealarm.audio.HeadphoneDetector
import com.headphonealarm.data.AlarmItem
import com.headphonealarm.data.AlarmRepository
import com.headphonealarm.data.AlarmOperations
import com.headphonealarm.data.AlarmScheduler
import com.headphonealarm.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 应用入口，同时作为轻量级依赖容器（避免引入 DI 框架带来的构建负担）。
 */
class AlarmApp : Application() {

    val alarmRepository: AlarmRepository by lazy { AlarmRepository(this) }
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(this) }
    val alarmOperations: AlarmOperations by lazy { AlarmOperations(this, alarmRepository, alarmScheduler) }
    val headphoneDetector: HeadphoneDetector by lazy { HeadphoneDetector(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** 响铃可在用户立即关闭后销毁；触发记账必须独立于服务的生命周期。 */
    private val accountingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** A snooze write/rollback must outlive a ring service destroyed during the write. */
    private val snoozeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun launchSnoozeTransaction(block: suspend () -> Unit): Job = snoozeScope.launch { block() }

    fun recordAlarmFire(item: AlarmItem) {
        accountingScope.launch {
            try {
                alarmOperations.recordFire(item)
            } catch (error: Exception) {
                Log.e("AlarmApp", "闹钟已触发但更新下次排程失败 id=${item.id}", error)
            }
        }
    }

    /** Process an incoming fire even while another alarm owns the ring service. The returned
     * snapshot lets the service play a one-shot alarm after its accounting disables it. */
    fun prepareQueuedFire(id: Long, isSnooze: Boolean, snoozeAt: Long): Deferred<AlarmItem?> =
        accountingScope.async {
            if (isSnooze) {
                alarmOperations.consumeSnooze(id, snoozeAt)
            } else {
                alarmRepository.getById(id)?.takeIf { it.enabled }?.also { item ->
                    try {
                        alarmOperations.recordFire(item)
                    } catch (error: Exception) {
                        // Do not silence a valid alarm just because its next scheduling failed.
                        Log.e("AlarmApp", "排队闹钟触发后续排失败 id=$id", error)
                    }
                }
            }
        }

    /** A second regular fire for an already ringing/queued ID still needs bookkeeping. */
    fun recordAlarmFireById(id: Long) {
        accountingScope.launch {
            try {
                alarmRepository.getById(id)?.takeIf { it.enabled }?.let { alarmOperations.recordFire(it) }
            } catch (error: Exception) {
                Log.e("AlarmApp", "重复触发的闹钟续排失败 id=$id", error)
            }
        }
    }

    /** 同一闹钟已在响铃时也要消费对应的贪睡令牌，避免重启后重复补响。 */
    fun consumeRedundantSnooze(id: Long, token: Long) {
        accountingScope.launch {
            try {
                alarmOperations.consumeSnooze(id, token)
            } catch (error: Exception) {
                Log.e("AlarmApp", "重复贪睡触发记账失败 id=$id", error)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // 闹钟通道：静默（声音由耳机播放引擎负责），但保持高优先级以便弹出全屏界面
        val ringChannel = NotificationChannel(
            CHANNEL_RING,
            getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alarm_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(ringChannel, serviceChannel))
    }

    companion object {
        const val CHANNEL_RING = "alarm_ring"
        const val CHANNEL_SERVICE = "alarm_service"

        private lateinit var instance: AlarmApp

        fun from(context: Context): AlarmApp = context.applicationContext as AlarmApp
    }
}

/** 便捷取用应用级依赖 */
val Context.alarmApp: AlarmApp get() = AlarmApp.from(this)
