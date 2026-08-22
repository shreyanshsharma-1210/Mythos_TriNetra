package com.trustmesh.app.vcd.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.service.VoipCallService
import com.trustmesh.app.vcd.ui.permission.rememberMicPermissionState
import com.trustmesh.app.vcd.voip.CallManager
import com.trustmesh.app.vcd.voip.CallStage

/**
 * Entry point for the calling module.
 *
 * Routes between the dialler and the call UI on one rule: if there is a call — ringing, connected,
 * or just finished — the call takes the whole screen, the way it does on any phone. Everything that
 * is not a call lives in the dialler.
 */
@Composable
fun CallScreen(app: VcdApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permission = rememberMicPermissionState()
    val state by CallManager.state.collectAsStateWithLifecycle()

    // The service follows the call, not the screen: a call must survive the user switching apps.
    LaunchedEffect(state.active, state.remoteName) {
        if (state.active) VoipCallService.start(context, state.remoteName)
        else VoipCallService.stop(context)
    }

    DisposableEffect(Unit) {
        onDispose { VoipCallService.stop(context) }
    }

    BackHandler(enabled = state.onCallScreen) {
        if (state.stage == CallStage.ENDED || state.stage == CallStage.FAILED) {
            CallManager.dismiss()
        } else {
            CallManager.hangUp()
        }
    }

    if (state.onCallScreen) {
        ActiveCallScreen(state = state, onDismiss = { CallManager.dismiss() })
    } else {
        DialerScreen(
            app = app,
            micGranted = permission.granted,
            onRequestMic = permission::request,
            modifier = modifier,
        )
    }
}

/** A coloured disc with the caller's initial. Cheap, and it reads as a person rather than an IP. */
@Composable
fun Avatar(name: String?, size: Int) {
    // Plenty of real address books hold entries with no name at all, where the "name" is the
    // number itself. A "+" in a circle looks like a bug, so those fall back to a handset glyph.
    val initial = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
        ?: if (name?.any { it.isDigit() } == true) "☎" else "?"
    val palette = listOf(0xFF3F6FE0, 0xFF2E9E7B, 0xFF9A5BD1, 0xFFD1745B, 0xFF4FA3C7)
    val colour = androidx.compose.ui.graphics.Color(
        palette[((name ?: "").hashCode().let { if (it < 0) -it else it }) % palette.size]
    )
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colour),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            initial,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size / 2.4f).sp,
        )
    }
}
