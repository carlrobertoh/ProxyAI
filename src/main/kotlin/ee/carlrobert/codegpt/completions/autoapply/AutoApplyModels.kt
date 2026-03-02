package ee.carlrobert.codegpt.completions.autoapply

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

data class AutoApplyRequest(
    val model: String,
    val code: String,
    val update: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AutoApplyResponse(
    val mergedCode: String = "",
)
