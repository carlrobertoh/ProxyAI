package ee.carlrobert.codegpt.settings.service.codegpt

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class CodeGPTApiException(
    var title: String? = null,
    var status: Int = 0,
    var detail: String? = null,
    var instance: String? = null,
) : RuntimeException(detail)
