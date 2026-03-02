package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.notification.NotificationType
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.ui.OverlayUtil

object ErrorHandler {
    
    fun handleError(error: CompletionError?, ex: Throwable?) {
        val errorMessage = formatErrorMessage(error, ex)
        OverlayUtil.showNotification(errorMessage, NotificationType.ERROR)
    }
    
    fun formatErrorMessage(error: CompletionError?, ex: Throwable?): String {
        return when {
            error?.code == "insufficient_quota" -> "You exceeded your current quota, please check your plan and billing details."
            ex?.message != null -> "Error: ${ex.message}"
            else -> "An unknown error occurred while applying changes."
        }
    }
}
