package com.mythos.vcd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythos.vcd.ui.call.CallScreen
import com.mythos.vcd.voip.CallManager
import com.mythos.vcd.ui.enroll.EnrollScreen
import com.mythos.vcd.ui.shell.AppShell
import com.mythos.vcd.ui.shell.ShellTab
import com.mythos.vcd.ui.live.LiveVerificationScreen
import com.mythos.vcd.ui.permission.PermissionScreen
import com.mythos.vcd.ui.spike.CaptureSpikeScreen
import com.mythos.vcd.ui.testmode.TestModeScreen
import com.mythos.vcd.ui.theme.VcdTheme

object Routes {
    const val HOME = "home"
    const val PERMISSION = "permission"
    const val ENROLL = "enroll"
    const val TEST_MODE = "test"
    const val SPIKE = "spike"
    const val CALL = "call"
    const val LIVE = "live/{contactId}"

    fun live(contactId: Long?) = "live/${contactId ?: -1L}"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VcdTheme {
                // enableEdgeToEdge draws behind the status and navigation bars, which is
                // mandatory at targetSdk 35. Without consuming the insets somewhere, the top of
                // every screen sits under the clock — including the disclosure banner on Live
                // Verification, which is the one thing in this app that must never be obscured.
                // The Surface still paints the full window, so the inset strips take the app
                // background rather than showing through.
                Surface(Modifier.fillMaxSize()) {
                    VcdNavHost(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                }
            }
        }
    }
}

@Composable
private fun VcdNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VcdApp
    val call by CallManager.state.collectAsStateWithLifecycle()
    var shellTab by remember { mutableStateOf(ShellTab.CALLS) }

    // A ringing phone goes to the call screen wherever the user happens to be. Leaving them on the
    // home screen while a notification quietly claims someone is calling is not how a phone
    // behaves, and the accept and decline buttons live here.
    LaunchedEffect(call.active) {
        if (call.active) {
            shellTab = ShellTab.CALLS
            if (nav.currentDestination?.route != Routes.HOME) {
                nav.popBackStack(Routes.HOME, inclusive = false)
            }
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            AppShell(
                app = app,
                tab = shellTab,
                onTabChange = { shellTab = it },
                onEnroll = { nav.navigate(Routes.ENROLL) },
                onLive = { contactId -> nav.navigate(Routes.live(contactId)) },
                onTestMode = { nav.navigate(Routes.TEST_MODE) },
                onSpike = { nav.navigate(Routes.SPIKE) },
                onPermission = { nav.navigate(Routes.PERMISSION) },
            )
        }
        composable(Routes.PERMISSION) {
            PermissionScreen(
                onDone = { nav.popBackStack() },
                onTestMode = {
                    nav.popBackStack()
                    nav.navigate(Routes.TEST_MODE)
                },
            )
        }
        composable(Routes.ENROLL) {
            EnrollScreen(app = app, onDone = { nav.popBackStack() })
        }
        composable(Routes.TEST_MODE) {
            TestModeScreen(app = app, onBack = { nav.popBackStack() })
        }
        composable(Routes.SPIKE) {
            CaptureSpikeScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.LIVE,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("contactId") ?: -1L
            LiveVerificationScreen(
                app = app,
                contactId = id.takeIf { it >= 0 },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
