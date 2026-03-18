package ee.carlrobert.codegpt.agent.external

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import ee.carlrobert.codegpt.Icons
import javax.swing.Icon

object AcpIcons {

    private const val ACP_ICON_ROOT = "/icons/agents"
    private const val TARGET_ICON_SIZE = 16

    private val iconFileOverrides = mapOf(
        "agentpool" to "agentpool.png",
        "auggie" to "auggie.png",
        "blackbox-ai" to "blackbox.svg",
        "claude-code" to "claude.svg",
        "gemini-cli" to "gemini-agent.svg",
        "kimi-cli" to "kimi.svg",
        "kiro-cli" to "kiro.svg",
        "pi" to "pi-acp.svg",
        "qoder-cli" to "qoder.svg",
        "qwen-code" to "qwen.png",
        "vt-code" to "vtcode.svg"
    )

    fun iconFor(agentId: String?): Icon {
        val resolvedAgentId = agentId ?: return Icons.DefaultSmall
        return acpIconOrNull(iconFileOverrides[resolvedAgentId] ?: "$resolvedAgentId.svg")
            ?: Icons.DefaultSmall
    }

    private fun acpIconOrNull(fileName: String): Icon? {
        val icon =
            IconLoader.findIcon("$ACP_ICON_ROOT/$fileName", AcpIcons::class.java)
                ?: return null
        return normalize(icon)
    }

    private fun normalize(icon: Icon): Icon {
        val maxDimension = maxOf(icon.iconWidth, icon.iconHeight)
        if (maxDimension <= TARGET_ICON_SIZE) {
            return icon
        }
        return IconUtil.scale(icon, null, TARGET_ICON_SIZE.toFloat() / maxDimension.toFloat())
    }
}
