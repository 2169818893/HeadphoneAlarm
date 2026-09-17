package com.headphonealarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.headphonealarm.audio.HeadphoneDetector
import com.headphonealarm.data.AlarmRepository
import com.headphonealarm.data.AlarmScheduler
import com.headphonealarm.data.SettingsRepository

/**
 * 应用入口，同时作为轻量级依赖容器（避免引入 DI 框架带来的构建负担）。
 */
class AlarmApp : Application() {

    val alarmRepository: AlarmRepository by lazy { AlarmRepository(this) }
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(this) }
    val headphoneDetector: HeadphoneDetector by lazy { HeadphoneDetector(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

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
