package com.headphonealarm.ui.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.headphonealarm.alarmApp
import com.headphonealarm.service.AlarmRingService
import com.headphonealarm.ui.theme.AppTheme
import com.headphonealarm.ui.theme.HeadphoneAlarmTheme
import com.headphonealarm.ui.theme.LocalAppBackdrop
import kotlinx.coroutines.delay

/**
 * 响铃全屏界面。锁屏状态下直接展示，`singleInstance` 保证同一时间只有一个实例。
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showOverLockScreen()

        setContent {
            val themeKey by alarmApp.settingsRepository.themeKey.collectAsStateWithLifecycle(AppTheme.DEFAULT.key)
            HeadphoneAlarmTheme(theme = AppTheme.fromKey(themeKey)) {
                val ringState by AlarmRingService.state.collectAsStateWithLifecycle()
                val backdrop = LocalAppBackdrop.current
                var hasShownRing by remember { mutableStateOf(false) }
                var startupTimedOut by remember { mutableStateOf(false) }
                LaunchedEffect(ringState) {
                    if (ringState != null) hasShownRing = true
                    else if (hasShownRing) finish()
                }
                LaunchedEffect(ringState == null, hasShownRing) {
                    startupTimedOut = false
                    if (ringState == null && !hasShownRing) {
                        // The system may accept a start request without ever starting the service.
                        // Give the user a way out without automatically dismissing a late alarm.
                        delay(30_000L)
                        startupTimedOut = true
                    }
                }

                // 闹钟结束后自动退出，避免留下空白页
                if (ringState == null) {
                    BackHandler { }
                } else {
                    BackHandler { /* 响铃中屏蔽返回键，必须显式操作 */ }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(backdrop.ringGradient))
                ) {
                    val current = ringState
                    if (current == null) {
                        EmptyRingPlaceholder(
                            message = when {
                                hasShownRing -> "闹钟已结束"
                                startupTimedOut -> "等待闹钟服务超时，请检查系统后台启动限制"
                                else -> "正在准备闹钟…"
                            },
                            onClose = if (hasShownRing || startupTimedOut) ({ finish() }) else null
                        )
                    } else if (!current.actionsEnabled) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = current.statusText,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        RingContent(
                            alarmId = current.alarmId,
                            timeText = current.timeText,
                            label = current.label,
                            statusText = current.statusText,
                            viaHeadphone = current.viaHeadphone,
                            snoozeMinutes = current.snoozeMinutes,
                            volumeProgress = current.volumeProgress,
                            rampSeconds = current.rampSeconds,
                            maxVolumePercent = current.maxVolumePercent,
                            onSnooze = {
                                AlarmRingService.snooze(this@AlarmRingActivity, current.alarmId)
                                // Only the service knows whether AlarmManager accepted the snooze.
                                // On failure it keeps ringing and publishes a retryable error here.
                            },
                            onStop = {
                                AlarmRingService.stop(this@AlarmRingActivity, current.alarmId)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        showOverLockScreen()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // setShowWhenLocked 已让本界面显示在锁屏之上并可交互（贪睡/关闭无需先解锁）。
            // 不再调用 requestDismissKeyguard：设了 PIN/图案/指纹的设备上它会弹出解锁验证
            // 界面盖住闹钟 UI，导致「锁屏看不到闹钟、必须先解锁」。
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /**
         * 由 [com.headphonealarm.receiver.AlarmReceiver] 直接拉起全屏响铃界面。
         *
         * 作为前台服务 fullScreenIntent 的兜底：部分 ROM（尤其小米/HyperOS）会拦截
         * 通知的全屏意图，但精确闹钟触发的广播允许直接从后台启动 Activity。
         */
        fun launchFromAlarm(context: Context, alarmId: Long) {
            runCatching {
                val intent = Intent(context, AlarmRingActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    .putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
                context.startActivity(intent)
            }
        }
    }
}

@Composable
private fun RingContent(
    alarmId: Long,
    timeText: String,
    label: String,
    statusText: String,
    viaHeadphone: Boolean,
    snoozeMinutes: Int,
    volumeProgress: Float,
    rampSeconds: Int,
    maxVolumePercent: Int,
    onSnooze: () -> Unit,
    onStop: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ring")
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(pulse)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha), Color.Transparent),
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (viaHeadphone) Icons.Filled.Headphones else Icons.Filled.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = timeText,
            fontSize = 76.sp,
            lineHeight = 84.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (label.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = if (viaHeadphone) Icons.Filled.Headphones else Icons.Filled.Speaker,
                contentDescription = null,
                tint = if (viaHeadphone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (viaHeadphone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        if (rampSeconds > 0) {
            Spacer(Modifier.height(28.dp))
            VolumeRampIndicator(
                progress = volumeProgress,
                rampSeconds = rampSeconds,
                maxVolumePercent = maxVolumePercent
            )
        }

        Spacer(Modifier.height(44.dp))

        Button(
            onClick = onSnooze,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            )
        ) {
            Icon(Icons.Filled.Snooze, contentDescription = null)
            Spacer(Modifier.size(10.dp))
            Text("贪睡 $snoozeMinutes 分钟", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.Filled.AlarmOff, contentDescription = null)
            Spacer(Modifier.size(10.dp))
            Text("关闭闹钟", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = if (viaHeadphone) "声音仅在耳机中播放" else "请注意当前音频输出状态",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 系统“媒体音量安全”会在耳机输出过大时自动压低；以下两个入口分别应对“本次强制拉满”与“永久关闭”
        Spacer(Modifier.height(2.dp))
        val ctx = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { AlarmRingService.boost(ctx, alarmId) }) {
                Text(
                    "音量偏小？点按用最大",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Text(
                    "关闭系统限音量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 音量爬升进度条：直观展示「还有多久到达目标音量」 */
@Composable
private fun VolumeRampIndicator(
    progress: Float,
    rampSeconds: Int,
    maxVolumePercent: Int
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240),
        label = "volumeRamp"
    )
    val finished = progress >= 1f
    val remaining = (rampSeconds * (1f - progress)).toInt().coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxWidth(0.82f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (finished) "音量渐强完成" else "音量渐强中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (finished) {
                    "已到 $maxVolumePercent%"
                } else {
                    "${remaining}s 后到 $maxVolumePercent%"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)))
            )
        }
    }
}

@Composable
private fun EmptyRingPlaceholder(message: String, onClose: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (onClose != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("关闭")
            }
        }
    }
}
