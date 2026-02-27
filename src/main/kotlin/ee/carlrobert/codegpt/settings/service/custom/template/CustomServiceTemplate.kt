package ee.carlrobert.codegpt.settings.service.custom.template

enum class CustomServiceTemplate(
    val providerName: String,
    val docsUrl: String,
    val chatCompletionTemplate: CustomServiceChatCompletionTemplate,
    val codeCompletionTemplate: CustomServiceCodeCompletionTemplate? = null
) {
    ANYSCALE(
        "Anyscale",
        "https://docs.endpoints.anyscale.com/",
        CustomServiceChatCompletionTemplate.ANYSCALE,
        CustomServiceCodeCompletionTemplate.ANYSCALE,
    ),
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
        "https://readme.fireworks.ai/reference/createchatcompletion",
        CustomServiceChatCompletionTemplate.FIREWORKS,
        CustomServiceCodeCompletionTemplate.FIREWORKS
    ),
    GROQ(
        "Groq",
        "https://docs.api.groq.com/md/openai.oas.html",
        CustomServiceChatCompletionTemplate.GROQ
    ),
    OPENAI(
        "OpenAI",
        "https://platform.openai.com/docs/api-reference/chat",
        CustomServiceChatCompletionTemplate.OPENAI,
        CustomServiceCodeCompletionTemplate.OPENAI
    ),
    PERPLEXITY(
        "Perplexity AI",
        "https://docs.perplexity.ai/reference/post_chat_completions",
        CustomServiceChatCompletionTemplate.PERPLEXITY
    ),
    TOGETHER(
        "Together AI",
        "https://docs.together.ai/docs/openai-api-compatibility",
        CustomServiceChatCompletionTemplate.TOGETHER,
        CustomServiceCodeCompletionTemplate.TOGETHER
    ),
    OLLAMA(
        "Ollama",
        "https://github.com/ollama/ollama/blob/main/docs/openai.md",
        CustomServiceChatCompletionTemplate.OLLAMA
    ),
    LLAMA_CPP(
        "LLaMA C/C++",
        "https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md",
        CustomServiceChatCompletionTemplate.LLAMA_CPP
    ),
    MISTRAL_AI(
        "Mistral AI",
        "https://docs.mistral.ai/getting-started/quickstart",
        CustomServiceChatCompletionTemplate.MISTRAL_AI,
        CustomServiceCodeCompletionTemplate.MISTRAL_AI
    ),
    OPEN_ROUTER(
        "OpenRouter",
        "https://openrouter.ai/docs#quick-start",
        CustomServiceChatCompletionTemplate.OPEN_ROUTER
    ),
    OPENAI_RESPONSES(
        "OpenAI (Responses API)",
        "https://platform.openai.com/docs/api-reference/responses",
        CustomServiceChatCompletionTemplate.OPENAI_RESPONSES
    );

    override fun toString(): String {
        return providerName
    }
}