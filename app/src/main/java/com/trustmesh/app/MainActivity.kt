package com.trustmesh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trustmesh.app.ui.theme.TrustMeshTheme
import com.trustmesh.app.ui.screens.protection.NavigationTrigger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.trustmesh.app.interaction.InteractionManager.init(this)
        com.trustmesh.app.core.incident.SecurityIncidentManager.init(this)
        handleIntent(intent)
        setContent {
            TrustMeshTheme {
                TrustMeshApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val route = intent?.getStringExtra("navigate_to")
        if (!route.isNullOrEmpty()) {
            NavigationTrigger.triggerNavigation(route)
        }
    }
}

