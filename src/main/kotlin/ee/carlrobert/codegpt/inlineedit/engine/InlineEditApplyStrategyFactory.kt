package ee.carlrobert.codegpt.inlineedit.engine

import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType

object InlineEditApplyStrategyFactory {
    fun get(): ApplyStrategy {
        val serviceType = ModelSelectionService.getInstance().getServiceForFeature(FeatureType.INLINE_EDIT)
        return if (serviceType == ServiceType.PROXYAI) ProxyAIApplyStrategy() else SearchReplaceApplyStrategy()
    }
}

