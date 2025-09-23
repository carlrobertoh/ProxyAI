package ee.carlrobert.codegpt.settings.models

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifier
import ee.carlrobert.codegpt.settings.service.ServiceType
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

    private fun getModelsForFeatureType(featureType: FeatureType): List<ModelSelection> {
        return ModelRegistry.getInstance().getAllModelsForFeature(featureType)
    }

    override fun loadState(state: ModelSettingsState) {
        val oldState = this.state
        super.loadState(state)

        migrateCustomOpenAIModelCodesToIds()
        migrateMissingProviderInformation()
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
                ModelRegistry.getInstance().findModel(provider, model)
            }
        }
    }

    fun getModelForFeature(featureType: FeatureType): String? {
        return getModelDetailsState(featureType)?.model
    }

    fun getProviderForFeature(featureType: FeatureType): ServiceType? {
        return getModelDetailsState(featureType)?.provider
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
                val service = findServiceTypeForModel(featureType, newModel)
                notifyModelChange(featureType, newModel, service)
            }
        }
    }

    private fun getModelFromState(state: ModelSettingsState, featureType: FeatureType): String? {
        return state.getModelSelection(featureType)?.model
    }

    private fun findServiceTypeForModel(featureType: FeatureType, modelId: String?): ServiceType {
        return ServiceType.CUSTOM_OPENAI
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

    private fun inferProviderFromModelCode(
        featureType: FeatureType,
        modelCode: String
    ): ServiceType? {
        val models = getModelsForFeatureType(featureType)
        return models.find { it.model == modelCode }?.provider
    }

    private fun migrateCustomOpenAIModelCodesToIds() {
        val servicesByName: Map<String, List<String>> = try {
            CustomServicesSettings::class.java
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
        fun getInstance(): ModelSettings = service()
    }
}
