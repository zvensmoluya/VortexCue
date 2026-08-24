package io.github.zvensmoluya.vortexcue.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal fun requiredAudioLibraryPermission(sdkInt: Int): String =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun requiredAudioLibraryPermission(): String = requiredAudioLibraryPermission(Build.VERSION.SDK_INT)

fun mediaLibraryPermissionsToRequest(context: Context): Array<String> =
    if (context.hasAudioLibraryPermission()) emptyArray() else arrayOf(requiredAudioLibraryPermission())

fun Context.hasAudioLibraryPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredAudioLibraryPermission()) ==
        PackageManager.PERMISSION_GRANTED
