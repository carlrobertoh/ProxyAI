package ee.carlrobert.codegpt.settings.prompts.form.details

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.ui.CardLayoutPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.settings.Placeholder
import ee.carlrobert.codegpt.settings.Placeholder.GIT_DIFF
import ee.carlrobert.codegpt.settings.prompts.CommitMessageTemplate
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState.Companion.DEFAULT_EDIT_CODE_PROMPT
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState.Companion.DEFAULT_FIX_COMPILE_ERRORS_PROMPT
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState.Companion.DEFAULT_GENERATE_COMMIT_MESSAGE_PROMPT
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState.Companion.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState.Companion.DEFAULT_REVIEW_CHANGES_PROMPT
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import javax.swing.JComponent
import javax.swing.JPanel

class CoreActionsDetailsPanel : PromptDetailsPanel {

    private val cardLayoutPanel =
        object : CardLayoutPanel<CoreActionPromptDetails, CoreActionPromptDetails, JComponent>() {
            override fun prepare(key: CoreActionPromptDetails) = key

            override fun create(details: CoreActionPromptDetails): JComponent {
                val editorPanel = when (details.code) {

                    "EDIT_CODE" -> CoreActionEditorPanel(
                        details,
                        DEFAULT_EDIT_CODE_PROMPT,
                        "Template used for the 'Edit Code' feature in the main editor."
                    )

                    "REVIEW_CHANGES" -> CoreActionEditorPanel(
                        details,
                        DEFAULT_REVIEW_CHANGES_PROMPT,
                        "Template used for reviewing changes in the commit dialog."
                    )

                    "FIX_COMPILE_ERRORS" -> CoreActionEditorPanel(
                        details,
                        DEFAULT_FIX_COMPILE_ERRORS_PROMPT,
                        "Template used for resolving compile errors in the code."
                    )

                    "GENERATE_COMMIT_MESSAGE" -> CoreActionEditorPanel(
                        details,
                        DEFAULT_GENERATE_COMMIT_MESSAGE_PROMPT,
                        CommitMessageTemplate.getHtmlDescription(),
                        listOf("{BRANCH_NAME}", "{DATE_ISO_8601}")
                    )

                    "GENERATE_NAME_LOOKUPS" -> CoreActionEditorPanel(
                        details,
                        DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT,
                        "Template used for generating suitable lookup names for a given method or function body."
                    )

                    else -> null
                }

                return editorPanel?.getPanel() ?: JPanel()
            }
        }

    init {
        val settings = service<PromptsSettings>().state.coreActions
        listOf(
            settings.editCode,
            settings.fixCompileErrors,
            settings.generateCommitMessage,
            settings.generateNameLookups,
            settings.reviewChanges,
        ).forEach {
            cardLayoutPanel.select(CoreActionPromptDetails(it), true)
        }
    }

    override fun getPanel() = cardLayoutPanel

    override fun updateData(details: FormPromptDetails) {
        if (details !is CoreActionPromptDetails || details.code.isNullOrEmpty()) {
            return
        }

        cardLayoutPanel.select(details, true)
    }

    override fun remove(details: FormPromptDetails) {
        TODO("Not implemented")
    }

    private class CoreActionEditorPanel(
        details: CoreActionPromptDetails,
        private val defaultInstructions: String,
        private val description: String,
        highlightedPlaceholders: List<String> = emptyList()
    ) : AbstractEditorPromptPanel(details, highlightedPlaceholders) {

        override fun getPanel(): JPanel = panel {
            row {
                cell(BorderLayoutPanel().addToTop(editor.component))
                    .align(Align.FILL)
                    .resizableColumn()
            }
            row {
                link("Revert to default") {
                    updateEditorText(defaultInstructions)
                }
            }.visibleIf(object : ComponentPredicate() {
                override fun addListener(listener: (Boolean) -> Unit) {
                    editor.document.addDocumentListener(object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            listener(invoke())
                        }
                    })
                }

                override fun invoke(): Boolean = defaultInstructions != editor.document.text
            })
            row {
                comment(description, 96)
            }
        }
    }
}