package ee.carlrobert.codegpt.settings.skills.form

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.settings.skills.SkillDescriptor
import ee.carlrobert.codegpt.settings.skills.SkillDiscoveryService
import ee.carlrobert.codegpt.ui.OverlayUtil
import java.awt.Dimension
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class SkillsForm(private val project: Project) {
    companion object {
        private const val SKILLS_DIRECTORY = ".proxyai/skills"
        private const val SKILL_FILE_NAME = "SKILL.md"
        private const val BINARY_FILE_HINT =
            "Binary or non-text file. Open this file in the editor to inspect it."
    }

    private val discoveryService = project.service<SkillDiscoveryService>()
    private val rootNode = SkillTreeNode(
        kind = TreeNodeKind.ROOT,
        displayName = "Skills",
        relativePath = "",
        skillName = null
    )
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = SimpleTree(treeModel).apply {
        isRootVisible = false
        cellRenderer = SkillTreeCellRenderer()
    }

    private val skills = mutableListOf<SkillItem>()
    private val originalFileContents = mutableMapOf<String, String>()
    private val editedFileContents = mutableMapOf<String, String>()

    private var currentSkill: SkillItem? = null
    private var selectedContentRelativePath: String? = null
    private var updatingUi = false

    private val titleField = JBTextField()
    private val nameField = JBTextField()
    private val descriptionField = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 4
        border = JBUI.Borders.empty(8)
    }
    private val descriptionScroll = JBScrollPane(descriptionField).apply {
        minimumSize = Dimension(JBUI.scale(220), JBUI.scale(96))
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(120))
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border =
            JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
    }
    private val locationValue = JBLabel(".proxyai/skills/<name>/SKILL.md")
    private val editorTitle = JBLabel("File content")
    private val editorContainer = BorderLayoutPanel().apply {
        border = JBUI.Borders.emptyTop(8)
    }

    private var bodyEditor = createBodyEditor(
        content = "",
        fileName = SKILL_FILE_NAME,
        editable = true,
        relativePath = null
    )

    init {
        installMetaFieldListeners()
    }

    fun createPanel(): JComponent {
        tree.addTreeSelectionListener {
            switchToSelectedNode()
        }

        rebuildEditorContainer()
        refreshSkills()

        val treePanel = ToolbarDecorator.createDecorator(tree)
            .setPreferredSize(Dimension(JBUI.scale(330), 0))
            .setAddAction { createSkill() }
            .setRemoveActionUpdater { false }
            .addExtraAction(object :
                AnAction("New Folder", "Create folder in selected skill", AllIcons.Nodes.Folder) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = resolveCreationDirectory() != null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    createFolder()
                }
            })
            .addExtraAction(object :
                AnAction("New File", "Create file in selected skill", AllIcons.FileTypes.Text) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = resolveCreationDirectory() != null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    createFile()
                }
            })
            .addExtraAction(object :
                AnAction("Refresh", "Refresh skills tree", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun actionPerformed(e: AnActionEvent) {
                    refreshSkills(currentSkill?.name, selectedRelativePath())
                }
            })
            .addExtraAction(object :
                AnAction("Open File", "Open selected file in editor", AllIcons.Actions.MenuOpen) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(e: AnActionEvent) {
                    val node = selectedNode()
                    e.presentation.isEnabled = node != null && node.kind != TreeNodeKind.ROOT
                }

                override fun actionPerformed(e: AnActionEvent) {
                    openSelectedNode()
                }
            })
            .disableUpDownActions()
            .createPanel()

        return BorderLayoutPanel(8, 0)
            .addToLeft(treePanel)
            .addToCenter(createDetailsPanel())
    }

    fun isModified(): Boolean {
        collectDetails()
        persistCurrentEditorBuffer()
        return skills.any { it.hasChanges() } || hasChangedNonSkillFiles()
    }

    fun applyChanges() {
        collectDetails()
        persistCurrentEditorBuffer()
        validateEntries()?.let { throw ConfigurationException(it) }

        val renameMap = saveSkills()
        saveEditedFiles(renameMap)

        val currentName = currentSkill?.name?.let { renameMap[it] ?: it }
        val currentPath = remapRelativePath(selectedRelativePath(), renameMap)
        refreshSkills(currentName, currentPath)
    }

    fun resetChanges() {
        editedFileContents.clear()
        originalFileContents.clear()
        refreshSkills()
    }

    private fun createDetailsPanel(): JComponent {
        val formPanel = panel {
            row("Title:") {
                cell(titleField).align(Align.FILL).resizableColumn()
            }
            row("Name:") {
                cell(nameField).align(Align.FILL).resizableColumn()
            }
            row {
                label("Description")
            }
            row {
                cell(descriptionScroll).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row("Location:") {
                cell(locationValue).align(Align.FILL).resizableColumn()
            }
        }.apply {
            border = JBUI.Borders.empty(8, 8, 0, 8)
        }
        return BorderLayoutPanel(8, 0)
            .addToTop(formPanel)
            .addToCenter(editorContainer)
    }

    private fun refreshSkills(
        selectedSkillName: String? = currentSkill?.name,
        selectedRelativePath: String? = selectedContentRelativePath
    ) {
        loadSkillItems()
        rebuildTree(selectedSkillName, selectedRelativePath)
        if (selectedNode() == null) {
            currentSkill = null
            updateMetaFields(null)
            loadEditorForNode(null)
        }
    }

    private fun loadSkillItems() {
        skills.clear()
        skills += discoveryService.listSkills().map { SkillItem.fromDescriptor(it) }
    }

    private fun rebuildTree(selectedSkillName: String?, selectedRelativePath: String?) {
        rootNode.removeAllChildren()
        val basePath = project.basePath ?: return
        val skillsRoot = Path.of(basePath, SKILLS_DIRECTORY)

        if (Files.exists(skillsRoot) && Files.isDirectory(skillsRoot)) {
            val skillDirs = Files.list(skillsRoot).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .sorted(compareBy { it.fileName.toString().lowercase(Locale.getDefault()) })
                    .toList()
            }

            for (dir in skillDirs) {
                val skillName = dir.fileName.toString()
                val skillNode = SkillTreeNode(
                    kind = TreeNodeKind.SKILL,
                    displayName = skillName,
                    relativePath = skillName,
                    skillName = skillName
                )
                addChildren(skillNode, dir, skillsRoot, skillName)
                rootNode.add(skillNode)
            }
        }

        treeModel.reload()
        expandAll()

        val targetNode = selectedRelativePath?.let { findNodeByRelativePath(it) }
            ?: selectedSkillName?.let { findNodeBySkillName(it) }
            ?: firstSkillNode()
        if (targetNode != null) {
            tree.selectionPath = TreePath(targetNode.path)
        }
    }

    private fun addChildren(
        parent: SkillTreeNode,
        dir: Path,
        skillsRoot: Path,
        skillName: String
    ) {
        val children = Files.list(dir).use { stream ->
            stream
                .sorted(
                    compareBy<Path> { !Files.isDirectory(it) }
                        .thenBy { it.fileName.toString().lowercase(Locale.getDefault()) }
                )
                .toList()
        }

        for (child in children) {
            val relative = skillsRoot.relativize(child).toString().replace('\\', '/')
            val isDir = Files.isDirectory(child)
            val node = SkillTreeNode(
                kind = if (isDir) TreeNodeKind.DIRECTORY else TreeNodeKind.FILE,
                displayName = child.fileName.toString(),
                relativePath = relative,
                skillName = skillName
            )
            parent.add(node)
            if (isDir) {
                addChildren(node, child, skillsRoot, skillName)
            }
        }
    }

    private fun switchToSelectedNode() {
        collectDetails()
        persistCurrentEditorBuffer()
        val node = selectedNode()
        currentSkill = findSkillByName(node?.skillName)
        updateMetaFields(currentSkill)
        loadEditorForNode(node)
    }

    private fun selectedNode(): SkillTreeNode? {
        return tree.selectionPath?.lastPathComponent as? SkillTreeNode
    }

    private fun selectedRelativePath(): String? {
        return selectedNode()?.relativePath?.takeIf { it.isNotBlank() }
    }

    private fun findSkillByName(skillName: String?): SkillItem? {
        if (skillName.isNullOrBlank()) return null
        return skills.firstOrNull { it.name == skillName || it.originalName == skillName }
    }

    private fun findNodeByRelativePath(relativePath: String): SkillTreeNode? {
        val root = treeModel.root as? SkillTreeNode ?: return null
        return depthFirst(root).firstOrNull { it.relativePath == relativePath }
    }

    private fun findNodeBySkillName(skillName: String): SkillTreeNode? {
        val root = treeModel.root as? SkillTreeNode ?: return null
        return depthFirst(root).firstOrNull { it.kind == TreeNodeKind.SKILL && it.skillName == skillName }
    }

    private fun firstSkillNode(): SkillTreeNode? {
        val root = treeModel.root as? SkillTreeNode ?: return null
        return depthFirst(root).firstOrNull { it.kind == TreeNodeKind.SKILL }
    }

    private fun depthFirst(node: SkillTreeNode): Sequence<SkillTreeNode> = sequence {
        yield(node)
        val children =
            (0 until node.childCount).mapNotNull { node.getChildAt(it) as? SkillTreeNode }
        for (child in children) {
            yieldAll(depthFirst(child))
        }
    }

    private fun expandAll() {
        for (row in 0 until tree.rowCount) {
            tree.expandRow(row)
        }
    }

    private fun resolveEditorRelativePath(node: SkillTreeNode?): String? {
        if (node == null || node.kind == TreeNodeKind.ROOT) return null
        if (node.kind == TreeNodeKind.FILE) return node.relativePath
        val skill = node.skillName ?: return null
        return "$skill/$SKILL_FILE_NAME"
    }

    private fun loadEditorForNode(node: SkillTreeNode?) {
        val relativePath = resolveEditorRelativePath(node)
        selectedContentRelativePath = relativePath

        if (relativePath == null) {
            locationValue.text = ".proxyai/skills/<name>/SKILL.md"
            replaceBodyEditor("", SKILL_FILE_NAME, editable = false, relativePath = null)
            return
        }

        val fileName = Path.of(relativePath).fileName.toString()
        val diskText = readFileText(relativePath)
        val content = if (isSkillFile(relativePath) && currentSkill != null) {
            currentSkill!!.content
        } else {
            editedFileContents[relativePath] ?: diskText
        }
        val editable = content != BINARY_FILE_HINT

        originalFileContents.putIfAbsent(relativePath, diskText)
        locationValue.text = ".proxyai/skills/$relativePath"
        replaceBodyEditor(content, fileName, editable, relativePath)
    }

    private fun replaceBodyEditor(
        content: String,
        fileName: String,
        editable: Boolean,
        relativePath: String?
    ) {
        bodyEditor = createBodyEditor(content, fileName, editable, relativePath)
        rebuildEditorContainer()
    }

    private fun rebuildEditorContainer() {
        editorContainer.removeAll()
        editorContainer.border = JBUI.Borders.empty(8, 8, 8, 8)
        editorContainer.addToTop(editorTitle.apply {
            border = JBUI.Borders.emptyBottom(6)
        })
        editorContainer.addToCenter(
            JBScrollPane(bodyEditor).apply {
                border = JBUI.Borders.empty()
                viewportBorder = JBUI.Borders.empty()
            }
        )
        editorContainer.revalidate()
        editorContainer.repaint()
    }

    private fun createBodyEditor(
        content: String,
        fileName: String,
        editable: Boolean,
        relativePath: String?
    ): EditorTextField {
        val virtualFile = resolveVirtualFile(relativePath)
        val fileType =
            virtualFile?.fileType ?: FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        return EditorTextField(content, project, fileType).apply {
            setOneLineMode(false)
            preferredSize = Dimension(JBUI.scale(500), JBUI.scale(320))
            border = JBUI.Borders.empty(6)
            addSettingsProvider { editor ->
                val settings = editor.settings
                settings.isUseSoftWraps = true
                settings.isLineNumbersShown = false
                settings.isFoldingOutlineShown = false
                settings.isRightMarginShown = false
                settings.isWhitespacesShown = false
                if (editor is EditorEx) {
                    editor.setVerticalScrollbarVisible(true)
                    editor.setHorizontalScrollbarVisible(true)
                    if (virtualFile != null) {
                        editor.highlighter = EditorHighlighterFactory.getInstance()
                            .createEditorHighlighter(project, virtualFile)
                    }
                }
                editor.isViewer = !editable
                editor.caretModel.moveToOffset(0)
                editor.scrollingModel.scrollVertically(0)
                editor.scrollingModel.scrollHorizontally(0)
            }
        }
    }

    private fun resolveVirtualFile(relativePath: String?): VirtualFile? {
        if (relativePath.isNullOrBlank()) return null
        val basePath = project.basePath ?: return null
        val absolutePath = Path.of(basePath, SKILLS_DIRECTORY, relativePath).toString()
        return LocalFileSystem.getInstance().findFileByPath(absolutePath)
    }

    private fun persistCurrentEditorBuffer() {
        val relativePath = selectedContentRelativePath ?: return
        val text = bodyEditor.text
        editedFileContents[relativePath] = text
        if (isSkillFile(relativePath)) {
            currentSkill?.content = text
        }
    }

    private fun readFileText(relativePath: String): String {
        val basePath = project.basePath ?: return ""
        val path = Path.of(basePath, SKILLS_DIRECTORY, relativePath)
        if (!Files.exists(path) || Files.isDirectory(path)) return ""
        return try {
            Files.readString(path)
        } catch (_: MalformedInputException) {
            BINARY_FILE_HINT
        } catch (_: Exception) {
            BINARY_FILE_HINT
        }
    }

    private fun openSelectedNode() {
        val node = selectedNode() ?: return
        val relative = resolveEditorRelativePath(node) ?: return
        val basePath = project.basePath ?: return
        val path = Path.of(basePath, SKILLS_DIRECTORY, relative)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
        if (file == null) {
            OverlayUtil.showNotification(
                "Could not find file: ${path.fileName}",
                NotificationType.ERROR
            )
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun resolveCreationDirectory(): Path? {
        val node = selectedNode() ?: return null
        val skillName = node.skillName ?: return null
        val basePath = project.basePath ?: return null
        return when (node.kind) {
            TreeNodeKind.SKILL, TreeNodeKind.DIRECTORY -> Path.of(
                basePath,
                SKILLS_DIRECTORY,
                node.relativePath
            )

            TreeNodeKind.FILE -> Path.of(basePath, SKILLS_DIRECTORY, node.relativePath).parent
            TreeNodeKind.ROOT -> Path.of(basePath, SKILLS_DIRECTORY, skillName)
        }
    }

    private fun createFolder() {
        val directory = resolveCreationDirectory() ?: return
        val dialog = NameDialog(
            title = "Create Folder",
            fieldLabel = "Folder name:",
            validationMessage = "Folder name is required."
        )
        if (!dialog.showAndGet()) return
        val name = dialog.value
        if (!isSafeName(name)) {
            OverlayUtil.showNotification(
                "Invalid folder name. Use a simple name without path separators.",
                NotificationType.ERROR
            )
            return
        }
        try {
            val newFolder = directory.resolve(name)
            Files.createDirectories(newFolder)
            refreshSkills(currentSkill?.name, toSkillRelativePath(newFolder))
        } catch (e: Exception) {
            OverlayUtil.showNotification(
                "Failed to create folder '$name': ${e.message}",
                NotificationType.ERROR
            )
        }
    }

    private fun createFile() {
        val directory = resolveCreationDirectory() ?: return
        val dialog = NameDialog(
            title = "Create File",
            fieldLabel = "File name:",
            validationMessage = "File name is required."
        )
        if (!dialog.showAndGet()) return
        val name = dialog.value
        if (!isSafeName(name)) {
            OverlayUtil.showNotification(
                "Invalid file name. Use a simple name without path separators.",
                NotificationType.ERROR
            )
            return
        }
        try {
            val newFile = directory.resolve(name)
            if (!Files.exists(newFile)) {
                Files.createFile(newFile)
            }
            val selectedPath = toSkillRelativePath(newFile)
            refreshSkills(currentSkill?.name, selectedPath)
            openSelectedNode()
        } catch (e: Exception) {
            OverlayUtil.showNotification(
                "Failed to create file '$name': ${e.message}",
                NotificationType.ERROR
            )
        }
    }

    private fun createSkill() {
        val dialog = CreateSkillDialog()
        if (!dialog.showAndGet()) return

        val name = dialog.skillName.lowercase(Locale.getDefault())
        val title = dialog.skillTitle
        val description = dialog.skillDescription
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            OverlayUtil.showNotification("Project path is unavailable.", NotificationType.ERROR)
            return
        }

        val skillDir = Path.of(basePath, SKILLS_DIRECTORY, name)
        val skillFile = skillDir.resolve(SKILL_FILE_NAME)

        try {
            Files.createDirectories(skillDir)
            if (!Files.exists(skillFile)) {
                Files.writeString(skillFile, template(name, title, description))
                OverlayUtil.showNotification("Created skill '$name'.")
            } else {
                OverlayUtil.showNotification("Skill '$name' already exists. Opening existing file.")
            }
            refreshSkills(name, "$name/$SKILL_FILE_NAME")
            openSelectedNode()
        } catch (e: Exception) {
            OverlayUtil.showNotification(
                "Failed to create skill '$name': ${e.message}",
                NotificationType.ERROR
            )
        }
    }

    private fun updateMetaFields(skill: SkillItem?) {
        updatingUi = true
        if (skill == null) {
            titleField.text = ""
            nameField.text = ""
            descriptionField.text = ""
            updatingUi = false
            return
        }
        titleField.text = skill.title
        nameField.text = skill.name
        descriptionField.text = skill.description
        descriptionField.caretPosition = 0
        updatingUi = false
    }

    private fun collectDetails() {
        val skill = currentSkill ?: return
        if (updatingUi) return
        skill.title = titleField.text.trim()
        skill.name = nameField.text.trim().lowercase(Locale.getDefault())
        skill.description = descriptionField.text.trim()
    }

    private fun validateEntries(): String? {
        val seen = mutableSetOf<String>()
        for (item in skills) {
            if (item.title.isBlank()) return "Title is required."
            if (item.name.isBlank()) return "Name is required."
            if (!item.name.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
                return "Name '${item.name}' is invalid. Use lowercase letters, numbers, and hyphens."
            }
            if (!seen.add(item.name)) return "Skill names must be unique."
            if (item.description.isBlank()) return "Description is required."
        }
        return null
    }

    private fun saveSkills(): Map<String, String> {
        val basePath = project.basePath ?: return emptyMap()
        val skillsRoot = Path.of(basePath, SKILLS_DIRECTORY)
        Files.createDirectories(skillsRoot)

        val renameMap = mutableMapOf<String, String>()
        for (item in skills) {
            val oldDir = skillsRoot.resolve(item.originalName)
            val newDir = skillsRoot.resolve(item.name)
            renameMap[item.originalName] = item.name

            if (item.name != item.originalName) {
                if (Files.exists(newDir) && oldDir.normalize().toString() != newDir.normalize()
                        .toString()
                ) {
                    throw ConfigurationException("Cannot rename '${item.originalName}' to '${item.name}' because that folder already exists.")
                }
                if (Files.exists(oldDir)) {
                    Files.move(oldDir, newDir)
                }
            }
            Files.createDirectories(newDir)
            val skillFile = newDir.resolve(SKILL_FILE_NAME)
            val body = editedFileContents["${item.originalName}/$SKILL_FILE_NAME"] ?: item.content
            item.content = body
            Files.writeString(skillFile, template(item.name, item.title, item.description, body))
        }
        return renameMap
    }

    private fun saveEditedFiles(renameMap: Map<String, String>) {
        val basePath = project.basePath ?: return
        val skillsRoot = Path.of(basePath, SKILLS_DIRECTORY)
        for ((relativePath, content) in editedFileContents) {
            if (isSkillFile(relativePath)) continue
            val original = originalFileContents[relativePath]
            if (original != null && original == content) continue
            val remapped = remapRelativePath(relativePath, renameMap) ?: continue
            val path = skillsRoot.resolve(remapped)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }

        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
        if (root != null) {
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
        }
    }

    private fun hasChangedNonSkillFiles(): Boolean {
        return editedFileContents.any { (relativePath, text) ->
            !isSkillFile(relativePath) && text != originalFileContents[relativePath]
        }
    }

    private fun template(name: String, title: String, description: String): String {
        return """
            ---
            name: $name
            title: $title
            description: $description
            ---

            # $title

            Add step-by-step instructions for this skill.
        """.trimIndent() + "\n"
    }

    private fun template(name: String, title: String, description: String, body: String): String {
        return """
            ---
            name: $name
            title: $title
            description: $description
            ---

            ${body.trim()}
        """.trimIndent() + "\n"
    }

    private fun installMetaFieldListeners() {
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onMetaChanged()
            override fun removeUpdate(e: DocumentEvent) = onMetaChanged()
            override fun changedUpdate(e: DocumentEvent) = onMetaChanged()
        }
        titleField.document.addDocumentListener(listener)
        nameField.document.addDocumentListener(listener)
        descriptionField.document.addDocumentListener(listener)
    }

    private fun onMetaChanged() {
        if (updatingUi) return
        collectDetails()
    }

    private fun remapRelativePath(relativePath: String?, renameMap: Map<String, String>): String? {
        if (relativePath.isNullOrBlank()) return null
        val oldSkill = relativePath.substringBefore('/', missingDelimiterValue = "")
        if (oldSkill.isBlank()) return relativePath
        val newSkill = renameMap[oldSkill] ?: oldSkill
        return if (newSkill == oldSkill) {
            relativePath
        } else {
            newSkill + relativePath.removePrefix(oldSkill)
        }
    }

    private fun toSkillRelativePath(path: Path): String? {
        val basePath = project.basePath ?: return null
        val skillsRoot = Path.of(basePath, SKILLS_DIRECTORY)
        return runCatching { skillsRoot.relativize(path).toString().replace('\\', '/') }.getOrNull()
    }

    private fun isSafeName(name: String): Boolean {
        return name.isNotBlank() && !name.contains('/') && !name.contains('\\')
    }

    private fun isSkillFile(relativePath: String): Boolean {
        return relativePath.endsWith("/$SKILL_FILE_NAME") || relativePath == SKILL_FILE_NAME
    }
}

private enum class TreeNodeKind {
    ROOT,
    SKILL,
    DIRECTORY,
    FILE
}

private class SkillTreeNode(
    val kind: TreeNodeKind,
    val displayName: String,
    val relativePath: String,
    val skillName: String?
) : DefaultMutableTreeNode(displayName) {
    override fun toString(): String = displayName
}

private class SkillTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? SkillTreeNode ?: return
        icon = when (node.kind) {
            TreeNodeKind.ROOT -> AllIcons.Nodes.Folder
            TreeNodeKind.SKILL -> AllIcons.Nodes.Folder
            TreeNodeKind.DIRECTORY -> AllIcons.Nodes.Folder
            TreeNodeKind.FILE ->
                FileTypeManager.getInstance().getFileTypeByFileName(node.displayName).icon
                    ?: AllIcons.FileTypes.Any_type
        }
        append(node.displayName)
        if (node.kind == TreeNodeKind.SKILL) {
            append("  skill", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

private data class SkillItem(
    val originalName: String,
    val originalTitle: String,
    val originalDescription: String,
    val originalContent: String,
    var name: String,
    var title: String,
    var description: String,
    var content: String
) {
    fun hasChanges(): Boolean {
        return name != originalName ||
                title != originalTitle ||
                description != originalDescription ||
                content.trim() != originalContent.trim()
    }

    companion object {
        fun fromDescriptor(descriptor: SkillDescriptor): SkillItem {
            return SkillItem(
                originalName = descriptor.name,
                originalTitle = descriptor.title,
                originalDescription = descriptor.description,
                originalContent = descriptor.content,
                name = descriptor.name,
                title = descriptor.title,
                description = descriptor.description,
                content = descriptor.content
            )
        }
    }
}

private class CreateSkillDialog : DialogWrapper(true) {
    private val nameField = JBTextField()
    private val titleField = JBTextField()
    private val descriptionField = JBTextField()

    val skillName: String
        get() = nameField.text.trim()
    val skillTitle: String
        get() = titleField.text.trim()
    val skillDescription: String
        get() = descriptionField.text.trim()

    init {
        title = "Create Skill"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Name:") {
                cell(nameField).resizableColumn().focused()
                comment("Use lowercase letters, numbers, and hyphens.")
            }
            row("Title:") {
                cell(titleField).resizableColumn()
            }
            row("Description:") {
                cell(descriptionField).resizableColumn()
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = skillName
        if (name.isBlank()) {
            return ValidationInfo("Name is required.", nameField)
        }
        if (!name.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
            return ValidationInfo(
                "Name must start with a letter/number and use only lowercase letters, numbers, and hyphens.",
                nameField
            )
        }
        if (skillTitle.isBlank()) {
            return ValidationInfo("Title is required.", titleField)
        }
        if (skillDescription.isBlank()) {
            return ValidationInfo("Description is required.", descriptionField)
        }
        return null
    }
}

private class NameDialog(
    private val fieldLabel: String,
    private val validationMessage: String,
    title: String
) : DialogWrapper(true) {
    private val valueField = JBTextField()

    val value: String
        get() = valueField.text.trim()

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row(fieldLabel) {
                cell(valueField).resizableColumn().focused()
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (value.isBlank()) {
            return ValidationInfo(validationMessage, valueField)
        }
        return null
    }
}
