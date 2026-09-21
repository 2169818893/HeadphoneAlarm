package com.headphonealarm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow

/**
 * 惯性滚轮选择器。基于 LazyColumn + 吸附 fling，滚动帧率与列表一致。
 *
 * @param visibleCount 可见行数（奇数），中间一行即为选中项
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeight: Dp = 54.dp,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    suffix: String? = null
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val haptics = LocalHapticFeedback.current

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(items.indices)
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    val snappedIndex by remember {
        derivedStateOf {
            val base = listState.firstVisibleItemIndex
            if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2f) base + 1 else base
        }
    }

    /** 程序化滚动（外部同步）期间挂起通知，避免 onSelected 回环打断动画 */
    val isProgrammaticScroll = remember { mutableStateOf(false) }

    // 用户滚动 -> 向外通知（程序化同步期间不通知，否则会形成回环）：
    // 外部值变化 -> animateScrollToItem -> 滚动途中每跨一项触发 onSelected ->
    // 外部 state 被改成途中值 -> LaunchedEffect(selectedIndex) 重启并取消动画 -> 卡在中间
    LaunchedEffect(listState, itemHeightPx) {
        snapshotFlow { snappedIndex }
            .distinctUntilChanged()
            .map { it.coerceIn(items.indices) }
            .collect { index ->
                if (!isProgrammaticScroll.value) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelected(index)
                }
            }
    }

    // 外部值变化 -> 同步滚动
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(items.indices)
        if (snappedIndex != target) {
            isProgrammaticScroll.value = true
            try {
                listState.animateScrollToItem(target)
            } finally {
                // 用户手势可能取消动画，必须保证标志复位，否则后续用户滚动被永久抑制
                isProgrammaticScroll.value = false
            }
        }
    }

    Box(
        modifier = modifier.height(itemHeight * visibleCount),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * (visibleCount / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { index ->
                val selected = index == snappedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Text(
                        text = buildString {
                            append(items[index])
                            suffix?.let { append(it) }
                        },
                        style = if (selected) {
                            textStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = textStyle.fontSize * 1.12f)
                        } else {
                            textStyle.copy(fontSize = textStyle.fontSize * 0.94f)
                        },
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        // 中央高亮带：不参与滚动，只做视觉锚点
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), Color.Transparent)
                    )
                )
        )
    }
}

/** 由两个滚轮拼成的时:分选择器 */
@Composable
fun TimeWheel(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minuteStep: Int = 1
) {
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember(minuteStep) { (0..59 step minuteStep).map { "%02d".format(it) } }
    val minuteIndex = remember(minute) {
        (minute - minute % minuteStep) / minuteStep
    }

    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WheelPicker(
            items = hours,
            selectedIndex = hour,
            onSelected = onHourChange,
            modifier = Modifier.weight(1f),
            suffix = " 时"
        )
        androidx.compose.material3.Text(
            text = ":",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        WheelPicker(
            items = minutes,
            selectedIndex = minuteIndex.coerceIn(minutes.indices),
            onSelected = { onMinuteChange(it * minuteStep) },
            modifier = Modifier.weight(1f),
            suffix = " 分"
        )
    }
}
