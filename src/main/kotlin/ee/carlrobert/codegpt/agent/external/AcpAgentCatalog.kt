package ee.carlrobert.codegpt.agent.external

data class ExternalAcpAgentPreset(
    val id: String,
    val displayName: String,
    val vendor: String,
    val command: String,
    val args: List<String>,
    val toolEventFlavor: AcpToolEventFlavor = AcpToolEventFlavor.STANDARD,
    val env: Map<String, String> = emptyMap(),
    val enabledByDefault: Boolean = false,
    val description: String? = null,
) {
    fun fullCommand(): String = buildString {
        append(command)
        if (args.isNotEmpty()) {
            append(' ')
            append(args.joinToString(" "))
        }
    }
}

object ExternalAcpAgents {

    private val presets = listOf(
        ExternalAcpAgentPreset(
            id = "codex",
            displayName = "Codex",
            vendor = "OpenAI",
            command = "npx",
            args = listOf("-y", "@zed-industries/codex-acp"),
            toolEventFlavor = AcpToolEventFlavor.ZED_ADAPTER,
            enabledByDefault = true,
            description = "OpenAI Codex via the Zed ACP adapter."
        ),
        ExternalAcpAgentPreset(
            id = "opencode",
            displayName = "OpenCode",
            vendor = "OpenCode",
            command = "opencode",
            args = listOf("acp"),
            enabledByDefault = true,
            description = "OpenCode CLI running its ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "cursor",
            displayName = "Cursor",
            vendor = "Cursor",
            command = "agent",
            args = listOf("acp"),
            description = "Cursor Agent in ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "claude-code",
            displayName = "Claude Code",
            vendor = "Anthropic",
            command = "npx",
            args = listOf("-y", "@zed-industries/claude-code-acp"),
            toolEventFlavor = AcpToolEventFlavor.ZED_ADAPTER,
            enabledByDefault = true,
            description = "Anthropic Claude Code via the Zed ACP adapter."
        ),
        ExternalAcpAgentPreset(
            id = "gemini-cli",
            displayName = "Gemini CLI",
            vendor = "Google",
            command = "gemini",
            args = listOf("--experimental-acp"),
            toolEventFlavor = AcpToolEventFlavor.GEMINI_CLI,
            description = "Google Gemini CLI in experimental ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "goose",
            displayName = "Goose",
            vendor = "Block",
            command = "goose",
            args = listOf("acp"),
            description = "Block Goose running as an ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "github-copilot",
            displayName = "GitHub Copilot",
            vendor = "GitHub",
            command = "copilot",
            args = listOf("--acp"),
            description = "GitHub Copilot CLI ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "qwen-code",
            displayName = "Qwen Code",
            vendor = "Qwen",
            command = "qwen",
            args = listOf("--acp"),
            description = "Qwen Code running its ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "auggie",
            displayName = "Auggie CLI",
            vendor = "Augment",
            command = "auggie",
            args = listOf("--acp"),
            description = "Augment's Auggie CLI in ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "agentpool",
            displayName = "AgentPool",
            vendor = "AgentPool",
            command = "agentpool",
            args = listOf("serve-acp", "agents.yml"),
            description = "AgentPool serving ACP from a local agents.yml configuration."
        ),
        ExternalAcpAgentPreset(
            id = "blackbox-ai",
            displayName = "Blackbox AI",
            vendor = "Blackbox AI",
            command = "blackbox",
            args = listOf("--experimental-acp"),
            description = "Blackbox CLI running in experimental ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "claude-agent",
            displayName = "Claude Agent",
            vendor = "Anthropic",
            command = "npx",
            args = listOf("-y", "@zed-industries/claude-agent-acp"),
            toolEventFlavor = AcpToolEventFlavor.ZED_ADAPTER,
            description = "Anthropic Claude Agent via the Zed ACP adapter."
        ),
        ExternalAcpAgentPreset(
            id = "cline",
            displayName = "Cline",
            vendor = "Cline",
            command = "npx",
            args = listOf("-y", "cline", "--acp"),
            description = "Cline running as an ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "code-assistant",
            displayName = "Code Assistant",
            vendor = "stippi",
            command = "code-assistant",
            args = listOf("acp"),
            description = "Code Assistant running in ACP agent mode."
        ),
        ExternalAcpAgentPreset(
            id = "docker-cagent",
            displayName = "Docker's cagent",
            vendor = "Docker",
            command = "cagent",
            args = listOf("acp"),
            description = "Docker cagent serving ACP; project agent configuration may still be required."
        ),
        ExternalAcpAgentPreset(
            id = "fast-agent",
            displayName = "fast-agent",
            vendor = "fast-agent",
            command = "uvx",
            args = listOf("fast-agent-acp", "-x"),
            description = "fast-agent's ACP bridge via uvx."
        ),
        ExternalAcpAgentPreset(
            id = "factory-droid",
            displayName = "Factory Droid",
            vendor = "Factory AI",
            command = "npx",
            args = listOf("-y", "droid", "exec", "--output-format", "acp"),
            env = mapOf(
                "DROID_DISABLE_AUTO_UPDATE" to "true",
                "FACTORY_DROID_AUTO_UPDATE_ENABLED" to "false",
            ),
            description = "Factory Droid running in ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "junie",
            displayName = "Junie",
            vendor = "JetBrains",
            command = "junie",
            args = listOf("--acp=true"),
            description = "JetBrains Junie running as an ACP agent."
        ),
        ExternalAcpAgentPreset(
            id = "kimi-cli",
            displayName = "Kimi CLI",
            vendor = "Moonshot AI",
            command = "kimi",
            args = listOf("acp"),
            description = "Moonshot AI's Kimi CLI in ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "kiro-cli",
            displayName = "Kiro CLI",
            vendor = "Kiro",
            command = "kiro",
            args = listOf("--acp"),
            description = "Kiro CLI running as an ACP-compliant agent."
        ),
        ExternalAcpAgentPreset(
            id = "minion-code",
            displayName = "Minion Code",
            vendor = "Minion",
            command = "uvx",
            args = listOf("minion-code", "acp"),
            description = "Minion Code running its ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "mistral-vibe",
            displayName = "Mistral Vibe",
            vendor = "Mistral AI",
            command = "vibe-acp",
            args = emptyList(),
            description = "Mistral Vibe's ACP bridge."
        ),
        ExternalAcpAgentPreset(
            id = "openclaw",
            displayName = "OpenClaw",
            vendor = "OpenClaw",
            command = "openclaw",
            args = listOf("acp"),
            description = "OpenClaw running as an ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "openhands",
            displayName = "OpenHands",
            vendor = "All Hands AI",
            command = "openhands",
            args = listOf("acp"),
            description = "OpenHands in ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "pi",
            displayName = "Pi",
            vendor = "pi",
            command = "npx",
            args = listOf("-y", "pi-acp"),
            description = "Pi via the pi ACP adapter."
        ),
        ExternalAcpAgentPreset(
            id = "qoder-cli",
            displayName = "Qoder CLI",
            vendor = "Qoder AI",
            command = "npx",
            args = listOf("-y", "@qoder-ai/qodercli", "--acp"),
            description = "Qoder CLI running its ACP server."
        ),
        ExternalAcpAgentPreset(
            id = "stakpak",
            displayName = "Stakpak",
            vendor = "Stakpak",
            command = "stakpak",
            args = listOf("acp"),
            description = "Stakpak running in ACP mode."
        ),
        ExternalAcpAgentPreset(
            id = "vt-code",
            displayName = "VT Code",
            vendor = "VT Code",
            command = "vtcode",
            args = listOf("acp"),
            description = "VT Code running its ACP bridge."
        )
    )

    fun all(): List<ExternalAcpAgentPreset> = presets.sortedBy { it.displayName.lowercase() }

    fun find(id: String?): ExternalAcpAgentPreset? = presets.firstOrNull { it.id == id }

    fun enabledByDefaultIds(): List<String> =
        presets.filter { it.enabledByDefault }.map { it.id }

    fun displayName(id: String?): String {
        val safeId = id?.takeIf(String::isNotBlank)
        return safeId?.let(::find)?.displayName ?: safeId ?: "agent"
    }

    fun buildFailureMessage(
        id: String,
        throwable: Throwable,
        fallbackMessage: String
    ): String {
        val command = find(id)?.command ?: id
        val message = throwable.message.orEmpty()
        return when {
            message.contains("Cannot run program", ignoreCase = true) &&
                    message.contains("No such file or directory", ignoreCase = true) ->
                "Command not found: $command"

            message.isNotBlank() -> message
            else -> fallbackMessage
        }
    }
}
