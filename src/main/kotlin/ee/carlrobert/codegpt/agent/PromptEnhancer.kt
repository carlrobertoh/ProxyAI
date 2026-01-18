package ee.carlrobert.codegpt.agent

import ai.koog.prompt.dsl.prompt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.ui.textarea.TagProcessorFactory
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.ThinkingOutputParser
import ee.carlrobert.codegpt.util.file.FileUtil
import kotlinx.coroutines.runBlocking
import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.Message as PromptMessage
import ee.carlrobert.codegpt.conversations.message.Message as ChatMessage

data class PromptEnhancement(val prompt: String, val includeGitChanges: Boolean)

class PromptEnhancer(private val project: Project) {

    fun enhancePrompt(
        prompt: String,
        tags: List<TagDetails> = emptyList(),
        sessionId: String? = null
    ): PromptEnhancement {
        return runBlocking { enhancePromptInternal(prompt, tags, sessionId) }
    }

    private suspend fun enhancePromptInternal(
        prompt: String,
        tags: List<TagDetails>,
        sessionId: String?
    ): PromptEnhancement {
        val trimmedPrompt = prompt.trim()
        val gitDiff = GitUtil.getCurrentChanges(project)?.trim().orEmpty()
        val tagsContext = buildTagsContext(tags)
        val historyContext = buildHistoryContext(sessionId)

        val modelService = service<ModelSelectionService>()
        val provider = modelService.getServiceForFeature(FeatureType.AGENT)
        val model = modelService.getAgentModel()
        val executor = AgentFactory.createExecutor(provider)

        val responseText = runCatching {
            val p = prompt("prompt-enhancer") {
                system(PromptEnhancerPrompts.DEFAULT_PROMPT)
                user(buildUserPrompt(trimmedPrompt, gitDiff, tagsContext, historyContext))
            }
            val responses = executor.execute(p, model, emptyList())
            responses.filterIsInstance<KoogMessage.Assistant>()
                .joinToString("\n") { it.content.trim() }
        }.getOrDefault("")

        val enhanced = extractEnhancedPrompt(responseText.ifBlank { trimmedPrompt })

        return PromptEnhancement(
            prompt = enhanced,
            includeGitChanges = gitDiff.isNotBlank()
        )
    }

    private fun buildUserPrompt(
        prompt: String,
        gitDiff: String,
        tagsContext: String,
        historyContext: String
    ): String {
        return buildString {
            if (historyContext.isNotBlank()) {
                append("Previous conversation history:\n")
                append(historyContext.trim())
                append("\n\n")
            }
            if (tagsContext.isNotBlank()) {
                append("Selected tag context:\n")
                append(tagsContext.trim())
                append("\n\n")
            }
            append("Original prompt:\n")
            append(prompt)
            if (gitDiff.isNotBlank()) {
                append("\n\nCurrent git changes:\n```diff\n")
                append(gitDiff)
                append("\n```")
            }
        }
    }

    private fun extractEnhancedPrompt(response: String): String {
        val parser = ThinkingOutputParser()
        val parsed = parser.processChunk(response)
        val candidate = parsed.ifBlank { response }.trim()
        return stripCodeFence(candidate)
    }

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }
        val closingIndex = trimmed.lastIndexOf("```")
        if (closingIndex <= 2) {
            return trimmed
        }
        val inner = trimmed.substring(3, closingIndex).trimStart()
        val newlineIndex = inner.indexOf('\n')
        if (newlineIndex == -1) {
            return inner.trim()
        }
        val header = inner.substring(0, newlineIndex)
        val body = inner.substring(newlineIndex + 1)
        return if (header.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            body.trim()
        } else {
            inner.trim()
        }
    }

    private fun buildTagsContext(tags: List<TagDetails>): String {
        if (tags.isEmpty()) return ""
        val contextBuilder = StringBuilder()
        val tempMessage = ChatMessage("")
        tags.forEach { tagDetails ->
            val processor = TagProcessorFactory.getProcessor(project, tagDetails)
            processor.process(tempMessage, contextBuilder)
        }
        appendTagMetadata(contextBuilder, tempMessage)
        return contextBuilder.toString().trim()
    }

    private fun appendTagMetadata(builder: StringBuilder, message: ChatMessage) {
        val referencedFiles = message.referencedFilePaths.orEmpty()
        if (referencedFiles.isNotEmpty()) {
            builder.append("\nReferenced files:\n")
            referencedFiles.forEach { path ->
                builder.append("- ").append(path).append("\n")
            }
        }
        message.documentationDetails?.let { details ->
            builder.append("\nDocumentation:\n")
                .append(details.name)
                .append(" (")
                .append(details.url)
                .append(")\n")
        }
        message.personaName?.takeIf { it.isNotBlank() }?.let { persona ->
            builder.append("\nPersona:\n").append(persona).append("\n")
        }
        message.imageFilePath?.takeIf { it.isNotBlank() }?.let { path ->
            builder.append("\nImage:\n").append(path).append("\n")
        }
    }

    private suspend fun buildHistoryContext(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) return ""
        val checkpoint = project.service<AgentService>().getCheckpoint(sessionId)
        return formatHistory(checkpoint?.messageHistory ?: emptyList())
    }

    private fun formatHistory(messages: List<PromptMessage>): String {
        val builder = StringBuilder()
        messages.filterNot { it is PromptMessage.System }.forEach { msg ->
            when (msg) {
                is PromptMessage.User -> {
                    appendHistoryLine(builder, "User", msg.content)
                }

                is PromptMessage.Assistant -> {
                    appendHistoryLine(builder, "Assistant", msg.content)
                }

                is PromptMessage.Reasoning -> {
                    appendHistoryLine(builder, "Assistant", msg.content)
                }

                is PromptMessage.Tool.Call -> {
                    appendHistoryLine(builder, "Tool Call (${msg.tool})", msg.content)
                }

                is PromptMessage.Tool.Result -> {
                    appendHistoryLine(builder, "Tool Result (${msg.tool})", msg.content)
                }

                else -> {
                    appendHistoryLine(builder, "Message", msg.toString())
                }
            }
        }
        return builder.toString().trim()
    }

    private fun appendHistoryLine(builder: StringBuilder, label: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        builder.append(label).append(": ").append(trimmed).append("\n\n")
    }
}

private object PromptEnhancerPrompts {
    private const val PROMPT_PATH = "/prompts/core/prompt-enhancer.txt"
    val DEFAULT_PROMPT: String = FileUtil.getResourceContent(PROMPT_PATH)
}
