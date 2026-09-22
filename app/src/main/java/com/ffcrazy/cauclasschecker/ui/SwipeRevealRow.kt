package com.ffcrazy.cauclasschecker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 左滑露出操作按钮的列表行。
 *
 * 没用 `SwipeToDismissBox` —— 那个只能「滑到底触发动作然后消失」，
 * 不支持停在中间让按钮保持可见可点。这里用 `Animatable` + `draggable` 自己做，
 * 只有两个锚点：合上（0）和展开（-actionsWidth）。
 *
 * [revealed] 由调用方持有，这样列表能保证**同一时刻只展开一行** ——
 * 否则滑开好几行既难看又容易误触。
 */
@Composable
fun SwipeRevealRow(
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    actionsWidth: Dp,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionsPx = with(density) { actionsWidth.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    // 外部状态变化（例如滑开了别的行）时跟着收放
    LaunchedEffect(revealed, actionsPx) {
        val target = if (revealed) -actionsPx else 0f
        if (offsetX.value != target) {
            offsetX.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    Box(modifier.fillMaxWidth()) {
        // 背景层：操作按钮，靠右排
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )

        // 前景层：卡片本体，可水平拖动
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-actionsPx, 0f))
                        }
                    },
                    onDragStopped = {
                        // 过半就吸附到展开位，否则弹回
                        val open = offsetX.value < -actionsPx / 2
                        offsetX.animateTo(
                            if (open) -actionsPx else 0f,
                            spring(stiffness = Spring.StiffnessMediumLow),
                        )
                        onRevealedChange(open)
                    },
                ),
        ) {
            content()
        }
    }
}
