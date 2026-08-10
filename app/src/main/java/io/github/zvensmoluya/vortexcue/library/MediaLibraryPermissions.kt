package io.github.zvensmoluya.vortexcue.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class VideoLibraryAccess {
    None,
    Partial,
    Full,
}

fun requiredAudioLibraryPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun requiredVideoLibraryPermissions(): Array<String> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

fun mediaLibraryPermissionsToRequest(context: Context): Array<String> =
    buildList {
        if (!context.hasAudioLibraryPermission()) add(requiredAudioLibraryPermission())
        if (context.videoLibraryAccess() != VideoLibraryAccess.Full) {
            addAll(requiredVideoLibraryPermissions())
        }
    }.distinct().toTypedArray()

fun Context.hasAudioLibraryPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredAudioLibraryPermission()) ==
        PackageManager.PERMISSION_GRANTED

fun Context.videoLibraryAccess(): VideoLibraryAccess {
    val fullAccessPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    val hasFullAccess =
        ContextCompat.checkSelfPermission(this, fullAccessPermission) ==
            PackageManager.PERMISSION_GRANTED
    val hasSelectedAccess =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
    return resolveVideoLibraryAccess(hasFullAccess, hasSelectedAccess)
}

internal fun resolveVideoLibraryAccess(
    hasFullAccess: Boolean,
    hasSelectedAccess: Boolean,
): VideoLibraryAccess =
    when {
        hasFullAccess -> VideoLibraryAccess.Full
        hasSelectedAccess -> VideoLibraryAccess.Partial
        else -> VideoLibraryAccess.None
    }
