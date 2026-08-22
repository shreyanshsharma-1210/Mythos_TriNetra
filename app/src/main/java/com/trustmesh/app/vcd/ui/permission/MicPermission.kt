package com.trustmesh.app.vcd.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Microphone permission state, with the three outcomes that actually differ in the UI:
 * granted, refusable (ask again), and permanently denied (only Settings can fix it).
 */
class MicPermissionState internal constructor(
    val granted: Boolean,
    val permanentlyDenied: Boolean,
    private val requestFn: () -> Unit,
    private val openSettingsFn: () -> Unit,
) {
    fun request() = requestFn()
    fun openSettings() = openSettingsFn()
}

fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Notification permission is requested alongside the microphone on Android 13+, because the
 * foreground-service notification is part of how capture is disclosed. If the user refuses it, the
 * service still runs and still posts the notification — Android keeps showing it for a
 * microphone-type foreground service — but the on-screen banner carries the disclosure regardless.
 */
@Composable
fun rememberMicPermissionState(): MicPermissionState {
    val context = LocalContext.current
    val activity = context as? Activity

    var granted by remember { mutableStateOf(hasMicPermission(context)) }
    var askedOnce by remember { mutableStateOf(false) }
    var rationaleAfterDenial by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result[Manifest.permission.RECORD_AUDIO] == true
        askedOnce = true
        rationaleAfterDenial = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it, Manifest.permission.RECORD_AUDIO,
            )
        } ?: false
    }

    return MicPermissionState(
        granted = granted,
        // "Don't ask again" looks like: we have asked, we were denied, and the system now says a
        // rationale would not be shown. Only Settings can recover from that.
        permanentlyDenied = askedOnce && !granted && !rationaleAfterDenial,
        requestFn = {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            launcher.launch(perms.toTypedArray())
        },
        openSettingsFn = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    )
}
