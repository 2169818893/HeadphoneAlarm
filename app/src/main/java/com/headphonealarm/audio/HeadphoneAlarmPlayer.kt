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
import androidx.core.content.ContextCompat
import com.headphonealarm.data.AlarmItem
import com.headphonealarm.data.NoHeadphoneAction
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
 * 闹钟播放器：**只在耳机上出声**，从原理上避免外放到扬声器。
 *
 * 四层保障（`setPreferredDevice` 只是「偏好」，系统策略尤其蓝牙有最终决定权，所以必须层层兜底）：
 * 1. 播放前必须存在耳机设备才会启动 MediaPlayer（无耳机就完全不产生音频流）；
 * 2. API 28+ 调用 [MediaPlayer.setPreferredDevice]，并在 prepare 前后、start 前及每次路由变化后重复声明；
 * 3. 播放开始时静音，只有巡检确认实际输出是耳机后才开始出声并渐强；
 * 4. 播放中每 600ms 巡检 [MediaPlayer.getRoutedDevice]，配合插拔回调与
 *    `ACTION_AUDIO_BECOMING_NOISY`，一旦发现输出落到非耳机设备立刻暂停。
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

    /** 期望的输出设备。路由变化后偏好可能失效，需要重新声明 */
    private var preferredDevice: AudioDeviceInfo? = null
    private var noisyReceiverRegistered = false

    /** 蓝牙媒体音量兜底：响铃时媒体音量过低会临时抬高，停止后恢复原值 */
    private var savedMusicVolume = -1
    private var mediaVolumeRaised = false

    /** 系统在耳机/蓝牙断开前会发这个广播，提示音频即将回落到扬声器 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseForSafety("音频输出已断开，已自动静音")
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
     * 用户在响铃界面点按后调用：尽力把媒体音量抬到系统上限，并把播放器增益直接推到目标。
     *
     * 说明：Android 的“媒体音量安全”会监听耳机实际输出分贝并自动压低，App 无法用 API 绕过；
     * 这里只在用户真实点击后重新抬升（相当于系统要求的那次“手动确认”）。若仍被压低，
     * 需用户到系统“声音 → 媒体音量安全/降低过大音量”里关闭该保护。
     */
    fun boostToMaxVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        if (savedMusicVolume < 0) {
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
        mediaVolumeRaised = true
        mediaPlayer?.let { mp ->
            val target = ((alarm?.maxVolumePercent ?: 100) / 100f).coerceIn(MIN_VOLUME, 1f)
            rampJob?.cancel()
            runCatching { mp.setVolume(target, target) }
            emitVolume(1f)
        }
    }

    /** 服务销毁时调用，不可再次使用 */
    fun release() {
        stopInternal(notify = false)
        scope.cancel()
    }

    val currentState: State get() = state

    // region 内部实现

    private fun play(alarm: AlarmItem, device: AudioDeviceInfo?, enforceHeadphone: Boolean) {
        val viaBluetooth = isBluetooth(device)
        // 蓝牙 A2DP 只承载媒体流：USAGE_ALARM 会被系统强制留在机身扬声器，
        // 导致耳机无声 + 误判为“未走耳机”而反复静音。目标为蓝牙时改用 USAGE_MEDIA。
        val usage = if (viaBluetooth) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val player = runCatching { createPlayer(alarm, device, attributes) }
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

        runCatching { player.start() }
            .onFailure {
                emit(State.Failed(it.message ?: "音频启动失败"))
                return
            }

        requestFocus(attributes)
        // 蓝牙走媒体流：媒体音量几乎为 0 时耳机听不见，响铃前临时抬到下限
        if (viaBluetooth) ensureMediaVolume()
        if (alarm.vibrate) startVibration(alarm)

        if (enforceHeadphone) {
            attachRoutingGuard(player)
            registerNoisyReceiver()
            watchForDisconnect()
            // 由路由巡检确认后才开始出声
            startRouteMonitor(player, alarm)
        } else {
            startVolumeRamp(player, alarm)
        }

        emit(State.Playing(viaHeadphone = enforceHeadphone, deviceName = device?.productName?.toString()))
    }

    /** 目标输出是否为蓝牙设备：蓝牙 A2DP 只承载媒体流，需据此改变音频 usage */
    private fun isBluetooth(device: AudioDeviceInfo?): Boolean {
        val type = device?.type ?: return false
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** 走媒体流时，媒体音量过低会导致耳机几乎无声，临时抬到下限，停止后恢复 */
    private fun ensureMediaVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val floor = (max * MUSIC_VOLUME_FLOOR).toInt().coerceAtLeast(1)
        if (current < floor) {
            savedMusicVolume = current
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0) }
            mediaVolumeRaised = true
        }
    }

    private fun restoreMediaVolume() {
        if (!mediaVolumeRaised) return
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC, savedMusicVolume.coerceAtLeast(0), 0
            )
        }
        mediaVolumeRaised = false
        savedMusicVolume = -1
    }

    private fun createPlayer(
        alarm: AlarmItem,
        device: AudioDeviceInfo?,
        attributes: AudioAttributes
    ): MediaPlayer {
        val player = MediaPlayer()
        player.setAudioAttributes(attributes)
        player.isLooping = true
        applyPreferredDevice(player, device)
        player.setDataSource(context, resolveUri(alarm))
        player.prepare()
        // setDataSource / prepare 会重建底层音频通道，偏好必须重新声明一次
        applyPreferredDevice(player, device)
        return player
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

    private fun resolveUri(alarm: AlarmItem): Uri {
        alarm.ringtoneUri?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

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

        val target = (alarm.maxVolumePercent / 100f).coerceIn(MIN_VOLUME, 1f)
        val rampSeconds = alarm.volumeRampSeconds.coerceIn(0, MAX_RAMP_SECONDS)
        val from = (alarm.rampStartPercent / 100f).coerceIn(MIN_VOLUME, target)

        if (rampSeconds == 0 || from >= target) {
            runCatching { player.setVolume(target, target) }
            emitVolume(1f)
            return
        }

        runCatching { player.setVolume(from, from) }
        emitVolume(0f)

        rampJob = scope.launch {
            val steps = rampSeconds * STEPS_PER_SECOND
            val ratio = target / from
            repeat(steps) { index ->
                delay(STEP_INTERVAL_MS)
                val progress = (index + 1).toFloat() / steps
                val volume = from * ratio.pow(progress)
                runCatching { player.setVolume(volume, volume) }
                emitVolume(progress)
            }
            runCatching { player.setVolume(target, target) }
            emitVolume(1f)
        }
    }

    private fun emitVolume(progress: Float) {
        volumeListener?.invoke(progress)
    }

    /**
     * 持续校验实际输出设备。
     *
     * 不能只在开始时检查一次，因为：
     * 1. 蓝牙 A2DP 建立链路有延迟，首帧可能还没切过去；
     * 2. 系统在耳机/蓝牙断开时会把音频回落到扬声器；
     * 3. `getRoutedDevice()` 在音频通道未完全激活时会返回 null。
     *
     * 因此：输出确认落到非耳机 → 立即静音；长时间拿不到路由信息但耳机仍在线 →
     * 信任已声明的首选设备开始出声（避免部分机型「插着耳机也永不响」）；
     * 耳机已消失 → 静音。真正的扬声器外放由「非耳机路由」与插拔/noisy 广播兜底拦截。
     *
     * 另外，播放开始时音量是 0，**只有在首次确认路由为耳机之后才开始出声**，
     * 这样即使系统忽略了偏好，也不会有一瞬间的外放。
     */
    private fun startRouteMonitor(player: MediaPlayer, alarm: AlarmItem) {
        routeMonitorJob?.cancel()
        routeMonitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var confirmed = false
            var offHeadphoneStreak = 0
            while (isActive) {
                delay(ROUTE_CHECK_INTERVAL_MS)
                val routed = runCatching { player.routedDevice }.getOrNull()
                when {
                    detector.isHeadphone(routed) -> {
                        offHeadphoneStreak = 0
                        if (!confirmed) {
                            confirmed = true
                            startVolumeRamp(player, alarm)
                        }
                        // 重路由后偏好会丢失，周期性重新声明
                        applyPreferredDevice(player, preferredDevice)
                    }

                    routed == null -> {
                        // 拿不到路由（蓝牙建链中很常见）：宽限期后只要耳机仍在线就信任首选设备出声，
                        // 只有耳机确实消失才判定为不安全并静音。
                        if (!confirmed && System.currentTimeMillis() - startedAt > ROUTE_GRACE_MS) {
                            if (detector.findHeadphone() != null) {
                                confirmed = true
                                startVolumeRamp(player, alarm)
                            } else {
                                pauseForSafety("耳机已断开，无法确认输出，已自动静音")
                                return@launch
                            }
                        }
                    }

                    else -> {
                        // 明确路由到非耳机（扬声器）。蓝牙唤醒会有瞬时抖动，
                        // 连续多次确认才静音，避免误杀导致提示闪烁。
                        offHeadphoneStreak++
                        if (detector.findHeadphone() == null) {
                            pauseForSafety("耳机已断开，已自动静音")
                            return@launch
                        } else if (offHeadphoneStreak >= OFF_HEADPHONE_STREAK_LIMIT &&
                            System.currentTimeMillis() - startedAt > ROUTE_GRACE_MS
                        ) {
                            // 耳机在，但音频持续走扬声器：确实无法路由到耳机，为“绝不外放”而静音
                            pauseForSafety("音频持续走扬声器，已自动静音（请检查蓝牙耳机连接）")
                            return@launch
                        } else {
                            applyPreferredDevice(player, preferredDevice)
                        }
                    }
                }
            }
        }
    }

    private fun attachRoutingGuard(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val listener = AudioRouting.OnRoutingChangedListener { router ->
            // 路由事件多为蓝牙唤醒/临时回落扬声器的瞬时抖动，不在此直接判“外放”，
            // 只重新声明首选设备；是否静音交给 startRouteMonitor 的稳定判定，避免提示闪烁。
            val routed = runCatching { router.routedDevice }.getOrNull()
            if (routed == null || detector.isHeadphone(routed)) {
                applyPreferredDevice(player, preferredDevice)
            }
        }
        runCatching { player.addOnRoutingChangedListener(listener, mainHandler) }
            .onSuccess { routingListener = listener }
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
            override fun onHeadphoneConnected() = Unit

            override fun onHeadphoneDisconnected() {
                if (detector.findHeadphone() == null) {
                    pauseForSafety("耳机已拔出，等待重新接入")
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

    /** 安全暂停：销毁音频通道但保留震动与耳机监听 */
    private fun pauseForSafety(reason: String) {
        rampJob?.cancel()
        routeMonitorJob?.cancel()
        releaseRoutingListener()
        unregisterNoisyReceiver()
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        preferredDevice = null
        abandonFocus()
        watchForHeadphone()
        emit(State.WaitingHeadphone(reason))
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
        routingListener?.let { listener ->
            runCatching { mediaPlayer?.removeOnRoutingChangedListener(listener) }
        }
        routingListener = null
    }

    private fun stopInternal(notify: Boolean) {
        rampJob?.cancel()
        routeMonitorJob?.cancel()
        releaseRoutingListener()
        unregisterNoisyReceiver()
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        preferredDevice = null
        deviceRegistration?.release()
        deviceRegistration = null
        abandonFocus()
        restoreMediaVolume()
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
        /** 音量下限，约 -46dB，保证可闻又不惊扰 */
        const val MIN_VOLUME = 0.005f
        const val MAX_RAMP_SECONDS = 120
        const val STEPS_PER_SECOND = 20
        const val STEP_INTERVAL_MS = 50L

        /** 路由巡检间隔：足够快，能赶在用户被扬声器吵到之前静音 */
        const val ROUTE_CHECK_INTERVAL_MS = 600L

        /** 蓝牙链路建立需要时间，这段时间内允许路由尚未确认 */
        const val ROUTE_GRACE_MS = 4_000L

        /** 连续多少次巡检都落在非耳机才判定为“确实外放”，用于避开蓝牙唤醒抖动 */
        const val OFF_HEADPHONE_STREAK_LIMIT = 4

        /** 走媒体流时的媒体音量下限（占最大音量的比例） */
        const val MUSIC_VOLUME_FLOOR = 0.7f
        val VIBRATION_PATTERN = longArrayOf(0L, 700L, 500L, 700L, 900L)
    }
}
