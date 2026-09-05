package io.github.zvensmoluya.vortexcue.playback

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import io.github.zvensmoluya.vortexcue.R

@UnstableApi
internal class PlaybackNotificationProvider(
    private val context: Context,
    private val delegate: MediaNotification.Provider = DefaultMediaNotificationProvider(context),
) : MediaNotification.Provider by delegate {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = padControls(
        delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) {
            onNotificationChangedCallback.onNotificationChanged(padControls(it))
        }
    )

    private fun padControls(mediaNotification: MediaNotification): MediaNotification {
        val original = mediaNotification.notification
        val actions = original.actions ?: return mediaNotification
        if (actions.size != 3) return mediaNotification

        // OriginOS distributes native actions across five positions. Keep the transport
        // controls in the middle without replacing the system's media notification layout.
        val spacer = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification_spacer), "", null
        ).build()
        val notification = original.clone().apply {
            this.actions = arrayOf(spacer, *actions, spacer)
            extras.putIntArray(Notification.EXTRA_COMPACT_ACTIONS, intArrayOf(1, 2, 3))
        }
        return MediaNotification(mediaNotification.notificationId, notification)
    }
}
