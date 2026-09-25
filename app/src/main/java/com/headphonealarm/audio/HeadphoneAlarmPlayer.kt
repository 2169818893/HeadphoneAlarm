package com.headphonealarm.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.headphonealarm.data.AlarmItem
import com.headphonealarm.data.NoHeadphoneAction
import com.headphonealarm.util.RingtoneImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * 闹钟播放器：耳机模式仅在确认实际路由为耳机后出声；用户选择外放策略时可使用扬声器。
 *
 * 四层保障（`setPreferredDevice` 只是「偏好」，系统策略尤其蓝牙有最终决定权，所以必须层层兜底）：
 * 1. 耳机模式在播放前必须存在耳机设备（没有耳机就不产生音频流）；
 * 2. 声明 [MediaPlayer.setPreferredDevice]，并在 prepare 前后、start 前及每次路由变化后重复声明；
 * 3. 播放开始时静音，只有巡检确认实际输出是耳机后才开始出声并渐强；
 * 4. 播放中每 600ms 巡检 [MediaPlayer.getRoutedDevice]，配合插拔回调与
 *    `ACTION_AUDIO_BECOMING_NOISY`，一旦发现输出落到非耳机设备立刻静音并等待恢复。
 */
class HeadphoneAlarmPlayer(private val context: Context) {

    sealed interface State {
        /** 未播放 */
        data object Idle : State

        /** 正在等待耳机接入（静默，绝无声音） */
        data class WaitingHeadphone(val reason: String) : State

        /** 仅震动 */
        data object VibrateOnly : State

        /** 播放中 */
        data class Playing(val viaHeadphone: Boolean, val deviceName: String?) : State

        /** 播放失败 */
        data class Failed(val reason: String) : State
    }

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val detector = HeadphoneDetector(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var deviceRegistration: HeadphoneDetector.Registration? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var focusRequest: AudioFocusRequest? = null
    private var rampJob: Job? = null
    private var routeMonitorJob: Job? = null
    /** 只有实际输出设备被验证为耳机时，才允许向播放器写入非零增益。 */
    private var headphoneRouteConfirmed = false
    /** 允许扬声器是显式策略，而不是通过首选设备是否为空来推断。 */
    private var headphoneMode = false

    /** 期望的输出设备。路由变化后偏好可能失效，需要重新声明 */
    private var preferredDevice: AudioDeviceInfo? = null
    private var noisyReceiverRegistered = false

    /** 蓝牙媒体音量兜底：响铃时媒体音量过低会临时抬高，停止后恢复原值 */
    private val mediaVolumeOverride = MediaVolumeOverride()

    /** 系统在耳机/蓝牙断开前会发这个广播，提示音频即将回落到扬声器 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (mediaPlayer?.let { !muteUnsafeRoute(it) } == true) return
                if (detector.findHeadphone() == null) {
                    pauseForSafety("音频输出已断开，已自动静音")
                }
            }
        }
    }

    private var alarm: AlarmItem? = null
    private var listener: ((State) -> Unit)? = null

    /** 渐强进度回调，0f 起步、1f 达到目标音量；回主线程触发 */
    private var volumeListener: ((Float) -> Unit)? = null

    private var state: State = State.Idle

    /**
     * 开始响铃。回调会在主线程触发，用于同步通知与界面。
     *
     * @param onVolume 音量渐强进度（0~1），供界面展示爬升过程
     */
    fun start(
        alarm: AlarmItem,
        onState: (State) -> Unit,
        onVolume: (Float) -> Unit = {}
    ) {
        stopInternal(notify = false)
        this.alarm = alarm
        this.listener = onState
        this.volumeListener = onVolume

        val headphone = if (alarm.headphoneOnly) detector.findHeadphone() else null

        when {
            headphone != null -> play(alarm, headphone, enforceHeadphone = true)

            !alarm.headphoneOnly -> play(alarm, null, enforceHeadphone = false)

            alarm.noHeadphoneAction == NoHeadphoneAction.SPEAKER ->
                play(alarm, null, enforceHeadphone = false)

            alarm.noHeadphoneAction == NoHeadphoneAction.VIBRATE_ONLY -> {
                startVibration(alarm)
                emit(State.VibrateOnly)
            }

            else -> {
                startVibration(alarm)
                watchForHeadphone()
                emit(State.WaitingHeadphone("等待耳机接入，接入后自动播放"))
            }
        }
    }

    /** 停止播放并释放全部资源（震动、音频焦点、监听器） */
    fun stop() = stopInternal(notify = true)

    /**
     * 用户在响铃界面点按后调用：跳过渐强，把播放器增益直接推到目标音量。
     *
     * 媒体音量在响铃开始时已抬到系统上限；此按钮主要应对 Android 的
     * “媒体音量安全”（监听耳机实际输出分贝并自动压低，App 无法用 API 绕过），
     * 只在用户真实点击后重新抬升（相当于系统要求的那次“手动确认”）。若仍被
     * 压低，需用户到系统“声音 → 媒体音量安全/降低过大音量”里关闭该保护。
     */
    fun boostToMaxVolume() {
        val mp = mediaPlayer ?: return
        if (!safeToOutput(mp)) {
            muteUnsafeRoute(mp)
            return
        }
        if (!headphoneMode) return // 扬声器模式使用闹钟音量流，不能修改媒体流音量
        raiseMediaVolumeToMax()
        val targetGain = percentToGain((alarm?.maxVolumePercent ?: 100) / 100f)
        rampJob?.cancel()
        if (!safeToOutput(mp)) {
            muteUnsafeRoute(mp)
            return
        }
        if (setPlaybackVolume(mp, targetGain)) emitVolume(1f)
    }

    /** 服务销毁时调用，不可再次使用 */
    fun release() {
        stopInternal(notify = false)
        scope.cancel()
    }

    val currentState: State get() = state

    // region 内部实现

    private fun play(alarm: AlarmItem, device: AudioDeviceInfo?, enforceHeadphone: Boolean) {
        headphoneMode = enforceHeadphone
        if (enforceHeadphone && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // MediaPlayer 路由查询/指定输出从 API 28 才可用；早期系统无法保证不外放。
            if (alarm.vibrate) startVibration(alarm)
            emit(State.WaitingHeadphone("系统版本无法验证耳机输出，已保持静音"))
            return
        }
        // 走耳机时统一使用 USAGE_MEDIA：
        // 1. 蓝牙 A2DP 只承载媒体流，USAGE_ALARM 会被系统强制留在机身扬声器；
        // 2. 部分机型为保证响铃可靠性把闹钟流锁在扬声器，插着有线耳机也会外放；
        //    而媒体流在插入有线 / USB / 蓝牙耳机后路由到耳机是所有 ROM 的标准行为。
        // 外放模式保持 USAGE_ALARM：扬声器上闹钟流响度最大且不受媒体音量影响。
        val useMediaStream = enforceHeadphone
        val usage = if (useMediaStream) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val primaryUri = resolveUri(alarm)
        val player = runCatching { createPlayer(device, attributes, primaryUri) }
            .recoverCatching { first ->
                // 自定义铃声文件可能因备份恢复/换机/被系统清理而丢失：回退系统默认铃声，
                // 确保闹钟「一定能响」而不是静默失败（丢失文件时 setDataSource/prepare 会抛异常）。
                val fallback = defaultAlarmUri()
                if (fallback.toString() != primaryUri.toString()) {
                    Log.w(TAG, "铃声播放失败(${first.message})，回退系统默认铃声")
                    createPlayer(device, attributes, fallback)
                } else {
                    throw first
                }
            }
            .getOrElse { error ->
                emit(State.Failed(error.message ?: "音频文件无法播放"))
                return
            }

        mediaPlayer = player
        preferredDevice = device
        // start 之前最后一次声明偏好：音频通道是在 start 时真正创建的
        applyPreferredDevice(player, device)
        // 关键保护：在确认实际输出设备之前保持完全静音。
        // 万一系统的音频策略忽略了"偏好"而路由到扬声器，也不会漏出任何声音。
        runCatching { player.setVolume(0f, 0f) }
            .onFailure { error ->
                // Do not start a stream that could still be audible on the speaker.
                runCatching { player.release() }
                mediaPlayer = null
                preferredDevice = null
                emit(State.Failed(error.message ?: "无法安全设置音频音量"))
                return
            }

        runCatching { player.start() }
            .onFailure {
                runCatching { player.release() }
                mediaPlayer = null
                preferredDevice = null
                emit(State.Failed(it.message ?: "音频启动失败"))
                return
            }

        requestFocus(attributes)
        // 走媒体流时只有确认实际路由是耳机，才抬升系统媒体音量。
        if (alarm.vibrate) startVibration(alarm)

        if (enforceHeadphone) {
            emit(State.WaitingHeadphone("正在确认耳机输出，确认前保持静音"))
            attachRoutingGuard(player)
            registerNoisyReceiver()
            watchForDisconnect()
            // 由路由巡检确认后才开始出声
            startRouteMonitor(player, alarm)
        } else {
            startVolumeRamp(player, alarm)
            if (mediaPlayer === player) {
                emit(State.Playing(viaHeadphone = false, deviceName = null))
            }
        }
    }

    /**
     * 走媒体流（耳机响铃）时把媒体音量临时抬到系统最大值，停止后恢复原值。
     *
     * 原理：实际听感响度 = 系统媒体音量 × 播放器增益。媒体音量往往停在用户
     * 平时听歌的水平（可能只有一半），若不抬满，渐强爬得再高也会被它封顶，
     * 「上限 50% 以上」的设置永远无法实现。抬满之后，响度完全由播放器增益
     * （即音量上限百分比，已按 dB 感知映射）决定，跨设备一致且渐强全程有效。
     */
    private fun raiseMediaVolumeToMax() {
        val route = connectedRouteId() ?: return
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            mediaVolumeOverride.observe(current, route)
            if (current >= max) return
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            // Android may silently clamp a safe-media-volume write. Track only its actual
            // effect; never restore a failed write or a volume set on a different route.
            if (connectedRouteId() == route) {
                val after = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                mediaVolumeOverride.recordIncrease(current, after, route)
            }
        }.onFailure { Log.w(TAG, "抬升媒体音量被系统拒绝", it) }
    }

    private fun restoreMediaVolume() {
        val route = connectedRouteId()
        val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val original = mediaVolumeOverride.takeRestoration(current, route) ?: return
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        }.onFailure { Log.w(TAG, "恢复媒体音量失败", it) }
    }

    private fun connectedRouteId(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
        mediaPlayer?.routedDevice?.takeIf(detector::isConnectedHeadphone)?.id
        }.getOrNull()
    }

    private fun createPlayer(
        device: AudioDeviceInfo?,
        attributes: AudioAttributes,
        uri: Uri
    ): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.isLooping = true
            applyPreferredDevice(player, device)
            player.setDataSource(context, uri)
            player.prepare()
            // setDataSource / prepare 会重建底层音频通道，偏好必须重新声明一次
            applyPreferredDevice(player, device)
            return player
        } catch (t: Throwable) {
            // 失败时释放半初始化的 MediaPlayer，避免回退重试时泄漏原生资源
            runCatching { player.release() }
            throw t
        }
    }

    /**
     * 声明首选输出设备。
     *
     * 注意这只是「偏好」而非「强制」：系统音频策略（尤其蓝牙）有最终决定权，
     * 因此必须配合 [startRouteMonitor] 校验实际路由。
     */
    private fun applyPreferredDevice(player: MediaPlayer, device: AudioDeviceInfo?) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { player.setPreferredDevice(device) }
    }

    private fun resolveUri(alarm: AlarmItem): Uri =
        alarm.ringtoneUri?.takeIf { it.isNotBlank() }?.let {
            RingtoneImporter.resolveOwnedUri(context, it) ?: Uri.parse(it)
        } ?: defaultAlarmUri()

    /** 系统默认闹钟铃声，多级回退保证非空 */
    private fun defaultAlarmUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI

    /**
     * 音量由小到大缓慢爬升，保护听力。
     *
     * 关键点：人耳感知的音量近似与振幅的**对数**成正比，若按振幅线性增长，
     * 前几秒会突然变响、之后又几乎听不出变化。因此这里用指数插值
     * `v(t) = start · (target/start)^(t/T)`，让分贝值匀速上升，
     * 听感上才是真正平缓的「从小到大」。
     */
    private fun startVolumeRamp(player: MediaPlayer, alarm: AlarmItem) {
        rampJob?.cancel()

        // 百分比必须先经 percentToGain 换算成线性增益，不能直接当 setVolume 用
        val targetGain = percentToGain(alarm.maxVolumePercent / 100f)
        val rampSeconds = alarm.volumeRampSeconds.coerceIn(0, MAX_RAMP_SECONDS)
        val fromGain = percentToGain(alarm.rampStartPercent / 100f).coerceAtMost(targetGain)

        if (rampSeconds == 0 || fromGain >= targetGain) {
            if (!safeToOutput(player)) return
            if (setPlaybackVolume(player, targetGain)) emitVolume(1f)
            return
        }

        if (!safeToOutput(player)) return
        if (!setPlaybackVolume(player, fromGain)) return
        emitVolume(0f)

        rampJob = scope.launch {
            val steps = rampSeconds * STEPS_PER_SECOND
            val ratio = targetGain / fromGain
            repeat(steps) { index ->
                delay(STEP_INTERVAL_MS)
                if (!safeToOutput(player)) {
                    muteUnsafeRoute(player)
                    return@launch
                }
                val progress = (index + 1).toFloat() / steps
                val volume = fromGain * ratio.pow(progress)
                if (!setPlaybackVolume(player, volume)) return@launch
                emitVolume(progress)
            }
            if (!safeToOutput(player)) return@launch
            if (setPlaybackVolume(player, targetGain)) emitVolume(1f)
        }
    }

    /** A failed gain change leaves the previous (possibly audible) gain in place. */
    private fun setPlaybackVolume(player: MediaPlayer, gain: Float): Boolean {
        if (mediaPlayer !== player) return false
        return runCatching { player.setVolume(gain, gain) }.fold(
            onSuccess = { true },
            onFailure = { error ->
                Log.e(TAG, "播放音量设置失败，销毁音频通道", error)
                if (headphoneMode) {
                    pauseForSafety("无法安全设置音量，已停止音频播放；请关闭闹钟并检查设备", retryOnConnect = false)
                } else {
                    stopInternal(notify = false)
                    emit(State.Failed("无法设置播放音量，已停止播放"))
                }
                false
            }
        )
    }

    /**
     * 感知音量百分比（0~1）→ MediaPlayer.setVolume 的线性增益。
     *
     * 原理：`setVolume` 是线性振幅缩放，把「20%」直接当 0.2 用只衰减约 14dB，
     * 听感依然非常响；而人耳响度感知与 dB 近似线性，系统音量条的 20% 实际
     * 对应约 -30dB。因此按 dB 换算：`gain = 10^((p-1)·RANGE_DB/20)`，
     * RANGE_DB 取 40dB 与主流 ROM 音量曲线跨度一致，使「上限 20%」的
     * 听感与系统音量条拉到 20% 相当。
     */
    private fun percentToGain(percent: Float): Float =
        10f.pow((percent.coerceIn(MIN_VOLUME_PERCENT, 1f) - 1f) * VOLUME_RANGE_DB / 20f)

    private fun emitVolume(progress: Float) {
        volumeListener?.invoke(progress)
    }

    private fun safeToOutput(player: MediaPlayer): Boolean =
        mediaPlayer === player && (!headphoneMode || (headphoneRouteConfirmed &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            detector.isConnectedHeadphone(runCatching { player.routedDevice }.getOrNull())))

    /**
     * 持续校验实际输出设备。
     *
     * 不能只在开始时检查一次，因为：
     * 1. 蓝牙 A2DP 建立链路有延迟，首帧可能还没切过去；
     * 2. 系统在耳机/蓝牙断开时会把音频回落到扬声器；
     * 3. `getRoutedDevice()` 在音频通道未完全激活时会返回 null。
     *
     * 任何非耳机/未知路由都立即静音；只有确认为耳机才可渐强。宁可部分
     * 不报告路由的设备保持静默，也不能仅凭「耳机在线」猜测音频实际走向。
     */
    private fun startRouteMonitor(player: MediaPlayer, alarm: AlarmItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        routeMonitorJob?.cancel()
        routeMonitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var offHeadphoneStreak = 0
            while (isActive) {
                delay(ROUTE_CHECK_INTERVAL_MS)
                val routed = runCatching { player.routedDevice }.getOrNull()
                when {
                    detector.isConnectedHeadphone(routed) -> {
                        offHeadphoneStreak = 0
                        confirmHeadphoneRoute(player, alarm)
                        // 重路由后偏好会丢失，周期性重新声明
                        applyPreferredDevice(player, preferredDevice)
                    }

                    routed == null -> {
                        if (!muteUnsafeRoute(player)) return@launch
                        if (detector.findHeadphone() == null) {
                            pauseForSafety("耳机已断开，无法确认输出，已自动静音")
                            return@launch
                        }
                        if (System.currentTimeMillis() - startedAt > ROUTE_GRACE_MS) {
                            emit(State.WaitingHeadphone("无法确认耳机输出，保持静音并等待路由恢复"))
                        }
                    }

                    else -> {
                        // 即使蓝牙短暂抖动，也只能宽限状态提示，不能宽限出声。
                        if (!muteUnsafeRoute(player)) return@launch
                        offHeadphoneStreak++
                        if (detector.findHeadphone() == null) {
                            pauseForSafety("耳机已断开，已自动静音")
                            return@launch
                        } else if (offHeadphoneStreak >= OFF_HEADPHONE_STREAK_LIMIT &&
                            System.currentTimeMillis() - startedAt > ROUTE_GRACE_MS
                        ) {
                            emit(State.WaitingHeadphone("音频走扬声器，保持静音并等待路由恢复"))
                            applyPreferredDevice(player, preferredDevice)
                        } else {
                            applyPreferredDevice(player, preferredDevice)
                        }
                    }
                }
            }
        }
    }

    private fun attachRoutingGuard(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val listener = AudioRouting.OnRoutingChangedListener { router ->
            if (mediaPlayer !== player) return@OnRoutingChangedListener
            val routed = runCatching { router.routedDevice }.getOrNull()
            if (!detector.isConnectedHeadphone(routed)) {
                if (!muteUnsafeRoute(player)) return@OnRoutingChangedListener
            } else {
                alarm?.let { confirmHeadphoneRoute(player, it) }
                applyPreferredDevice(player, preferredDevice)
            }
        }
        runCatching { player.addOnRoutingChangedListener(listener, mainHandler) }
            .onSuccess { routingListener = listener }
    }

    /** 静音失败时立即销毁音频通道，而非让之前的增益在扬声器上继续播放。 */
    private fun muteUnsafeRoute(player: MediaPlayer): Boolean {
        if (mediaPlayer !== player) return false
        headphoneRouteConfirmed = false
        rampJob?.cancel()
        runCatching { player.setVolume(0f, 0f) }
            .onFailure { error ->
                Log.e(TAG, "无法安全静音，销毁音频通道", error)
                pauseForSafety("无法安全静音，已停止音频播放；请关闭闹钟并检查设备", retryOnConnect = false)
                return false
            }
        emitVolume(0f)
        if (state is State.Playing) {
            emit(State.WaitingHeadphone("耳机路由中断，已静音并等待恢复"))
        }
        return true
    }

    private fun confirmHeadphoneRoute(player: MediaPlayer, alarm: AlarmItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            headphoneRouteConfirmed || mediaPlayer !== player ||
            !detector.isConnectedHeadphone(runCatching { player.routedDevice }.getOrNull())) return
        headphoneRouteConfirmed = true
        raiseMediaVolumeToMax()
        startVolumeRamp(player, alarm)
        if (mediaPlayer !== player) return
        emit(State.Playing(viaHeadphone = true, deviceName = preferredDevice?.productName?.toString()))
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onSuccess { noisyReceiverRegistered = true }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
    }

    /** 监听耳机插拔：拔掉就静音，插回自动恢复 */
    private fun watchForDisconnect() {
        deviceRegistration?.release()
        deviceRegistration = detector.register(object : HeadphoneDetector.Callback {
            override fun onHeadphoneConnected() {
                val replacement = detector.findHeadphone() ?: return
                if (preferredDevice?.id != replacement.id) {
                    // An added wired headset can supersede Bluetooth, or a disconnected
                    // headset can reconnect before the removal callback reaches us. The old
                    // preference may no longer be usable: mute until the new route is verified.
                    if (mediaPlayer?.let { !muteUnsafeRoute(it) } == true) return
                    preferredDevice = replacement
                    mediaPlayer?.let { applyPreferredDevice(it, replacement) }
                }
            }

            override fun onHeadphoneDisconnected() {
                if (mediaPlayer?.let { !muteUnsafeRoute(it) } == true) return
                val replacement = detector.findHeadphone()
                if (replacement == null) {
                    pauseForSafety("耳机已拔出，等待重新接入")
                } else {
                    preferredDevice = replacement
                    mediaPlayer?.let { applyPreferredDevice(it, replacement) }
                }
            }
        })
    }

    private fun watchForHeadphone() {
        deviceRegistration?.release()
        deviceRegistration = detector.register(object : HeadphoneDetector.Callback {
            override fun onHeadphoneConnected() {
                val current = alarm ?: return
                val device = detector.findHeadphone() ?: return
                if (state is State.Playing) return
                play(current, device, enforceHeadphone = true)
            }
        })
    }

    /** 安全暂停：销毁音频通道但保留震动；仅正常断线时等待重新接入。 */
    private fun pauseForSafety(reason: String, retryOnConnect: Boolean = true) {
        // 不能递归调用 muteUnsafeRoute：它的失败路径会调用 pauseForSafety。
        runCatching { mediaPlayer?.setVolume(0f, 0f) }
        restoreMediaVolume()
        rampJob?.cancel()
        routeMonitorJob?.cancel()
        releaseRoutingListener()
        unregisterNoisyReceiver()
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        preferredDevice = null
        headphoneRouteConfirmed = false
        abandonFocus()
        deviceRegistration?.release()
        deviceRegistration = null
        if (retryOnConnect) {
            watchForHeadphone()
            emit(State.WaitingHeadphone(reason))
        } else {
            // 注册监听时 Android 会立即回调当前已连接设备；音频增益故障不能
            // 借这个初始回调不断创建新播放器，直到设备反复失败/耗尽资源。
            emit(State.Failed(reason))
        }
    }

    private fun startVibration(alarm: AlarmItem) {
        if (!alarm.vibrate) return
        val device = obtainVibrator() ?: return
        vibrator = device
        runCatching {
            device.vibrate(
                VibrationEffect.createWaveform(VIBRATION_PATTERN, /* repeat = */ 0)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun obtainVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun requestFocus(attributes: AudioAttributes) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun releaseRoutingListener() {
        val listener = routingListener ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { mediaPlayer?.removeOnRoutingChangedListener(listener) }
        }
        routingListener = null
    }

    private fun stopInternal(notify: Boolean) {
        // 直接清理，不在静音失败后重新注册耳机监听。
        runCatching { mediaPlayer?.setVolume(0f, 0f) }
        restoreMediaVolume()
        rampJob?.cancel()
        routeMonitorJob?.cancel()
        releaseRoutingListener()
        unregisterNoisyReceiver()
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        preferredDevice = null
        headphoneRouteConfirmed = false
        headphoneMode = false
        deviceRegistration?.release()
        deviceRegistration = null
        abandonFocus()
        runCatching { vibrator?.cancel() }
        vibrator = null
        alarm = null
        volumeListener = null
        if (notify) emit(State.Idle) else state = State.Idle
    }

    private fun emit(newState: State) {
        state = newState
        listener?.invoke(newState)
    }

    // endregion

    private companion object {
        private const val TAG = "HeadphoneAlarmPlayer"

        /** 音量百分比下限（1%），经 dB 映射后约 -40dB，保证可闻又不惊扰 */
        const val MIN_VOLUME_PERCENT = 0.01f

        /** 感知音量曲线的总 dB 跨度：100% → 0dB，1% → -40dB */
        const val VOLUME_RANGE_DB = 40f

        const val MAX_RAMP_SECONDS = 120
        const val STEPS_PER_SECOND = 20
        const val STEP_INTERVAL_MS = 50L

        /** 路由巡检间隔：足够快，能赶在用户被扬声器吵到之前静音 */
        const val ROUTE_CHECK_INTERVAL_MS = 600L

        /** 蓝牙链路建立需要时间，这段时间内允许路由尚未确认 */
        const val ROUTE_GRACE_MS = 4_000L

        /** 连续多少次巡检都落在非耳机才判定为“确实外放”，用于避开蓝牙唤醒抖动 */
        const val OFF_HEADPHONE_STREAK_LIMIT = 4

        val VIBRATION_PATTERN = longArrayOf(0L, 700L, 500L, 700L, 900L)
    }
}
