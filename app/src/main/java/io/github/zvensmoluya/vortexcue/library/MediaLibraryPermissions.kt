package io.github.zvensmoluya.vortexcue.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun requiredAudioLibraryPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun requiredVideoLibraryPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun requiredMediaLibraryPermissions(): Array<String> =
    arrayOf(requiredAudioLibraryPermission(), requiredVideoLibraryPermission())
        .distinct()
        .toTypedArray()

fun Context.hasAudioLibraryPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredAudioLibraryPermission()) ==
        PackageManager.PERMISSION_GRANTED

fun Context.hasVideoLibraryPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredVideoLibraryPermission()) ==
        PackageManager.PERMISSION_GRANTED
