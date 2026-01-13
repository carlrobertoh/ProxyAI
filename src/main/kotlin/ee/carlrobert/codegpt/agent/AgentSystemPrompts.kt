package ee.carlrobert.codegpt.agent

import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.util.file.FileUtil
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal object AgentSystemPrompts {
    private const val WORKING_DIRECTORY_TOKEN = "{{WORKING_DIRECTORY}}"
    private const val CURRENT_DATE_TOKEN = "{{CURRENT_DATE}}"

    private const val OPENAI_KEY = "openai"
    private const val GEMINI_KEY = "gemini"
    private const val ANTHROPIC_KEY = "anthropic"

    private val openAiPrompt: String by lazy { loadPrompt("/prompts/agent/openai.txt") }
    private val geminiPrompt: String by lazy { loadPrompt("/prompts/agent/google.txt") }
    private val anthropicPrompt: String by lazy { loadPrompt("/prompts/agent/anthropic.txt") }

    fun createSystemPrompt(
        provider: ServiceType,
        modelSelection: ModelSelection?,
        projectPath: String? = null
    ): String {
        return when (provider) {
            ServiceType.PROXYAI -> createProxyAiSystemPrompt(modelSelection, projectPath)
            ServiceType.OPENAI -> createOpenAiSystemPrompt(projectPath)
            ServiceType.GOOGLE -> createGeminiSystemPrompt(projectPath)
            else -> createAnthropicSystemPrompt(projectPath)
        }
    }

    private fun createProxyAiSystemPrompt(
        modelSelection: ModelSelection?,
        projectPath: String?
    ): String {
        val modelId = modelSelection?.model
        return if (modelId == ModelRegistry.PROXYAI_AUTO || modelId?.startsWith("claude") == true) {
            createAnthropicSystemPrompt(projectPath)
        } else {
            createOpenAiSystemPrompt(projectPath)
        }
    }

    private fun createOpenAiSystemPrompt(projectPath: String? = null): String {
        return renderPrompt(OPENAI_KEY, projectPath)
    }

    private fun createGeminiSystemPrompt(projectPath: String? = null): String {
        return renderPrompt(GEMINI_KEY, projectPath)
    }

    private fun createAnthropicSystemPrompt(projectPath: String? = null): String {
        return renderPrompt(ANTHROPIC_KEY, projectPath)
    }

    private fun renderPrompt(key: String, projectPath: String?): String {
        val workingDirectory = projectPath ?: "unknown"
        val currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return getPrompt(key)
            .replace(WORKING_DIRECTORY_TOKEN, workingDirectory)
            .replace(CURRENT_DATE_TOKEN, currentDate)
    }

    private fun getPrompt(key: String): String {
        return when (key) {
            OPENAI_KEY -> openAiPrompt
            GEMINI_KEY -> geminiPrompt
            ANTHROPIC_KEY -> anthropicPrompt
            else -> error("Unknown system prompt key: $key")
        }
    }

    private fun loadPrompt(path: String): String {
        return FileUtil.getResourceContent(path).trimEnd()
    }
}
