package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType.CUSTOM_OPENAI
import ee.carlrobert.codegpt.settings.service.ServiceType.OPENAI
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.mcp.AddMcpServerActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.mcp.McpServerActionItem
import javax.swing.Icon

class MCPGroupItem(
    private val tagManager: TagManager,
    private val featureType: FeatureType = FeatureType.CHAT
) : AbstractLookupGroupItem() {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.mcp.displayName")
    override val icon: Icon = Icons.MCP
    override val enabled: Boolean = isEnabled()

    fun isEnabled(): Boolean {
        if (featureType == FeatureType.AGENT) {
            return true
        }
        val serviceType = service<ModelSelectionService>().getServiceForFeature(featureType)
        return serviceType == OPENAI || serviceType == CUSTOM_OPENAI
    }

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        super.setPresentation(element, presentation)
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        val mcpSettings = service<McpSettings>()
        val attachedServerIds = tagManager.getTags()
            .filterIsInstance<McpTagDetails>()
            .map { it.serverId }
            .toSet()

        val items = mutableListOf<LookupActionItem>()

        items.add(AddMcpServerActionItem())

        val availableServers = mcpSettings.state.servers
            .filter { serverDetails ->
                !attachedServerIds.contains(serverDetails.id.toString()) &&
                        (searchText.isEmpty() || serverDetails.name?.contains(
                            searchText,
                            true
                        ) == true)
            }
            .map { McpServerActionItem(it) }
            .take(9) // Take 9 to leave room for AddMcpServerActionItem

        items.addAll(availableServers)

        return items
    }
}
