package com.headphonealarm.ui.list

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.headphonealarm.AlarmApp
import com.headphonealarm.audio.HeadphoneAlarmPlayer
import com.headphonealarm.audio.HeadphoneDetector
import com.headphonealarm.data.AlarmItem
import com.headphonealarm.data.NoHeadphoneAction
import com.headphonealarm.ui.components.GlassCard
import com.headphonealarm.ui.components.SectionTitle
import com.headphonealarm.ui.components.StatusPill
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.headphonealarm.ui.theme.AppTheme
import kotlinx.coroutines.flow.map
import com.headphonealarm.util.AlarmPermissions
import com.headphonealarm.util.TimeUtils
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun AlarmListScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    viewModel: AlarmListViewModel = viewModel()
) {
    val context = LocalContext.current
    val alarmState by viewModel.alarmState.collectAsStateWithLifecycle()
    val alarms = (alarmState as? AlarmLoadState.Ready)?.alarms.orEmpty()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val failedScheduleIds by viewModel.failedScheduleIds.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val now = rememberTickingNow()
    val headphone = rememberHeadphoneConnected()
    val deviceSummary = rememberAudioOutputSummary()
    val isXiaomi = remember { AlarmPermissions.isXiaomi() }
    var refreshKey by remember { mutableIntStateOf(0) }

    val exactAlarmGranted = remember(refreshKey) { AlarmPermissions.canScheduleExactAlarms(context) }
    val notificationGranted = remember(refreshKey) { AlarmPermissions.hasNotificationPermission(context) }
    val fullScreenGranted = remember(refreshKey) { AlarmPermissions.canUseFullScreenIntent(context) }
    val batteryOptimized = remember(refreshKey) {
        !AlarmPermissions.isIgnoringBatteryOptimizations(context)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

    LaunchedEffect(Unit) {
        if (!AlarmPermissions.hasNotificationPermission(context)) {
            val permissions = AlarmPermissions.runtimePermissions()
            if (permissions.isNotEmpty()) {
                notificationLauncher.launch(permissions.first())
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                viewModel.rescheduleAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val loaded = alarmState is AlarmLoadState.Ready
    val scheduledAlarms = alarms.filter { it.nextScheduledTime(now) != null }
    val nextTrigger = scheduledAlarms.mapNotNull { it.nextScheduledTime(now) }.minOrNull()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (loaded) {
                ExtendedFloatingActionButton(
                    onClick = onAddAlarm,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("新建闹钟", fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = scaffoldPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 24.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header(headphone, deviceSummary) }

            when (val current = alarmState) {
                is AlarmLoadState.Ready -> item {
                    NextAlarmCard(nextTrigger, now, scheduledAlarms.size)
                }
                AlarmLoadState.Loading -> item {
                    GlassCard { Text("正在读取闹钟…") }
                }
                is AlarmLoadState.Error -> item {
                    GlassCard {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retryLoading) { Text("重试读取") }
                    }
                }
            }

            if (!exactAlarmGranted || !notificationGranted || !fullScreenGranted || batteryOptimized) {
                item {
                    PermissionNotice(
                        exactAlarmGranted = exactAlarmGranted,
                        notificationGranted = notificationGranted,
                        fullScreenGranted = fullScreenGranted,
                        batteryOptimized = batteryOptimized,
                        onFixExact = {
                            AlarmPermissions.openSettingsSafely(
                                context,
                                AlarmPermissions.exactAlarmSettingsIntent(context)
                            )
                        },
                        onFixNotification = {
                            AlarmPermissions.notificationPermission()?.let(notificationLauncher::launch)
                        },
                        onFixFullScreen = {
                            AlarmPermissions.openSettingsSafely(
                                context,
                                AlarmPermissions.fullScreenIntentSettingsIntent(context)
                            )
                        },
                        onFixBattery = {
                            AlarmPermissions.openSettingsSafely(
                                context,
                                AlarmPermissions.batteryOptimizationIntent(context)
                            )
                        }
                    )
                }
            }

            if (isXiaomi) {
                item {
                    XiaomiNotice(
                        onOpenSettings = {
                            AlarmPermissions.openSettingsSafely(
                                context,
                                AlarmPermissions.appDetailsIntent(context)
                            )
                        }
                    )
                }
            }

            message?.let { text ->
                item { MessageBanner(text) }
            }
            if (loaded && failedScheduleIds.isNotEmpty()) {
                item {
                    GlassCard {
                        Text(
                            "有 ${failedScheduleIds.size} 项闹钟未能在系统中注册或取消，可能不响或误响。请检查权限后重试。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = viewModel::rescheduleAll) { Text("重新排程") }
                    }
                }
            }

            if (alarms.isNotEmpty()) {
                item { SectionTitle("我的闹钟（${alarms.size}）") }
            }

            items(items = alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    now = now,
                    onToggle = { enabled -> viewModel.setEnabled(alarm, enabled) },
                    onClick = { onEditAlarm(alarm.id) },
                    onPreview = { viewModel.preview(alarm) },
                    onStopPreview = { viewModel.stopPreview() },
                    isPreviewing = viewModel.previewingId == alarm.id,
                    onCancelSnooze = { until -> viewModel.cancelSnooze(alarm.id, until) }
                )
            }

            if (loaded && alarms.isEmpty()) {
                item { EmptyState() }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SectionTitle("外观主题") }
            item { ThemePickerCard(current = theme, onSelect = viewModel::selectTheme) }
            if (loaded) {
                item {
                    FooterHint(
                        onTestLockScreen = { viewModel.scheduleLockScreenTest() },
                        exactAlarmGranted = exactAlarmGranted
                    )
                }
            }

            item { VolumeSafetyNotice() }
        }
    }
}

@Composable
private fun Header(headphoneConnected: Boolean, deviceSummary: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "闹钟",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(
                text = if (headphoneConnected) "耳机已连接" else "未检测到耳机",
                active = headphoneConnected,
                icon = Icons.Outlined.Headphones
            )
            Text(
                text = "默认耳机播放；每条闹钟可单独设置外放策略",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        // 诊断：实时列出系统当前所有音频输出设备，便于排查“耳机识别不到”
        Text(
            text = "当前输出设备：$deviceSummary",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NextAlarmCard(nextTrigger: Long?, now: Long, alarmCount: Int) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (nextTrigger == null) "没有待响的闹钟" else "下一次响铃",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                if (nextTrigger == null) {
                    Text(
                        text = "开启一个闹钟开始使用",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = TimeUtils.describe(nextTrigger, now),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${TimeUtils.countdownText(nextTrigger, now)} · 共 $alarmCount 个待响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmItem,
    now: Long,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    isPreviewing: Boolean,
    onCancelSnooze: (Long) -> Unit
) {
    val mayUseSpeaker = !alarm.headphoneOnly || alarm.noHeadphoneAction == NoHeadphoneAction.SPEAKER
    val snoozeUntil = alarm.activeSnoozeTime(now)
    val nextTrigger = alarm.nextScheduledTime(now)
    GlassCard(onClick = onClick, modifier = Modifier.alpha(if (nextTrigger != null) 1f else 0.55f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alarm.timeText(),
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Light,
                    color = if (nextTrigger != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = alarm.repeatText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (alarm.label.isNotBlank()) {
                        Text(
                            text = "· ${alarm.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (nextTrigger != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = (if (nextTrigger == snoozeUntil) "贪睡中 · " else "下次 ") +
                            TimeUtils.countdownText(nextTrigger, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                }
                if (snoozeUntil != null) {
                    Text(
                        text = "贪睡 ${TimeUtils.describe(snoozeUntil, now)}" +
                            if (alarm.enabled) " · 与重复闹钟分别排程" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    TextButton(onClick = { onCancelSnooze(snoozeUntil) }) {
                        Text("取消本次贪睡（不影响重复闹钟）")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(
                        text = if (mayUseSpeaker) "允许外放" else "仅耳机",
                        active = !mayUseSpeaker,
                        icon = if (mayUseSpeaker) Icons.Outlined.Speaker else Icons.Outlined.Headphones
                    )
                    if (alarm.vibrate) {
                        StatusPill(text = "震动", active = false, icon = Icons.Outlined.NotificationsActive)
                    }
                }
                if (alarm.volumeRampSeconds > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "护耳渐强 · ${TimeUtils.rampDurationText(alarm.volumeRampSeconds)} " +
                            "→ ${alarm.maxVolumePercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = if (isPreviewing) onStopPreview else onPreview) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        contentDescription = "试听",
                        tint = if (isPreviewing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePickerCard(current: AppTheme, onSelect: (AppTheme) -> Unit) {
    GlassCard {
        Text(
            "选择界面风格，立即生效并自动保存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppTheme.entries.forEach { t ->
                val active = t == current
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.067f)
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = if (active) 0.6f else 0.25f),
                            CircleShape
                        )
                        .clickable { onSelect(t) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeSafetyNotice() {
    val context = LocalContext.current
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "蓝牙耳机音量升不满？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "安卓「媒体音量安全」会在耳机输出过大时自动压低并弹确认，导致渐强卡在中间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "解决：进系统「声音设置」关闭「媒体音量安全 / 降低过大音量」；响铃界面也有「点按用最大音量」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开系统声音设置", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyState() {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AlarmOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("还没有闹钟", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "点击右下角新建；默认只从耳机播放，可逐条调整策略",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(14.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FooterHint(onTestLockScreen: () -> Unit, exactAlarmGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f), MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                )
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "睡前检查耳机连接与每条闹钟的播放策略。默认无耳机时等待接入；若选择「允许外放」，响铃可能通过扬声器播放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "锁屏可靠性：本应用使用系统精确闹钟 + 前台服务 + 全屏提醒，" +
                "并持有唤醒锁，锁屏、息屏后仍会正常响铃。建议先实测一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onTestLockScreen) {
            Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (exactAlarmGranted) "测试锁屏响铃（1 分钟后）" else "先开启精确闹钟权限",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PermissionNotice(
    exactAlarmGranted: Boolean,
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    batteryOptimized: Boolean,
    onFixExact: () -> Unit,
    onFixNotification: () -> Unit,
    onFixFullScreen: () -> Unit,
    onFixBattery: () -> Unit
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "需要补充授权",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "缺少权限可能导致闹钟不准时或无法全屏提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!exactAlarmGranted) {
            PermissionAction("开启「精确闹钟」权限（否则响铃可能不准时）", onFixExact)
        }
        if (batteryOptimized) {
            PermissionAction("关闭电池优化（否则锁屏后可能被系统压制）", onFixBattery)
        }
        if (!fullScreenGranted) {
            PermissionAction("允许全屏提醒（锁屏时直接弹出闹钟界面）", onFixFullScreen)
        }
        if (!notificationGranted) {
            PermissionAction("允许发送通知", onFixNotification)
        }
    }
}

@Composable
private fun PermissionAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 小米 / HyperOS 专项引导。
 *
 * 小米的「自启动」「省电策略」「后台弹出界面」「锁屏显示」是独立于安卓标准权限的，
 * 缺任意一项都可能导致到点不响、不弹界面，是小米上闹钟类应用最常见的“失效”原因。
 */
@Composable
private fun XiaomiNotice(onOpenSettings: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "小米 / HyperOS 必做设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "缺任意一项都可能导致到点不响、不弹界面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在「应用信息」页里逐项开启：\n" +
                "① 自启动 → 允许\n" +
                "② 省电策略 → 无限制\n" +
                "③ 后台弹出界面 → 允许（锁屏弹出闹钟的关键）\n" +
                "④ 锁屏显示 → 允许\n" +
                "⑤ 蓝牙耳机需在响铃前保持已连接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("打开应用信息页去设置", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 每秒推进一次的当前时间，用于倒计时展示 */
@Composable
private fun rememberTickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return now
}

/** 实时耳机连接状态 */
@Composable
private fun rememberHeadphoneConnected(): Boolean {
    val context = LocalContext.current
    val detector = remember { HeadphoneDetector(context) }
    var connected by remember { mutableStateOf(detector.isConnected()) }
    DisposableEffect(detector) {
        val registration = detector.register(object : HeadphoneDetector.Callback {
            override fun onHeadphoneConnected() {
                connected = true
            }

            override fun onHeadphoneDisconnected() {
                connected = detector.isConnected()
            }
        })
        onDispose { registration.release() }
    }
    return connected
}

/** 实时音频输出设备诊断文本，便于排查“耳机识别不到” */
@Composable
private fun rememberAudioOutputSummary(): String {
    val context = LocalContext.current
    val detector = remember { HeadphoneDetector(context) }
    var summary by remember { mutableStateOf(detector.describeOutputs()) }
    DisposableEffect(detector) {
        val registration = detector.register(object : HeadphoneDetector.Callback {
            override fun onHeadphoneConnected() {
                summary = detector.describeOutputs()
            }

            override fun onHeadphoneDisconnected() {
                summary = detector.describeOutputs()
            }
        })
        onDispose { registration.release() }
    }
    return summary
}

/** 试听时长，与铃声编辑页保持一致 */
private const val PREVIEW_DURATION_MS = 6_000L

sealed interface AlarmLoadState {
    data object Loading : AlarmLoadState
    data class Ready(val alarms: List<AlarmItem>) : AlarmLoadState
    data class Error(val message: String) : AlarmLoadState
}

class AlarmListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = AlarmApp.from(application)
    private val repository = app.alarmRepository
    private val operations = app.alarmOperations
    private val settings = app.settingsRepository

    /** 当前界面主题，供选择器高亮；改后 DataStore 驱动全局重组 */
    val theme: StateFlow<AppTheme> = settings.themeKey
        .map { AppTheme.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.DEFAULT)

    fun selectTheme(target: AppTheme) {
        viewModelScope.launch { settings.setThemeKey(target.key) }
    }

    private var previewPlayer: HeadphoneAlarmPlayer? = null
    private var previewJob: Job? = null
    var previewingId by mutableLongStateOf(-1L)
        private set

    /** 试听 / 测试的反馈信息，让失败不再静默 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val reload = MutableStateFlow(0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val alarmState: StateFlow<AlarmLoadState> = reload.flatMapLatest {
        repository.alarms
            .map<List<AlarmItem>, AlarmLoadState> { AlarmLoadState.Ready(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(AlarmLoadState.Error("闹钟读取失败，未修改数据：" + (error.message ?: "未知错误")))
            }
            .onStart { emit(AlarmLoadState.Loading) }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AlarmLoadState.Loading
    )
    val failedScheduleIds: StateFlow<Set<Long>> = operations.failedScheduleIds

    fun retryLoading() {
        reload.value++
        // ON_RESUME may already have observed the previous Error and skipped scheduling.
        // Wait for a fresh, successful read before rebuilding OS alarms; never treat a
        // failed read as an empty alarm list.
        viewModelScope.launch {
            try {
                repository.alarms.first()
                operations.rescheduleAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = "读取或重新排程失败：" + (error.message ?: "未知错误")
            }
        }
    }

    fun setEnabled(alarm: AlarmItem, enabled: Boolean) {
        viewModelScope.launch {
            try {
                operations.setEnabled(alarm.id, enabled)
            } catch (error: Exception) {
                _message.value = "修改闹钟失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun cancelSnooze(id: Long, until: Long) {
        viewModelScope.launch {
            try {
                operations.cancelSnooze(id, until)
            } catch (error: Exception) {
                _message.value = "取消贪睡失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun rescheduleAll() {
        viewModelScope.launch {
            try {
                // ON_RESUME may precede the first DataStore emission; corrupt data must not
                // be mistaken for an empty store or silently passed to the scheduler.
                if (alarmState.first { it !is AlarmLoadState.Loading } !is AlarmLoadState.Ready) return@launch
                operations.rescheduleAll()
            } catch (error: Exception) {
                _message.value = "重新排程失败：${error.message ?: "未知错误"}"
            }
        }
    }

    /** 试听：复用响铃引擎，因此试听同样只走耳机 */
    fun preview(alarm: AlarmItem) {
        stopPreview()
        val player = HeadphoneAlarmPlayer(getApplication())
        previewPlayer = player
        previewingId = alarm.id
        _message.value = null

        // 先布置收尾任务：播放失败的回调是同步触发的
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DURATION_MS)
            if (previewingId == alarm.id) stopPreview()
        }

        player.start(
            alarm = alarm.copy(
                enabled = true,
                vibrate = false,
                // 出于安全考虑，试听固定只走耳机；闹钟若设了「允许外放」，
                // 正式响铃会遵从外放配置，但试听仍保持静音等待耳机。
                headphoneOnly = true,
                noHeadphoneAction = NoHeadphoneAction.WAIT,
                // 试听同样演示渐强效果，压缩到 5 秒以便在 6 秒内听完
                volumeRampSeconds = 5
            ),
            onState = { state ->
                when (state) {
                    is HeadphoneAlarmPlayer.State.Failed -> {
                        _message.value = "试听失败：${state.reason}"
                        previewingId = -1L
                    }

                    is HeadphoneAlarmPlayer.State.WaitingHeadphone ->
                        _message.value = "未检测到耳机，试听不会外放：${state.reason}"

                    is HeadphoneAlarmPlayer.State.Playing -> _message.value = null
                    else -> Unit
                }
            }
        )
    }

    /**
     * 锁屏响铃测试：安排一条 1 分钟后响铃的一次性闹钟，走完整的真实链路
     * （AlarmManager → 广播 → 前台服务 → 全屏通知），用户锁屏即可验证。
     */
    fun scheduleLockScreenTest() {
        if (alarmState.value !is AlarmLoadState.Ready) {
            _message.value = "请先成功读取闹钟，再创建锁屏测试"
            return
        }
        if (!AlarmPermissions.canScheduleExactAlarms(getApplication())) {
            _message.value = "请先开启「精确闹钟」权限，否则无法安排测试"
            return
        }
        viewModelScope.launch {
            val stored = alarmState.value as? AlarmLoadState.Ready ?: return@launch
            val target = Calendar.getInstance().apply {
                timeInMillis = TimeUtils.nextWholeMinuteAfter(System.currentTimeMillis(), 60_000L)
            }
            // 沿用已有闹钟的耳机策略，保证测的就是真实行为
            val template = stored.alarms.firstOrNull { it.enabled }
            val item = (template ?: AlarmItem(id = 0L, hour = 0, minute = 0)).copy(
                id = 0L,
                hour = target.get(Calendar.HOUR_OF_DAY),
                minute = target.get(Calendar.MINUTE),
                label = "锁屏测试",
                enabled = true,
                repeatDays = emptySet(),
                autoStopMinutes = 2
            )
            try {
                val id = operations.create(item)
                _message.value = if (id in operations.failedScheduleIds.value) {
                    "测试闹钟已保存，但系统排程失败；请检查权限，不要等待响铃"
                } else {
                    "已安排 ${item.timeText()} 响铃（已加入列表），请立刻锁屏等待"
                }
            } catch (error: Exception) {
                _message.value = "创建测试闹钟失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer?.release()
        previewPlayer = null
        previewingId = -1L
    }

    override fun onCleared() {
        stopPreview()
        super.onCleared()
    }
}
