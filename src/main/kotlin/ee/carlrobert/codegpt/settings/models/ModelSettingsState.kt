package ee.carlrobert.codegpt.settings.models

import com.intellij.openapi.components.BaseState
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType

class ModelSettingsState : BaseState() {
    var modelSelections by map<FeatureType, ModelDetailsState>()

    init {
        if (modelSelections.isEmpty()) {
            initializeDefaults()
        }
    }

    private fun initializeDefaults() {
        val registry = ModelRegistry.getInstance()
        FeatureType.entries.forEach { featureType ->
            val defaultModel = registry.getDefaultModelForFeature(featureType)
            if (defaultModel != null) {
                setModelSelection(featureType, defaultModel.model, defaultModel.provider)
            }
        }
    }

    fun getModelSelection(featureType: FeatureType): ModelDetailsState? {
        return modelSelections[featureType]
    }

    fun setModelSelection(featureType: FeatureType, model: String?, provider: ServiceType) {
        modelSelections[featureType] = ModelDetailsState().apply {
            this.model = model
            this.provider = provider
        }
    }
}

class ModelDetailsState : BaseState() {
    var model by string()
    var provider by enum<ServiceType>()
}