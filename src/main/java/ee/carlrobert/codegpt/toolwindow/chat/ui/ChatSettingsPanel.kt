package ee.carlrobert.codegpt.toolwindow.chat.ui

import com.intellij.openapi.Disposable
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.structure.presentation.PsiStructurePanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import java.awt.BorderLayout
import javax.swing.JPanel


internal class ChatSettingsPanel(
    parentDisposable: Disposable,
    psiStructureRepository: PsiStructureRepository,
    tagManager: TagManager
) : JPanel() {

    init {
        layout = BorderLayout()
        add(PsiStructurePanel(parentDisposable, psiStructureRepository, tagManager))
    }

}