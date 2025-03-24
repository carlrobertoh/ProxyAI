package ee.carlrobert.codegpt.settings.service.custom.template

enum class CustomServiceChatCompletionTemplate(
    val url: String,
    val headers: MutableMap<String, String>,
    val body: MutableMap<String, Any>
) {
    ANYSCALE(
        "https://api.endpoints.anyscale.com/v1/chat/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(
            mapOf(
                "model" to "mistralai/Mixtral-8x7B-Instruct-v0.1",
                "max_tokens" to 1024
            )
        )
    ),
    AZURE(
        "https://{your-resource-name}.openai.azure.com/openai/deployments/{deployment-id}/chat/completions?api-version=2023-05-15",
        getDefaultHeaders("api-key", "\$CUSTOM_SERVICE_API_KEY"),
        getDefaultBodyParams(emptyMap())
    ),
    DEEP_INFRA(
        "https://api.deepinfra.com/v1/openai/chat/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(
            mapOf(
                "model" to "meta-llama/Llama-2-70b-chat-hf",
                "max_tokens" to 1024
            )
        )
    ),
    FIREWORKS(
        "https://api.fireworks.ai/inference/v1/chat/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(
            mapOf(
                "model" to "accounts/fireworks/models/qwen2p5-coder-32b-instruct",
                "max_tokens" to 1024
            )
        )
    ),
    GROQ(
        "https://api.groq.com/openai/v1/chat/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(
            mapOf(
                "model" to "codellama-34b",
                "max_tokens" to 1024
            )
        )
    ),
    OPENAI(
        "https://api.openai.com/v1/chat/completions",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        getDefaultBodyParams(
            mapOf(
                "model" to "gpt-4",
                "max_tokens" to 1024
            )
        )
    ),
    PERPLEXITY(
        "https://api.perplexity.ai/chat/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(
            mapOf(
                "model" to "codellama",
                "max_tokens" to 1024
            )
        )
    ),
    TOGETHER(
        "https://api.together.xyz/v1/chat/completions",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        getDefaultBodyParams(
            mapOf(
                "model" to "deepseek-ai/deepseek-coder-33b-instruct",
                "max_tokens" to 1024
            )
        )
    ),
    OLLAMA(
        "http://localhost:11434/v1/chat/completions",
        getDefaultHeaders(),
        getDefaultBodyParams(mapOf("model" to "codellama"))
    ),
    LLAMA_CPP(
        "http://localhost:8080/v1/chat/completions",
        getDefaultHeaders(),
        getDefaultBodyParams(emptyMap())
    ),
    MISTRAL_AI(
        "https://api.mistral.ai/v1/chat/completions",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        getDefaultBodyParams(
            mapOf(
                "model" to "open-mistral-7b",
                "max_tokens" to 1024
            )
        )
    ),
    OPEN_ROUTER(
        "https://openrouter.ai/api/v1/chat/completions",
        getDefaultHeaders(
            mapOf(
                "Authorization" to "Bearer \$CUSTOM_SERVICE_API_KEY",
                "HTTP-Referer" to "https://tryproxy.io",
                "X-Title" to "ProxyAI"
            )
        ),
        getDefaultBodyParams(
            mapOf(
                "model" to "meta-llama/llama-3.1-8b-instruct:free",
                "max_tokens" to 1024
            )
        )
    );
}

private fun getDefaultHeadersWithAuthentication(): MutableMap<String, String> {
    return getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY")
}

private fun getDefaultHeaders(): MutableMap<String, String> {
    return getDefaultHeaders(emptyMap())
}

private fun getDefaultHeaders(key: String, value: String): MutableMap<String, String> {
    return getDefaultHeaders(mapOf(key to value))
}

private fun getDefaultHeaders(additionalHeaders: Map<String, String>): MutableMap<String, String> {
    val defaultHeaders = mutableMapOf(
        "Content-Type" to "application/json",
        "X-LLM-Application-Tag" to "proxyai"
    )
    defaultHeaders.putAll(additionalHeaders)
    return defaultHeaders
}

private fun getDefaultBodyParams(additionalParams: Map<String, Any>): MutableMap<String, Any> {
    val defaultParams = mutableMapOf<String, Any>(
        "stream" to true,
        "messages" to "\$OPENAI_MESSAGES",
        "temperature" to 0.1
    )
    defaultParams.putAll(additionalParams)
    return defaultParams
}
