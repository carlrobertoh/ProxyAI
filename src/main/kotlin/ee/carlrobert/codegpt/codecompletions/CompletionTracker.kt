package ee.carlrobert.codegpt.codecompletions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key

object CompletionTracker {

    private val LAST_COMPLETION_REQUEST_TIME: Key<Long> = Key.create("LAST_COMPLETION_REQUEST_TIME")
    private const val DEBOUNCE_INTERVAL_MS = 500L

    fun calcDebounceTime(editor: Editor): Long {
        val lastCompletionTimestamp = editor.getUserData(LAST_COMPLETION_REQUEST_TIME)
        if (lastCompletionTimestamp != null) {
            val elapsed = System.currentTimeMillis() - lastCompletionTimestamp
            if (elapsed < DEBOUNCE_INTERVAL_MS) {
                return DEBOUNCE_INTERVAL_MS - elapsed
            }
        }
        return 0
    }

    fun updateLastCompletionRequestTime(editor: Editor) {
        editor.putUserData(LAST_COMPLETION_REQUEST_TIME, System.currentTimeMillis())
    }
}
