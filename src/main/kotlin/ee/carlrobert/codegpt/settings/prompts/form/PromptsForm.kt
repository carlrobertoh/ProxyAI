package ee.carlrobert.codegpt.settings.prompts.form

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.prompts.*
import ee.carlrobert.codegpt.settings.prompts.form.PromptsFormUtil.getFormState
import ee.carlrobert.codegpt.settings.prompts.form.PromptsFormUtil.toState
import ee.carlrobert.codegpt.settings.prompts.form.details.*
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.ApplicationUtil
import ee.carlrobert.codegpt.util.coroutines.EdtDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

enum class PromptCategory {
    CORE_ACTIONS,
    CHAT_ACTIONS,
    PERSONAS,
}

class PromptDetailsTreeNode(
    val details: FormPromptDetails,
    val category: PromptCategory
) : DefaultMutableTreeNode() {

    override fun toString(): String {
        return details.name ?: ""
    }
}

class PromptsForm {
    private val coroutineScope = CoroutineScope(SupervisorJob() + EdtDispatchers.Default)

    private val cardLayout = CardLayout()
    private val promptDetailsContainer = JPanel(cardLayout)
    private val categoryPanels = mapOf(
        PromptCategory.CORE_ACTIONS to CoreActionsDetailsPanel(),
        PromptCategory.CHAT_ACTIONS to ChatActionsDetailsPanel(),
        PromptCategory.PERSONAS to PersonasDetailsPanel { handleDefaultPersonaChanged(it) },
    ).onEach { (category, panel) ->
        promptDetailsContainer.add(panel.getPanel(), category.name)
    }

    private val coreActionsNode = DefaultMutableTreeNode("Core Actions")
    private val chatActionsNode = DefaultMutableTreeNode("Chat Actions")
    private val personasNode = DefaultMutableTreeNode("Personas")
    private val root = DefaultMutableTreeNode("Root").apply {
        add(coreActionsNode)
        add(chatActionsNode)
        add(personasNode)
    }
    private val treeModel = DefaultTreeModel(root)
    private val tree = SimpleTree(treeModel).apply {
        isRootVisible = false
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = PromptsFormTreeCellRenderer(root)

        setupChildNodes()

        addTreeSelectionListener { e ->
            val node = (e.newLeadSelectionPath?.lastPathComponent as? PromptDetailsTreeNode)
            if (node == null || node.parent == root) {
                return@addTreeSelectionListener
            }

            categoryPanels[node.category]?.updateData(node.details)
            cardLayout.show(promptDetailsContainer, node.category.name)
        }
    }

    private val project = ApplicationUtil.findCurrentProject()
    private val promptsFileProvider = PromptsFileProvider()

    private val exportButton: JButton
    private val importButton: JButton

    init {
        exportButton = JButton(CodeGPTBundle.get("settingsConfigurable.prompts.export")).apply {
            addActionListener {
                exportSettingsToFile()
            }
        }
        importButton = JButton(CodeGPTBundle.get("settingsConfigurable.prompts.import")).apply {
            addActionListener {
                importSettingsFromFile()
            }
        }

        runInEdt {
            expandAll()
            selectFirstPersonaNode()
        }
    }

    fun createPanel(): JComponent {
        return BorderLayoutPanel(8, 0)
            .addToTop(createImportExportPanel())
            .addToLeft(createToolbarDecorator().createPanel())
            .addToCenter(promptDetailsContainer)
    }

    private fun createImportExportPanel() = FormBuilder.createFormBuilder()
        .addComponent(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout()).apply {
                        add(importButton)
                        add(exportButton)
                    }, BorderLayout.WEST
                )
            }
        )
        .addVerticalGap(4)
        .panel

    fun isModified(): Boolean {
        val settings = service<PromptsSettings>().state
        return isCoreActionsModified(settings.coreActions) ||
                isChatActionsModified(settings.chatActions) ||
                isPersonasModified(settings.personas)
    }

    fun applyChanges() {
        val settings = service<PromptsSettings>().state

        val coreActionsFormState = getFormState<CoreActionPromptDetails>(coreActionsNode)
        settings.coreActions.apply {
            autoApply = coreActionsFormState[0].toState()
            inlineEdit = coreActionsFormState[1].toState()
            fixCompileErrors = coreActionsFormState[2].toState()
            generateCommitMessage = coreActionsFormState[3].toState()
            generateNameLookups = coreActionsFormState[4].toState()
            reviewChanges = coreActionsFormState[5].toState()
        }
        settings.chatActions.prompts = getFormState<ChatActionPromptDetails>(chatActionsNode)
            .map { it.toState() }
            .toMutableList()
        val personasFormState = getFormState<PersonaPromptDetails>(personasNode)
        settings.personas.prompts = personasFormState
            .map { it.toState() }
            .toMutableList()
        settings.personas.selectedPersona =
            personasFormState.find { it.selected.get() }?.toState() ?: PersonasState.DEFAULT_PERSONA
    }

    fun resetChanges() {
        removeAllChildNodes()
        setupChildNodes()
        reloadTreeView()
    }

    private fun removeAllChildNodes() {
        coreActionsNode.removeAllChildren()
        chatActionsNode.removeAllChildren()
        personasNode.removeAllChildren()
    }

    private fun reloadTreeView() {
        treeModel.reload()
        expandAll()
        selectFirstPersonaNode()
    }

    private fun selectFirstPersonaNode() {
        val defaultNode = personasNode.getFirstChild() as? PromptDetailsTreeNode
        if (defaultNode != null) {
            tree.selectionPath = TreePath(defaultNode.path)
        }
    }

    private fun expandAll() {
        tree.expandPath(TreePath(coreActionsNode.path))
        tree.expandPath(TreePath(personasNode.path))
        tree.expandPath(TreePath(chatActionsNode.path))
    }

    private fun isCoreActionsModified(settingsState: CoreActionsState): Boolean {
        val formState = getFormState<CoreActionPromptDetails>(coreActionsNode)

        val stateActions = listOf(
            settingsState.autoApply,
            settingsState.inlineEdit,
            settingsState.fixCompileErrors,
            settingsState.generateCommitMessage,
            settingsState.generateNameLookups,
            settingsState.reviewChanges,
        )

        return !stateActions.all { action ->
            formState.find { it.code == action.code }
                ?.let { details ->
                    details.name == action.name && details.instructions == action.instructions
                } ?: false
        }
    }

    private fun isChatActionsModified(settingsState: ChatActionsState): Boolean {
        val formState = getFormState<ChatActionPromptDetails>(chatActionsNode)

        if (formState.size != settingsState.prompts.size) {
            return true
        }

        return !formState.zip(settingsState.prompts)
            .all { (details, prompt) ->
                details.id == prompt.id &&
                        details.name == prompt.name &&
                        details.instructions == prompt.instructions
            }
    }

    private fun isPersonasModified(settingsState: PersonasState): Boolean {
        val formState = getFormState<PersonaPromptDetails>(personasNode)

        if (formState.size != settingsState.prompts.size) {
            return true
        }

        val selectedDefaultPersona = formState.find { it.selected.get() }
        if (selectedDefaultPersona?.id != settingsState.selectedPersona.id) {
            return true
        }

        return !formState.zip(settingsState.prompts)
            .all { (details, prompt) ->
                details.id == prompt.id &&
                        details.name == prompt.name &&
                        details.instructions == prompt.instructions &&
                        details.disabled == prompt.disabled
            }
    }

    private fun setupChildNodes() {
        val settings = service<PromptsSettings>().state

        listOf(
            settings.coreActions.autoApply,
            settings.coreActions.inlineEdit,
            settings.coreActions.fixCompileErrors,
            settings.coreActions.generateCommitMessage,
            settings.coreActions.generateNameLookups,
            settings.coreActions.reviewChanges,
        ).forEach {
            coreActionsNode.add(
                PromptDetailsTreeNode(CoreActionPromptDetails(it), PromptCategory.CORE_ACTIONS)
            )
        }

        settings.chatActions.prompts.forEach {
            chatActionsNode.add(
                PromptDetailsTreeNode(ChatActionPromptDetails(it), PromptCategory.CHAT_ACTIONS)
            )
        }

        settings.personas.prompts.forEach {
            val formDetails = PersonaPromptDetails(it)
            formDetails.selected.set(settings.personas.selectedPersona.id == it.id)
            personasNode.add(
                PromptDetailsTreeNode(formDetails, PromptCategory.PERSONAS)
            )
        }
    }

    private fun createToolbarDecorator(): ToolbarDecorator =
        ToolbarDecorator.createDecorator(tree)
            .setPreferredSize(Dimension(220, 0))
            .setAddAction { handleAddAction() }
            .setAddActionUpdater {
                val selectedNode = tree.selectionPath?.lastPathComponent
                if (selectedNode is PromptDetailsTreeNode) {
                    selectedNode.category != PromptCategory.CORE_ACTIONS
                } else {
                    selectedNode is DefaultMutableTreeNode && selectedNode.userObject != "Core Actions"
                }
            }
            .setRemoveAction { handleRemoveAction() }
            .setRemoveActionUpdater {
                val selectedNode = tree.selectionPath?.lastPathComponent
                selectedNode is PromptDetailsTreeNode
                        && selectedNode.category != PromptCategory.CORE_ACTIONS
                        && selectedNode.details.name != "Default Persona"
            }
            .addExtraAction(object :
                AnAction("Duplicate", "Duplicate prompt", AllIcons.Actions.Copy) {

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

                override fun update(e: AnActionEvent) {
                    val selectedNode = tree.selectionPath?.lastPathComponent

                    e.presentation.isEnabled =
                        selectedNode is PromptDetailsTreeNode && selectedNode.category != PromptCategory.CORE_ACTIONS
                }

                override fun actionPerformed(e: AnActionEvent) {
                    handleDuplicateAction()
                }
            })
            .disableUpDownActions()

    private fun handleAddAction() {
        val category = determineCategory(tree.selectionPath?.lastPathComponent) ?: return

        when (category) {
            PromptCategory.CHAT_ACTIONS -> {
                val newNode = PromptDetailsTreeNode(
                    ChatActionPromptDetails(
                        "New Action",
                        "New Prompt",
                        chatActionsNode.childCount.toLong() + 1,
                        null
                    ),
                    PromptCategory.CHAT_ACTIONS
                )
                insertAndSelectNode(newNode, chatActionsNode)
            }

            PromptCategory.PERSONAS -> {
                val newNode = PromptDetailsTreeNode(
                    PersonaPromptDetails(
                        "New Persona",
                        "New Prompt",
                        personasNode.childCount.toLong() + 1
                    ),
                    PromptCategory.PERSONAS
                )
                insertAndSelectNode(newNode, personasNode)
            }

            else -> throw IllegalStateException("Could not add new node for category $category")
        }
    }

    private fun handleDuplicateAction() {
        val selectedNode = tree.selectionPath?.lastPathComponent as? PromptDetailsTreeNode ?: return

        when (selectedNode.details) {
            is ChatActionPromptDetails -> {
                val newNode = PromptDetailsTreeNode(
                    ChatActionPromptDetails(
                        "${selectedNode.details.name} Copy",
                        selectedNode.details.instructions,
                        chatActionsNode.childCount.toLong() + 1,
                        null
                    ),
                    PromptCategory.CHAT_ACTIONS
                )
                insertAndSelectNode(newNode, chatActionsNode)
            }

            is PersonaPromptDetails -> {
                val newNode = PromptDetailsTreeNode(
                    PersonaPromptDetails(
                        "${selectedNode.details.name} Copy",
                        selectedNode.details.instructions,
                        personasNode.childCount.toLong() + 1
                    ),
                    PromptCategory.PERSONAS
                )
                insertAndSelectNode(newNode, personasNode)
            }

            else -> throw IllegalStateException("Unknown node $selectedNode")
        }
    }

    private fun handleDefaultPersonaChanged(promptDetails: PersonaPromptDetails) {
        val previousDefaultPersonaNode = findPromptDetailsNode {
            it.details is PersonaPromptDetails && it.details.selected.get()
        }

        if (previousDefaultPersonaNode != null) {
            (previousDefaultPersonaNode.details as PersonaPromptDetails).selected.set(false)
        }
        promptDetails.selected.set(true)

        treeModel.reload(previousDefaultPersonaNode)
        treeModel.reload(tree.selectionPath?.lastPathComponent as? PromptDetailsTreeNode)
    }

    private fun handleRemoveAction() {
        val selectedNode = tree.selectionPath?.lastPathComponent as? PromptDetailsTreeNode ?: return
        treeModel.removeNodeFromParent(selectedNode)
        categoryPanels[selectedNode.category]?.remove(selectedNode.details)

        if (selectedNode.details is PersonaPromptDetails && selectedNode.details.selected.get()) {
            val defaultPersonaNode = findPromptDetailsNode {
                it.details is PersonaPromptDetails && it.details.id == 1L
            }
            if (defaultPersonaNode != null) {
                handleDefaultPersonaChanged(defaultPersonaNode.details as PersonaPromptDetails)
            }
        }
    }

    private fun findPromptDetailsNode(predicate: (PromptDetailsTreeNode) -> Boolean): PromptDetailsTreeNode? {
        return personasNode.children().toList()
            .filterIsInstance<PromptDetailsTreeNode>()
            .find(predicate)
    }

    private fun insertAndSelectNode(
        newNode: PromptDetailsTreeNode,
        parentNode: DefaultMutableTreeNode
    ) {
        treeModel.insertNodeInto(newNode, parentNode, parentNode.childCount)
        tree.selectionPath = TreePath(newNode.path)
    }

    private fun determineCategory(component: Any?): PromptCategory? = when (component) {
        is PromptDetailsTreeNode -> component.category
        personasNode -> PromptCategory.PERSONAS
        chatActionsNode -> PromptCategory.CHAT_ACTIONS
        else -> null
    }

    private fun exportSettingsToFile() {
        val defaultSettingsFileName = "prompts.json"
        val settings = service<PromptsSettings>().state

        val fileNameTextField = JBTextField(defaultSettingsFileName).apply {
            columns = 20
        }
        val fileChooserDescriptor =
            FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                isForcedToUseIdeaFileChooser = true
            }
        val textFieldWithBrowseButton = TextFieldWithBrowseButton().apply {
            text = project?.basePath ?: System.getProperty("user.home")
            addBrowseFolderListener(
                TextBrowseFolderListener(fileChooserDescriptor, project)
            )
        }

        val result = exportSettingsDialog(
            fileNameTextField = fileNameTextField,
            filePathButton = textFieldWithBrowseButton
        ).show()

        val fileName = fileNameTextField.text.ifEmpty { defaultSettingsFileName }
        val filePath = textFieldWithBrowseButton.text

        if (result == OK_EXIT_CODE) {
            val fullFilePath = "$filePath/$fileName"
            coroutineScope.launch {
                runCatching {
                    promptsFileProvider.writePrompts(
                        path = fullFilePath,
                        data = settings,
                    )
                }.onFailure {
                    showExportErrorMessage()
                }
            }
        }
    }

    private fun exportSettingsDialog(
        fileNameTextField: JBTextField,
        filePathButton: TextFieldWithBrowseButton,
    ): DialogBuilder {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.prompts.exportDialog.saveTo"),
                fileNameTextField
            )
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportDialog.saveTo"),
                filePathButton
            )
            .panel

        return DialogBuilder().apply {
            CodeGPTBundle.get("settingsConfigurable.prompts.exportDialog.title")
            centerPanel(form)
            addOkAction()
            addCancelAction()
        }
    }

    private fun showExportErrorMessage() {
        OverlayUtil.showBalloon(
            CodeGPTBundle.get("settingsConfigurable.prompts.exportDialog.exportError"),
            MessageType.ERROR,
            exportButton,
        )
    }

    private fun importSettingsFromFile() {
        val fileChooserDescriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("json")
            .apply { isForcedToUseIdeaFileChooser = true }

        project?.let {
            FileChooser.chooseFile(fileChooserDescriptor, it, null)?.let { file ->
                ReadAction.nonBlocking<PromptsSettingsState> {
                    file.canonicalPath?.let {
                        promptsFileProvider.readFromFile(it)
                    }
                }
                    .inSmartMode(it)
                    .finishOnUiThread(ModalityState.defaultModalityState()) { settings ->
                        insertPersonasPrompts(settings.personas)
                        insertChatPrompts(settings.chatActions.prompts)
                        insertCorePrompts(settings.coreActions)
                        reloadTreeView()
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .onError { showImportErrorMessage() }
            }
        }

    }

    private fun showImportErrorMessage() {
        OverlayUtil.showBalloon(
            CodeGPTBundle.get("settingsConfigurable.prompts.importDialog.importError"),
            MessageType.ERROR,
            importButton,
        )
    }

    private fun insertChatPrompts(prompts: List<ChatActionPromptDetailsState>) {
        chatActionsNode.removeAllChildren()
        prompts.forEachIndexed { index, prompt ->
            val node = PromptDetailsTreeNode(
                details = ChatActionPromptDetails(
                    name = "${prompt.name}",
                    instructions = prompt.instructions,
                    id = prompt.id,
                    code = prompt.code,
                ),
                category = PromptCategory.CHAT_ACTIONS,
            )
            treeModel.insertNodeInto(node, chatActionsNode, index)
        }
    }

    private fun insertPersonasPrompts(state: PersonasState) {
        personasNode.removeAllChildren()
        state.prompts.forEachIndexed { index, prompt ->
            val node = PromptDetailsTreeNode(
                details = PersonaPromptDetails(
                    name = "${prompt.name}",
                    instructions = prompt.instructions,
                    id = prompt.id,
                    disabled = prompt.disabled,
                    selected = AtomicBooleanProperty(prompt.id == state.selectedPersona.id),
                ),
                category = PromptCategory.PERSONAS
            )
            treeModel.insertNodeInto(node, personasNode, index)
        }
    }

    private fun insertCorePrompts(prompts: CoreActionsState) {
        coreActionsNode.removeAllChildren()
        listOf(
            prompts.autoApply,
            prompts.inlineEdit,
            prompts.fixCompileErrors,
            prompts.generateCommitMessage,
            prompts.generateNameLookups,
            prompts.reviewChanges,
        ).forEach {
            coreActionsNode.add(
                PromptDetailsTreeNode(CoreActionPromptDetails(it), PromptCategory.CORE_ACTIONS)
            )
        }
    }
}