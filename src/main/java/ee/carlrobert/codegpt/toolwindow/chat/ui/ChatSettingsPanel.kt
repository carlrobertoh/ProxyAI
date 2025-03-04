package ee.carlrobert.codegpt.toolwindow.chat.ui

import com.intellij.openapi.Disposable
import ee.carlrobert.codegpt.toolwindow.chat.editorsettings.EditorTagSettings
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.structure.presentation.PsiStructurePanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JPanel

internal class ChatSettingsPanel(
    parentDisposable: Disposable,
    psiStructureRepository: PsiStructureRepository,
    tagManager: TagManager,
    onPsiTokenUpdate: (Int) -> Unit
) : JPanel() {

    private val psiStructurePanel = PsiStructurePanel(
        parentDisposable,
        psiStructureRepository,
        onPsiTokenUpdate,
    )
    private val editorTagSettings = EditorTagSettings(parentDisposable, tagManager)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        psiStructurePanel.alignmentX = Component.LEFT_ALIGNMENT
        editorTagSettings.alignmentX = Component.LEFT_ALIGNMENT
        add(psiStructurePanel)
        add(editorTagSettings)
    }
}