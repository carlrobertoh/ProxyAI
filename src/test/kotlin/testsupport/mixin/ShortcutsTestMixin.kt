package testsupport.mixin

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import ee.carlrobert.codegpt.completions.HuggingFaceModel
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.*
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.llm.client.google.models.GoogleModel
import java.util.function.BooleanSupplier

interface ShortcutsTestMixin {

    fun useCodeGPTService(role: FeatureType = FeatureType.CHAT) {
        setCredential(CodeGptApiKey, "TEST_API_KEY")
        val modelSettings = service<ModelSettings>()
        modelSettings.setModel(FeatureType.CHAT, "gpt-5-mini", ServiceType.PROXYAI)
        modelSettings.setModel(FeatureType.CODE_COMPLETION, "qwen-2.5-32b-code", ServiceType.PROXYAI)
        service<CodeGPTServiceSettings>().state.run {
            codeCompletionSettings.codeCompletionsEnabled = true
        }
    }

    fun useOpenAIService(chatModel: String? = "gpt-4o", featureType: FeatureType = FeatureType.CHAT) {
        setCredential(OpenaiApiKey, "TEST_API_KEY")
        val modelSettings = service<ModelSettings>()
        
        when (featureType) {
            FeatureType.CODE_COMPLETION -> {
                modelSettings.setModel(FeatureType.CODE_COMPLETION, "gpt-3.5-turbo-instruct", ServiceType.OPENAI)
            }
            else -> {
                modelSettings.setModel(featureType, chatModel ?: "gpt-4o", ServiceType.OPENAI)
                modelSettings.setModel(FeatureType.CODE_COMPLETION, "gpt-3.5-turbo-instruct", ServiceType.OPENAI)
            }
        }
        
        service<OpenAISettings>().state.run {
            isCodeCompletionsEnabled = true
        }
    }

    fun useLlamaService(
        codeCompletionsEnabled: Boolean = false,
        role: FeatureType = FeatureType.CHAT
    ) {
        LlamaSettings.getCurrentState().serverPort = null
        LlamaSettings.getCurrentState().isCodeCompletionsEnabled = codeCompletionsEnabled
        LlamaSettings.getCurrentState().huggingFaceModel = HuggingFaceModel.CODE_LLAMA_7B_Q4
        
        val modelSettings = service<ModelSettings>()
        when (role) {
            FeatureType.CHAT -> {
                modelSettings.setModel(FeatureType.CHAT, HuggingFaceModel.CODE_LLAMA_7B_Q4.code, ServiceType.LLAMA_CPP)
            }
            FeatureType.CODE_COMPLETION -> {
                modelSettings.setModel(FeatureType.CODE_COMPLETION, HuggingFaceModel.CODE_LLAMA_7B_Q4.code, ServiceType.LLAMA_CPP)
            }
            else -> {
                modelSettings.setModel(FeatureType.CHAT, HuggingFaceModel.CODE_LLAMA_7B_Q4.code, ServiceType.LLAMA_CPP)
                modelSettings.setModel(FeatureType.CODE_COMPLETION, HuggingFaceModel.CODE_LLAMA_7B_Q4.code, ServiceType.LLAMA_CPP)
            }
        }
    }

    fun useOllamaService(role: FeatureType = FeatureType.CHAT) {
        setCredential(OllamaApikey, "TEST_API_KEY")
        service<OllamaSettings>().state.apply {
            model = HuggingFaceModel.LLAMA_3_8B_Q6_K.code
            codeCompletionsEnabled = true
            fimOverride = false
            host = null
            availableModels = mutableListOf(
                HuggingFaceModel.LLAMA_3_8B_Q6_K.code,
                HuggingFaceModel.CODE_QWEN_2_5_3B_Q4_K_M.code
            )
        }
        
        val modelSettings = service<ModelSettings>()
        when (role) {
            FeatureType.CHAT -> {
                modelSettings.setModel(FeatureType.CHAT, HuggingFaceModel.LLAMA_3_8B_Q6_K.code, ServiceType.OLLAMA)
            }
            FeatureType.CODE_COMPLETION -> {
                modelSettings.setModel(FeatureType.CODE_COMPLETION, HuggingFaceModel.CODE_QWEN_2_5_3B_Q4_K_M.code, ServiceType.OLLAMA)
            }
            else -> {
                modelSettings.setModel(FeatureType.CHAT, HuggingFaceModel.LLAMA_3_8B_Q6_K.code, ServiceType.OLLAMA)
                modelSettings.setModel(FeatureType.CODE_COMPLETION, HuggingFaceModel.CODE_QWEN_2_5_3B_Q4_K_M.code, ServiceType.OLLAMA)
            }
        }
    }

    fun useGoogleService(role: FeatureType = FeatureType.CHAT) {
        setCredential(GoogleApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(FeatureType.CHAT, GoogleModel.GEMINI_2_0_FLASH.code, ServiceType.GOOGLE)
    }

    fun waitExpecting(condition: BooleanSupplier?) {
        PlatformTestUtil.waitWithEventsDispatching(
            "Waiting for message response timed out or did not meet expected conditions",
            condition!!,
            5
        )
    }
}
