package ee.carlrobert.codegpt.settings.models

import ai.koog.prompt.llm.LLMCapability
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.util.messages.MessageBusConnection
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifier
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.util.concurrent.atomic.AtomicReference

class ModelSettingsTest : IntegrationTest() {

    private lateinit var modelSettings: ModelSettings
    private lateinit var connection: MessageBusConnection
    private val lastNotification = AtomicReference<NotificationData>()

    data class NotificationData(
        val featureType: FeatureType,
        val model: String,
        val serviceType: ServiceType,
        val specificNotification: String
    )

    override fun setUp() {
        super.setUp()
        modelSettings = service<ModelSettings>()
        connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(ModelChangeNotifier.getTopic(), object : ModelChangeNotifier {
            override fun agentModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.AGENT,
                        newModel,
                        serviceType,
                        "agent"
                    )
                )
            }

            override fun chatModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.CHAT,
                        newModel,
                        serviceType,
                        "chat"
                    )
                )
            }

            override fun codeModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.CODE_COMPLETION,
                        newModel,
                        serviceType,
                        "code"
                    )
                )
            }

            override fun autoApplyModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.AUTO_APPLY,
                        newModel,
                        serviceType,
                        "autoApply"
                    )
                )
            }

            override fun commitMessageModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.COMMIT_MESSAGE,
                        newModel,
                        serviceType,
                        "commitMessage"
                    )
                )
            }

            override fun inlineEditModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.INLINE_EDIT,
                        newModel,
                        serviceType,
                        "inlineEdit"
                    )
                )
            }

            override fun nextEditModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.NEXT_EDIT,
                        newModel,
                        serviceType,
                        "nextEdit"
                    )
                )
            }

            override fun nameLookupModelChanged(newModel: String, serviceType: ServiceType) {
                lastNotification.set(
                    NotificationData(
                        FeatureType.LOOKUP,
                        newModel,
                        serviceType,
                        "lookup"
                    )
                )
            }

            override fun modelChanged(
                featureType: FeatureType,
                newModel: String,
                serviceType: ServiceType
            ) {
                lastNotification.compareAndSet(
                    null,
                    NotificationData(featureType, newModel, serviceType, "general")
                )
            }
        })
    }

    override fun tearDown() {
        connection.disconnect()
        super.tearDown()
    }

    fun `test setModelWithProvider with new model triggers change notification`() {
        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-3.5-turbo", ServiceType.OPENAI)
        lastNotification.set(null)

        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-4o", ServiceType.OPENAI)

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.CHAT)
        assertThat(notification.model).isEqualTo("gpt-4o")
        assertThat(notification.serviceType).isEqualTo(ServiceType.OPENAI)
    }

    fun `test setModelWithProvider with different provider triggers notification`() {
        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-4o", ServiceType.OPENAI)
        lastNotification.set(null)

        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-4o", ServiceType.PROXYAI)

        val notification = lastNotification.get()
        assertThat(notification!!.model).isEqualTo("gpt-4o")
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModelWithProvider with code completion triggers code model notification`() {
        modelSettings.setModelWithProvider(
            FeatureType.CODE_COMPLETION,
            "gpt-3.5-turbo-instruct",
            ServiceType.OPENAI
        )
        lastNotification.set(null)

        modelSettings.setModelWithProvider(
            FeatureType.CODE_COMPLETION,
            "qwen-2.5-32b-code",
            ServiceType.PROXYAI
        )

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.CODE_COMPLETION)
        assertThat(notification.model).isEqualTo("qwen-2.5-32b-code")
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModelWithProvider with auto apply triggers auto apply notification`() {
        lastNotification.set(null)

        modelSettings.setModelWithProvider(FeatureType.AUTO_APPLY, "gpt-4.1", ServiceType.PROXYAI)

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.AUTO_APPLY)
        assertThat(notification.model).isEqualTo("gpt-4.1")
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModelWithProvider with commit message triggers commit message notification`() {
        lastNotification.set(null)

        modelSettings.setModelWithProvider(
            FeatureType.COMMIT_MESSAGE,
            "deepseek-v3",
            ServiceType.PROXYAI
        )

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.COMMIT_MESSAGE)
        assertThat(notification.model).isEqualTo("deepseek-v3")
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModelWithProvider with edit code triggers edit code notification`() {
        lastNotification.set(null)

        modelSettings.setModelWithProvider(
            FeatureType.INLINE_EDIT,
            "claude-4-5-sonnet",
            ServiceType.PROXYAI
        )

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.INLINE_EDIT)
        assertThat(notification.model).isEqualTo("claude-4-5-sonnet")
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModelWithProvider with next edit triggers next edit notification`() {
        modelSettings.state.modelSelections.remove("NEXT_EDIT")
        lastNotification.set(null)

        modelSettings.setModelWithProvider(
            FeatureType.NEXT_EDIT,
            ModelCatalog.MERCURY_CODER,
            ServiceType.PROXYAI
        )

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.NEXT_EDIT)
        assertThat(notification.model).isEqualTo(ModelCatalog.MERCURY_CODER)
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModelWithProvider with lookup triggers lookup notification`() {
        modelSettings.setModelWithProvider(FeatureType.LOOKUP, "deepseek-v3", ServiceType.PROXYAI)
        lastNotification.set(null)

        modelSettings.setModelWithProvider(FeatureType.LOOKUP, "gpt-4.1-mini", ServiceType.PROXYAI)

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.LOOKUP)
        assertThat(notification.model).isEqualTo("gpt-4.1-mini")
        assertThat(notification.serviceType).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test setModel delegates to setModelWithProvider`() {
        lastNotification.set(null)

        modelSettings.setModel(FeatureType.CHAT, "gpt-4o", ServiceType.OPENAI)

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.CHAT)
        assertThat(notification.model).isEqualTo("gpt-4o")
        assertThat(notification.serviceType).isEqualTo(ServiceType.OPENAI)
    }

    fun `test setModel with model selection delegates correctly`() {
        lastNotification.set(null)
        modelSettings.setModel(FeatureType.CHAT, "claude-sonnet-4-20250514", ServiceType.ANTHROPIC)

        val notification = lastNotification.get()
        assertThat(notification!!.featureType).isEqualTo(FeatureType.CHAT)
        assertThat(notification.model).isEqualTo("claude-sonnet-4-20250514")
        assertThat(notification.serviceType).isEqualTo(ServiceType.ANTHROPIC)
    }

    fun `test getModelSelection with valid feature returns model selection`() {
        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-4.1", ServiceType.OPENAI)

        val result = modelSettings.getModelSelection(FeatureType.CHAT)

        assertThat(result).isNotNull
        assertThat(result?.provider).isEqualTo(ServiceType.OPENAI)
        assertThat(result?.model).isEqualTo("gpt-4.1")
    }

    fun `test getModelForFeature returns stored model`() {
        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-4.1", ServiceType.OPENAI)

        val result = modelSettings.getStoredModelForFeature(FeatureType.CHAT)

        assertThat(result).isEqualTo("gpt-4.1")
    }

    fun `test getProviderForFeature returns stored provider`() {
        modelSettings.setModelWithProvider(FeatureType.CHAT, "gpt-4.1", ServiceType.OPENAI)

        val result = modelSettings.getStoredProviderForFeature(FeatureType.CHAT)

        assertThat(result).isEqualTo(ServiceType.OPENAI)
    }

    fun `test custom openai models keep configured order for chat and agent`() {
        val customServicesSettings = service<CustomServicesSettings>()
        val first = createCustomService(name = "First", model = "chat-first")
        val second = createCustomService(name = "Second", model = "chat-second")

        customServicesSettings.state.services.clear()
        customServicesSettings.state.services.add(first)
        customServicesSettings.state.services.add(second)

        assertThat(customModelNames(FeatureType.CHAT))
            .containsExactly("First (chat-first)", "Second (chat-second)")
        assertThat(customModelNames(FeatureType.AGENT))
            .containsExactly("First (chat-first)", "Second (chat-second)")

        customServicesSettings.state.services.clear()
        customServicesSettings.state.services.add(second)
        customServicesSettings.state.services.add(first)

        assertThat(customModelNames(FeatureType.CHAT))
            .containsExactly("Second (chat-second)", "First (chat-first)")
        assertThat(customModelNames(FeatureType.AGENT))
            .containsExactly("Second (chat-second)", "First (chat-first)")
    }

    fun `test custom openai agent model uses configured token limits`() {
        val customServicesSettings = service<CustomServicesSettings>()
        val customService = createCustomService(
            name = "Custom Agent",
            model = "custom-agent-model",
            contextWindowSize = 512_000L,
            maxOutputTokens = 12_000L
        )

        customServicesSettings.state.services.clear()
        customServicesSettings.state.services.add(customService)

        val selection = modelSettings.getAvailableModels(FeatureType.AGENT)
            .single { it.id == customService.id }

        assertThat(selection.llmModel.contextLength).isEqualTo(512_000L)
        assertThat(selection.llmModel.maxOutputTokens).isEqualTo(12_000L)
        assertThat(selection.llmModel.supports(LLMCapability.OpenAIEndpoint.Responses)).isFalse()
    }

    fun `test custom openai agent model uses max_completion_tokens from request body`() {
        val customServicesSettings = service<CustomServicesSettings>()
        val customService = createCustomService(
            name = "Custom Agent",
            model = "custom-agent-model",
            contextWindowSize = 256_000L,
            maxOutputTokens = 48_000L
        )
        customServicesSettings.state.services.clear()
        customServicesSettings.state.services.add(customService)

        val selection = modelSettings.getAvailableModels(FeatureType.AGENT)
            .single { it.id == customService.id }

        assertThat(selection.llmModel.contextLength).isEqualTo(256_000L)
        assertThat(selection.llmModel.maxOutputTokens).isEqualTo(48_000L)
        assertThat(selection.llmModel.supports(LLMCapability.OpenAIEndpoint.Responses)).isFalse()
    }

    fun `test custom openai responses agent model uses request body token limits`() {
        val customServicesSettings = service<CustomServicesSettings>()
        val customService = createCustomService(
            name = "Custom Responses",
            model = "custom-responses-model",
            contextWindowSize = 1_048_576L,
            maxOutputTokens = 65_536L,
            path = "https://example.com/v1/responses"
        )

        customServicesSettings.state.services.clear()
        customServicesSettings.state.services.add(customService)

        val selection = modelSettings.getAvailableModels(FeatureType.AGENT)
            .single { it.id == customService.id }

        assertThat(selection.llmModel.contextLength).isEqualTo(1_048_576L)
        assertThat(selection.llmModel.maxOutputTokens).isEqualTo(65_536L)
        assertThat(selection.llmModel.supports(LLMCapability.OpenAIEndpoint.Responses)).isTrue()
    }

    fun `test migrateMissingProviderInformation updates missing providers`() {
        val state = ModelSettingsState()
        val detailsState = ModelDetailsState()
        detailsState.model = "gpt-4.1"
        detailsState.provider = null
        state.modelSelections["CHAT"] = detailsState

        modelSettings.loadState(state)

        val result = modelSettings.getStoredProviderForFeature(FeatureType.CHAT)
        assertThat(result).isEqualTo(ServiceType.OPENAI)
    }

    fun `test migrateMissingProviderInformation with unknown model keeps model but no provider`() {
        val state = ModelSettingsState()
        val detailsState = ModelDetailsState()
        detailsState.model = "unknown-model"
        detailsState.provider = null
        state.modelSelections["CHAT"] = detailsState

        modelSettings.loadState(state)

        assertThat(modelSettings.getStoredModelForFeature(FeatureType.CHAT)).isEqualTo("unknown-model")
        assertThat(modelSettings.getStoredProviderForFeature(FeatureType.CHAT)).isNull()
    }

    private fun customModelNames(featureType: FeatureType): List<String> {
        return modelSettings.getAvailableModels(featureType)
            .filter { it.provider == ServiceType.CUSTOM_OPENAI }
            .map { it.displayName }
    }

    private fun createCustomService(
        name: String,
        model: String,
        contextWindowSize: Long = 200_000L,
        maxOutputTokens: Long = 32_768L,
        path: String = "https://example.com/v1/chat/completions"
    ): CustomServiceSettingsState {
        return CustomServiceSettingsState().apply {
            this.name = name
            this.contextWindowSize = contextWindowSize
            chatCompletionSettings.url = path
            chatCompletionSettings.body.clear()
            chatCompletionSettings.body["model"] = model
            chatCompletionSettings.body["max_output_tokens"] = maxOutputTokens
        }
    }
}
