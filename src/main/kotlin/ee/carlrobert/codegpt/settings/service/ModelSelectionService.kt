package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.llm.client.codegpt.PricingPlan

@Service
class ModelSelectionService {

    fun getModelSelectionForFeature(
        featureType: FeatureType,
        pricingPlan: PricingPlan? = null
    ): ModelSelection {
        return try {
            service<ModelSettings>().getModelSelection(featureType)
        } catch (exception: Exception) {
            logger.warn(
                "Error getting model selection for feature: $featureType, using default",
                exception
            )
            service<ModelRegistry>().getDefaultModelForFeature(featureType, pricingPlan)
        }
    }

    fun getServiceForFeature(featureType: FeatureType): ServiceType {
        return getServiceForFeature(featureType, null)
    }

    fun getServiceForFeature(featureType: FeatureType, pricingPlan: PricingPlan?): ServiceType {
        return try {
            getModelSelectionForFeature(featureType, pricingPlan).provider
        } catch (exception: Exception) {
            logger.warn("Error getting service for feature: $featureType, using default", exception)
            ServiceType.PROXYAI
        }
    }

    fun getModelForFeature(featureType: FeatureType, pricingPlan: PricingPlan? = null): String {
        return try {
            getModelSelectionForFeature(featureType, pricingPlan).model
        } catch (exception: Exception) {
            logger.warn("Error getting model for feature: $featureType, using default", exception)
            service<ModelRegistry>().getDefaultModelForFeature(featureType, pricingPlan).model
        }
    }

    companion object {

        private val logger = thisLogger()

        @JvmStatic
        fun getInstance(): ModelSelectionService {
            return ApplicationManager.getApplication().getService(ModelSelectionService::class.java)
        }
    }
}