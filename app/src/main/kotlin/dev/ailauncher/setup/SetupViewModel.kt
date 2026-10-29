package dev.ailauncher.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ailauncher.AiLauncherApp
import dev.ailauncher.agents.AgentRegistry
import dev.ailauncher.agents.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    sealed class State {
        data object Idle : State()
        data class Working(val message: String) : State()
        data class Done(val installedIds: Set<String>) : State()
        data class Failed(val reason: String) : State()
    }

    private val runtime = ProotRuntime(app)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun runInstall(agentIds: Set<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (!runtime.isInstalled) {
                        runtime.installRootfs { msg -> _state.value = State.Working(msg) }
                            .getOrThrow()
                        (getApplication<AiLauncherApp>()).prefs.rootfsInstalled = true
                    }
                    for (id in agentIds) {
                        val spec = AgentRegistry.byId(id) ?: continue
                        runtime.installAgent(spec) { line -> _state.value = State.Working(line) }
                            .getOrThrow()
                    }
                    (getApplication<AiLauncherApp>()).prefs.installedAgentIds =
                        (getApplication<AiLauncherApp>()).prefs.installedAgentIds + agentIds
                    _state.value = State.Done(agentIds)
                } catch (t: Throwable) {
                    _state.value = State.Failed(t.message ?: "unknown error")
                }
            }
            onDone()
        }
    }
}
