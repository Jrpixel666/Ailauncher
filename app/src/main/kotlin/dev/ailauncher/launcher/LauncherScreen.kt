package dev.ailauncher.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ailauncher.agents.chat.AgentChatPanel
import dev.ailauncher.launcher.components.AppDrawer
import dev.ailauncher.widgets.WidgetSurface

/**
 * Three panes reachable by horizontal swipe on the home screen, mirroring a conventional
 * launcher's layout but with an agent chat pane standing in for a search bar:
 *   [ widgets/home ]  [ app drawer ]  [ AI agent panel ]
 */
@Composable
fun LauncherScreen() {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Widgets, contentDescription = "Home") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Apps, contentDescription = "Apps") },
                    label = { Text("Apps") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.SmartToy, contentDescription = "Agents") },
                    label = { Text("Agents") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> WidgetSurface()
                1 -> AppDrawer()
                2 -> AgentChatPanel()
            }
        }
    }
}
