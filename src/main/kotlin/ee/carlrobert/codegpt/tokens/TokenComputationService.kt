package ee.carlrobert.codegpt.tokens

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import ee.carlrobert.codegpt.EncodingManager

@Service
class TokenComputationService {
    private val encodingManager = EncodingManager.getInstance()

    fun countTextTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return encodingManager.countTokens(text)
    }

    fun estimateTokensByLength(length: Int): Int = length / 4

    companion object {
        fun getInstance(): TokenComputationService =
            ApplicationManager.getApplication().getService(TokenComputationService::class.java)
    }
}
