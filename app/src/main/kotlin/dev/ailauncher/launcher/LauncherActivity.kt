package dev.ailauncher.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.ailauncher.AiLauncherApp
import dev.ailauncher.agents.AgentManagerService
import dev.ailauncher.setup.SetupWizardActivity
import dev.ailauncher.ui.theme.AiLauncherTheme
import dev.ailauncher.window.WindowBridgeService

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = (application as AiLauncherApp).prefs
        if (!prefs.setupCompleted) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }

        // Keep the agent runtime + Linux window bridge alive as long-running foreground
        // services, independent of this Activity's lifecycle.
        startService(Intent(this, AgentManagerService::class.java))
        startService(Intent(this, WindowBridgeService::class.java))

        setContent {
            AiLauncherTheme {
                LauncherScreen()
            }
        }
    }

    override fun onBackPressed() {
        // Home screens never navigate back out.
    }
}
