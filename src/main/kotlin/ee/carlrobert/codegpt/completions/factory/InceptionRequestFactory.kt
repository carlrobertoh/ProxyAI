package ee.carlrobert.codegpt.completions.factory

import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.completions.inception.InceptionChatMessage
import ee.carlrobert.codegpt.completions.inception.InceptionNextEditRequest
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.util.EditWindowFormatter.FormatResult

class InceptionRequestFactory : BaseRequestFactory() {

    override fun createNextEditRequest(params: NextEditParameters, formatResult: FormatResult): InceptionNextEditRequest {
        val model = ModelSettings.getInstance().getModelForFeature(FeatureType.NEXT_EDIT)
        val content = composeNextEditMessage(params, formatResult)
        val message = InceptionChatMessage("user", content)
        return InceptionNextEditRequest.Builder()
            .setModel(model)
            .setMessages(listOf(message))
            .build()
    }
}
