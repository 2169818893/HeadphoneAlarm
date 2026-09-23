package com.headphonealarm.ui.edit

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.VolumeMute
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.headphonealarm.AlarmApp
import com.headphonealarm.audio.BuiltInTones
import com.headphonealarm.audio.HeadphoneAlarmPlayer
import com.headphonealarm.data.AlarmItem
import com.headphonealarm.data.NoHeadphoneAction
import com.headphonealarm.data.repeatTextOf
import com.headphonealarm.ui.components.DaySelector
import com.headphonealarm.ui.components.GlassCard
import com.headphonealarm.ui.components.SectionTitle
import com.headphonealarm.ui.components.SegmentedOptions
import com.headphonealarm.ui.components.SettingRow
import com.headphonealarm.ui.components.StatusPill
import com.headphonealarm.ui.components.TimeWheel
import com.headphonealarm.util.RingtoneImporter
import com.headphonealarm.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long,
    onBack: () -> Unit,
    viewModel: AlarmEditViewModel = viewModel(
        key = "alarm-edit-$alarmId",
        factory = AlarmEditViewModel.factory(alarmId)
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // 选择后立即复制到私有目录，避免 SAF 授权失效或云端文件不可读导致播放失败
        if (uri != null) viewModel.importCustomRingtone(uri)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (alarmId <= 0) "新建闹钟" else "编辑闹钟",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { scaffoldPadding ->
        // 等数据加载完成再渲染：ViewModel 异步读取闹钟，若先用默认值（7:30）组合，
        // 数据到达后再跳变，滚轮的 animate 同步会闪跳；加载通常在毫秒级完成。
        if (!state.loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = scaffoldPadding.calculateTopPadding()),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = scaffoldPadding.calculateTopPadding()),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                GlassCard {
                    TimeWheel(
                        hour = state.hour,
                        minute = state.minute,
                        onHourChange = viewModel::setHour,
                        onMinuteChange = viewModel::setMinute,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (state.repeatDays.isEmpty()) {
                            "仅在下次触发"
                        } else {
                            "重复：${repeatTextOf(state.repeatDays)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item { SectionTitle("标签") }
            item {
                GlassCard {
                    OutlinedTextField(
                        value = state.label,
                        onValueChange = viewModel::setLabel,
                        placeholder = { Text("例如：起床、吃药", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            item { SectionTitle("重复") }
            item {
                GlassCard {
                    DaySelector(selected = state.repeatDays, onToggle = viewModel::toggleDay)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickRepeatChip("仅一次") { viewModel.setRepeatDays(emptySet()) }
                        QuickRepeatChip("每天") { viewModel.setRepeatDays((1..7).toSet()) }
                        QuickRepeatChip("工作日") { viewModel.setRepeatDays(setOf(1, 2, 3, 4, 5)) }
                        QuickRepeatChip("周末") { viewModel.setRepeatDays(setOf(6, 7)) }
                    }
                }
            }

            item { SectionTitle("铃声") }
            item {
                GlassCard {
                    ToneRow(
                        title = "系统默认闹钟",
                        subtitle = "跟随系统闹钟提示音",
                        selected = state.ringtoneUri == null,
                        onSelect = { viewModel.selectDefaultRingtone() },
                        previewing = viewModel.previewingKey == DEFAULT_KEY,
                        onPreview = { viewModel.previewRingtone(DEFAULT_KEY) }
                    )
                    BuiltInTones.ALL.forEach { tone ->
                        ToneRow(
                            title = tone.name,
                            subtitle = tone.description,
                            selected = state.ringtoneUri?.endsWith(tone.key) == true,
                            onSelect = { viewModel.selectBuiltInTone(tone) },
                            previewing = viewModel.previewingKey == tone.key,
                            onPreview = { viewModel.previewRingtone(tone.key) }
                        )
                    }
                    if (state.ringtoneUri != null && BuiltInTones.ALL.none { state.ringtoneUri!!.endsWith(it.key) }) {
                        ToneRow(
                            title = state.ringtoneName.ifBlank { "自定义音频" },
                            subtitle = "来自本地文件",
                            selected = true,
                            onSelect = { },
                            previewing = viewModel.previewingKey == CUSTOM_KEY,
                            onPreview = { viewModel.previewRingtone(CUSTOM_KEY) }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { filePicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("从本地选择音频文件")
                    }
                    message?.let { text ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item { SectionTitle("耳机播放") }
            item {
                GlassCard {
                    SettingRow(
                        icon = Icons.Outlined.Headphones,
                        title = "仅通过耳机播放",
                        subtitle = "强制把闹钟音频路由到已连接的耳机，绝不经扬声器外放",
                        trailing = {
                            Switch(
                                checked = state.headphoneOnly,
                                onCheckedChange = viewModel::setHeadphoneOnly,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    )
                    if (state.headphoneOnly) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "未检测到耳机时",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        SegmentedOptions(
                            options = listOf("静默等待", "仅震动", "允许外放"),
                            selectedIndex = state.noHeadphoneAction.ordinal,
                            onSelect = { viewModel.setNoHeadphoneAction(NoHeadphoneAction.values()[it]) }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when (state.noHeadphoneAction) {
                                NoHeadphoneAction.WAIT ->
                                    "闹钟会保持静默，一旦耳机接入立即开始播放，全程不会外放。"
                                NoHeadphoneAction.VIBRATE_ONLY ->
                                    "只通过震动提醒，不发出任何声音。"
                                NoHeadphoneAction.SPEAKER ->
                                    "没有耳机时按系统默认输出播放，可能会外放。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.noHeadphoneAction == NoHeadphoneAction.SPEAKER) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        StatusPill(text = "允许使用扬声器", active = false, icon = Icons.AutoMirrored.Outlined.VolumeUp)
                    }
                }
            }

            item { SectionTitle("护耳音量") }
            item {
                GlassCard {
                    SliderRow(
                        icon = Icons.AutoMirrored.Outlined.TrendingUp,
                        title = "音量渐强时长",
                        valueText = TimeUtils.rampDurationText(state.volumeRampSeconds),
                        value = state.volumeRampSeconds.toFloat(),
                        range = 0f..120f,
                        steps = 23,
                        onValueChange = { viewModel.setVolumeRamp(it.toInt()) }
                    )
                    SliderRow(
                        icon = Icons.AutoMirrored.Outlined.VolumeMute,
                        title = "起始音量",
                        valueText = "${state.rampStartPercent}%",
                        value = state.rampStartPercent.toFloat(),
                        range = 1f..30f,
                        steps = 28,
                        onValueChange = { viewModel.setRampStartPercent(it.toInt()) }
                    )
                    SliderRow(
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        title = "最高音量",
                        valueText = "${state.maxVolumePercent}%",
                        value = state.maxVolumePercent.toFloat(),
                        range = 20f..100f,
                        steps = 15,
                        onValueChange = { viewModel.setMaxVolume(it.toInt()) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.volumeRampSeconds == 0) {
                            "渐强已关闭，响铃会直接从起始音量跳到最高音量，容易惊扰耳朵。"
                        } else {
                            "响铃从 ${state.rampStartPercent}% 开始，在 ${state.volumeRampSeconds} 秒内平缓升到 " +
                                "${state.maxVolumePercent}%。音量按听感匀速上升，不会前几秒突然变响。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.volumeRampSeconds == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { SectionTitle("提示方式") }
            item {
                GlassCard {
                    SettingRow(
                        icon = Icons.Outlined.Vibration,
                        title = "震动提醒",
                        subtitle = "与声音同时进行",
                        trailing = {
                            Switch(
                                checked = state.vibrate,
                                onCheckedChange = viewModel::setVibrate,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    )
                }
            }

            item { SectionTitle("贪睡与超时") }
            item {
                GlassCard {
                    SettingRow(
                        icon = Icons.Outlined.Snooze,
                        title = "贪睡时长",
                        subtitle = "点击贪睡后再次响铃的间隔",
                        trailing = {
                            Text(
                                "${state.snoozeMinutes} 分钟",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    )
                    SegmentedOptions(
                        options = listOf("1", "5", "10", "15"),
                        selectedIndex = SNOOZE_OPTIONS.indexOf(state.snoozeMinutes).coerceAtLeast(0),
                        onSelect = { viewModel.setSnoozeMinutes(SNOOZE_OPTIONS[it]) }
                    )
                    Spacer(Modifier.height(14.dp))
                    SettingRow(
                        icon = Icons.Outlined.Timer,
                        title = "自动停止",
                        subtitle = "持续无人操作时自动关闭",
                        trailing = {
                            Text(
                                "${state.autoStopMinutes} 分钟",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    )
                    SegmentedOptions(
                        options = listOf("5", "10", "15", "30"),
                        selectedIndex = AUTO_STOP_OPTIONS.indexOf(state.autoStopMinutes).coerceAtLeast(0),
                        onSelect = { viewModel.setAutoStopMinutes(AUTO_STOP_OPTIONS[it]) }
                    )
                }
            }

            item {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("保存", fontWeight = FontWeight.SemiBold)
                }
            }

            if (alarmId > 0) {
                item {
                    TextButton(
                        onClick = viewModel::delete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("删除这个闹钟", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // 只在真正完成保存/删除后才回退。
    // 注意不能直接 collect：StateFlow 初值为 false，会让界面刚进入就被弹回列表。
    LaunchedEffect(viewModel) {
        viewModel.saved.first { it }
        viewModel.stopPreview()
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }
}

@Composable
private fun QuickRepeatChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToneRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    previewing: Boolean,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPreview) {
            Icon(
                imageVector = if (previewing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                contentDescription = "试听",
                tint = if (previewing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(valueText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
            )
        )
    }
}

private const val DEFAULT_KEY = "__default__"
private const val CUSTOM_KEY = "__custom__"
private const val PREVIEW_DURATION_MS = 6_000L
private val SNOOZE_OPTIONS = listOf(1, 5, 10, 15)
private val AUTO_STOP_OPTIONS = listOf(5, 10, 15, 30)

// region ViewModel

data class AlarmEditState(
    val hour: Int = 7,
    val minute: Int = 30,
    val label: String = "",
    val repeatDays: Set<Int> = emptySet(),
    val ringtoneUri: String? = null,
    val ringtoneName: String = "",
    val headphoneOnly: Boolean = true,
    val noHeadphoneAction: NoHeadphoneAction = NoHeadphoneAction.WAIT,
    val vibrate: Boolean = true,
    val volumeRampSeconds: Int = 30,
    val rampStartPercent: Int = 5,
    val maxVolumePercent: Int = 85,
    val snoozeMinutes: Int = 5,
    val autoStopMinutes: Int = 10,
    val loaded: Boolean = false
) {
    fun toAlarmItem(id: Long, enabled: Boolean) = AlarmItem(
        id = id,
        hour = hour,
        minute = minute,
        label = label.trim(),
        enabled = enabled,
        repeatDays = repeatDays,
        ringtoneUri = ringtoneUri,
        ringtoneName = ringtoneName,
        headphoneOnly = headphoneOnly,
        noHeadphoneAction = noHeadphoneAction,
        vibrate = vibrate,
        volumeRampSeconds = volumeRampSeconds,
        rampStartPercent = rampStartPercent,
        maxVolumePercent = maxVolumePercent,
        snoozeMinutes = snoozeMinutes,
        autoStopMinutes = autoStopMinutes
    )
}

class AlarmEditViewModel(
    application: Application,
    private val alarmId: Long
) : AndroidViewModel(application) {

    private val app = AlarmApp.from(application)
    private val repository = app.alarmRepository
    private val scheduler = app.alarmScheduler

    private val _state = MutableStateFlow(AlarmEditState())
    val state: StateFlow<AlarmEditState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** 导入 / 试听的反馈信息，让失败不再静默 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var enabled: Boolean = true
    private var previewPlayer: HeadphoneAlarmPlayer? = null
    private var previewJob: kotlinx.coroutines.Job? = null

    /** 本次编辑会话导入的自定义铃声 URI；离开页面时清理其中未保存的，避免私有目录只增不减 */
    private val sessionImportedUris = mutableListOf<String>()

    var previewingKey by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            if (alarmId > 0) {
                repository.getById(alarmId)?.let { item ->
                    enabled = item.enabled
                    _state.value = AlarmEditState(
                        hour = item.hour,
                        minute = item.minute,
                        label = item.label,
                        repeatDays = item.repeatDays,
                        ringtoneUri = item.ringtoneUri,
                        ringtoneName = item.ringtoneName,
                        headphoneOnly = item.headphoneOnly,
                        noHeadphoneAction = item.noHeadphoneAction,
                        vibrate = item.vibrate,
                        volumeRampSeconds = item.volumeRampSeconds,
                        rampStartPercent = item.rampStartPercent,
                        maxVolumePercent = item.maxVolumePercent,
                        snoozeMinutes = item.snoozeMinutes,
                        autoStopMinutes = item.autoStopMinutes,
                        loaded = true
                    )
                }
            } else {
                val now = java.util.Calendar.getInstance()
                _state.value = _state.value.copy(
                    hour = now.get(java.util.Calendar.HOUR_OF_DAY),
                    minute = now.get(java.util.Calendar.MINUTE),
                    loaded = true
                )
            }
        }
    }

    fun setHour(value: Int) = update { it.copy(hour = value) }
    fun setMinute(value: Int) = update { it.copy(minute = value) }
    fun setLabel(value: String) = update { it.copy(label = value) }
    fun setRepeatDays(value: Set<Int>) = update { it.copy(repeatDays = value) }
    fun setHeadphoneOnly(value: Boolean) = update { it.copy(headphoneOnly = value) }
    fun setNoHeadphoneAction(value: NoHeadphoneAction) = update { it.copy(noHeadphoneAction = value) }
    fun setVibrate(value: Boolean) = update { it.copy(vibrate = value) }
    fun setVolumeRamp(value: Int) = update { it.copy(volumeRampSeconds = value.coerceIn(0, 120)) }

    /** 起始音量必须低于最高音量，否则渐强没有意义 */
    fun setRampStartPercent(value: Int) = update {
        it.copy(rampStartPercent = value.coerceIn(1, minOf(30, it.maxVolumePercent - 5)))
    }

    fun setMaxVolume(value: Int) = update { current ->
        val max = value.coerceIn(20, 100)
        current.copy(
            maxVolumePercent = max,
            rampStartPercent = current.rampStartPercent.coerceAtMost(minOf(30, max - 5))
        )
    }
    fun setSnoozeMinutes(value: Int) = update { it.copy(snoozeMinutes = value) }
    fun setAutoStopMinutes(value: Int) = update { it.copy(autoStopMinutes = value) }

    fun toggleDay(day: Int) = update { current ->
        val days = current.repeatDays.toMutableSet()
        if (!days.add(day)) days.remove(day)
        current.copy(repeatDays = days)
    }

    fun selectDefaultRingtone() =
        update { it.copy(ringtoneUri = null, ringtoneName = "") }

    fun selectBuiltInTone(tone: BuiltInTones.Tone) = update {
        it.copy(
            ringtoneUri = BuiltInTones.uriFor(getApplication(), tone),
            ringtoneName = tone.name
        )
    }

    /** 导入本地音频：复制进私有目录后使用稳定的本地路径 */
    fun importCustomRingtone(uri: Uri) {
        viewModelScope.launch {
            _message.value = "正在导入音频…"
            val result = withContext(Dispatchers.IO) {
                RingtoneImporter.import(getApplication(), uri)
            }
            result
                .onSuccess { imported ->
                    val newUri = RingtoneImporter.toUriString(imported.file)
                    // 本次会话此前导入、已被这次替换的文件：清理掉，避免未保存就堆积
                    val stale = sessionImportedUris.filterNot { it == newUri }
                    sessionImportedUris.clear()
                    sessionImportedUris.add(newUri)
                    update {
                        it.copy(ringtoneUri = newUri, ringtoneName = imported.displayName)
                    }
                    _message.value = "已导入「${imported.displayName}」，点右侧 ▶ 试听"
                    cleanupRingtones(stale)
                }
                .onFailure { error ->
                    _message.value = "导入失败：${error.message ?: "未知错误"}"
                }
        }
    }

    /** 删除不再被任何闹钟引用的自定义铃声文件（IO 线程执行，带引用检查防误删） */
    private suspend fun cleanupRingtones(uris: List<String>) {
        if (uris.isEmpty()) return
        val referenced = repository.alarms.first().mapNotNull { it.ringtoneUri }.toSet()
        withContext(Dispatchers.IO) {
            uris.forEach { RingtoneImporter.deleteOwnedRingtone(getApplication(), it, referenced) }
        }
    }

    /** 试听同样走耳机独占引擎，确保与真实响铃一致 */
    fun previewRingtone(key: String) {
        if (previewingKey == key) {
            stopPreview()
            return
        }
        stopPreview()

        val current = _state.value
        // 注意不能对整个 when 追加 `?: current.ringtoneUri`：
        // 否则试听「系统默认」(uri=null) 时会被错误地回退成当前已选的自定义铃声。
        val uri = when (key) {
            DEFAULT_KEY -> null
            CUSTOM_KEY -> current.ringtoneUri
            else -> BuiltInTones.uriFor(getApplication(), BuiltInTones.find(key) ?: return)
        }

        val player = HeadphoneAlarmPlayer(getApplication())
        previewPlayer = player
        previewingKey = key
        _message.value = null

        // 先布置收尾任务：播放失败的回调是同步触发的
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DURATION_MS)
            if (previewingKey == key) stopPreview()
        }

        player.start(
            alarm = current.toAlarmItem(id = -1L, enabled = true).copy(
                ringtoneUri = uri,
                vibrate = false,
                // 试听铃声时不渐强，直接听到完整音色
                volumeRampSeconds = 0,
                // 试听与真实响铃一致：只走耳机、无耳机时静默等待并提示，绝不外放
                headphoneOnly = true,
                noHeadphoneAction = NoHeadphoneAction.WAIT
            ),
            onState = { state -> reportPreviewState(state) }
        )
    }

    /** 把试听过程中的异常/等待状态显示出来，避免"点了没反应" */
    private fun reportPreviewState(state: HeadphoneAlarmPlayer.State) {
        _message.value = when (state) {
            is HeadphoneAlarmPlayer.State.Failed -> "试听失败：${state.reason}"
            is HeadphoneAlarmPlayer.State.WaitingHeadphone ->
                "未检测到耳机，试听不会外放：${state.reason}"
            is HeadphoneAlarmPlayer.State.Playing -> null
            else -> _message.value
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer?.release()
        previewPlayer = null
        previewingKey = null
    }

    fun save() {
        viewModelScope.launch {
            val id = if (alarmId > 0) alarmId else System.currentTimeMillis()
            // 保留原有启用状态：编辑页不展示开关，不应静默把用户已关闭的闹钟重新打开。
            // 新建闹钟时 enabled 默认为 true，仍然会直接启用。
            val item = _state.value.toAlarmItem(id, enabled = enabled)
            val oldUri = if (alarmId > 0) repository.getById(alarmId)?.ringtoneUri else null
            repository.upsert(item)
            if (item.enabled) scheduler.schedule(item) else scheduler.cancel(item)
            // 已保存的铃声进入被引用集合，移出待清理，避免离开页面时被误删
            item.ringtoneUri?.let { sessionImportedUris.remove(it) }
            // 更换过铃声：清理不再被任何闹钟引用的旧文件
            if (oldUri != null && oldUri != item.ringtoneUri) cleanupRingtones(listOf(oldUri))
            _saved.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (alarmId > 0) {
                val existing = repository.getById(alarmId)
                repository.remove(alarmId)
                existing?.let { scheduler.cancel(it) }
                // 清理该闹钟独占的自定义铃声文件（仍被其它闹钟引用时会自动跳过）
                existing?.ringtoneUri?.let { cleanupRingtones(listOf(it)) }
            }
            _saved.value = true
        }
    }

    private fun update(block: (AlarmEditState) -> AlarmEditState) {
        _state.value = block(_state.value)
    }

    override fun onCleared() {
        stopPreview()
        // 清理本次会话导入、但最终未保存的铃声文件，避免私有目录只增不减。
        // viewModelScope 已随 onCleared 取消，用独立 scope 收尾；引用检查保证不会误删已保存的。
        val orphans = sessionImportedUris.toList()
        sessionImportedUris.clear()
        if (orphans.isNotEmpty()) {
            val repo = repository
            val app = getApplication<Application>()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val referenced = repo.alarms.first().mapNotNull { it.ringtoneUri }.toSet()
                orphans.forEach { RingtoneImporter.deleteOwnedRingtone(app, it, referenced) }
            }
        }
        super.onCleared()
    }

    companion object {
        fun factory(alarmId: Long) = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AlarmApp
                AlarmEditViewModel(application, alarmId)
            }
        }
    }
}

// endregion
