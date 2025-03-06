package ee.carlrobert.codegpt.ui.textarea

import ee.carlrobert.codegpt.conversations.message.Message

fun interface TagProcessor {
    fun process(message: Message, promptBuilder: StringBuilder)
}
