package ee.carlrobert.codegpt.settings.service.custom.template

enum class CustomServiceTemplate(
    val providerName: String,
    val docsUrl: String,
    val chatCompletionTemplate: CustomServiceChatCompletionTemplate,
    val codeCompletionTemplate: CustomServiceCodeCompletionTemplate? = null
) {
    AZURE(
        "Azure OpenAI",
        "https://learn.microsoft.com/en-us/azure/ai-services/openai/reference",
        CustomServiceChatCompletionTemplate.AZURE,
        CustomServiceCodeCompletionTemplate.AZURE
    ),
    DEEP_INFRA(
        "DeepInfra",
        "https://deepinfra.com/docs/advanced/openai_api",
        CustomServiceChatCompletionTemplate.DEEP_INFRA,
        CustomServiceCodeCompletionTemplate.DEEP_INFRA
    ),
    FIREWORKS(
        "Fireworks",
        "https://docs.fireworks.ai/api-reference/post-chatcompletions",
        CustomServiceChatCompletionTemplate.FIREWORKS,
        CustomServiceCodeCompletionTemplate.FIREWORKS
    ),
    GROQ(
        "Groq",
        "https://console.groq.com/docs/openai",
        CustomServiceChatCompletionTemplate.GROQ
    ),
    OPENAI(
        "OpenAI (Chat Completions API)",
        "https://platform.openai.com/docs/api-reference/chat/create",
        CustomServiceChatCompletionTemplate.OPENAI,
        CustomServiceCodeCompletionTemplate.OPENAI
    ),
    OPENAI_RESPONSES(
        "OpenAI (Responses API)",
        "https://platform.openai.com/docs/api-reference/responses/create",
        CustomServiceChatCompletionTemplate.OPENAI_RESPONSES,
        CustomServiceCodeCompletionTemplate.OPENAI
    ),
    TOGETHER(
        "Together AI",
        "https://docs.together.ai/docs/openai-api-compatibility",
        CustomServiceChatCompletionTemplate.TOGETHER,
        CustomServiceCodeCompletionTemplate.TOGETHER
    ),
    OLLAMA(
        "Ollama",
        "https://docs.ollama.com/api/openai-compatibility",
        CustomServiceChatCompletionTemplate.OLLAMA
    ),
    LLAMA_CPP(
        "llama.cpp Server",
        "https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md",
        CustomServiceChatCompletionTemplate.LLAMA_CPP
    ),
    MISTRAL_AI(
        "Mistral AI",
        "https://docs.mistral.ai/capabilities/completion/usage",
        CustomServiceChatCompletionTemplate.MISTRAL_AI,
        CustomServiceCodeCompletionTemplate.MISTRAL_AI
    ),
    OPEN_ROUTER(
        "OpenRouter",
        "https://openrouter.ai/docs/quickstart",
        CustomServiceChatCompletionTemplate.OPENROUTER
    );

    override fun toString(): String {
        return providerName
    }
}
