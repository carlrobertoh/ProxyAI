package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.CodeGPTPlugin
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory.Companion.buildOpenAIMessages
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.client.codegpt.request.chat.*
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage

class CodeGPTRequestFactory(private val classStructureSerializer: ClassStructureSerializer) :
    BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): ChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)

        val configuration = service<ConfigurationSettings>().state
        val requestBuilder: ChatCompletionRequest.Builder =
            ChatCompletionRequest.Builder(
                buildOpenAIMessages(model, params, emptyList(), emptyList())
            )
                .setModel(model)
                .setSessionId(params.sessionId)
                .setStream(true)
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
        params.message.documentationDetails?.let {
            requestBuilder.setDocumentationDetails(DocumentationDetails(it.name, it.url))
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

        val conversationsHistory = params.history?.joinToString("\n\n") {
            ConversationTagProcessor.formatConversation(it)
        }
        requestBuilder.setContext(
            AdditionalRequestContext(
                contextFiles + psiContext,
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
}
