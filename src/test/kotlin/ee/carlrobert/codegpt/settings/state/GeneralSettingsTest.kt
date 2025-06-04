package ee.carlrobert.codegpt.settings.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ee.carlrobert.codegpt.completions.HuggingFaceModel
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import org.assertj.core.api.Assertions.assertThat

class GeneralSettingsTest : BasePlatformTestCase() {

  fun testOpenAISettingsSync() {
    val openAISettings = OpenAISettings.getCurrentState()
    openAISettings.model = "gpt-3.5-turbo"
    val conversation = Conversation()
    conversation.model = "gpt-4"
    conversation.clientCode = "chat.completion"
    val settings = GeneralSettings.getInstance()

    settings.sync(conversation)

    assertThat(settings.state.selectedService).isEqualTo(ServiceType.OPENAI)
    assertThat(openAISettings.model).isEqualTo("gpt-4")
  }

  fun testCustomOpenAISettingsSync() {
    val conversation = Conversation()
    conversation.clientCode = "custom.openai.chat.completion"
    val settings = GeneralSettings.getInstance()
    settings.state.selectedService = ServiceType.OPENAI

    settings.sync(conversation)

    assertThat(settings.state.selectedService).isEqualTo(ServiceType.CUSTOM_OPENAI)
  }

  fun testLlamaSettingsModelPathSync() {
    val llamaSettings = LlamaSettings.getCurrentState()
    llamaSettings.huggingFaceModel = HuggingFaceModel.WIZARD_CODER_PYTHON_7B_Q3
    val conversation = Conversation()
    conversation.model = "TEST_LLAMA_MODEL_PATH"
    conversation.clientCode = "llama.chat.completion"
    val settings = GeneralSettings.getInstance()

    settings.sync(conversation)

    assertThat(settings.state.selectedService).isEqualTo(ServiceType.LLAMA_CPP)
    assertThat(llamaSettings.customLlamaModelPath).isEqualTo("TEST_LLAMA_MODEL_PATH")
    assertThat(llamaSettings.isUseCustomModel).isTrue()
  }

  fun testLlamaSettingsHuggingFaceModelSync() {
    val llamaSettings = LlamaSettings.getCurrentState()
    llamaSettings.huggingFaceModel = HuggingFaceModel.WIZARD_CODER_PYTHON_7B_Q3
    val conversation = Conversation()
    conversation.model = "CODE_LLAMA_7B_Q3"
    conversation.clientCode = "llama.chat.completion"
    val settings = GeneralSettings.getInstance()

    settings.sync(conversation)

    assertThat(settings.state.selectedService).isEqualTo(ServiceType.LLAMA_CPP)
    assertThat(llamaSettings.huggingFaceModel).isEqualTo(HuggingFaceModel.CODE_LLAMA_7B_Q3)
    assertThat(llamaSettings.isUseCustomModel).isFalse()
  }
}
