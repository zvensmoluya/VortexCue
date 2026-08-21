package io.github.zvensmoluya.vortexcue.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.zvensmoluya.vortexcue.playback.PlaybackService
import io.github.zvensmoluya.vortexcue.ui.theme.VortexCueTheme

class MainActivity : ComponentActivity() {
    private val controllerState = mutableStateOf<MediaController?>(null)
    private val connectionErrorState = mutableStateOf<String?>(null)
    private val externalMediaRequestState = mutableStateOf<ExternalMediaRequest?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var nextExternalMediaRequestId = 0L
    private var immersiveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreExternalMediaRequest(savedInstanceState)
        publishExternalMediaRequest(intent)

        setContent {
            VortexCueTheme {
                val controller = controllerState.value
                if (controller != null) {
                    VortexCueApp(
                        player = controller,
                        onWindowBrightnessChanged = ::setWindowBrightness,
                        onImmersiveModeChanged = ::setImmersiveMode,
                        onPlaybackActiveChanged = ::setKeepScreenOn,
                        externalMediaRequest = externalMediaRequestState.value,
                        onExternalMediaRequestConsumed = ::consumeExternalMediaRequest,
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = connectionErrorState.value ?: "正在连接播放器…",
                            color = Color(0xFF666666),
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        connectController()
        applySystemBars()
    }

    override fun onDestroy() {
        setKeepScreenOn(false)
        controllerState.value = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    override fun onPostResume() {
        super.onPostResume()
        appContainer.syncTextScheduler.onAppForeground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    private fun connectController() {
        connectionErrorState.value = null
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture === future) {
                    runCatching { future.get() }
                        .onSuccess { controllerState.value = it }
                        .onFailure { connectionErrorState.value = "无法连接后台播放器" }
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun publishExternalMediaRequest(intent: Intent) {
        val uri = intent.data ?: return
        if (
            !isSupportedExternalMediaRequest(
                action = intent.action,
                scheme = uri.scheme,
                mimeType = intent.type,
                uriValue = uri.toString(),
            )
        ) {
            return
        }
        nextExternalMediaRequestId += 1L
        externalMediaRequestState.value = ExternalMediaRequest(nextExternalMediaRequestId, uri)
        // 请求已经转存到显式状态，避免 Activity 重用同一个 Intent 时重复消费。
        setIntent(Intent(Intent.ACTION_MAIN).setComponent(componentName))
    }

    private fun restoreExternalMediaRequest(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        nextExternalMediaRequestId = state.getLong(ExternalRequestSequenceKey)
        val requestId = state.getLong(ExternalRequestIdKey)
        val uri = state.getString(ExternalRequestUriKey)?.let(Uri::parse)
        if (requestId > 0L && uri != null) {
            externalMediaRequestState.value = ExternalMediaRequest(requestId, uri)
        }
    }

    private fun consumeExternalMediaRequest(requestId: Long) {
        if (externalMediaRequestState.value?.id == requestId) {
            externalMediaRequestState.value = null
        }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setWindowBrightness(value: Float?) {
        val params = window.attributes
        params.screenBrightness =
            value?.coerceIn(0.01f, 1f)
                ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    private fun setImmersiveMode(enabled: Boolean) {
        if (immersiveMode == enabled) return
        immersiveMode = enabled
        setImmersive(enabled)
        applySystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(ExternalRequestSequenceKey, nextExternalMediaRequestId)
        externalMediaRequestState.value?.let { request ->
            outState.putLong(ExternalRequestIdKey, request.id)
            outState.putString(ExternalRequestUriKey, request.uri.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        publishExternalMediaRequest(intent)
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!immersiveMode)
            window.decorView.windowInsetsController?.run {
                if (immersiveMode) {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    show(WindowInsets.Type.systemBars())
                }
            }
        } else if (immersiveMode) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private companion object {
        const val ExternalRequestSequenceKey = "external_request_sequence"
        const val ExternalRequestIdKey = "external_request_id"
        const val ExternalRequestUriKey = "external_request_uri"
    }
}
