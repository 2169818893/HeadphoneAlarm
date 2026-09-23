package com.headphonealarm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.headphonealarm.AlarmApp
import com.headphonealarm.R
import com.headphonealarm.audio.HeadphoneAlarmPlayer
import com.headphonealarm.data.AlarmItem
import com.headphonealarm.ui.ring.AlarmRingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 响铃前台服务：承载音频播放、通知与自动超时。
 * 所有播放逻辑委托给 [HeadphoneAlarmPlayer]，本类只做生命周期与状态编排。
 */
class AlarmRingService : Service() {

    /** 暴露给响铃界面订阅的 UI 状态 */
    data class RingUiState(
        val alarmId: Long,
        val timeText: String,
        val label: String,
        val statusText: String,
        val viaHeadphone: Boolean,
        val snoozeMinutes: Int,
        /** 音量渐强进度 0~1，用于界面展示爬升过程 */
        val volumeProgress: Float = 1f,
        /** 渐强总时长（秒），0 表示未开启 */
        val rampSeconds: Int = 0,
        val maxVolumePercent: Int = 100
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app: AlarmApp by lazy { AlarmApp.from(this) }
    private lateinit var player: HeadphoneAlarmPlayer

    private var alarm: AlarmItem? = null
    private var autoStopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 渐强进度只驱动界面，不重建通知，避免每秒几十次通知刷新 */
    private var volumeProgress: Float = 1f
    private var lastNotifiedVolume: Float = -1f

    override fun onCreate() {
        super.onCreate()
        player = HeadphoneAlarmPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> dismiss()
            ACTION_SNOOZE -> snooze()
            ACTION_BOOST -> player.boostToMaxVolume()
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                if (id < 0) {
                    stopSelf()
                } else {
                    // 同步持锁：必须在进入任何协程/异步读盘之前就握住 CPU，否则息屏后
                    // CPU 可能在读取闹钟数据、启动播放器之前挂起，造成「后台不响、切回前台才响」。
                    acquireWakeLock()
                    // 系统要求 startForegroundService 后 5 秒内进入前台，
                    // 先用占位通知抢占，再异步读取闹钟数据，避免超时崩溃
                    startForegroundPlaceholder()
                    startRinging(id)
                }
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        autoStopJob?.cancel()
        player.release()
        releaseWakeLock()
        _state.value = null
        scope.cancel()
        super.onDestroy()
    }

    /** 响铃期间持有部分唤醒锁，避免深度休眠打断播放与震动 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKELOCK_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // region 响铃流程

    private fun startRinging(alarmId: Long) {
        scope.launch {
            val item = app.alarmRepository.getById(alarmId)
            if (item == null) {
                Log.w(TAG, "找不到闹钟 id=$alarmId，服务退出")
                stopSelf()
                return@launch
            }
            Log.i(
                TAG,
                "开始响铃 id=$alarmId headphoneOnly=${item.headphoneOnly} " +
                    "noHeadphoneAction=${item.noHeadphoneAction} ringtone=${item.ringtoneUri}"
            )
            alarm = item
            volumeProgress = 1f
            lastNotifiedVolume = -1f
            acquireWakeLock()
            publish(item, "准备播放…", viaHeadphone = false, foreground = true)

            player.start(
                alarm = item,
                onState = { state -> handlePlayerState(item, state) },
                onVolume = { progress -> handleVolumeProgress(progress) }
            )
            scheduleAutoStop(item)
        }
    }

    private fun handlePlayerState(item: AlarmItem, state: HeadphoneAlarmPlayer.State) {
        Log.i(TAG, "播放状态: $state")
        when (state) {
            is HeadphoneAlarmPlayer.State.Playing -> publish(
                item,
                if (state.viaHeadphone) {
                    state.deviceName?.let { "正在通过耳机播放 · $it" } ?: "正在通过耳机播放"
                } else {
                    "正在通过扬声器播放"
                },
                viaHeadphone = state.viaHeadphone
            )

            is HeadphoneAlarmPlayer.State.WaitingHeadphone ->
                publish(item, state.reason, viaHeadphone = false)

            HeadphoneAlarmPlayer.State.VibrateOnly ->
                publish(item, "未连接耳机，仅震动提醒", viaHeadphone = false)

            is HeadphoneAlarmPlayer.State.Failed ->
                publish(item, state.reason, viaHeadphone = false)

            HeadphoneAlarmPlayer.State.Idle -> Unit
        }
    }

    /**
     * 渐强进度只更新界面状态，不刷新通知。
     * 按 1% 粒度节流，避免 20Hz 的回调把主线程刷爆。
     */
    private fun handleVolumeProgress(progress: Float) {
        if (progress < 1f && progress - lastNotifiedVolume < VOLUME_UI_STEP) return
        lastNotifiedVolume = progress
        volumeProgress = progress
        val current = _state.value ?: return
        _state.value = current.copy(volumeProgress = progress)
    }

    /** 占位前台通知，保证服务在 5 秒内进入前台状态 */
    private fun startForegroundPlaceholder() {
        val notification = NotificationCompat.Builder(this, AlarmApp.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("闹钟即将响起…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType()) }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

    private fun publish(
        item: AlarmItem,
        status: String,
        viaHeadphone: Boolean,
        foreground: Boolean = false
    ) {
        _state.value = RingUiState(
            alarmId = item.id,
            timeText = item.timeText(),
            label = item.label,
            statusText = status,
            viaHeadphone = viaHeadphone,
            snoozeMinutes = item.snoozeMinutes,
            volumeProgress = volumeProgress,
            rampSeconds = item.volumeRampSeconds,
            maxVolumePercent = item.maxVolumePercent
        )
        val notification = buildNotification(item, status)
        if (foreground) {
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType())
            }
        } else {
            // 通知权限被拒时显式跳过（Lint 要求），不中断响铃
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
            }
        }
    }

    private fun scheduleAutoStop(item: AlarmItem) {
        autoStopJob?.cancel()
        val minutes = item.autoStopMinutes.coerceAtLeast(1)
        autoStopJob = scope.launch {
            delay(minutes * 60_000L)
            dismiss()
        }
    }

    private fun dismiss() {
        autoStopJob?.cancel()
        player.stop()
        releaseWakeLock()
        _state.value = null
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun snooze() {
        val item = alarm ?: return dismiss()
        app.alarmScheduler.scheduleSnooze(item, item.snoozeMinutes)
        // 一次性闹钟触发即被消费、列表开关显示为关闭，但贪睡确实会再响一次。
        // 给出明确提示，避免用户误以为闹钟已彻底关闭（贪睡状态可见反馈）。
        notifySnoozedUntil(item.snoozeMinutes)
        dismiss()
    }

    /** 提示「已贪睡，将于 HH:MM 再响」；界面按钮与通知按钮两个入口都会经过这里 */
    private fun notifySnoozedUntil(minutes: Int) {
        runCatching {
            val until = System.currentTimeMillis() + minutes * 60_000L
            val time = DateFormat.getTimeFormat(this).format(java.util.Date(until))
            Toast.makeText(this, "已贪睡，将于 $time 再响", Toast.LENGTH_LONG).show()
        }
    }

    // endregion

    private fun buildNotification(item: AlarmItem, status: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_ALARM_ID, item.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = buildString {
            append(item.timeText())
            if (item.label.isNotBlank()) append(" · ").append(item.label)
        }

        return NotificationCompat.Builder(this, AlarmApp.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .addAction(0, "贪睡 ${item.snoozeMinutes} 分钟", servicePendingIntent(REQUEST_SNOOZE, ACTION_SNOOZE))
            .addAction(0, "关闭", servicePendingIntent(REQUEST_STOP, ACTION_STOP))
            .build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AlarmRingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_START = "com.headphonealarm.action.RING_START"
        const val ACTION_STOP = "com.headphonealarm.action.RING_STOP"
        const val ACTION_SNOOZE = "com.headphonealarm.action.RING_SNOOZE"
        const val ACTION_BOOST = "com.headphonealarm.action.RING_BOOST"
        const val EXTRA_ALARM_ID = "extra_alarm_id"

        private const val TAG = "AlarmRingService"

        private const val NOTIFICATION_ID = 0x1001
        private const val REQUEST_CONTENT = 0x2001
        private const val REQUEST_SNOOZE = 0x2002
        private const val REQUEST_STOP = 0x2003
        private const val WAKELOCK_TAG = "HeadphoneAlarm:ring"
        // 兜底超时须明显大于最大自动停止时长(30分)：唤醒锁从 onStartCommand 起计时，
        // 而 autoStop 的 delay 从稍晚的读盘完成后起计，相等会在收尾前提前释锁。
        // 正常路径由 dismiss() 主动释放，此处仅为防泄漏兜底。
        private const val MAX_WAKELOCK_MS = 60 * 60 * 1000L
        private const val VOLUME_UI_STEP = 0.01f

        private val _state = MutableStateFlow<RingUiState?>(null)

        /** 供 [AlarmRingActivity] 订阅的实时状态 */
        val state: StateFlow<RingUiState?> = _state.asStateFlow()

        fun start(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALARM_ID, alarmId)
            try {
                context.startForegroundService(intent)
                Log.i(TAG, "已请求启动响铃前台服务 id=$alarmId")
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务受限；精确闹钟本应豁免，但部分 ROM 仍会拦截。
                Log.e(TAG, "startForegroundService 失败 id=$alarmId，回退 startService", t)
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            // 服务未运行时 startService 可能抛异常，界面已关闭即可忽略
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        fun snooze(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_SNOOZE)
                )
            }
        }

        /** 响铃中用户点按“使用最大音量”：在服务内抬升媒体音量（服务已前台） */
        fun boost(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_BOOST)
                )
            }
        }
    }
}
