package ee.carlrobert.codegpt.agent.external.acpcompat.vendor

internal enum class AcpPeerProfile(
    val profileId: String,
    val displayName: String,
    val supportsTerminalAuthMeta: Boolean = false
) {
    STANDARD(
        profileId = "standard",
        displayName = "Standard ACP"
    ),
    CODEX(
        profileId = "codex",
        displayName = "Codex ACP"
    ),
    GEMINI(
        profileId = "gemini",
        displayName = "Gemini CLI ACP"
    ),
    OPENCODE(
        profileId = "opencode",
        displayName = "OpenCode ACP"
    ),
    CLAUDE_CODE(
        profileId = "claude-code",
        displayName = "Claude Code ACP",
        supportsTerminalAuthMeta = true
    )
}
