package ee.carlrobert.codegpt.nextedit.executors

import ee.carlrobert.codegpt.agent.clients.HttpClientProvider
import ee.carlrobert.codegpt.agent.clients.InceptionAILLMClient
import ee.carlrobert.codegpt.completions.inception.InceptionChatCompletionResponse
import ee.carlrobert.codegpt.completions.inception.InceptionNextEditRequest
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential

internal object InceptionNextEditExecutor {
    fun execute(request: InceptionNextEditRequest): InceptionChatCompletionResponse {
        val apiKey = getCredential(CredentialKey.InceptionApiKey) ?: ""
        return InceptionAILLMClient(
            apiKey = apiKey,
            baseClient = HttpClientProvider.createHttpClient()
        ).use { client ->
            client.getNextEditCompletion(request)
        }
    }
}
