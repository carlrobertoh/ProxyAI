package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.ToolApprovalMode
import ee.carlrobert.codegpt.mcp.McpToolPromptFormatter
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.llm.client.anthropic.completion.*
import ee.carlrobert.llm.completion.CompletionRequest

class ClaudeRequestFactory : BaseRequestFactory() {

    private val mcpToolPromptFormatter = McpToolPromptFormatter()

    override fun createChatRequest(params: ChatCompletionParameters): ClaudeCompletionRequest {
        return ClaudeCompletionRequest().apply {
            model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
            maxTokens = service<ConfigurationSettings>().state.maxTokens
            isStream = true

            var systemPrompt = ""
            val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
            if (!selectedPersona.disabled) {
                val base =
                    service<FilteredPromptsService>().getFilteredPersonaPrompt(params.chatMode)
                systemPrompt = service<FilteredPromptsService>().applyClickableLinks(base)
            }

            if (!params.mcpTools.isNullOrEmpty() && params.toolApprovalMode != ToolApprovalMode.BLOCK_ALL) {
                val toolsPrompt =
                    mcpToolPromptFormatter.formatToolsForSystemPrompt(params.mcpTools!!)
                if (toolsPrompt.isNotEmpty()) {
                    systemPrompt = if (systemPrompt.isEmpty()) {
                        toolsPrompt
                    } else {
                        "$systemPrompt\n\n$toolsPrompt"
                    }
                }

                tools = params.mcpTools!!.map { mcpTool ->
                    ClaudeTool(
                        mcpTool.name,
                        mcpTool.description,
                        convertMcpSchemaToClaudeInputSchema(mcpTool.schema)
                    )
                }
                toolChoice = when (params.toolApprovalMode) {
                    ToolApprovalMode.AUTO_APPROVE -> ClaudeToolChoice.auto()
                    ToolApprovalMode.REQUIRE_APPROVAL -> ClaudeToolChoice.auto()
                    else -> null
                }
            }

            if (systemPrompt.isNotEmpty()) {
                system = systemPrompt
            }

            messages = buildClaudeMessages(params)

            if (params.toolResults.isNullOrEmpty()) {
                when {
                    params.imageDetails != null -> {
                        messages.add(
                            ClaudeCompletionDetailedMessage(
                                "user",
                                listOf(
                                    ClaudeMessageImageContent(
                                        ClaudeBase64Source(
                                            params.imageDetails!!.mediaType,
                                            params.imageDetails!!.data
                                        )
                                    ),
                                    ClaudeMessageTextContent(params.message.prompt)
                                )
                            )
                        )
                    }

                    else -> {
                        val promptWithContext = getPromptWithFilesContext(params)
                        if (promptWithContext.isNotBlank()) {
                            messages.add(
                                ClaudeCompletionStandardMessage(
                                    "user", promptWithContext
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): CompletionRequest {
        return ClaudeCompletionRequest().apply {
            system = systemPrompt
            isStream = stream
            model = ModelSelectionService.getInstance().getModelForFeature(featureType)
            messages =
                listOf<ClaudeCompletionMessage>(ClaudeCompletionStandardMessage("user", userPrompt))
            this.maxTokens = maxTokens
        }
    }

    private fun buildClaudeMessages(params: ChatCompletionParameters): MutableList<ClaudeCompletionMessage> {
        val messages = mutableListOf<ClaudeCompletionMessage>()

        for (prevMessage in params.conversation.messages) {
            if (prevMessage.id == params.message.id && params.toolResults.isNullOrEmpty()) {
                if (params.retry) {
                    break
                } else {
                    continue
                }
            }

            if (prevMessage.prompt.isNotEmpty()) {
                messages.add(ClaudeCompletionStandardMessage("user", prevMessage.prompt))
            }

            val response = prevMessage.response
            if (!response.isNullOrEmpty()) {
                if (!params.toolResults.isNullOrEmpty()) {
                    val isCurrentMessageWithToolResults =
                        prevMessage.id == params.message.id && !params.toolResults.isNullOrEmpty()

                    if (params.toolResults.isNullOrEmpty() && !isCurrentMessageWithToolResults) {
                        continue
                    }

                    try {
                        if (isCurrentMessageWithToolResults) {
                            return mutableListOf()
                        }

                    } catch (e: Exception) {
                        messages.add(ClaudeCompletionStandardMessage("assistant", response))
                    }
                } else {
                    messages.add(ClaudeCompletionStandardMessage("assistant", response))
                }
            }
        }
        return messages
    }

    private fun convertMcpSchemaToClaudeInputSchema(mcpSchema: Map<String, Any>): Map<String, Any> {
        if (mcpSchema.containsKey("type")) {
            return mcpSchema
        }

        return mapOf(
            "type" to "object",
            "properties" to mcpSchema,
            "required" to mcpSchema.keys.toList()
        )
    }
}
