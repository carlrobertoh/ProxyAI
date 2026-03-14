package ee.carlrobert.codegpt.settings.service.custom.template

enum class CustomServiceChatCompletionTemplate(
    val url: String,
    val headers: MutableMap<String, String>,
    val body: MutableMap<String, Any>
) {
    AZURE(
        "https://{your-resource-name}.openai.azure.com/openai/deployments/{deployment-id}/chat/completions?api-version=2024-10-21",
        getDefaultHeaders("api-key", $$"$CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
        )
    ),
    DEEP_INFRA(
        "https://api.deepinfra.com/v1/openai/chat/completions",
        getDefaultHeadersWithAuthentication(),
        mutableMapOf(
            "stream" to true,
            "model" to "deepseek-ai/DeepSeek-V3.2",
            "max_tokens" to 32_000
        )
    ),
    FIREWORKS(
        "https://api.fireworks.ai/inference/v1/chat/completions",
        getDefaultHeadersWithAuthentication(),
        mutableMapOf(
            "stream" to true,
            "model" to "accounts/fireworks/models/deepseek-v3p1",
            "max_tokens" to 32_000
        )
    ),
    GROQ(
        "https://api.groq.com/openai/v1/chat/completions",
        getDefaultHeadersWithAuthentication(),
        mutableMapOf(
            "stream" to true,
            "model" to "openai/gpt-oss-20b",
            "max_tokens" to 32_000
        )
    ),
    OPENAI(
        "https://api.openai.com/v1/chat/completions",
        getDefaultHeaders("Authorization", $$"Bearer $CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
            "model" to "gpt-4.1",
            "max_tokens" to 8192
        )
    ),
    OPENAI_RESPONSES(
        "https://api.openai.com/v1/responses",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
            "model" to "gpt-5.3-codex",
            "max_output_tokens" to 32_000
        )
    ),
    TOGETHER(
        "https://api.together.xyz/v1/chat/completions",
        getDefaultHeaders("Authorization", $$"Bearer $CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
            "model" to "zai-org/GLM-5",
            "max_tokens" to 32_000
        )
    ),
    OLLAMA(
        "http://localhost:11434/v1/chat/completions",
        getDefaultHeaders(),
        mutableMapOf(
            "stream" to true,
            "model" to "gpt-oss:20b",
            "max_tokens" to 32_000
        )
    ),
    LLAMA_CPP(
        "http://localhost:8080/v1/chat/completions",
        getDefaultHeaders(),
        mutableMapOf(
            "stream" to true,
            "model" to "gpt-oss:20b",
            "max_tokens" to 32_000
        )
    ),
    MISTRAL_AI(
        "https://api.mistral.ai/v1/chat/completions",
        getDefaultHeaders("Authorization", $$"Bearer $CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
            "model" to "mistral-large-2512",
            "max_tokens" to 32_000
        )
    ),
    OPENROUTER(
        "https://openrouter.ai/api/v1/chat/completions",
        getDefaultHeaders(
            mapOf(
                "Authorization" to $$"Bearer $CUSTOM_SERVICE_API_KEY",
                "HTTP-Referer" to "https://tryproxy.io",
                "X-OpenRouter-Title" to "ProxyAI"
            )
        ),
        mutableMapOf(
            "stream" to true,
            "model" to "moonshotai/kimi-k2.5",
            "max_tokens" to 8192
        )
    ),
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
