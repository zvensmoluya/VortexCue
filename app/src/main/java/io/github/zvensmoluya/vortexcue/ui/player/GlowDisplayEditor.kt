package io.github.zvensmoluya.vortexcue.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private enum class GlowAdjustment(val label: String, val step: Float, val range: ClosedFloatingPointRange<Float>) {
    TextLight("文字明暗", 1f, 4f..96f),
    Size("字号", 0.5f, 18f..36f),
    Position("位置", 0.02f, -0.6f..0.6f),
    ScreenLight("屏幕亮度", 0.005f, 0.01f..0.30f),
    Controls("按钮明暗", 2f, 24f..160f);

    fun value(p: DisplayProfile): Float = when (this) {
        TextLight -> p.glowSubtitleLevel.toFloat()
        Size -> p.glowSubtitleSizeSp
        Position -> p.glowSubtitleVerticalBias
        ScreenLight -> p.glowWindowBrightness
        Controls -> p.glowControlLevel.toFloat()
    }

    fun update(p: DisplayProfile, value: Float): DisplayProfile {
        val v = value.coerceIn(range)
        val custom = p.copy(name = DisplayProfile.CustomName)
        return when (this) {
            TextLight -> custom.copy(glowSubtitleLevel = v.roundToInt())
            Size -> custom.copy(glowSubtitleSizeSp = v)
            Position -> custom.copy(glowSubtitleVerticalBias = v)
            ScreenLight -> custom.copy(glowWindowBrightness = v)
            Controls -> custom.copy(glowControlLevel = v.roundToInt())
        }
    }

    fun valueLabel(p: DisplayProfile): String = when (this) {
        TextLight -> "${(value(p) / 255f * 100).roundToInt()}%"
        Controls -> "${(value(p) / 255f * 100).roundToInt()}%"
        ScreenLight -> "${(value(p) * 100).roundToInt()}%"
        Size -> "${value(p).roundToInt()}"
        Position -> glowVerticalPositionLabel(value(p))
    }
}

@Composable
internal fun GlowDisplayEditor(
    profile: DisplayProfile,
    isOnboarding: Boolean,
    onProfileChanged: (DisplayProfile) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(GlowAdjustment.TextLight) }
    var purePreview by rememberSaveable { mutableStateOf(false) }
    var presetsVisible by rememberSaveable { mutableStateOf(false) }
    // Editing tools stay readable independently of the brightness being previewed.
    val ink = Color(0xFFB8BBC2)
    val subtitle = profile.glowSubtitleLevel.coerceIn(0, 255).let { Color(it, it, it) }
    val controls = profile.glowControlLevel.coerceIn(0, 255).let { Color(it, it, it) }
    Box(modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Box(Modifier.fillMaxSize().then(if (purePreview) Modifier.semantics { contentDescription = "显示微光调整面板" }.clickable { purePreview = false } else Modifier)) {
            Column(
                Modifier.align(BiasAlignment(0f, profile.glowSubtitleVerticalBias.coerceIn(-0.6f, 0.6f)))
                    .fillMaxWidth().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("声音继续，画面退场。\n需要时，只让当前文字发光。",
                    color = subtitle, fontSize = profile.glowSubtitleSizeSp.sp,
                    lineHeight = (profile.glowSubtitleSizeSp * 1.4f).sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Text("The sound continues as the screen fades away.",
                    color = subtitle.copy(alpha = 0.68f), fontSize = (profile.glowSubtitleSizeSp * 0.68f).sp,
                    textAlign = TextAlign.Center)
            }
            if (selected == GlowAdjustment.Controls) {
                Row(Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.Replay10, "后退 10 秒预览", tint = controls)
                    Icon(Icons.Outlined.Pause, "暂停预览", tint = controls)
                    Icon(Icons.Outlined.Forward10, "前进 10 秒预览", tint = controls)
                }
            }
        }
        if (!purePreview) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("返回", color = ink) }
                Text(if (isOnboarding) "微光校准" else "微光显示", color = ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton(onClick = { purePreview = true }) { Text("纯净预览", color = ink) }
                TextButton(onClick = onComplete) { Text("完成", color = ink) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xFF111214), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { presetsVisible = !presetsVisible }) { Text("预设 · ${profile.name}", color = ink) }
                    Spacer(Modifier.weight(1f))
                    Text(selected.valueLabel(profile), color = ink, fontSize = 18.sp, modifier = Modifier.padding(end = 12.dp))
                }
                if (presetsVisible) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        DisplayProfile.Presets.forEach { preset ->
                            TextButton(onClick = { onProfileChanged(preset); presetsVisible = false }) { Text(preset.name, color = ink) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlowAdjustment.entries.forEach { option ->
                        TextButton(
                            onClick = { selected = option },
                            modifier = Modifier.background(if (selected == option) Color(0xFF2A2C31) else Color.Transparent, RoundedCornerShape(12.dp)),
                        ) { Text(option.label, color = ink, fontSize = 12.sp) }
                    }
                }
                key(selected) {
                    GlowSpringRocker(
                        value = selected.value(profile), range = selected.range, step = selected.step,
                        onValueChange = { onProfileChanged(selected.update(profile, it)) }, ink = ink,
                    )
                }
                Text(if (selected == GlowAdjustment.Position) "向左偏上 · 向右偏下 · 松手停止" else "向左减 · 向右加 · 拖得越远调得越快",
                    color = ink.copy(alpha = 0.6f), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun GlowSpringRocker(
    value: Float, range: ClosedFloatingPointRange<Float>, step: Float,
    onValueChange: (Float) -> Unit, ink: Color,
) {
    var displacement by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    // Keep a fractional accumulator while held so slow movement works for integer settings.
    LaunchedEffect(held) {
        if (held) {
            var current = latestValue
            while (isActive) {
                delay(40)
                val distance = abs(displacement)
                if (distance > 0.12f) {
                    val speed = ((distance - 0.12f) / 0.88f)
                    current = (current + sign(displacement) * step * (2f + 22f * speed * speed) * 0.04f).coerceIn(range)
                    latestChange(current)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { latestChange((latestValue - step).coerceIn(range)) }) { Text("−", color = ink, fontSize = 28.sp) }
        Canvas(
            Modifier.weight(1f).height(64.dp)
                .semantics { contentDescription = "回弹调节杆，向左减少，向右增加" }
                .pointerInput(Unit) {
                    var drag = 0f
                    detectDragGestures(
                        onDragStart = { drag = 0f; held = true },
                        onDragEnd = { held = false; displacement = 0f },
                        onDragCancel = { held = false; displacement = 0f },
                    ) { change, amount ->
                        change.consume()
                        drag = (drag + amount.x).coerceIn(-size.width * 0.38f, size.width * 0.38f)
                        displacement = drag / (size.width * 0.38f).coerceAtLeast(1f)
                    }
                }
        ) {
            val middle = size.height / 2
            drawLine(Color(0xFF34363C), Offset(size.width * 0.12f, middle), Offset(size.width * 0.88f, middle), 4.dp.toPx(), StrokeCap.Round)
            drawLine(ink.copy(alpha = 0.4f), Offset(size.width / 2, middle - 9.dp.toPx()), Offset(size.width / 2, middle + 9.dp.toPx()), 1.dp.toPx())
            val x = size.width / 2 + displacement * size.width * 0.38f
            drawCircle(ink, 17.dp.toPx(), Offset(x, middle))
            drawLine(Color(0xFF34363C), Offset(x - 3.dp.toPx(), middle - 5.dp.toPx()), Offset(x - 3.dp.toPx(), middle + 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF34363C), Offset(x + 3.dp.toPx(), middle - 5.dp.toPx()), Offset(x + 3.dp.toPx(), middle + 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }
        TextButton(onClick = { latestChange((latestValue + step).coerceIn(range)) }) { Text("+", color = ink, fontSize = 26.sp) }
    }
}
