package com.trustmesh.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.interaction.InteractionManager
import com.trustmesh.app.ui.components.TrustMeshTopBar
import com.trustmesh.app.ui.screens.history.HistoryScreen
import com.trustmesh.app.ui.screens.home.HomeScreen
import com.trustmesh.app.ui.screens.onboarding.OnboardingScreen
import com.trustmesh.app.ui.screens.report.ReportScreen
import com.trustmesh.app.ui.screens.settings.SettingsScreen
import com.trustmesh.app.ui.theme.*

import androidx.compose.runtime.LaunchedEffect
import com.trustmesh.app.ui.screens.protection.NavigationTrigger

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


    Scaffold(
        topBar = { 
            if (currentRoute != "onboarding") {
                TrustMeshTopBar("TrustMesh")
            }
        },
        bottomBar = {
            if (currentRoute != "onboarding" && !(currentRoute.startsWith("report"))) {
                NavigationBar(
                    containerColor = TrustMeshSurface
                ) {
                    NavigationBarItem(
                        selected = currentRoute == "home",
                        onClick = { navController.navigate("home") { launchSingleTop = true } },
                        label = { Text("Home") },
                        icon = { },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = SecurityAccent,
                            unselectedTextColor = TextSecondary
                        )
                    )
                    NavigationBarItem(
                        selected = currentRoute == "history",
                        onClick = { navController.navigate("history") { launchSingleTop = true } },
                        label = { Text("History") },
                        icon = { },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = SecurityAccent,
                            unselectedTextColor = TextSecondary
                        )
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true } },
                        label = { Text("Settings") },
                        icon = { },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = SecurityAccent,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "onboarding",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("onboarding") {
                OnboardingScreen(onFinish = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                })
            }
            composable("home") {
                HomeScreen(onInteractionClick = { id -> navController.navigate("report/$id") })
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
