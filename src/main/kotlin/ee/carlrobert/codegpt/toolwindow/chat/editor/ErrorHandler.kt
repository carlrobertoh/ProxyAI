package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.notification.NotificationType
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.llm.client.openai.completion.ErrorDetails

object ErrorHandler {
    
    fun handleError(error: ErrorDetails?, ex: Throwable?) {
        val errorMessage = formatErrorMessage(error, ex)
        OverlayUtil.showNotification(errorMessage, NotificationType.ERROR)
    }
    
    fun formatErrorMessage(error: ErrorDetails?, ex: Throwable?): String {
        return when {
            error?.code == "insufficient_quota" -> "You exceeded your current quota, please check your plan and billing details."
            ex?.message != null -> "Error: ${ex.message}"
            else -> "An unknown error occurred while applying changes."
        }
    }
}