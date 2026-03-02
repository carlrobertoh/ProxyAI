package ee.carlrobert.codegpt.settings.models

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType

class ModelSettingsState : BaseState() {
    var modelSelections by map<String, ModelDetailsState>()

    init {
        if (modelSelections.isEmpty()) {
            initializeDefaults()
        }
    }

    private fun initializeDefaults() {
        val registry = service<ModelCatalog>()
        FeatureType.entries.forEach { featureType ->
            val defaultModel = registry.getDefaultModelForFeature(featureType)
            setModelSelection(featureType, defaultModel.model, defaultModel.provider)
        }
    }

    fun getModelSelection(featureType: FeatureType): ModelDetailsState? {
        return modelSelections[featureType.name]
    }

    fun setModelSelection(featureType: FeatureType, model: String?, provider: ServiceType) {
        modelSelections[featureType.name] = ModelDetailsState().apply {
            this.model = model
            this.provider = provider
        }
    }
}

class ModelDetailsState : BaseState() {
    var model by string()
    var provider by enum<ServiceType>()
}