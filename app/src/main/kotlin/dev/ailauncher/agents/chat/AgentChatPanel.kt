package dev.ailauncher.agents.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ailauncher.AiLauncherApp
import dev.ailauncher.agents.AgentRegistry

data class ChatLine(val fromAgent: Boolean, val text: String)

/**
 * The "agentic extras" surface: pick an installed agent, chat with it, and trigger
 * launcher-level actions (open a widget, spawn a bridged Linux GUI app, etc.) — those
 * actions are exposed to the agent as simple shell commands it can run inside its own
 * proot session, e.g. `am start ...`-style helper scripts installed alongside the CLIs.
 */
@Composable
fun AgentChatPanel() {
    val context = LocalContext.current
    val installedIds = (context.applicationContext as AiLauncherApp).prefs.installedAgentIds
    val agents = AgentRegistry.all.filter { it.id in installedIds }

    var selectedAgentId by remember { mutableStateOf(agents.firstOrNull()?.id) }
    val lines = remember { mutableStateListOf<ChatLine>() }
    var input by remember { mutableStateOf("") }
    val controller = remember { AgentChatController(context) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (agents.isEmpty()) {
            Text("No agents installed yet. Open Settings → Setup to install one.")
            return@Column
        }

        ExposedDropdownMenuBoxPlaceholder(agents.map { it.displayName }, selectedAgentId) { idx ->
            selectedAgentId = agents[idx].id
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { line ->
                Text(if (line.fromAgent) "🤖 ${line.text}" else "🧑 ${line.text}")
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask your agent…") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val agentId = selectedAgentId ?: return@Button
                lines.add(ChatLine(fromAgent = false, text = input))
                controller.send(agentId, input) { reply -> lines.add(ChatLine(fromAgent = true, text = reply)) }
                input = ""
            }) { Text("Send") }
        }
    }
}

@Composable
private fun ExposedDropdownMenuBoxPlaceholder(options: List<String>, selectedId: String?, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(options.getOrNull(0) ?: "Select agent")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { idx, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(idx); expanded = false })
            }
        }
    }
}
