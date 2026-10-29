package dev.ailauncher.agents

/**
 * One entry per selectable AI agent CLI shown in the first-launch install picker.
 * [installCmd] runs inside the proot Arch rootfs (as the unprivileged `ailauncher` user,
 * via `proot-distro login archlinux --user ailauncher -- bash -lc '<installCmd>'`).
 *
 * Install methods favor each tool's own published install path (npm, pipx, curl|sh,
 * or a prebuilt binary release) so this list stays correct as the tools update
 * themselves; AiLauncher does not vendor or pin agent binaries itself.
 */
data class AgentSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val installCmd: String,
    val launchCmd: String,
    val requiresApiKeyEnv: String? = null,
)

object AgentRegistry {
    val all: List<AgentSpec> = listOf(
        AgentSpec(
            id = "claude",
            displayName = "Claude Code",
            description = "Anthropic's agentic coding CLI",
            installCmd = "npm install -g @anthropic-ai/claude-code",
            launchCmd = "claude",
            requiresApiKeyEnv = "ANTHROPIC_API_KEY",
        ),
        AgentSpec(
            id = "opencode",
            displayName = "OpenCode",
            description = "Open-source terminal coding agent",
            installCmd = "curl -fsSL https://opencode.ai/install | bash",
            launchCmd = "opencode",
        ),
        AgentSpec(
            id = "codex",
            displayName = "Codex CLI",
            description = "OpenAI's agentic coding CLI",
            installCmd = "npm install -g @openai/codex",
            launchCmd = "codex",
            requiresApiKeyEnv = "OPENAI_API_KEY",
        ),
        AgentSpec(
            id = "grok",
            displayName = "Grok CLI",
            description = "xAI Grok coding agent CLI",
            installCmd = "npm install -g @vibe-kit/grok-cli",
            launchCmd = "grok",
            requiresApiKeyEnv = "XAI_API_KEY",
        ),
        AgentSpec(
            id = "copilot",
            displayName = "GitHub Copilot CLI",
            description = "GitHub's agentic CLI",
            installCmd = "npm install -g @github/copilot",
            launchCmd = "copilot",
        ),
        AgentSpec(
            id = "crush",
            displayName = "Crush",
            description = "Charm's multi-model terminal coding agent",
            installCmd = "curl -fsSL https://charm.sh/crush/install.sh | bash",
            launchCmd = "crush",
        ),
        AgentSpec(
            id = "cursor-agent",
            displayName = "Cursor Agent",
            description = "Cursor's headless CLI agent",
            installCmd = "curl -fsSL https://cursor.com/install | bash",
            launchCmd = "cursor-agent",
        ),
        AgentSpec(
            id = "agy",
            displayName = "Agy",
            description = "Lightweight terminal agent runner",
            installCmd = "npm install -g agy-cli",
            launchCmd = "agy",
        ),
        AgentSpec(
            id = "pi",
            displayName = "Pi",
            description = "Pi agent CLI",
            installCmd = "pipx install pi-cli",
            launchCmd = "pi",
        ),
        AgentSpec(
            id = "omp",
            displayName = "OMP",
            description = "Open multi-provider agent CLI",
            installCmd = "pipx install omp-cli",
            launchCmd = "omp",
        ),
        AgentSpec(
            id = "ori",
            displayName = "Ori",
            description = "Ori coding agent CLI",
            installCmd = "npm install -g ori-cli",
            launchCmd = "ori",
        ),
        AgentSpec(
            id = "muse",
            displayName = "Muse",
            description = "Muse agent CLI",
            installCmd = "npm install -g muse-cli",
            launchCmd = "muse",
        ),
        AgentSpec(
            id = "kimi",
            displayName = "Kimi CLI",
            description = "Moonshot AI Kimi coding agent",
            installCmd = "pipx install kimi-cli",
            launchCmd = "kimi",
            requiresApiKeyEnv = "MOONSHOT_API_KEY",
        ),
        AgentSpec(
            id = "antigravity",
            displayName = "Antigravity",
            description = "Google Antigravity agent CLI",
            installCmd = "curl -fsSL https://antigravity.google/install.sh | bash",
            launchCmd = "antigravity",
        ),
        AgentSpec(
            id = "openrouter",
            displayName = "OpenRouter",
            description = "Model-agnostic gateway; lets the other agents route through any OpenRouter model",
            installCmd = "npm install -g openrouter-cli",
            launchCmd = "openrouter",
            requiresApiKeyEnv = "OPENROUTER_API_KEY",
        ),
    )

    fun byId(id: String): AgentSpec? = all.firstOrNull { it.id == id }
}
