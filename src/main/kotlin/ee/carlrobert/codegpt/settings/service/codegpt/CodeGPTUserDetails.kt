package ee.carlrobert.codegpt.settings.service.codegpt

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class CodeGPTUserDetails(
    val fullName: String? = null,
    val pricingPlan: String? = null,
    val creditsUsed: Long? = null,
    val creditsTotal: Long? = null,
    val availableModels: List<AvailableModel>? = null,
    val toolWindowModels: List<AvailableModel>? = null,
    val avatarBase64: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvailableModel(
    val code: String? = null,
    val title: String? = null,
)
