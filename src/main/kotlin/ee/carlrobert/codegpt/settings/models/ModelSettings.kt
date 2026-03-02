package ee.carlrobert.codegpt.settings.models

import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifier
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings

@Service
@State(
    name = "CodeGPT_ModelSettings",
    storages = [Storage("CodeGPT_ModelSettings.xml")]
)
class ModelSettings : SimplePersistentStateComponent<ModelSettingsState>(ModelSettingsState()) {

    private data class PublisherMethod(
        val publisher: (ModelChangeNotifier, String, ServiceType) -> Unit
    )

    private val publisherMethods = mapOf(
        FeatureType.AGENT to PublisherMethod { publisher, model, serviceType ->
            publisher.agentModelChanged(model, serviceType)
        },
        FeatureType.CHAT to PublisherMethod { publisher, model, serviceType ->
            publisher.chatModelChanged(model, serviceType)
        },
        FeatureType.CODE_COMPLETION to PublisherMethod { publisher, model, serviceType ->
            publisher.codeModelChanged(model, serviceType)
        },
        FeatureType.AUTO_APPLY to PublisherMethod { publisher, model, serviceType ->
            publisher.autoApplyModelChanged(model, serviceType)
        },
        FeatureType.COMMIT_MESSAGE to PublisherMethod { publisher, model, serviceType ->
            publisher.commitMessageModelChanged(model, serviceType)
        },
        FeatureType.INLINE_EDIT to PublisherMethod { publisher, model, serviceType ->
            publisher.inlineEditModelChanged(model, serviceType)
        },
        FeatureType.NEXT_EDIT to PublisherMethod { publisher, model, serviceType ->
            publisher.nextEditModelChanged(model, serviceType)
        },
        FeatureType.LOOKUP to PublisherMethod { publisher, model, serviceType ->
            publisher.nameLookupModelChanged(model, serviceType)
        }
    )

    private fun getModelDetailsState(featureType: FeatureType): ModelDetailsState? {
        return state.getModelSelection(featureType)
    }

    private fun getPublisherMethod(featureType: FeatureType): PublisherMethod {
        return publisherMethods[featureType] ?: error("No publisher method for $featureType")
    }

    fun getAvailableModels(featureType: FeatureType): List<ModelSelection> {
        return service<ModelCatalog>().getAllModelsForFeature(featureType)
    }

    fun getAvailableProviders(featureType: FeatureType): List<ServiceType> {
        return service<ModelCatalog>().getProvidersForFeature(featureType)
    }

    fun isFeatureSupportedByProvider(featureType: FeatureType, provider: ServiceType): Boolean {
        return service<ModelCatalog>().isFeatureSupportedByProvider(featureType, provider)
    }

    fun findModel(provider: ServiceType, modelCode: String): ModelSelection? {
        return service<ModelCatalog>().findModel(provider, modelCode)
    }

    fun getDefaultModelSelection(featureType: FeatureType): ModelSelection {
        return service<ModelCatalog>().getDefaultModelForFeature(featureType)
    }

    fun getModelDisplayName(provider: ServiceType, modelCode: String?): String {
        return service<ModelCatalog>().getModelDisplayName(provider, modelCode)
    }

    override fun loadState(state: ModelSettingsState) {
        val oldState = this.state
        super.loadState(state)

        migrateCustomOpenAIModelCodesToIds()
        migrateMissingProviderInformation()
        migrateEditCodeModel()
        migrateProxyAIModels()
        notifyIfChanged(oldState, this.state)
    }

    fun setModel(featureType: FeatureType, model: String?, serviceType: ServiceType) {
        setModelWithProvider(featureType, model, serviceType)
    }

    fun setModelWithProvider(
        featureType: FeatureType,
        model: String?,
        serviceType: ServiceType
    ) {
        state.setModelSelection(featureType, model, serviceType)
        notifyModelChange(featureType, model, serviceType)
    }

    fun getModelSelection(featureType: FeatureType): ModelSelection? {
        val details = getModelDetailsState(featureType)
        return details?.model?.let { model ->
            details.provider?.let { provider ->
                findModel(provider, model)
            }
        }
    }

    fun getStoredModelForFeature(featureType: FeatureType): String? {
        return getModelDetailsState(featureType)?.model
    }

    fun getStoredProviderForFeature(featureType: FeatureType): ServiceType? {
        return getModelDetailsState(featureType)?.provider
    }

    fun getAgentModel(): LLModel {
        return getModelSelectionForFeature(FeatureType.AGENT).llmModel
    }

    fun getModelSelectionForFeature(featureType: FeatureType): ModelSelection {
        val modelDetailsState = state.getModelSelection(featureType)
        if (modelDetailsState?.model != null && modelDetailsState.provider != null) {
            val foundModel = findModel(modelDetailsState.provider!!, modelDetailsState.model!!)
            if (foundModel != null) {
                return foundModel
            }

            logger.warn(
                "Stored model selection not found for feature=$featureType provider=${modelDetailsState.provider} model=${modelDetailsState.model}; using default."
            )
        }

        return getDefaultModelSelection(featureType)
    }

    fun getServiceForFeature(featureType: FeatureType): ServiceType {
        return getModelSelectionForFeature(featureType).provider
    }

    fun getModelForFeature(featureType: FeatureType): String {
        return getModelSelectionForFeature(featureType).model
    }

    fun syncWithAvailableCustomOpenAIModels(preferredServiceId: String? = null) {
        FeatureType.entries.forEach { featureType ->
            if (!isFeatureSupportedByProvider(featureType, ServiceType.CUSTOM_OPENAI)) {
                return@forEach
            }

            val current = getModelSelection(featureType)
            if (current?.provider != ServiceType.CUSTOM_OPENAI) {
                return@forEach
            }

            val available = getAvailableModels(featureType)
                .filter { it.provider == ServiceType.CUSTOM_OPENAI }
            val isCurrentValid = available.any { it.model == current.model }

            if (!isCurrentValid) {
                val newId = when {
                    !preferredServiceId.isNullOrBlank() && available.any { it.model == preferredServiceId } -> preferredServiceId
                    available.isNotEmpty() -> available.first().model
                    else -> null
                }
                setModelWithProvider(featureType, newId, ServiceType.CUSTOM_OPENAI)
            }
        }
    }

    private fun notifyModelChange(
        featureType: FeatureType,
        model: String?,
        serviceType: ServiceType
    ) {
        val publisher = ApplicationManager.getApplication().messageBus
            .syncPublisher(ModelChangeNotifier.getTopic())

        val safeModel = model ?: ""
        publisher.modelChanged(featureType, safeModel, serviceType)

        getPublisherMethod(featureType).publisher(publisher, safeModel, serviceType)
    }

    private fun notifyIfChanged(oldState: ModelSettingsState, newState: ModelSettingsState) {
        FeatureType.entries.forEach { featureType ->
            val oldModel = getModelFromState(oldState, featureType)
            val newModel = getModelFromState(newState, featureType)

            if (oldModel != newModel) {
                val service = getStoredProviderForFeature(featureType) ?: return
                notifyModelChange(featureType, newModel, service)
            }
        }
    }

    private fun getModelFromState(state: ModelSettingsState, featureType: FeatureType): String? {
        return state.getModelSelection(featureType)?.model
    }

    private fun migrateMissingProviderInformation() {
        FeatureType.entries.forEach { featureType ->
            val modelDetailsState = getModelDetailsState(featureType)
            val modelCode = modelDetailsState?.model
            val provider = modelDetailsState?.provider

            if (modelCode != null && provider == null) {
                val inferredProvider = inferProviderFromModelCode(featureType, modelCode)
                inferredProvider?.let { state.setModelSelection(featureType, modelCode, it) }
            }
        }
    }

    private fun migrateEditCodeModel() {
        state.modelSelections["EDIT_CODE"]?.let {
            state.setModelSelection(FeatureType.INLINE_EDIT, it.model, it.provider!!)
            state.modelSelections.remove("EDIT_CODE")
        }
    }

    private fun migrateProxyAIModels() {
        listOf(FeatureType.AUTO_APPLY, FeatureType.CODE_COMPLETION, FeatureType.NEXT_EDIT).forEach {
            val modelSelection = state.getModelSelection(it)
            if (modelSelection?.provider == PROXYAI && modelSelection.model != ModelCatalog.MERCURY_CODER) {
                setModelWithProvider(it, ModelCatalog.MERCURY_CODER, PROXYAI)
            }
        }
        listOf(
            FeatureType.CHAT,
            FeatureType.COMMIT_MESSAGE,
            FeatureType.LOOKUP,
            FeatureType.INLINE_EDIT
        ).forEach {
            val modelSelection = state.getModelSelection(it)
            if (modelSelection?.provider == PROXYAI) {
                if (modelSelection.model == ModelCatalog.CLAUDE_4_SONNET) {
                    setModelWithProvider(it, ModelCatalog.CLAUDE_4_5_SONNET, PROXYAI)
                } else if (modelSelection.model == ModelCatalog.CLAUDE_4_SONNET_THINKING) {
                    setModelWithProvider(it, ModelCatalog.CLAUDE_4_5_SONNET_THINKING, PROXYAI)
                }
            }
        }
    }

    private fun inferProviderFromModelCode(
        featureType: FeatureType,
        modelCode: String
    ): ServiceType? {
        val models = getAvailableModels(featureType)
        return models.find { it.selectionId == modelCode || it.modelId == modelCode }?.provider
    }

    private fun migrateCustomOpenAIModelCodesToIds() {
        val servicesByName: Map<String, List<String>> = try {
            service<CustomServicesSettings>().state.services
                .groupBy({ it.name ?: "" }, { it.id ?: "" })
                .filterKeys { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyMap()
        }

        if (servicesByName.isEmpty()) return

        FeatureType.entries.forEach { featureType ->
            val details = state.getModelSelection(featureType) ?: return@forEach
            if (details.provider == ServiceType.CUSTOM_OPENAI && !details.model.isNullOrBlank()) {
                val current = details.model!!
                val ids = servicesByName[current]
                if (ids != null && ids.size == 1) {
                    val id = ids.first()
                    if (id.isNotBlank() && id != current) {
                        state.setModelSelection(featureType, id, ServiceType.CUSTOM_OPENAI)
                    }
                }
            }
        }
    }

    companion object {
        private val logger = thisLogger()

        @JvmStatic
        fun getInstance(): ModelSettings = service()
    }
}
