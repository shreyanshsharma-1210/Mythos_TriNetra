package com.trustmesh.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trustmesh.app.ui.screens.history.HistoryScreen
import com.trustmesh.app.ui.screens.home.HomeScreen
import com.trustmesh.app.ui.screens.onboarding.OnboardingScreen
import com.trustmesh.app.ui.screens.protection.NavigationTrigger
import com.trustmesh.app.ui.screens.report.ReportScreen
import com.trustmesh.app.ui.screens.settings.SettingsScreen
import com.trustmesh.app.ui.theme.*
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.ui.enroll.EnrollScreen
import com.trustmesh.app.vcd.ui.live.LiveVerificationScreen
import com.trustmesh.app.vcd.ui.permission.PermissionScreen
import com.trustmesh.app.vcd.ui.shell.AppShell
import com.trustmesh.app.vcd.ui.shell.ShellTab
import com.trustmesh.app.vcd.ui.spike.CaptureSpikeScreen
import com.trustmesh.app.vcd.ui.testmode.TestModeScreen
import com.trustmesh.app.vcd.voip.CallManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ── VCD sub-route keys ──────────────────────────────────────────────────────
private const val ROUTE_VCD_HOME    = "vcd_home"
private const val ROUTE_VCD_ENROLL  = "vcd_enroll"
private const val ROUTE_VCD_PERM    = "vcd_permission"
private const val ROUTE_VCD_TEST    = "vcd_test"
private const val ROUTE_VCD_SPIKE   = "vcd_spike"
private const val ROUTE_VCD_LIVE    = "vcd_live/{contactId}"
private fun routeVcdLive(id: Long?) = "vcd_live/${id ?: -1L}"

@Composable
fun TrustMeshApp() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("trinetra_prefs", android.content.Context.MODE_PRIVATE)
    val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // VCD: when a VoIP call comes in, jump to VCD home (Calls tab)
    val call by CallManager.state.collectAsStateWithLifecycle()
    var vcdShellTab by remember { mutableStateOf(ShellTab.CALLS) }
    LaunchedEffect(call.active) {
        if (call.active) {
            vcdShellTab = ShellTab.CALLS
            if (navController.currentDestination?.route == "home") {
                navController.navigate(ROUTE_VCD_HOME)
            }
        }
    }

    LaunchedEffect(Unit) {
        NavigationTrigger.navigationEvents.collect { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (isFirstLaunch) "onboarding" else "home",
        modifier = Modifier
    ) {
        composable("onboarding") {
            val context = androidx.compose.ui.platform.LocalContext.current
            OnboardingScreen(onFinish = {
                val prefs = context.getSharedPreferences("trinetra_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putBoolean("isFirstLaunch", false).apply()
                navController.navigate("home") {
                    popUpTo("onboarding") { inclusive = true }
                }
            })
        }
        composable("home") {
            HomeScreen(
                onInteractionClick = { id -> navController.navigate("report/$id") },
                onSettingsClick = { navController.navigate("settings") },
                onSecurityInsightsClick = { navController.navigate(ROUTE_VCD_HOME) }
            )
        }
        composable("history") {
            HistoryScreen(onInteractionClick = { id -> navController.navigate("report/$id") })
        }
        composable("report/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            ReportScreen(
                interactionId = id,
                onBackClick = { navController.navigateUp() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBackClick = { navController.navigateUp() }
            )
        }

        // ── Voice Clone Defence Module routes ─────────────────────────────
        composable(ROUTE_VCD_HOME) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VcdApp
            AppShell(
                app = app,
                tab = vcdShellTab,
                onTabChange = { vcdShellTab = it },
                onEnroll     = { navController.navigate(ROUTE_VCD_ENROLL) },
                onLive       = { id -> navController.navigate(routeVcdLive(id)) },
                onTestMode   = { navController.navigate(ROUTE_VCD_TEST) },
                onSpike      = { navController.navigate(ROUTE_VCD_SPIKE) },
                onPermission = { navController.navigate(ROUTE_VCD_PERM) },
            )
        }
        composable(ROUTE_VCD_PERM) {
            PermissionScreen(
                onDone = { navController.popBackStack() },
                onTestMode = {
                    navController.popBackStack()
                    navController.navigate(ROUTE_VCD_TEST)
                },
            )
        }
        composable(ROUTE_VCD_ENROLL) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VcdApp
            EnrollScreen(app = app, onDone = { navController.popBackStack() })
        }
        composable(ROUTE_VCD_TEST) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VcdApp
            TestModeScreen(app = app, onBack = { navController.popBackStack() })
        }
        composable(ROUTE_VCD_SPIKE) {
            CaptureSpikeScreen(onBack = { navController.popBackStack() })
        }
        composable(
            ROUTE_VCD_LIVE,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType }),
        ) { entry ->
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VcdApp
            val id = entry.arguments?.getLong("contactId") ?: -1L
            LiveVerificationScreen(
                app = app,
                contactId = id.takeIf { it >= 0 },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
