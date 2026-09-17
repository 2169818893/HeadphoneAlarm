package com.headphonealarm.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.headphonealarm.data.AlarmItem

/** 半透明玻璃卡片，统一全应用容器样式 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

/** 分组标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp)
    )
}

/** 设置行：图标 + 标题/副标题 + 尾部控件 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** 星期多选胶囊 */
@Composable
fun DaySelector(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AlarmItem.DAY_SHORT.forEachIndexed { index, label ->
            val day = index + 1
            val active = day in selected
            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.067f),
                label = "dayBackground"
            )
            val textColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "dayText"
            )
            val scale by animateFloatAsState(if (active) 1f else 0.94f, label = "dayScale")

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(background)
                    .clickable { onToggle(day) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** 分段选择器 */
@Composable
fun SegmentedOptions(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.067f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f) else Color.Transparent,
                label = "segmentBackground"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .clickable(enabled = enabled) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 状态胶囊标签 */
@Composable
fun StatusPill(
    text: String,
    active: Boolean,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(15.dp)) }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
