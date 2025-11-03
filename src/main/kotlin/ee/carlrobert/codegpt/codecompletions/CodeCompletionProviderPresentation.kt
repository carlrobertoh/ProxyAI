package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.InlineCompletionProviderPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import ee.carlrobert.codegpt.Icons
import javax.swing.JComponent
import javax.swing.SwingConstants

class CodeCompletionProviderPresentation : InlineCompletionProviderPresentation {

    override fun getTooltip(project: Project?): JComponent {
        val selectedModelCode =
            service<CodeCompletionService>().getSelectedModelCode() ?: ""
        val text = if (selectedModelCode.isNotEmpty()) {
            "CodeGPT: $selectedModelCode"
        } else {
            "ProxyAI"
        }
        return JBLabel(text, Icons.DefaultSmall, SwingConstants.LEADING).withFont(JBFont.small())
    }
}