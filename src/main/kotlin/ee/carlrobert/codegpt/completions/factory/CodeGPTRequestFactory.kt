package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.CodeGPTPlugin
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.InlineEditCompletionParameters
import ee.carlrobert.codegpt.completions.ToolApprovalMode
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory.Companion.buildOpenAIMessages
import ee.carlrobert.codegpt.mcp.McpToolConverter
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.client.codegpt.request.InlineEditRequest
import ee.carlrobert.llm.client.codegpt.request.chat.*
import ee.carlrobert.llm.client.openai.completion.request.*
import ee.carlrobert.llm.completion.CompletionRequest

class CodeGPTRequestFactory(private val classStructureSerializer: ClassStructureSerializer) :
    BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): ChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)

        val configuration = service<ConfigurationSettings>().state
        val messages = buildCodeGPTMessages(model, params, emptyList(), null)
        val requestBuilder: ChatCompletionRequest.Builder =
            ChatCompletionRequest.Builder(messages)
                .setModel(model)
                .setSessionId(params.sessionId)
                .setStream(true)
                .setTools(params.mcpTools?.map { McpToolConverter.convertToOpenAITool(it) }
                    ?: emptyList())
                .setMetadata(
                    Metadata(
                        CodeGPTPlugin.getVersion(),
                        service<ApplicationInfo>().build.asString()
                    )
                )

        if ("o4-mini" == model) {
            requestBuilder
                .setMaxTokens(null)
                .setTemperature(null)
        } else {
            requestBuilder
                .setMaxTokens(configuration.maxTokens)
                .setTemperature(configuration.temperature.toDouble())
        }

        if (params.message.isWebSearchIncluded) {
            requestBuilder.setWebSearchIncluded(true)
        }

        val contextFiles = params.referencedFiles
            ?.mapNotNull { file ->
                LocalFileSystem.getInstance().findFileByPath(file.filePath)?.let {
                    if (it.isDirectory) {
                        val children = mutableListOf<ContextFile>()
                        processFolder(it, children)
                        children
                    } else {
                        listOf(ContextFile(file.fileName(), file.filePath(), file.fileContent()))
                    }
                }
            }
            ?.flatten()
            .orEmpty()


        val psiContext = params.psiStructure?.map { classStructure ->
            ContextFile(
                classStructure.virtualFile.name,
                classStructure.virtualFile.path,
                classStructureSerializer.serialize(classStructure)
            )
        }.orEmpty()

        val contextFilesWithPsi = contextFiles + psiContext

        if (!params.mcpTools.isNullOrEmpty() && params.toolApprovalMode != ToolApprovalMode.BLOCK_ALL) {
            val codegptTools = params.mcpTools!!.map { mcpTool ->
                Tool().apply {
                    function = ToolFunction().apply {
                        name = mcpTool.name
                        description = mcpTool.description
                        parameters = convertMcpSchemaToCodeGPTParameters(mcpTool.schema)
                    }
                }
            }
            requestBuilder.setTools(codegptTools)

            val toolChoice = when (params.toolApprovalMode) {
                ToolApprovalMode.AUTO_APPROVE -> "auto"
                ToolApprovalMode.REQUIRE_APPROVAL -> "require_approval"
                else -> null
            }
            if (toolChoice != null) {
                requestBuilder.setToolChoice(toolChoice)
            }
        }

        val conversationsHistory = params.history?.joinToString("\n\n") {
            ConversationTagProcessor.formatConversation(it)
        }
        requestBuilder.setContext(
            AdditionalRequestContext(
                contextFilesWithPsi,
                conversationsHistory
            )
        )
        return requestBuilder.build()
    }

    private fun processFolder(folder: VirtualFile, contextFiles: MutableList<ContextFile>) {
        folder.children.forEach { child ->
            when {
                child.isDirectory -> processFolder(child, contextFiles)
                else -> contextFiles.add(
                    ContextFile(
                        child.name,
                        child.path,
                        FileUtil.readContent(child)
                    )
                )
            }
        }
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): ChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(featureType)
        if (model == "o4-mini") {
            return buildBasicO1Request(model, userPrompt, systemPrompt, maxTokens, stream = stream)
        }

        return ChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", userPrompt)
            )
        )
            .setModel(model)
            .setStream(stream)
            .build()
    }

    override fun createInlineEditRequest(params: InlineEditCompletionParameters): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.INLINE_EDIT)
        val systemTemplate = service<PromptsSettings>().state.coreActions.inlineEdit.instructions
            ?: CoreActionsState.DEFAULT_INLINE_EDIT_PROMPT

        val contextFiles = mutableListOf<InlineEditRequest.ContextFile>()
        params.referencedFiles
            ?.filter { !it.fileContent.isNullOrBlank() }
            ?.forEach { ref ->
                contextFiles.add(
                    InlineEditRequest.ContextFile(ref.fileName(), ref.filePath(), ref.fileContent())
                )
            }

        val conversationHistory = params.conversationHistory?.map { conversation ->
            conversation.messages.flatMap { msg ->
                listOfNotNull(
                    msg.prompt?.takeIf { it.isNotBlank() }?.let {
                        InlineEditRequest.ConversationMessage("user", it)
                    },
                    msg.response?.takeIf { it.isNotBlank() }?.let {
                        InlineEditRequest.ConversationMessage("assistant", it)
                    }
                )
            }
        } ?: emptyList()

        val fileContent = params.filePath?.let { path ->
            LocalFileSystem.getInstance().findFileByPath(path)?.let { vf ->
                FileUtil.readContent(vf)
            }
        }

        val currentConversationMessages = params.conversation?.messages?.flatMap { msg ->
            listOfNotNull(
                msg.prompt?.takeIf { it.isNotBlank() }?.let {
                    InlineEditRequest.ConversationMessage("user", it)
                },
                msg.response?.takeIf { it.isNotBlank() }?.let {
                    InlineEditRequest.ConversationMessage("assistant", it)
                }
            )
        }

        return InlineEditRequest.Builder(model, systemTemplate)
            .setMetadata(
                Metadata(CodeGPTPlugin.getVersion(), service<ApplicationInfo>().build.asString())
            )
            .setConversation(currentConversationMessages)
            .setSelectedText(params.selectedText)
            .setCursorOffset(params.cursorOffset ?: 0)
            .setFilePath(params.filePath)
            .setFileContent(fileContent)
            .setProjectBasePath(params.projectBasePath)
            .setContextFiles(contextFiles)
            .setGitDiff(params.gitDiff)
            .setConversationHistory(conversationHistory.toMutableList())
            .setDiagnosticsInfo(params.diagnosticsInfo)
            .build()
    }

    private fun buildBasicO1Request(
        model: String,
        prompt: String,
        systemPrompt: String = "",
        maxCompletionTokens: Int = 4096,
        stream: Boolean = false
    ): ChatCompletionRequest {
        val messages = if (systemPrompt.isEmpty()) {
            listOf(OpenAIChatCompletionStandardMessage("user", prompt))
        } else {
            listOf(
                OpenAIChatCompletionStandardMessage("user", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", prompt)
            )
        }
        return ChatCompletionRequest.Builder(messages)
            .setModel(model)
            .setMaxTokens(maxCompletionTokens)
            .setStream(stream)
            .setTemperature(null)
            .build()
    }

    private fun convertMcpSchemaToCodeGPTParameters(mcpSchema: Map<String, Any>): ToolFunctionParameters {
        return ToolFunctionParameters().apply {
            if (mcpSchema.containsKey("type")) {
                type = mcpSchema["type"] as? String ?: "object"
                properties = mcpSchema["properties"] as? Map<String, Any> ?: mcpSchema
                required = mcpSchema["required"] as? List<String> ?: emptyList()
            } else {
                type = "object"
                properties = mcpSchema
                required = mcpSchema.keys.toList()
            }
        }
    }

    private fun buildCodeGPTMessages(
        model: String?,
        params: ChatCompletionParameters,
        referencedFiles: List<ReferencedFile>?,
        psiStructure: Set<ClassStructure>?
    ): List<OpenAIChatCompletionMessage> {
        val messages =
            buildOpenAIMessages(model, params, referencedFiles, emptyList(), psiStructure)

        /*if (params.toolResults.isNullOrEmpty()) {
            return messages
        }

        val convertedMessages = mutableListOf<OpenAIChatCompletionMessage>()
        var pendingToolResults = mutableListOf<Pair<String, String>>()

        for (message in messages) {
            when (message) {
                is OpenAIChatCompletionToolMessage -> {
                    val callId = message.callId ?: ""
                    val content = message.content ?: ""
                    pendingToolResults.add(Pair(callId, content))
                }

                is OpenAIChatCompletionAssistantMessage -> {
                    if (!message.toolCalls.isNullOrEmpty() && pendingToolResults.isNotEmpty()) {
                        val toolCallsInfo = message.toolCalls.joinToString("\n") { toolCall ->
                            val result = pendingToolResults.find { it.first == toolCall.id }?.second
                                ?: "No result"
                            "Tool ${toolCall.function.name} (${toolCall.id}): $result"
                        }
                        val newContent = if (message.content.isNullOrEmpty()) {
                            "Tool execution results:\n$toolCallsInfo"
                        } else {
                            "${message.content}\n\nTool execution results:\n$toolCallsInfo"
                        }
                        convertedMessages.add(
                            OpenAIChatCompletionStandardMessage("assistant", newContent)
                        )
                        pendingToolResults.clear()
                    } else {
                        convertedMessages.add(message)
                    }
                }

                else -> {
                    convertedMessages.add(message)
                }
            }
        }

        if (pendingToolResults.isNotEmpty()) {
            val toolResultsContent = pendingToolResults.joinToString("\n") { (toolCallId, result) ->
                "Tool result ($toolCallId): $result"
            }
            convertedMessages.add(
                OpenAIChatCompletionStandardMessage(
                    "assistant",
                    "Tool execution results:\n$toolResultsContent"
                )
            )
        }*/

        return messages
    }
}
