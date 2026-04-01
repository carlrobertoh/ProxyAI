package ee.carlrobert.codegpt.toolwindow.chat

import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.toolwindow.ToolWindowInitialState

fun interface InitialMessageSubmitHandler {
    fun submitInitialMessage(
        message: Message,
        psiStructure: Set<ClassStructure>,
        initialState: ToolWindowInitialState
    )
}
