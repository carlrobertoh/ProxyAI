package ee.carlrobert.codegpt.mcp

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class McpSelectionResolverTest : IntegrationTest() {

    fun testMcpResolutionReturnsFailedServerWithoutDroppingOtherServers() {
        val conversation = ConversationService.getInstance().startConversation(project)
        val resolver = service<McpSelectionResolver>()
        val selectedTags = listOf(
            McpTagDetails(serverId = "missing-1", serverName = "Missing 1"),
            McpTagDetails(serverId = "missing-2", serverName = "Missing 2")
        )

        val resolution = resolver.ensureConnected(conversation.id, selectedTags)

        assertThat(resolution.tools).isEmpty()
        assertThat(resolution.connectedServerIds).isEmpty()
        assertThat(resolution.failedServerIds).containsKeys("missing-1", "missing-2")
    }

    fun testSelectedServerIdsOnlyReturnsSelectedMcpTags() {
        val resolver = service<McpSelectionResolver>()
        val selectedTag = McpTagDetails(serverId = "server-1", serverName = "Server 1").apply {
            selected = true
        }
        val unselectedTag = McpTagDetails(serverId = "server-2", serverName = "Server 2").apply {
            selected = false
        }

        val selectedIds = resolver.selectedServerIds(listOf(selectedTag, unselectedTag))

        assertThat(selectedIds).containsExactly("server-1")
    }
}
