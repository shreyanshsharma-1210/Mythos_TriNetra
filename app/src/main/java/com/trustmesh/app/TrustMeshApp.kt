package com.trustmesh.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trustmesh.app.ui.components.TrustMeshTopBar
import com.trustmesh.app.ui.screens.history.HistoryScreen
import com.trustmesh.app.ui.screens.home.HomeScreen
import com.trustmesh.app.ui.screens.onboarding.OnboardingScreen
import com.trustmesh.app.ui.screens.protection.NavigationTrigger
import com.trustmesh.app.ui.screens.report.ReportScreen
import com.trustmesh.app.ui.screens.settings.SettingsScreen
import com.trustmesh.app.ui.theme.*

@Composable
fun TrustMeshApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "onboarding"

    LaunchedEffect(Unit) {
        NavigationTrigger.navigationEvents.collect { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    val isOnboarding = currentRoute == "onboarding"
    val isReport = currentRoute.startsWith("report")

    Scaffold(
        topBar = {
            if (!isOnboarding && currentRoute != "home") {
                val title = if (currentRoute == "settings") "Settings" else "Trinetra"
                TrustMeshTopBar(
                    title = title,
                    onBackClick = { navController.navigateUp() }
                )
            }
        }
    ) { paddingValues ->
        // For onboarding we want 0 padding — it manages insets itself
        val contentModifier = if (isOnboarding) Modifier else Modifier.padding(paddingValues)

        NavHost(
            navController = navController,
            startDestination = "onboarding",
            modifier = contentModifier
        ) {
            composable("onboarding") {
                OnboardingScreen(onFinish = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                })
            }
            composable("home") {
                HomeScreen(
                    onInteractionClick = { id -> navController.navigate("report/$id") },
                    onSettingsClick = { navController.navigate("settings") }
                )
            }
            composable("history") {
                HistoryScreen(onInteractionClick = { id -> navController.navigate("report/$id") })
            }
            composable("report/{id}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: ""
                ReportScreen(interactionId = id)
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
