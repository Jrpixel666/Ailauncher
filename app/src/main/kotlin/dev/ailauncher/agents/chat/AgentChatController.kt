package dev.ailauncher.agents.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.ailauncher.agents.AgentManagerService

/** Binds to [AgentManagerService] and relays chat turns to/from the running agent CLI process. */
class AgentChatController(private val context: Context) {

    private var service: AgentManagerService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AgentManagerService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    init {
        context.bindService(Intent(context, AgentManagerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun send(agentId: String, text: String, onReply: (String) -> Unit) {
        val svc = service ?: run {
            onReply("(agent service not connected yet — try again in a moment)")
            return
        }
        svc.startAgentSession(agentId, workingDir = "/home/ailauncher", onOutput = onReply)
        svc.sendInput(agentId, text)
    }
}
