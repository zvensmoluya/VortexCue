package io.github.zvensmoluya.vortexcue

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK

        setContent {
            VortexCueTheme {
                GlowPrototype(onWindowBrightnessChanged = ::setWindowBrightness)
            }
        }

        // The decor view is created by setContent; access its insets controller afterwards.
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setWindowBrightness(value: Float) {
        val params = window.attributes
        params.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = params
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.run {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}

@Composable
private fun GlowPrototype(
    onWindowBrightnessChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var subtitleGray by rememberSaveable { mutableFloatStateOf(12f) }
    var windowBrightness by rememberSaveable { mutableFloatStateOf(0.08f) }

    LaunchedEffect(windowBrightness) {
        onWindowBrightnessChanged(windowBrightness)
    }

    val gray = subtitleGray.toInt().coerceIn(0, 255)
    val subtitleColor = Color(gray, gray, gray)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                },
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset(y = 56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "夜深了，世界安静下来。",
                color = subtitleColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Only the words emit light.",
                color = subtitleColor,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
            )
        }

        if (controlsVisible) {
            CalibrationControls(
                subtitleGray = subtitleGray,
                onSubtitleGrayChanged = { subtitleGray = it },
                windowBrightness = windowBrightness,
                onWindowBrightnessChanged = { windowBrightness = it },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 24.dp, vertical = 28.dp),
            )
        }
    }
}

@Composable
private fun CalibrationControls(
    subtitleGray: Float,
    onSubtitleGrayChanged: (Float) -> Unit,
    windowBrightness: Float,
    onWindowBrightnessChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlColor = Color(0xFF484848)
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = controlColor,
            activeTrackColor = Color(0xFF303030),
            inactiveTrackColor = Color(0xFF101010),
        )

    Column(modifier = modifier) {
        Text(
            text = "窗口亮度  ${(windowBrightness * 100).toInt()}%",
            color = controlColor,
            fontSize = 13.sp,
        )
        Slider(
            value = windowBrightness,
            onValueChange = onWindowBrightnessChanged,
            valueRange = 0.01f..0.25f,
            colors = sliderColors,
        )
        Text(
            text = "字幕灰阶  RGB ${subtitleGray.toInt()}",
            color = controlColor,
            fontSize = 13.sp,
        )
        Slider(
            value = subtitleGray,
            onValueChange = onSubtitleGrayChanged,
            valueRange = 2f..32f,
            colors = sliderColors,
        )
        Text(
            text = "调到刚刚能看清，然后点按屏幕隐藏控制",
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF383838),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VortexCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                primary = Color(0xFF484848),
                onBackground = Color(0xFF484848),
                onSurface = Color(0xFF484848),
            ),
        content = content,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GlowPrototypePreview() {
    VortexCueTheme {
        GlowPrototype(onWindowBrightnessChanged = {})
    }
}
