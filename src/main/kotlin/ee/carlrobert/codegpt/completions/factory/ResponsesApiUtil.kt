package ee.carlrobert.codegpt.completions.factory

import java.net.URI

object ResponsesApiUtil {

    fun isResponsesApiUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val path = URI(url.trim()).path?.trimEnd('/') ?: return false
            path.endsWith("/responses")
        } catch (_: Exception) {
            false
        }
    }
}
