package com.mythos.vcd.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythos.vcd.VcdApp
import com.mythos.vcd.ui.call.CallScreen
import com.mythos.vcd.ui.theme.CallColors
import com.mythos.vcd.voip.CallManager

/**
 * The app's home. Three places, always reachable, always in the same order.
 *
 * Before this the app was a home screen full of buttons that launched unrelated-looking screens,
 * which made a set of capabilities feel like a set of separate tools. A person deciding whether to
 * trust what this app says about a caller has more reason to believe a product than a toolbox.
 *
 * A call takes the whole screen when one exists — including the navigation bar. There is no state
 * in which someone is on a call and being invited to wander off to Test Mode.
 */
enum class ShellTab(val label: String, val icon: ImageVector, val description: String) {
    CALLS("Calls", Icons.Filled.Call, "Calls: dial, recents and nearby devices"),
    VOICES("Voices", Icons.Filled.RecordVoiceOver, "Voices: enrolled people this app can recognise"),
    TOOLS("Tools", Icons.Filled.Build, "Tools: test mode and diagnostics"),
}

@Composable
fun AppShell(
    app: VcdApp,
    tab: ShellTab,
    onTabChange: (ShellTab) -> Unit,
    onEnroll: () -> Unit,
    onLive: (Long?) -> Unit,
    onTestMode: () -> Unit,
    onSpike: () -> Unit,
    onPermission: () -> Unit,
) {
    val call by CallManager.state.collectAsStateWithLifecycle()

    // A call owns the display outright. Leaving the navigation bar up during one would invite the
    // user away from the screen carrying the verification result.
    if (call.onCallScreen) {
        CallScreen(app = app, onBack = { onTabChange(ShellTab.CALLS) })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                ShellTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { onTabChange(entry) },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                        modifier = Modifier.semantics { contentDescription = entry.description },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CallColors.brand,
                            selectedTextColor = CallColors.brand,
                            indicatorColor = CallColors.brandMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val inner = Modifier
            .fillMaxSize()
            .padding(padding)

        when (tab) {
            ShellTab.CALLS -> CallScreen(
                app = app,
                onBack = { onTabChange(ShellTab.VOICES) },
                modifier = inner,
            )

            ShellTab.VOICES -> VoicesScreen(
                app = app,
                modifier = inner,
                onEnroll = onEnroll,
                onLive = onLive,
                onPermission = onPermission,
            )

            ShellTab.TOOLS -> ToolsScreen(
                app = app,
                modifier = inner,
                onTestMode = onTestMode,
                onSpike = onSpike,
                onPermission = onPermission,
            )
        }
    }
}
