package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.editor.Editor

interface NextEditProvider {
    fun request(editor: Editor, fileContent: String, caretOffset: Int, addToQueue: Boolean)
}

