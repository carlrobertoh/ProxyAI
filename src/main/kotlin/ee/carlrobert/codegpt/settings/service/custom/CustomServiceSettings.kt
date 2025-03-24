package ee.carlrobert.codegpt.settings.service.custom

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceChatCompletionTemplate
import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceCodeCompletionTemplate
import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceTemplate
import ee.carlrobert.codegpt.util.BaseConverter
import ee.carlrobert.codegpt.util.MapConverter

private const val DEFAULT_SERVICE_SETTINGS_NANE = "Default"

@Service
@State(
    name = "CodeGPT_CustomServiceSettings",
    storages = [Storage("CodeGPT_CustomServiceSettings.xml")]
)
@Deprecated("Migrate to CustomServicesSettings")
class CustomServiceSettings :
    SimplePersistentStateComponent<CustomServiceSettingsState>(CustomServiceSettingsState()) {

    override fun loadState(state: CustomServiceSettingsState) {
        if (state.url != null || state.body.isNotEmpty() || state.headers.isNotEmpty()) {
            super.loadState(this.state.apply {
                // Migrate old settings
                template = state.template
                chatCompletionSettings.url = state.url
                chatCompletionSettings.body = state.body
                chatCompletionSettings.headers = state.headers
                url = null
                body = mutableMapOf()
                headers = mutableMapOf()
            })
        } else {
            super.loadState(state)
        }
    }
}

@Service
@State(
    name = "CodeGPT_CustomServicesSettings",
    storages = [Storage("CodeGPT_CustomServicesSettings.xml")]
)
class CustomServicesSettings :
    SimplePersistentStateComponent<CustomServicesState>(CustomServicesState()) {

    override fun initializeComponent() {
        super.initializeComponent()
        val oldSettingsService = serviceOrNull<CustomServiceSettings>()

        // This line checks if the legacy API key exists to determine if migration of old settings is needed
        val oldApiKey = CredentialsStore.getCredential(CredentialsStore.CredentialKey.CustomServiceApiKeyLegacy)

        if (oldSettingsService != null && oldApiKey != null) {
            val migrated = CustomServiceSettingsState().apply { copyFrom(oldSettingsService.state) }
            state.services.clear()
            state.services.add(migrated)
            state.active = migrated

            CredentialsStore.setCredential(CredentialsStore.CredentialKey.CustomServiceApiKeyLegacy, null)
            CredentialsStore.setCredential(
                CredentialsStore.CredentialKey.CustomServiceApiKey(state.active.name.orEmpty()),
                oldApiKey
            )

            oldSettingsService.state.apply {
                template = CustomServiceTemplate.OPENAI
                chatCompletionSettings = chatCompletionSettings.apply {
                    url = ""
                    headers = mutableMapOf()
                    body = mutableMapOf()
                }
                codeCompletionSettings = codeCompletionSettings.apply {
                    codeCompletionsEnabled = false
                    parseResponseAsChatCompletions = false
                    infillTemplate = InfillPromptTemplate.OPENAI
                    url = ""
                    headers = mutableMapOf()
                    body = mutableMapOf()
                }
                url = null
                body = mutableMapOf()
                headers = mutableMapOf()
            }
        }
    }
}

private class CustomServiceSettingsListConverter : BaseConverter<List<CustomServiceSettingsState>>(
    object : TypeReference<List<CustomServiceSettingsState>>() {}
)

@JsonIgnoreProperties(ignoreUnknown = true)
class CustomServicesState(
    initialState: CustomServiceSettingsState = CustomServiceSettingsState()
) : BaseState() {
    @get:OptionTag(converter = CustomServiceSettingsListConverter::class)
    var services by list<CustomServiceSettingsState>()

    var active by property<CustomServiceSettingsState>(initialState)

    init {
        services.add(initialState)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class CustomServiceSettingsState : BaseState() {
    var name by string(DEFAULT_SERVICE_SETTINGS_NANE)
    var template by enum(CustomServiceTemplate.OPENAI)
    var chatCompletionSettings by property(CustomServiceChatCompletionSettingsState())
    var codeCompletionSettings by property(CustomServiceCodeCompletionSettingsState())

    @Deprecated("", ReplaceWith("this.chatCompletionSettings.url"))
    var url by string()

    @Deprecated("", ReplaceWith("this.chatCompletionSettings.headers"))
    var headers by map<String, String>()

    @get:OptionTag(converter = MapConverter::class)
    @Deprecated("", ReplaceWith("this.chatCompletionSettings.body"))
    var body by map<String, Any>()
}

@JsonIgnoreProperties(ignoreUnknown = true)
class CustomServiceChatCompletionSettingsState : BaseState() {
    var url by string(CustomServiceChatCompletionTemplate.OPENAI.url)
    var headers by map<String, String>()

    @get:OptionTag(converter = MapConverter::class)
    var body by map<String, Any>()

    init {
        headers.putAll(CustomServiceChatCompletionTemplate.OPENAI.headers)
        body.putAll(CustomServiceChatCompletionTemplate.OPENAI.body)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class CustomServiceCodeCompletionSettingsState : BaseState() {
    var codeCompletionsEnabled by property(true)
    var parseResponseAsChatCompletions by property(false)
    var infillTemplate by enum(InfillPromptTemplate.OPENAI)
    var url by string(CustomServiceCodeCompletionTemplate.OPENAI.url)
    var headers by map<String, String>()

    @get:OptionTag(converter = MapConverter::class)
    var body by map<String, Any>()

    init {
        headers.putAll(CustomServiceCodeCompletionTemplate.OPENAI.headers)
        body.putAll(CustomServiceCodeCompletionTemplate.OPENAI.body)
    }
}
