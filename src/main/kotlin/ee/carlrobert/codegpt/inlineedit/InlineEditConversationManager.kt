package ee.carlrobert.codegpt.inlineedit

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Key
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message

/**
 * Maintains an ephemeral inline-edit conversation (user/assistant turns) per editor.
 * This mirrors how regular chat builds provider-friendly message histories.
 */
object InlineEditConversationManager {

    private val CONVERSATION_KEY: Key<Conversation> = Key.create("InlineEditConversation")

    fun getOrCreate(editor: EditorEx): Conversation {
        val existing = editor.getUserData(CONVERSATION_KEY)
        if (existing != null) return existing
        val conversation = Conversation().apply {
            projectPath = editor.project?.basePath
            title = "Inline Edit (${editor.virtualFile?.name ?: "untitled"})"
        }
        editor.putUserData(CONVERSATION_KEY, conversation)
        return conversation
    }

    fun addUserMessage(editor: EditorEx, prompt: String): Message {
        val message = Message(prompt)
        getOrCreate(editor).addMessage(message)
        return message
    }

    fun addAssistantResponse(message: Message, content: String) {
        message.response = content
    }

    fun clear(editor: EditorEx) {
        editor.putUserData(CONVERSATION_KEY, null)
    }

    fun moveConversation(source: EditorEx?, target: EditorEx?) {
        if (source == null || target == null) return
        val conversation = source.getUserData(CONVERSATION_KEY) ?: return
        target.putUserData(CONVERSATION_KEY, conversation)
        source.putUserData(CONVERSATION_KEY, null)
    }
}