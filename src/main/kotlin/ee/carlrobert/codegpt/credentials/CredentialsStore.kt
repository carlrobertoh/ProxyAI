package ee.carlrobert.codegpt.credentials

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

object CredentialsStore {

    private val credentialsMap = ConcurrentHashMap<String, String>()

    @JvmStatic
    // TODO Refactoring is needed, because it is often called from the form refactoring and @RequiresBackgroundThread
    fun getCredential(keyModel: CredentialKey): String? =
        credentialsMap.getOrPut(keyModel.value) {
            runBlocking(Dispatchers.IO) {
                PasswordSafe.instance.getPassword(
                    CredentialAttributes(
                        generateServiceName("CodeGPT", keyModel.value)
                    )
                ) ?: ""
            }
        }.takeIf { !it.isNullOrEmpty() }

    fun setCredential(keyModel: CredentialKey, password: String?) {
        val prevPassword = credentialsMap[keyModel.value]
        credentialsMap[keyModel.value] = password.orEmpty()

        if (prevPassword != password) {
            val credentialAttributes =
                CredentialAttributes(generateServiceName("CodeGPT", keyModel.value))
            PasswordSafe.instance.setPassword(credentialAttributes, password)
        }
    }

    fun isCredentialSet(keyModel: CredentialKey): Boolean = !getCredential(keyModel).isNullOrEmpty()

    sealed class CredentialKey {
        abstract val value: String

        data object CodeGptApiKey : CredentialKey() {
            override val value: String = "CODEGPT_API_KEY"
        }

        data object OpenaiApiKey : CredentialKey() {
            override val value: String = "OPENAI_API_KEY"
        }

        data class CustomServiceApiKey(val name: String) : CredentialKey() {
            override val value: String = "CUSTOM_SERVICE_API_KEY:$name"
        }

        @Deprecated("Only for migration")
        data object CustomServiceApiKeyLegacy : CredentialKey() {
            override val value: String = "CUSTOM_SERVICE_API_KEY"
        }

        data object AnthropicApiKey : CredentialKey() {
            override val value: String = "ANTHROPIC_API_KEY"
        }

        data object AzureOpenaiApiKey : CredentialKey() {
            override val value: String = "AZURE_OPENAI_API_KEY"
        }

        data object AzureActiveDirectoryToken : CredentialKey() {
            override val value: String = "AZURE_ACTIVE_DIRECTORY_TOKEN"
        }

        data object LlamaApiKey : CredentialKey() {
            override val value: String = "LLAMA_API_KEY"
        }

        data object GoogleApiKey : CredentialKey() {
            override val value: String = "GOOGLE_API_KEY"
        }

        data object OllamaApikey : CredentialKey() {
            override val value: String = "OLLAMA_API_KEY"
        }
    }
}