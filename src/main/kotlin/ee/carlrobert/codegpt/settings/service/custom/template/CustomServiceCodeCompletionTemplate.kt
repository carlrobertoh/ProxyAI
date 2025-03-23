package ee.carlrobert.codegpt.settings.service.custom.template

enum class CustomServiceCodeCompletionTemplate(
    val url: String,
    val headers: MutableMap<String, String>,
    val body: MutableMap<String, Any>,
    val parseResponseAsChatCompletions: Boolean = false
) {
    ANYSCALE(
        "https://api.endpoints.anyscale.com/v1/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(mapOf("model" to "codellama/CodeLlama-70b-Instruct-hf"))
    ),
    AZURE(
        "https://{your-resource-name}.openai.azure.com/openai/deployments/{deployment-id}/completions?api-version=2023-05-15",
        getDefaultHeaders("api-key", "\$CUSTOM_SERVICE_API_KEY"),
        getDefaultBodyParams(emptyMap())
    ),
    DEEP_INFRA(
        "https://api.deepinfra.com/v1/inference/codellama/CodeLlama-70b-Instruct-hf",
        getDefaultHeadersWithAuthentication(),
        mutableMapOf("input" to "\$FIM_PROMPT")
    ),
    FIREWORKS(
        "https://api.fireworks.ai/inference/v1/completions",
        getDefaultHeadersWithAuthentication(),
        getDefaultBodyParams(mapOf("model" to "accounts/fireworks/models/qwen2p5-coder-32b-instruct"))
    ),
    OPENAI(
        "https://api.openai.com/v1/completions",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
            "prompt" to "\$PREFIX",
            "suffix" to "\$SUFFIX",
            "model" to "gpt-3.5-turbo-instruct",
            "temperature" to 0.2,
            "max_tokens" to 24
        )
    ),
    MISTRAL_AI(
        "https://api.mistral.ai/v1/fim/completions",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        mutableMapOf(
            "stream" to true,
            "prompt" to "\$PREFIX",
            "suffix" to "\$SUFFIX",
            "model" to "codestral-latest",
            "temperature" to 0.7,
            "max_tokens" to 1024
        ),
        true
    ),
    TOGETHER(
        "https://api.together.xyz/v1/completions",
        getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY"),
        getDefaultBodyParams(mapOf("model" to "codellama/CodeLlama-70b-hf"))
    )
}

private fun getDefaultHeadersWithAuthentication(): MutableMap<String, String> {
    return getDefaultHeaders("Authorization", "Bearer \$CUSTOM_SERVICE_API_KEY")
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
        "prompt" to "\$FIM_PROMPT",
        "temperature" to 0.2,
        "max_tokens" to 24
    )
    defaultParams.putAll(additionalParams)
    return defaultParams
}