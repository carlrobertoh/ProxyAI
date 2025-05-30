package testsupport.mixin

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import ee.carlrobert.codegpt.completions.HuggingFaceModel
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.*
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.llm.client.google.models.GoogleModel
import java.util.function.BooleanSupplier

interface ShortcutsTestMixin {

  fun useCodeGPTService() {
    service<GeneralSettings>().state.selectedService = ServiceType.CODEGPT
    setCredential(CodeGptApiKey, "TEST_API_KEY")
    service<CodeGPTServiceSettings>().state.run {
      chatCompletionSettings.model = "TEST_MODEL"
      codeCompletionSettings.model = "TEST_CODE_MODEL"
      codeCompletionSettings.codeCompletionsEnabled = true
    }
  }

  fun useOpenAIService(chatModel: String? = "gpt-4o") {
    service<GeneralSettings>().state.selectedService = ServiceType.OPENAI
    setCredential(OpenaiApiKey, "TEST_API_KEY")
    service<OpenAISettings>().state.run {
      model = chatModel
      isCodeCompletionsEnabled = true
    }
  }

  fun useLlamaService(codeCompletionsEnabled: Boolean = false) {
    GeneralSettings.getCurrentState().selectedService = ServiceType.LLAMA_CPP
    LlamaSettings.getCurrentState().serverPort = null
    LlamaSettings.getCurrentState().isCodeCompletionsEnabled = codeCompletionsEnabled
    LlamaSettings.getCurrentState().huggingFaceModel = HuggingFaceModel.CODE_LLAMA_7B_Q4
  }

  fun useOllamaService() {
    GeneralSettings.getCurrentState().selectedService = ServiceType.OLLAMA
    setCredential(OllamaApikey, "TEST_API_KEY")
    service<OllamaSettings>().state.apply {
      model = HuggingFaceModel.LLAMA_3_8B_Q6_K.code
      host = null
    }
  }

  fun useGoogleService() {
    GeneralSettings.getCurrentState().selectedService = ServiceType.GOOGLE
    setCredential(GoogleApiKey, "TEST_API_KEY")
    service<GoogleSettings>().state.model = GoogleModel.GEMINI_PRO.code
  }

  fun waitExpecting(condition: BooleanSupplier?) {
    PlatformTestUtil.waitWithEventsDispatching(
      "Waiting for message response timed out or did not meet expected conditions",
      condition!!,
      5
    )
  }
}
