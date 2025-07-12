package ee.carlrobert.codegpt.codecompletions

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.Placeholder.*
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.llm.client.llama.completion.LlamaCompletionRequest
import ee.carlrobert.llm.client.ollama.completion.request.OllamaCompletionRequest
import ee.carlrobert.llm.client.ollama.completion.request.OllamaParameters
import ee.carlrobert.llm.client.openai.completion.request.OpenAITextCompletionRequest
import ee.carlrobert.service.GrpcCodeCompletionRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets

object CodeCompletionRequestFactory {

    private const val MAX_TOKENS = 128

    @JvmStatic
    fun buildCodeGPTRequest(details: InfillRequest): GrpcCodeCompletionRequest {
        return GrpcCodeCompletionRequest.newBuilder()
            .setFilePath(details.fileDetails?.filePath)
            .setFileContent(details.fileDetails?.fileContent)
            .setGitDiff("")
            .setCursorPosition(details.caretOffset)
            .build()
    }

    @JvmStatic
    fun buildOpenAIRequest(details: InfillRequest): OpenAITextCompletionRequest {
        return OpenAITextCompletionRequest.Builder(details.prefix)
            .setSuffix(details.suffix)
            .setStream(true)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .setPresencePenalty(0.0)
            .setStop(details.stopTokens.ifEmpty { null })
            .build()
    }

    @JvmStatic
    fun buildCustomRequest(details: InfillRequest): Request {
        val activeService = service<CustomServicesSettings>().state.active
        val settings = activeService.codeCompletionSettings
        val credential =
            getCredential(CredentialKey.CustomServiceApiKey(activeService.name.orEmpty()))
        return buildCustomRequest(
            details,
            settings.url!!,
            settings.headers,
            settings.body,
            settings.infillTemplate,
            credential
        )
    }

    @JvmStatic
    fun buildCustomRequest(
        details: InfillRequest,
        url: String,
        headers: Map<String, String>,
        body: Map<String, Any>,
        infillTemplate: InfillPromptTemplate,
        credential: String?
    ): Request {
        val requestBuilder = Request.Builder().url(url)
        for (entry in headers.entries) {
            var value = entry.value
            if (credential != null && value.contains("\$CUSTOM_SERVICE_API_KEY")) {
                value = value.replace("\$CUSTOM_SERVICE_API_KEY", credential)
            }
            requestBuilder.addHeader(entry.key, value)
        }
        val transformedBody = body.entries.associate { (key, value) ->
            key to transformValue(value, infillTemplate, details)
        }

        try {
            val requestBody = ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(transformedBody)
                .toByteArray(StandardCharsets.UTF_8)
                .toRequestBody("application/json".toMediaType())
            return requestBuilder.post(requestBody).build()
        } catch (e: JsonProcessingException) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun buildLlamaRequest(details: InfillRequest): LlamaCompletionRequest {
        val settings = LlamaSettings.getCurrentState()
        val promptTemplate = getLlamaInfillPromptTemplate(settings)
        val prompt = promptTemplate.buildPrompt(details)
        val stopTokens = buildList {
            if (promptTemplate.stopTokens != null) addAll(promptTemplate.stopTokens)
            if (details.stopTokens.isNotEmpty()) addAll(details.stopTokens)
        }.ifEmpty { null }

        return LlamaCompletionRequest.Builder(prompt)
            .setN_predict(MAX_TOKENS)
            .setStream(true)
            .setTemperature(0.0)
            .setStop(stopTokens)
            .build()
    }

    fun buildOllamaRequest(details: InfillRequest): OllamaCompletionRequest {
        val settings = service<OllamaSettings>().state
        val stopTokens = buildList {
            if (details.stopTokens.isNotEmpty()) addAll(details.stopTokens)
        }.toMutableList()
        val prompt = if (settings.fimOverride) {
            settings.fimTemplate.stopTokens?.let { stopTokens.addAll(it) }
            settings.fimTemplate.buildPrompt(details)
        } else {
            details.prefix
        }

        return OllamaCompletionRequest.Builder(
            settings.codeCompletionModel,
            prompt
        )
            .setSuffix(if (settings.fimOverride) null else details.suffix)
            .setStream(true)
            .setOptions(
                OllamaParameters.Builder()
                    .stop(stopTokens.ifEmpty { null })
                    .numPredict(MAX_TOKENS)
                    .temperature(0.4)
                    .build()
            )
            .setRaw(true)
            .build()
    }

    private fun getLlamaInfillPromptTemplate(settings: LlamaSettingsState): InfillPromptTemplate {
        if (settings.isUseCustomModel) {
            return settings.localModelInfillPromptTemplate
        }
        return LlamaModel.findByHuggingFaceModel(settings.huggingFaceModel).infillPromptTemplate
    }

    private fun transformValue(
        value: Any,
        template: InfillPromptTemplate,
        details: InfillRequest
    ): Any {
        if (value !is String) return value

        return when (value) {
            FIM_PROMPT.code -> template.buildPrompt(details)
            PREFIX.code -> details.prefix
            SUFFIX.code -> details.suffix
            else -> {
                return value.takeIf { it.contains(PREFIX.code) || it.contains(SUFFIX.code) }
                    ?.replace(PREFIX.code, details.prefix)
                    ?.replace(SUFFIX.code, details.suffix) ?: value
            }
        }
    }
}
