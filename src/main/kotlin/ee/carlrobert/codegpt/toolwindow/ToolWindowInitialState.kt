package ee.carlrobert.codegpt.toolwindow

import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails

data class ToolWindowInitialState @JvmOverloads constructor(
    val conversation: Conversation,
    val tags: List<TagDetails> = emptyList(),
    val chatMode: ChatMode? = null,
)
