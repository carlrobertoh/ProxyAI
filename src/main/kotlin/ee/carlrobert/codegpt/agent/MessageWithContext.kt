package ee.carlrobert.codegpt.agent

import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import java.util.UUID

class MessageWithContext(val text: String, val tags: List<TagDetails>) {

    val id: UUID = UUID.randomUUID()
}