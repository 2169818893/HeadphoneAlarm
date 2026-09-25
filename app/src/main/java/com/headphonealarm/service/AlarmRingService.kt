package com.headphonealarm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

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
        val maxVolumePercent: Int = 100,
        /** 尚未验证这条触发时，界面不能操作上一条闹钟。 */
        val actionsEnabled: Boolean = true
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app: AlarmApp by lazy { AlarmApp.from(this) }
    private lateinit var player: HeadphoneAlarmPlayer

    private var alarm: AlarmItem? = null
    private var loadingAlarmId: Long? = null
    private data class FireRequest(val id: Long, val isSnooze: Boolean, val snoozeAt: Long)
    private data class QueuedFire(val request: FireRequest, val prepared: Deferred<AlarmItem?>)
    private val queuedFires = ArrayDeque<QueuedFire>()
    private var currentFire: FireRequest? = null
    /** 触发记账独立于 Service 生命周期；同 ID 的后续触发必须等待它完成。 */
    private var currentPrepared: Deferred<AlarmItem?>? = null
    private var startJob: Job? = null
    private class SnoozeRequest(val alarmId: Long, var cancelled: Boolean = false)
    private var pendingSnooze: SnoozeRequest? = null
    private var autoStopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId: Int = 0

    /** 渐强进度只驱动界面，不重建通知，避免每秒几十次通知刷新 */
    private var volumeProgress: Float = 1f
    private var lastNotifiedVolume: Float = -1f

    override fun onCreate() {
        super.onCreate()
        player = HeadphoneAlarmPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> if (matchesCurrentAlarm(intent)) {
                dismiss()
            } else stopIfIdle(startId)
            ACTION_SNOOZE -> if (matchesCurrentAlarm(intent)) snooze() else stopIfIdle(startId)
            ACTION_BOOST -> if (matchesCurrentAlarm(intent)) player.boostToMaxVolume() else stopIfIdle(startId)
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                val request = FireRequest(
                    id, intent.getBooleanExtra(EXTRA_SNOOZE, false),
                    intent.getLongExtra(EXTRA_SNOOZE_AT, 0L)
                )
                if (id <= 0) {
                    stopIfIdle(startId)
                } else if (alarm?.id == id) {
                    // 同 ID 的新普通触发也要记账：用户可能在响铃过程中修改了
                    // 重复闹钟，新的触发不应因播放器正在响而丢失下次排程。
                    // 不重复消费一次性闹钟，也不重启播放器/计时。
                    if (request.isSnooze) {
                        if (request != currentFire) app.consumeRedundantSnooze(id, request.snoozeAt)
                    } else {
                        app.recordAlarmFireById(id)
                    }
                } else if (loadingAlarmId == id && startJob?.isActive == true) {
                    // Loading 中的第二次触发不能抢在原请求读盘前关闭一次性闹钟。
                    if (request != currentFire) {
                        currentPrepared?.invokeOnCompletion {
                            if (request.isSnooze) app.consumeRedundantSnooze(id, request.snoozeAt)
                            else app.recordAlarmFireById(id)
                        }
                    }
                } else {
                    val queued = queuedFires.firstOrNull { it.request.id == id }
                    if (queued != null) {
                        // 先前的排队读盘与触发记账必须完成，再处理同 ID 的第二次触发。
                        // 否则第二次记账可能先关闭一次性闹钟，导致第一次不响。
                        if (request != queued.request) {
                            queued.prepared.invokeOnCompletion {
                                if (request.isSnooze) app.consumeRedundantSnooze(id, request.snoozeAt)
                                else app.recordAlarmFireById(id)
                            }
                        }
                    } else if (currentFire != null) {
                        // 不截断正在响的闹钟。提前完成下一次排程/贪睡消费，按触发顺序响铃。
                        queuedFires.addLast(QueuedFire(request, app.prepareQueuedFire(id, request.isSnooze, request.snoozeAt)))
                    } else {
                        beginRinging(request)
                    }
                }
            }
            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // System destruction is not an explicit dismissal; the app-owned snooze must complete.
        pendingSnooze = null
        startJob?.cancel()
        currentPrepared = null
        autoStopJob?.cancel()
        queuedFires.clear()
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

    private fun beginRinging(request: FireRequest, prepared: Deferred<AlarmItem?>? = null) {
        currentFire = request
        loadingAlarmId = request.id
        // 读盘/触发记账由应用持有，即使服务在加载途中销毁也须完成下次排程。
        val fire = prepared ?: app.prepareQueuedFire(request.id, request.isSnooze, request.snoozeAt)
        currentPrepared = fire
        _state.value = RingUiState(
            alarmId = request.id,
            timeText = "",
            label = "",
            statusText = "正在准备闹钟…",
            viaHeadphone = false,
            snoozeMinutes = 0,
            actionsEnabled = false
        )
        // 同步持锁并先进入前台；不能等待协程读盘，否则息屏时可能挂起或超 5 秒。
        acquireWakeLock()
        val foregroundStarted = runCatching { startForegroundPlaceholder(request.id) }
            .onFailure { Log.e(TAG, "无法启动响铃前台服务 id=${request.id}，停止播放", it) }
            .isSuccess
        if (!foregroundStarted) {
            // Deferred 属于 Application：已触发及排队闹钟仍会完成记账，
            // 但通知不可用时不能继续读盘、播放或尝试逐个启动排队闹钟。
            dismiss(continueQueue = false)
            return
        }
        startRinging(request, fire)
    }

    private fun startRinging(request: FireRequest, prepared: Deferred<AlarmItem?>) {
        val alarmId = request.id
        startJob = scope.launch {
            try {
                val fired = prepared.await()
                // 编辑、手动禁用或删除应取消尚未开始的响铃；一次性闹钟
                // 触发后的自动禁用及另一个触发消费贪睡令牌不应取消它。
                val item = fired?.takeIf { snapshot ->
                    val current = app.alarmRepository.getById(alarmId)
                    current != null && (current.enabled || current.consumedByFire) &&
                        current.copy(
                            enabled = snapshot.enabled,
                            consumedByFire = snapshot.consumedByFire,
                            snoozedUntil = snapshot.snoozedUntil
                        ) == snapshot
                }
                if (item == null) {
                    Log.w(TAG, "闹钟不存在、已禁用或贪睡已取消 id=$alarmId，服务退出")
                    if (loadingAlarmId == alarmId) dismiss()
                    return@launch
                }
                loadingAlarmId = null
                currentPrepared = null
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "闹钟启动/记账失败 id=$alarmId", error)
                if (alarm?.id == alarmId || loadingAlarmId == alarmId) {
                    // 更新前台通知失败时不能让下一条排队闹钟在无前台保障下播放。
                    dismiss(continueQueue = false)
                }
            }
        }
    }

    private fun matchesCurrentAlarm(intent: Intent): Boolean {
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        return id > 0 && (id == alarm?.id || id == loadingAlarmId)
    }

    /** 过期通知的操作不应重新启动一个永远不退出的空服务。 */
    private fun stopIfIdle(startId: Int) {
        if (alarm == null && loadingAlarmId == null && queuedFires.isEmpty()) stopSelfResult(startId)
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
    private fun startForegroundPlaceholder(alarmId: Long) {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .setData(Uri.parse("hpalarm://ring/$alarmId"))
                .putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, AlarmApp.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("闹钟即将响起…")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType())
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType())
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

    private fun dismiss(continueQueue: Boolean = true) {
        val request = pendingSnooze
        if (request != null && request.alarmId == alarm?.id) {
            // 先阻止继续响铃，待贪睡事务提交/回滚之后才能停止服务。
            request.cancelled = true
            player.stop()
            autoStopJob?.cancel()
            alarm?.let { publish(it, "正在取消贪睡…", viaHeadphone = false) }
            return
        }
        startJob?.cancel()
        startJob = null
        currentPrepared = null
        loadingAlarmId = null
        currentFire = null
        alarm = null
        autoStopJob?.cancel()
        autoStopJob = null
        player.stop()
        if (!continueQueue) queuedFires.clear()
        val next = queuedFires.pollFirst()
        if (next != null) {
            // 保留前台身份；beginRinging 同步替换旧闹钟的界面状态和通知。
            releaseWakeLock()
            beginRinging(next.request, next.prepared)
            return
        }
        releaseWakeLock()
        _state.value = null
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
            .onFailure { Log.w(TAG, "关闭响铃通知失败", it) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.w(TAG, "退出前台服务失败", it) }
        stopSelfResult(lastStartId)
    }

    private fun snooze() {
        val item = alarm ?: return dismiss()
        if (pendingSnooze != null) return
        val request = SnoozeRequest(item.id)
        pendingSnooze = request
        autoStopJob?.cancel()
        publish(item, "正在设置贪睡…", _state.value?.viaHeadphone == true)
        app.launchSnoozeTransaction {
            try {
                // 事务由 Application 持有：即使服务销毁，也要完成写盘和必要的撤销。
                val until = app.alarmOperations.snooze(item)
                if (until != null && request.cancelled) {
                    app.alarmOperations.cancelSnooze(item.id, until)
                }
                if (pendingSnooze !== request) return@launchSnoozeTransaction
                pendingSnooze = null
                if (request.cancelled) {
                    dismiss()
                } else if (until == null) {
                    publish(item, "贪睡设置失败，请重试或关闭闹钟", _state.value?.viaHeadphone == true)
                    scheduleAutoStop(item)
                    Toast.makeText(this@AlarmRingService, "贪睡设置失败，闹钟未停止", Toast.LENGTH_LONG).show()
                } else {
                    // 一次性闹钟虽已关闭，但已持久化的贪睡仍会再响。
                    notifySnoozedUntil(until)
                    dismiss()
                }
            } catch (error: Exception) {
                Log.e(TAG, "贪睡设置失败 id=${item.id}", error)
                if (pendingSnooze === request) {
                    pendingSnooze = null
                    if (request.cancelled) dismiss()
                    else {
                        publish(item, "贪睡设置失败，请重试或关闭闹钟", _state.value?.viaHeadphone == true)
                        scheduleAutoStop(item)
                        Toast.makeText(this@AlarmRingService, "贪睡设置失败，闹钟未停止", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                if (pendingSnooze === request) pendingSnooze = null
            }
        }
    }

    /** 提示「已贪睡，将于 HH:MM 再响」；界面按钮与通知按钮两个入口都会经过这里 */
    private fun notifySnoozedUntil(until: Long) {
        runCatching {
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
                .setData(Uri.parse("hpalarm://ring/${item.id}"))
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
            .addAction(0, "贪睡 ${item.snoozeMinutes} 分钟", servicePendingIntent(REQUEST_SNOOZE, ACTION_SNOOZE, item.id))
            .addAction(0, "关闭", servicePendingIntent(REQUEST_STOP, ACTION_STOP, item.id))
            .build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String, alarmId: Long): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AlarmRingService::class.java).setAction(action)
                .setData(Uri.parse("hpalarm://ring/$alarmId/$action"))
                .putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_START = "com.headphonealarm.action.RING_START"
        const val ACTION_STOP = "com.headphonealarm.action.RING_STOP"
        const val ACTION_SNOOZE = "com.headphonealarm.action.RING_SNOOZE"
        const val ACTION_BOOST = "com.headphonealarm.action.RING_BOOST"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_SNOOZE = "extra_snooze"
        const val EXTRA_SNOOZE_AT = "extra_snooze_at"

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

        /** True only if Android accepted a request to start the service. */
        fun start(context: Context, alarmId: Long, isSnooze: Boolean = false, snoozeAt: Long = 0L): Boolean {
            val intent = Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_SNOOZE, isSnooze)
                .putExtra(EXTRA_SNOOZE_AT, snoozeAt)
            // Android 12+ 后台启动前台服务受限；精确闹钟本应豁免，但部分 ROM 仍会拦截。
            val foregroundAccepted = runCatching { context.startForegroundService(intent) != null }
                .onFailure { Log.e(TAG, "startForegroundService 失败 id=$alarmId，回退 startService", it) }
                .getOrDefault(false)
            if (foregroundAccepted) {
                Log.i(TAG, "已请求启动响铃前台服务 id=$alarmId")
                return true
            }
            val fallbackAccepted = runCatching { context.startService(intent) != null }
                .onFailure { Log.e(TAG, "startService 失败 id=$alarmId", it) }
                .getOrDefault(false)
            if (!fallbackAccepted) Log.e(TAG, "系统拒绝启动响铃服务 id=$alarmId")
            return fallbackAccepted
        }

        fun stop(context: Context, alarmId: Long) {
            // 服务未运行时 startService 可能抛异常，界面已关闭即可忽略
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP)
                        .putExtra(EXTRA_ALARM_ID, alarmId)
                )
            }
        }

        fun snooze(context: Context, alarmId: Long) {
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_SNOOZE)
                        .putExtra(EXTRA_ALARM_ID, alarmId)
                )
            }
        }

        /** 响铃中用户点按“使用最大音量”：在服务内抬升媒体音量（服务已前台） */
        fun boost(context: Context, alarmId: Long) {
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_BOOST)
                        .putExtra(EXTRA_ALARM_ID, alarmId)
                )
            }
        }
    }
}
