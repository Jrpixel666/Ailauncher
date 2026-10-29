package dev.ailauncher.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the proot Arch runtime and keeps agent CLI sessions alive
 * across configuration changes / the launcher UI being backgrounded (e.g. while the user
 * is looking at a bridged Linux GUI window instead of the AiLauncher chat panel).
 */
class AgentManagerService : Service() {

    private lateinit var runtime: ProotRuntime
    private val sessions = mutableMapOf<String, Process>()

    inner class LocalBinder : Binder() {
        fun service(): AgentManagerService = this@AgentManagerService
    }

    override fun onCreate() {
        super.onCreate()
        runtime = ProotRuntime(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    /**
     * Starts [agentId]'s CLI process if it isn't already running. Idempotent and synchronous
     * so callers (e.g. [dev.ailauncher.agents.chat.AgentChatController.send]) can call this on
     * every chat turn and immediately follow with [sendInput] without a race: calling it again
     * for an already-running session is a no-op rather than spawning a second process that
     * orphans the first.
     */
    fun startAgentSession(agentId: String, workingDir: String, onOutput: (String) -> Unit) {
        if (sessions[agentId]?.isAlive == true) return
        val spec = AgentRegistry.byId(agentId) ?: return
        sessions[agentId] = runtime.launchAgent(spec, workingDir, onOutput)
    }

    fun stopAgentSession(agentId: String) {
        sessions.remove(agentId)?.destroy()
    }

    fun sendInput(agentId: String, text: String) {
        sessions[agentId]?.outputStream?.let {
            it.write((text + "\n").toByteArray())
            it.flush()
        }
    }

    fun runtime(): ProotRuntime = runtime

    private fun buildNotification(): Notification {
        val channelId = "ailauncher_agents"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "AI agents", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AiLauncher")
            .setContentText("AI agents running in background")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sessions.values.forEach { it.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
