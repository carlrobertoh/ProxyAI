package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.clients.HttpClientProvider
import ee.carlrobert.codegpt.agent.clients.InceptionAILLMClient
import ee.carlrobert.codegpt.agent.clients.ProxyAILLMClient
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType.INCEPTION
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.completions.autoapply.AutoApplyRequest
import ee.carlrobert.codegpt.completions.inception.InceptionApplyRequest
import ee.carlrobert.codegpt.completions.inception.InceptionChatMessage
import java.util.regex.Pattern

@Service
class AutoApplyService {

    companion object {
        private val UPDATED_CODE_PATTERN =
            Pattern.compile("<\\|updated_code\\|>(.*?)<\\|/updated_code\\|>", Pattern.DOTALL)
    }

    fun applyCode(params: AutoApplyParameters, originalCode: String): String {
        val modelSelection =
            service<ModelSettings>().getModelSelectionForFeature(FeatureType.AUTO_APPLY)
        return when (modelSelection.provider) {
            PROXYAI -> applyCodeWithProxyAI(modelSelection.model, originalCode, params.source)
            INCEPTION -> applyCodeWithInception(modelSelection.model, originalCode, params.source)
            else -> applyCodeWithDefault(params)
        }
    }

    private fun applyCodeWithProxyAI(
        model: String,
        originalCode: String,
        updateSnippet: String
    ): String {
        val request = AutoApplyRequest(model, originalCode, updateSnippet)
        return ProxyAILLMClient(getCredential(CredentialKey.CodeGptApiKey) ?: "")
            .use { client -> client.getApplyEditCompletion(request).mergedCode }
    }

    private fun applyCodeWithInception(
        model: String,
        originalCode: String,
        updateSnippet: String
    ): String {
        val prompt = buildInceptionPrompt(originalCode, updateSnippet)
        val request = InceptionApplyRequest.Builder()
            .setModel(model)
            .setMessages(listOf(InceptionChatMessage("user", prompt)))
            .build()

        return InceptionAILLMClient(
            apiKey = getCredential(CredentialKey.InceptionApiKey) ?: "",
            baseClient = HttpClientProvider.createHttpClient()
        ).use { client ->
            client.getApplyEditCompletion(request)
                .choices
                ?.firstOrNull()
                ?.message
                ?.content
                .orEmpty()
        }.let { extractUpdatedCode(it) }
    }

    private fun applyCodeWithDefault(params: AutoApplyParameters): String {
        return extractUpdatedCode(service<CompletionService>().autoApply(params))
    }

    private fun buildInceptionPrompt(originalCode: String, updateSnippet: String): String {
        return """
            <|original_code|>
            $originalCode
            <|/original_code|>

            <|update_snippet|>
            $updateSnippet
            <|/update_snippet|>
        """.trimIndent()
    }

    private fun extractUpdatedCode(content: String): String {
        val matcher = UPDATED_CODE_PATTERN.matcher(content)
        return if (matcher.find()) {
            matcher.group(1).trim()
        } else {
            content
        }
    }
}
