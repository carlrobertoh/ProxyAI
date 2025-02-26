package ee.carlrobert.codegpt.ui.textarea.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.EditorNotifier
import ee.carlrobert.codegpt.actions.IncludeFilesInContextNotifier
import ee.carlrobert.codegpt.ui.WrapLayout
import ee.carlrobert.codegpt.ui.textarea.PromptTextField
import ee.carlrobert.codegpt.ui.textarea.TagDetailsComparator
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.ui.textarea.suggestion.SuggestionsPopupManager
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.EditorUtil.getSelectedEditor
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import javax.swing.JButton
import javax.swing.JPanel

class UserInputHeaderPanel(
    private val project: Project,
    private val tagManager: TagManager,
    suggestionsPopupManager: SuggestionsPopupManager,
    private val promptTextField: PromptTextField
) : JPanel(WrapLayout(FlowLayout.LEFT, 4, 4)), TagManagerListener {

    companion object {
        private const val INITIAL_VISIBLE_FILES = 2
    }

    private val emptyText = JBLabel("No context included").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = JBUI.Fonts.smallFont()
        isVisible = getSelectedEditor(project) == null
        preferredSize = Dimension(preferredSize.width, 20)
        verticalAlignment = JBLabel.CENTER
    }

    //    private val selectionTagPanel = SelectionTagPanel(project, tagManager, promptTextField)
    private val defaultHeaderTagsPanel = CustomFlowPanel().apply {
        add(AddButton {
            if (suggestionsPopupManager.isPopupVisible()) {
                suggestionsPopupManager.hidePopup()
            } else {
                suggestionsPopupManager.showPopup(this)
            }
        })
        add(emptyText)
//        add(selectionTagPanel)
    }

    init {
        tagManager.addListener(this)
        initializeUI()
        initializeEventListeners()
    }

    fun getSelectedTags(): List<TagDetails> {
        val selectedTags = tagManager.getTags().filter { it.selected }.toMutableList()

        return selectedTags
    }

    fun addTag(tagDetails: TagDetails) {
        tagManager.addTag(tagDetails)
    }

    override fun onTagAdded(tag: TagDetails) {
        onTagsChanged()
    }

    override fun onTagRemoved(tag: TagDetails) {
        onTagsChanged()
    }

    override fun onTagSelectionChanged(tag: TagDetails) {
        onTagsChanged()
    }

    private fun onTagsChanged() {
        components.filterIsInstance<TagPanel>()
            .forEach { remove(it) }

        val allTags = tagManager.getTags()

        val editorVirtualFilesSet = allTags
            .filterIsInstance<EditorTagDetails>()
            .map { it.virtualFile }
            .toSet()

        /**
         * Filter the tags collection to prioritize EditorTagDetails over FileTagDetails
         * Keep all tags except FileTagDetails that have a corresponding EditorTagDetails
         */
        val tags = allTags.filter { tag ->
            if (tag is FileTagDetails) {
                !editorVirtualFilesSet.contains(tag.virtualFile)
            } else {
                true
            }
        }
            .sortedWith(TagDetailsComparator())
            .toSet()

        emptyText.isVisible = tags.none { it.selected }

        tags.forEach { add(createTagPanel(it)) }

        revalidate()
        repaint()
    }

    private fun createTagPanel(tagDetails: TagDetails) =
        if (tagDetails is EditorSelectionTagDetails) {
            SelectionTagPanel(tagDetails, tagManager, promptTextField)
        } else {
            object : TagPanel(tagDetails, tagManager, false) {

                init {
                    cursor =
                        if (tagDetails is FileTagDetails) Cursor(Cursor.HAND_CURSOR) else Cursor(Cursor.DEFAULT_CURSOR)
                }

                override fun onSelect(tagDetails: TagDetails) = Unit

                override fun onClose() {
                    tagManager.remove(tagDetails)
                }
            }
        }

    private fun initializeUI() {
        isOpaque = false
        border = JBUI.Borders.empty()

        add(defaultHeaderTagsPanel)
        addInitialTags()
    }

    private fun addInitialTags() {
        val selectedFile = getSelectedEditor(project)?.virtualFile
        EditorUtil.getOpenLocalFiles(project)
            .map { EditorTagDetails(it) }
            .filterNot { it.virtualFile == selectedFile }
            .take(INITIAL_VISIBLE_FILES)
            .forEach {
                tagManager.addTag(it.apply { selected = false })
            }
    }

    private fun initializeEventListeners() {
        project.messageBus.connect().apply {
            subscribe(EditorNotifier.SelectionChange.TOPIC, EditorSelectionChangeListener())
            subscribe(EditorNotifier.Released.TOPIC, EditorReleasedListener())
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, FileSelectionListener())
            subscribe(
                IncludeFilesInContextNotifier.FILES_INCLUDED_IN_CONTEXT_TOPIC,
                IncludedFilesListener()
            )
        }
    }

    private class AddButton(onAdd: () -> Unit) : JButton() {
        init {
            addActionListener {
                onAdd()
            }

            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(20, 20)
            isContentAreaFilled = false
            isOpaque = false
            border = null
            toolTipText = "Add Context"
            icon = IconUtil.scale(AllIcons.General.InlineAdd, null, 0.75f)
            rolloverIcon = IconUtil.scale(AllIcons.General.InlineAddHover, null, 0.75f)
            pressedIcon = IconUtil.scale(AllIcons.General.InlineAddHover, null, 0.75f)
        }

        override fun paintComponent(g: Graphics) {
            PaintUtil.drawRoundedBackground(g, this, true)
            super.paintComponent(g)
        }
    }

    private inner class EditorSelectionChangeListener : EditorNotifier.SelectionChange {
        override fun selectionChanged(selectionModel: SelectionModel, virtualFile: VirtualFile) {
            handleSelectionChange(selectionModel, virtualFile)
        }

        private fun handleSelectionChange(
            selectionModel: SelectionModel,
            virtualFile: VirtualFile
        ) {
            if (selectionModel.hasSelection()) {
                val editorSelectionTagDetails = EditorSelectionTagDetails(virtualFile, selectionModel)
                if (tagManager.getTags().contains(editorSelectionTagDetails)) {
                    tagManager.remove(editorSelectionTagDetails)
                }
                tagManager.addTag(EditorSelectionTagDetails(virtualFile, selectionModel))
            } else {
                tagManager.remove(EditorSelectionTagDetails(virtualFile, selectionModel))
            }

        }
    }

    private inner class EditorReleasedListener : EditorNotifier.Released {
        override fun editorReleased(editor: Editor) {
            if (editor.editorKind == EditorKind.MAIN_EDITOR && !editor.isDisposed && editor.virtualFile != null) {
                tagManager.remove(EditorTagDetails(editor.virtualFile))
            }
        }
    }

//    private fun getSelectedFile(): VirtualFile? {
//        return (selectedFileTagPanel.tagDetails as? FileTagDetails)?.virtualFile
//    }

    private inner class FileSelectionListener : FileEditorManagerListener {
        override fun selectionChanged(event: FileEditorManagerEvent) {
            event.newFile?.let { newFile ->
                val editorTagDetails = EditorTagDetails(newFile)
                tagManager.addTag(editorTagDetails)
                emptyText.isVisible = false
            }
        }
    }

    private inner class IncludedFilesListener : IncludeFilesInContextNotifier {
        override fun filesIncluded(includedFiles: MutableList<VirtualFile>) {
            includedFiles.forEach { tagManager.addTag(FileTagDetails(it)) }
        }
    }
}