package com.naveen.civilscompanion.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a function to call when the owner taps a button that needs a permission.
 * If it is already granted, onGranted runs at once; otherwise the system asks first.
 */
@Composable
fun rememberPermissionRequester(permission: String, onDenied: () -> Unit = {}, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted() else denied()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) granted()
        else launcher.launch(permission)
    }
}

@Composable
fun rememberMicPermission(onDenied: () -> Unit = {}, onGranted: () -> Unit): () -> Unit =
    rememberPermissionRequester(Manifest.permission.RECORD_AUDIO, onDenied, onGranted)

@Composable
fun rememberCameraPermission(onDenied: () -> Unit = {}, onGranted: () -> Unit): () -> Unit =
    rememberPermissionRequester(Manifest.permission.CAMERA, onDenied, onGranted)
