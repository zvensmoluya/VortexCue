package io.github.zvensmoluya.vortexcue.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.vortexcue.ui.theme.AppAccent
import io.github.zvensmoluya.vortexcue.ui.theme.AppHairline
import io.github.zvensmoluya.vortexcue.ui.theme.AppMutedText
import io.github.zvensmoluya.vortexcue.ui.theme.AppPrimaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSecondaryText
import io.github.zvensmoluya.vortexcue.ui.theme.AppSurfaceSelected
import kotlin.math.roundToInt

@Composable
internal fun QueueItemRow(
    itemKey: String,
    index: Int,
    itemCount: Int,
    title: String,
    artist: String?,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
) {
    var dragOffset by remember(itemKey) { mutableFloatStateOf(0f) }
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (isCurrent) AppSurfaceSelected else Color.Transparent)
                    .semantics {
                        customActions =
                            buildList {
                                if (index > 0) {
                                    add(CustomAccessibilityAction("上移") {
                                        onMove(index, index - 1)
                                        true
                                    })
                                }
                                if (index < itemCount - 1) {
                                    add(CustomAccessibilityAction("下移") {
                                        onMove(index, index + 1)
                                        true
                                    })
                                }
                            }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1}", color = AppMutedText, modifier = Modifier.width(28.dp))
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onSelect)
                        .padding(vertical = 8.dp),
            ) {
                Text(
                    title,
                    color = if (isCurrent) AppPrimaryText else AppSecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist.isNullOrBlank()) {
                    Text(artist, color = AppMutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isCurrent) {
                    Text("正在播放", color = AppAccent, fontSize = 12.sp)
                }
            }
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .graphicsLayer { translationY = dragOffset }
                        .pointerInput(itemKey, index, itemCount) {
                            detectDragGesturesAfterLongPress(
                                onDragEnd = {
                                    val delta = (dragOffset / 56.dp.toPx()).roundToInt()
                                    val target = (index + delta).coerceIn(0, itemCount - 1)
                                    dragOffset = 0f
                                    if (target != index) onMove(index, target)
                                },
                                onDragCancel = { dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                            )
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = "拖动重排 $title",
                    tint = AppSecondaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "移除 $title" },
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = AppSecondaryText)
            }
        }
        HorizontalDivider(color = AppHairline)
    }
}
