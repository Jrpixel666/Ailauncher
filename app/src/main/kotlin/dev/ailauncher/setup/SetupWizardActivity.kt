package dev.ailauncher.setup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ailauncher.AiLauncherApp
import dev.ailauncher.agents.AgentRegistry
import dev.ailauncher.launcher.LauncherActivity

class SetupWizardActivity : ComponentActivity() {

    private val viewModel: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SetupScreen(
                    viewModel = viewModel,
                    onFinish = {
                        (application as AiLauncherApp).prefs.setupCompleted = true
                        startActivity(Intent(this, LauncherActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
fun SetupScreen(viewModel: SetupViewModel, onFinish: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(24.dp)) {
            Text("Welcome to AiLauncher", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "AiLauncher runs a private, rootless Arch Linux environment (via proot) on this " +
                    "device. Pick the AI agent CLIs to install now — you can add more later from Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(AgentRegistry.all) { agent ->
                    ListItem(
                        headlineContent = { Text(agent.displayName) },
                        supportingContent = { Text(agent.description) },
                        trailingContent = {
                            Checkbox(
                                checked = selected[agent.id] ?: false,
                                onCheckedChange = { checked -> selected[agent.id] = checked },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (state is SetupViewModel.State.Working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text((state as SetupViewModel.State.Working).message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            Row {
                OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f)) {
                    Text("Skip setup")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        val ids = selected.filterValues { it }.keys.toSet()
                        viewModel.runInstall(ids, onFinish)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state !is SetupViewModel.State.Working,
                ) {
                    Text("Install selected")
                }
            }
        }
    }
}
