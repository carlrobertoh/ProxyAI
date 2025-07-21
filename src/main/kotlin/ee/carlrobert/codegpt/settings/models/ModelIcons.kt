package ee.carlrobert.codegpt.settings.models

import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.service.ServiceType
import javax.swing.Icon

object ModelIcons {
    fun getIconForModel(model: ModelSelection): Icon? {
        return when (model.provider) {
            ServiceType.PROXYAI -> getProxyAIModelIcon(model.model)
            else -> getIconForProvider(model.provider)
        }
    }

    fun getProxyAIModelIcon(modelId: String): Icon? {
        return when (modelId) {
            ModelRegistry.O4_MINI, ModelRegistry.GPT_4_1, ModelRegistry.GPT_4_1_MINI -> Icons.OpenAI
            ModelRegistry.CLAUDE_4_SONNET_THINKING, ModelRegistry.CLAUDE_4_SONNET -> Icons.Anthropic
            ModelRegistry.GEMINI_PRO_2_5, ModelRegistry.GEMINI_FLASH_2_5 -> Icons.Google
            ModelRegistry.DEEPSEEK_R1, ModelRegistry.DEEPSEEK_V3 -> Icons.DeepSeek
            "qwen-2.5-32b-chat", ModelRegistry.QWEN_2_5_32B_CODE -> Icons.Qwen
            "llama-3.1-405b" -> Icons.Meta
            else -> Icons.DefaultSmall
        }
    }

    fun getIconForProvider(provider: ServiceType): Icon? {
        return when (provider) {
            ServiceType.PROXYAI -> Icons.CodeGPTModel
            ServiceType.OPENAI -> Icons.OpenAI
            ServiceType.ANTHROPIC -> Icons.Anthropic
            ServiceType.GOOGLE -> Icons.Google
            ServiceType.MISTRAL -> Icons.Mistral
            ServiceType.OLLAMA -> Icons.Ollama
            ServiceType.CUSTOM_OPENAI -> Icons.OpenAI
            ServiceType.LLAMA_CPP -> Icons.Llama
        }
    }
}