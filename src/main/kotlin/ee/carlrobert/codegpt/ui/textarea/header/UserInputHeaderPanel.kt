package ee.carlrobert.codegpt.ui.textarea.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.EditorNotifier
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.WrapLayout
import ee.carlrobert.codegpt.ui.textarea.PromptTextField
import ee.carlrobert.codegpt.ui.textarea.TagDetailsComparator
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.EditorUtil.getSelectedEditor
import ee.carlrobert.codegpt.util.file.FileUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.ActionListener
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

class UserInputHeaderPanel(
    private val project: Project,
    private val tagManager: TagManager,
    private val totalTokensPanel: TotalTokensPanel,
    private val promptTextField: PromptTextField,
    private val withRemovableSelectedEditorTag: Boolean
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

    private val defaultHeaderTagsPanel = CustomFlowPanel().apply {
        add(AddButton {
            CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                promptTextField.requestFocus()

                // TODO: Replace with promptTextField.showGroupLookup()
                runUndoTransparentWriteAction {
                    val caretOffset = promptTextField.editor?.caretModel?.offset
                        ?: return@runUndoTransparentWriteAction
                    promptTextField.document.insertString(caretOffset, "@")
                    promptTextField.editor?.caretModel?.moveToOffset(caretOffset + 1)
                }
            }
        })
        add(emptyText)
    }

    init {
        tagManager.addListener(this)
        initializeUI()
        initializeEventListeners()
    }

    fun getSelectedTags(): List<TagDetails> =
        tagManager.getTags().filter { it.selected }.toMutableList()

    fun getLastTag(): TagDetails? {
        return tagManager.getTags()
            .sortedWith(TagDetailsComparator())
            .lastOrNull()
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

        val filesVirtualFilesSet = allTags
            .filterIsInstance<FileTagDetails>()
            .map { it.virtualFile }
            .toSet()

        /**
         * Filter the tags collection to prioritize FileTagDetails over EditorTagDetails
         * Keep all tags except EditorTagDetails that have a corresponding FileTagDetails
         */
        val tags = allTags.filter { tag ->
            if (tag is EditorTagDetails) {
                !filesVirtualFilesSet.contains(tag.virtualFile)
            } else {
                true
            }
        }
            .sortedWith(TagDetailsComparator())
            .toSet()

        updateReferencedFilesTokens(tags)
        emptyText.isVisible = tags.isEmpty()

        tags.forEach { add(createTagPanel(it)) }

        revalidate()
        repaint()
    }

    private fun createTagPanel(tagDetails: TagDetails) =
        (if (tagDetails is EditorSelectionTagDetails) {
            SelectionTagPanel(tagDetails, tagManager, promptTextField, project)
        } else {
            object : TagPanel(tagDetails, tagManager, false, project) {

                init {
                    cursor = if (tagDetails is FileTagDetails)
                        Cursor(Cursor.HAND_CURSOR)
                    else
                        Cursor(Cursor.DEFAULT_CURSOR)
                }

                override fun onSelect(tagDetails: TagDetails) = Unit

                override fun onClose() {
                    tagManager.remove(tagDetails)
                }
            }
        }).apply {
            componentPopupMenu = TagPopupMenu()
        }

    private fun initializeUI() {
        isOpaque = false
        border = JBUI.Borders.empty()

        add(defaultHeaderTagsPanel)
        addInitialTags()
    }

    private fun addInitialTags() {
        val selectedFile = getSelectedEditor(project)?.virtualFile
        if (selectedFile != null) {
            tagManager.addTag(
                EditorTagDetails(
                    selectedFile,
                    isRemovable = withRemovableSelectedEditorTag
                )
            )
        }

        EditorUtil.getOpenLocalFiles(project)
            .filterNot { it == selectedFile }
            .take(INITIAL_VISIBLE_FILES)
            .forEach {
                tagManager.addTag(EditorTagDetails(it).apply { selected = false })
            }
    }

    private fun updateReferencedFilesTokens(tags: Set<TagDetails>) {
        CoroutineScope(Dispatchers.Default).launch {
            val referencedFileContents = tags.asSequence()
                .filter { it.selected }
                .mapNotNull { tag ->
                    when (tag) {
                        is FileTagDetails -> FileUtil.readContent(tag.virtualFile)
                        is EditorTagDetails -> FileUtil.readContent(tag.virtualFile)
                        else -> null
                    }
                }
                .toList()
            runInEdt {
                totalTokensPanel.updateReferencedFilesTokens(referencedFileContents)
            }
        }
    }

    private fun initializeEventListeners() {
        project.messageBus.connect().apply {
            subscribe(EditorNotifier.SelectionChange.TOPIC, EditorSelectionChangeListener())
            subscribe(EditorNotifier.Released.TOPIC, EditorReleasedListener())
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, FileSelectionListener())
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
            val selectedVisually = isEnabled
            PaintUtil.drawRoundedBackground(g, this, selectedVisually)
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

    private inner class FileSelectionListener : FileEditorManagerListener {
        override fun selectionChanged(event: FileEditorManagerEvent) {
            event.newFile?.let { newFile ->
                val hasTag = tagManager.getTags()
                    .any { it is EditorTagDetails && it.virtualFile == newFile }
                if (!hasTag) {
                    tagManager.addTag(
                        EditorTagDetails(
                            newFile,
                            isRemovable = withRemovableSelectedEditorTag
                        )
                    )
                } else {
                    tagManager.getTags()
                        .filterIsInstance<EditorTagDetails>()
                        .firstOrNull { it.virtualFile == newFile && it.isRemovable }
                        ?.let { existing ->
                            tagManager.remove(existing)
                            tagManager.addTag(
                                EditorTagDetails(
                                    newFile,
                                    isRemovable = withRemovableSelectedEditorTag
                                )
                            )
                        }
                }

                tagManager.getTags()
                    .filterIsInstance<EditorTagDetails>()
                    .filter { it.virtualFile != newFile && !it.isRemovable }
                    .forEach { prev ->
                        tagManager.remove(prev)
                        tagManager.addTag(EditorTagDetails(prev.virtualFile).apply {
                            selected = false
                        })
                    }

                emptyText.isVisible = false
            }
        }
    }

    private inner class TagPopupMenu : JBPopupMenu() {
        private val closeMenuItem =
            createPopupMenuItem(CodeGPTBundle.get("tagPopupMenuItem.close")) {
                val tagPanel = invoker as? TagPanel
                tagPanel?.let {
                    if (it.tagDetails.isRemovable) {
                        tagManager.remove(it.tagDetails)
                    }
                }
            }

        private val closeOtherTagsMenuItem =
            createPopupMenuItem(CodeGPTBundle.get("tagPopupMenuItem.closeOthers")) {
                val tagPanel = invoker as? TagPanel
                tagPanel?.let { currentPanel ->
                    val currentTag = currentPanel.tagDetails
                    tagManager.getTags()
                        .filter { it != currentTag && it.isRemovable }
                        .forEach { tagManager.remove(it) }
                }
            }

        private val closeAllTagsMenuItem =
            createPopupMenuItem(CodeGPTBundle.get("tagPopupMenuItem.closeAll")) {
                tagManager.getTags()
                    .filter { it.isRemovable }
                    .forEach { tagManager.remove(it) }
            }

        private val closeTagsToLeftMenuItem =
            createPopupMenuItem(CodeGPTBundle.get("tagPopupMenuItem.closeTagsToLeft")) {
                closeTagsInRange { components, currentIndex ->
                    if (currentIndex > 0) {
                        components.take(currentIndex)
                    } else {
                        emptyList()
                    }
                }
            }

        private val closeTagsToRightMenuItem =
            createPopupMenuItem(CodeGPTBundle.get("tagPopupMenuItem.closeTagsToRight")) {
                closeTagsInRange { components, currentIndex ->
                    if (currentIndex >= 0 && currentIndex < components.size - 1) {
                        components.drop(currentIndex + 1)
                    } else {
                        emptyList()
                    }
                }
            }

        private fun closeTagsInRange(rangeSelector: (Array<Component>, Int) -> List<Component>) {
            val tagPanel = invoker as? TagPanel
            tagPanel?.let { currentPanel ->
                val components = this@UserInputHeaderPanel.components
                val currentIndex = components.indexOf(currentPanel)

                rangeSelector(components, currentIndex)
                    .filterIsInstance<TagPanel>()
                    .map { it.tagDetails }
                    .filter { it.isRemovable }
                    .forEach { tagManager.remove(it) }
            }
        }

        init {
            add(closeMenuItem)
            add(closeOtherTagsMenuItem)
            add(closeAllTagsMenuItem)
            add(closeTagsToLeftMenuItem)
            add(closeTagsToRightMenuItem)
        }

        override fun show(invoker: Component, x: Int, y: Int) {
            val tagPanel = when (invoker) {
                is TagPanel -> invoker
                else -> SwingUtilities.getAncestorOfClass(
                    TagPanel::class.java,
                    invoker
                ) as? TagPanel
            } ?: return
            if (!tagPanel.isEnabled) return
            val components = this@UserInputHeaderPanel.components.filterIsInstance<TagPanel>()
            val currentIndex = components.indexOf(tagPanel)

            val removableLeft = if (currentIndex > 0) {
                components.take(currentIndex).any { it.tagDetails.isRemovable }
            } else false
            val removableRight = if (currentIndex >= 0 && currentIndex < components.size - 1) {
                components.drop(currentIndex + 1).any { it.tagDetails.isRemovable }
            } else false
            val removableOthers = components
                .filterIndexed { index, _ -> index != currentIndex }
                .any { it.tagDetails.isRemovable }
            val anyRemovable = components.any { it.tagDetails.isRemovable }

            closeMenuItem.isEnabled = tagPanel.tagDetails.isRemovable
            closeTagsToLeftMenuItem.isEnabled = removableLeft
            closeTagsToRightMenuItem.isEnabled = removableRight
            closeOtherTagsMenuItem.isEnabled = removableOthers
            closeAllTagsMenuItem.isEnabled = anyRemovable

            super.show(tagPanel, x, y)
        }

        private fun createPopupMenuItem(label: String, listener: ActionListener): JBMenuItem {
            val menuItem = JBMenuItem(label)
            menuItem.addActionListener(listener)
            return menuItem
        }
    }
}
