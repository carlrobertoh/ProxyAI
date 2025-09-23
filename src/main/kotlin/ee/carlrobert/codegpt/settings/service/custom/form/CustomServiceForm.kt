package ee.carlrobert.codegpt.settings.service.custom.form

import com.intellij.icons.AllIcons
import com.intellij.icons.AllIcons.General
import com.intellij.ide.BrowserUtil
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.*
import com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.components.ActionLink
// removed: com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceSettingsData
import ee.carlrobert.codegpt.settings.service.custom.form.model.mapToData
import ee.carlrobert.codegpt.settings.service.custom.form.model.mapToState
import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceTemplate
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.UIUtil
import ee.carlrobert.codegpt.util.ApplicationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.internal.toImmutableList
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.net.MalformedURLException
import java.net.URL
import javax.swing.*

class CustomServiceForm(
    private val service: CustomServicesSettings,
    private val coroutineScope: CoroutineScope
) {

    private val formState = MutableStateFlow(service.state.mapToData())

    private val project = ApplicationUtil.findCurrentProject()
    private val customSettingsFileProvider = CustomSettingsFileProvider()

    private var lastSelectedIndex = 0
    private var selectedServiceId: String? = null
    private var pendingSelectedId: String? = null
    private var suppressSelectionEvents: Boolean = false

    private val customProvidersJBList = JBList(formState.value.services)
        .apply {
            cellRenderer = CustomServiceNameListRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION

            addListSelectionListener { _ ->
                if (suppressSelectionEvents) return@addListSelectionListener
                val localSelectedIndex = selectedIndex
                if (localSelectedIndex != -1) {
                    if (lastSelectedIndex != -1) {
                        updateStateFromForm(lastSelectedIndex)
                    }

                    lastSelectedIndex = localSelectedIndex
                    selectedServiceId = model.getElementAt(localSelectedIndex).id
                    updateFormData(lastSelectedIndex)
                }
            }
        }

    init {
        formState
            .onEach { newState ->
                val model = customProvidersJBList.model
                val current = (0 until model.size).map { model.getElementAt(it) }
                val currentIds = current.map { it.id }
                val newIds = newState.services.map { it.id }
                val idsChanged = currentIds != newIds
                val namesChanged = !idsChanged && current.indices.any { i ->
                    i < newState.services.size && current[i].name != newState.services[i].name
                }
                if (idsChanged || namesChanged) {
                    SwingUtilities.invokeLater {
                        suppressSelectionEvents = true
                        try {
                            customProvidersJBList.setListData(newState.services.toTypedArray())
                            val targetId = pendingSelectedId ?: selectedServiceId
                            val idx = newState.services.indexOfFirst { it.id == targetId }
                            val targetIndex = if (idx >= 0) idx else 0
                            if (newState.services.isNotEmpty()) {
                                customProvidersJBList.selectedIndex = targetIndex
                                lastSelectedIndex = targetIndex
                                selectedServiceId = newState.services[targetIndex].id
                                updateFormDataSilently(newState.services[targetIndex])
                            }
                            pendingSelectedId = null
                            customProvidersJBList.repaint()
                        } finally {
                            suppressSelectionEvents = false
                        }
                    }
                }
            }
            .launchIn(coroutineScope)
    }

    private val apiKeyField = JBPasswordField().apply {
        columns = 30
    }
    private val nameField = JBTextField().apply {
        columns = 30
    }
    private val templateHelpText = JBLabel(General.ContextHelp)
    private val templateComboBox = ComboBox(EnumComboBoxModel(CustomServiceTemplate::class.java))
    private val chatCompletionsForm: CustomServiceChatCompletionForm
    private val codeCompletionsForm: CustomServiceCodeCompletionForm
    private val tabbedPane: JTabbedPane
    private val exportButton: JButton
    private val importButton: JButton

    init {
        val selectedItem = formState.value.services.first()
        apiKeyField.text = getCredential(CredentialKey.CustomServiceApiKeyById(selectedItem.id))
        chatCompletionsForm =
            CustomServiceChatCompletionForm(selectedItem.chatCompletionSettings, this::getApiKey)
        codeCompletionsForm =
            CustomServiceCodeCompletionForm(selectedItem.codeCompletionSettings, this::getApiKey)
        tabbedPane = JBTabbedPane().apply {
            add(CodeGPTBundle.get("shared.chatCompletions"), chatCompletionsForm.form)
            add(CodeGPTBundle.get("shared.codeCompletions"), codeCompletionsForm.form)
        }

        exportButton =
            JButton(CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportSettings")).apply {
                addActionListener { exportSettingsToFile() }
            }
        importButton =
            JButton(CodeGPTBundle.get("settingsConfigurable.service.custom.openai.importSettings")).apply {
                addActionListener { importSettingsFromFile() }
            }

        templateComboBox.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                val template = event.item as CustomServiceTemplate
                updateTemplateHelpTextTooltip(template)

                chatCompletionsForm.run {
                    url = template.chatCompletionTemplate.url
                    headers = template.chatCompletionTemplate.headers
                    body = template.chatCompletionTemplate.body
                }
                if (template.codeCompletionTemplate != null) {
                    codeCompletionsForm.run {
                        url = template.codeCompletionTemplate.url
                        headers = template.codeCompletionTemplate.headers
                        body = template.codeCompletionTemplate.body
                        parseResponseAsChatCompletions =
                            template.codeCompletionTemplate.parseResponseAsChatCompletions
                    }
                    tabbedPane.setEnabledAt(1, true)
                } else {
                    tabbedPane.selectedIndex = 0
                    tabbedPane.setEnabledAt(1, false)
                }
            }
        }

        updateFormDataSilently(selectedItem)
        SwingUtilities.invokeLater {
            if (customProvidersJBList.model.size > 0) {
                customProvidersJBList.selectedIndex = 0
                lastSelectedIndex = 0
                selectedServiceId = customProvidersJBList.model.getElementAt(0).id
            }
        }
    }

    private fun updateFormData(index: Int) {
        val selectedItem = formState.value.services[index]
        SwingUtilities.invokeLater {
            updateFormDataSilently(selectedItem)
        }
    }

    private fun updateFormDataSilently(selectedItem: CustomServiceSettingsData) {
        val templateListener = templateComboBox.itemListeners.firstOrNull()
        templateListener?.let { templateComboBox.removeItemListener(it) }

        try {
            chatCompletionsForm.apply {
                val chatCompletionSettings = selectedItem.chatCompletionSettings
                url = chatCompletionSettings.url.orEmpty()
                body = chatCompletionSettings.body.toMutableMap()
                headers = chatCompletionSettings.headers.toMutableMap()
            }
            codeCompletionsForm.apply {
                val codeCompletionSettings = selectedItem.codeCompletionSettings
                url = codeCompletionSettings.url.orEmpty()
                body = codeCompletionSettings.body.toMutableMap()
                headers = codeCompletionSettings.headers.toMutableMap()
                infillTemplate = codeCompletionSettings.infillTemplate
                codeCompletionsEnabled = codeCompletionSettings.codeCompletionsEnabled
                parseResponseAsChatCompletions =
                    codeCompletionSettings.parseResponseAsChatCompletions
            }

            apiKeyField.text = getCredential(CredentialKey.CustomServiceApiKeyById(selectedItem.id))
            nameField.text = selectedItem.name
            templateComboBox.selectedItem = selectedItem.template
            updateTemplateHelpTextTooltip(selectedItem.template)
        } finally {
            templateListener?.let { templateComboBox.addItemListener(it) }
        }
    }

    private fun updateStateFromForm(editedIndex: Int) {
        if (editedIndex < 0 || editedIndex >= formState.value.services.size) return
        formState.update { state ->
            val editedItem = state.services[editedIndex]

            val updatedItem = editedItem.copy(
                name = nameField.text,
                template = templateComboBox.item,
                apiKey = getApiKey(),
                chatCompletionSettings = editedItem.chatCompletionSettings.copy(
                    url = chatCompletionsForm.url,
                    body = chatCompletionsForm.body,
                    headers = chatCompletionsForm.headers,
                ),
                codeCompletionSettings = editedItem.codeCompletionSettings.copy(
                    codeCompletionsEnabled = codeCompletionsForm.codeCompletionsEnabled,
                    parseResponseAsChatCompletions = codeCompletionsForm.parseResponseAsChatCompletions,
                    infillTemplate = codeCompletionsForm.infillTemplate,
                    url = codeCompletionsForm.url,
                    headers = codeCompletionsForm.headers,
                    body = codeCompletionsForm.body,
                )
            )

            if (editedItem == updatedItem) return@update state

            val updatedServices = state.services.toMutableList().let { mutableList ->
                mutableList[editedIndex] = updatedItem
                mutableList.toImmutableList()
            }
            state.copy(services = updatedServices)
        }
    }

    fun getForm(): JPanel =
        BorderLayoutPanel(8, 0)
            .addToTop(createTopPanel())
            .addToLeft(createToolbarDecorator().createPanel())
            .addToCenter(createContentPanel())

    private fun createTopPanel(): JPanel = FormBuilder.createFormBuilder()
        .addComponent(createMarketingPanel())
        .addVerticalGap(6)
        .addComponent(createImportExportPanel())
        .panel

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

    private fun createMarketingPanel(): JPanel {
        val marketingText = CodeGPTBundle.get("settingsConfigurable.service.custom.openai.marketing.text").trim()
        val learnMoreText = CodeGPTBundle.get("settingsConfigurable.service.custom.openai.marketing.learnMore").trim()

        val html = """
            <html>
              <body style='margin:0; padding:0;'>
                <div style='line-height:1.45;'>
                  $marketingText <a href='https://docs.tryproxy.io/enterprise/custom-extension'>$learnMoreText</a>
                </div>
              </body>
            </html>
        """.trimIndent()

        val fixedWidth = JBUI.scale(600)
        val content = UIUtil.createTextPane(html, false).apply {
            isOpaque = false
            size = Dimension(fixedWidth, Short.MAX_VALUE.toInt())
            preferredSize = Dimension(fixedWidth, preferredSize.height)
            maximumSize = Dimension(fixedWidth, Int.MAX_VALUE)
        }

        val wrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8)
            add(content)
        }

        return wrapper
    }

    private fun createToolbarDecorator(): ToolbarDecorator =
        ToolbarDecorator.createDecorator(customProvidersJBList)
            .setPreferredSize(Dimension(220, 0))
            .setAddAction { handleAddAction() }
            .setRemoveAction { handleRemoveAction() }
            .setRemoveActionUpdater {
                formState.value.services.size > 1
            }
            .addExtraAction(object :
                AnAction("Duplicate", "Duplicate service", AllIcons.Actions.Copy) {

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

                override fun update(e: AnActionEvent) {
                    val selected = customProvidersJBList.selectedIndex

                    e.presentation.isEnabled = selected != -1
                }

                override fun actionPerformed(e: AnActionEvent) {
                    handleDuplicateAction()
                }
            })
            .disableUpDownActions()

    private fun handleRemoveAction() {
        val prevSelectedIndex = customProvidersJBList.selectedIndex

        // Update form state before deletion to ensure current edits are saved
        if (lastSelectedIndex != -1 && lastSelectedIndex < formState.value.services.size) {
            updateStateFromForm(lastSelectedIndex)
        }

        val current = formState.value.services
        val targetNeighborId = when {
            current.isEmpty() -> null
            prevSelectedIndex <= 0 && current.size >= 2 -> current[1].id
            prevSelectedIndex > 0 -> current[prevSelectedIndex - 1].id
            else -> null
        }

        formState.update { state ->
            state.copy(services = state.services.filterIndexed { index, _ -> index != prevSelectedIndex })
        }

        pendingSelectedId = targetNeighborId
        lastSelectedIndex = -1
    }

    private fun handleDuplicateAction() {
        formState.update {
            val selectedIndex = customProvidersJBList.selectedIndex
            val src = it.services[selectedIndex]
            val copiedService = src.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = src.name + "Copied"
            )
            it.copy(
                services = it.services + copiedService
            )
        }
        pendingSelectedId = formState.value.services.last().id
    }

    private fun handleAddAction() {
        val newData = CustomServiceSettingsState().apply { name += formState.value.services.size }
            .mapToData()
        formState.update { it.copy(services = it.services + newData) }
        pendingSelectedId = newData.id
    }

    private fun createContentPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.custom.openai.presetTemplate.label"),
            JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                add(templateComboBox)
                add(Box.createHorizontalStrut(8))
                add(templateHelpText)
            }
        )
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.custom.openai.apiKey.provider.name"),
            nameField
        )
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"),
            apiKeyField
        )
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.custom.openai.apiKey.comment")
        )
        .addVerticalGap(4)
        .addComponent(tabbedPane)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getApiKey() = String(apiKeyField.password).ifEmpty { null }

    fun isModified(): Boolean {
        if (lastSelectedIndex >= 0 && lastSelectedIndex < formState.value.services.size) {
            updateStateFromForm(lastSelectedIndex)
        }
        return service.state.mapToData() != formState.value
    }

    private fun exportSettingsToFile() {
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val defaultSettingsFileName = "CustomOpenAiSettings.json"

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
                    customSettingsFileProvider.writeSettings(
                        path = fullFilePath,
                        data = formState.value.services,
                    )
                }.onFailure {
                    showExportErrorMessage()
                }
            }
        }
    }

    private fun importSettingsFromFile() {
        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .apply { isForcedToUseIdeaFileChooser = true }

        project?.let {
            FileChooser.chooseFile(fileChooserDescriptor, it, null)?.let { file ->
                ReadAction.nonBlocking<List<CustomServiceSettingsData>> {
                    file.canonicalPath?.let {
                        customSettingsFileProvider.readFromFile(it)
                    }
                }
                    .inSmartMode(it)
                    .finishOnUiThread(ModalityState.defaultModalityState()) { settings ->
                        if (settings != null) {
                            formState.update { state ->
                                state.copy(services = settings)
                            }
                            updateFormData(0)
                        }
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .onError { showImportErrorMessage() }
            }
        }
    }

    private fun exportSettingsDialog(
        fileNameTextField: JBTextField,
        filePathButton: TextFieldWithBrowseButton,
    ): DialogBuilder {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportDialog.filename"),
                fileNameTextField
            )
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportDialog.saveTo"),
                filePathButton
            )
            .panel

        return DialogBuilder().apply {
            CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportDialog.title")
            centerPanel(form)
            addOkAction()
            addCancelAction()
        }
    }

    private fun showExportErrorMessage() {
        OverlayUtil.showBalloon(
            CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportDialog.exportError"),
            MessageType.ERROR,
            exportButton,
        )
    }

    private fun showImportErrorMessage() {
        OverlayUtil.showBalloon(
            CodeGPTBundle.get("settingsConfigurable.service.custom.openai.exportDialog.importError"),
            MessageType.ERROR,
            importButton,
        )
    }

    fun applyChanges() {
        if (lastSelectedIndex != -1 && lastSelectedIndex < formState.value.services.size) {
            updateStateFromForm(lastSelectedIndex)
        }

        val formStateValue = formState.value

        val prevById = service.state.services.associateBy { it.id }
        val savedIds = prevById.keys.filterNotNull().toSet()
        val newIds = formStateValue.services.map { it.id }.toSet()
        val deletedIds = savedIds.subtract(newIds)
        deletedIds.forEach { deletedId ->
            CredentialsStore.setCredential(CredentialKey.CustomServiceApiKeyById(deletedId), null)
        }
        formStateValue.services.forEach {
            if (it.id.isNotBlank()) {
                CredentialsStore.setCredential(
                    CredentialKey.CustomServiceApiKeyById(it.id),
                    it.apiKey
                )
            }
        }

        service.state.run {
            services = formStateValue.services.mapTo(mutableListOf()) { it.mapToState() }
        }
        formState.value = service.state.mapToData()
    }

    fun getSelectedServiceId(): String? {
        val idx = customProvidersJBList.selectedIndex
        return if (idx >= 0 && idx < formState.value.services.size) {
            formState.value.services[idx].id
        } else {
            selectedServiceId
        }
    }

    fun resetForm() {
        lastSelectedIndex = -1
        formState.value = service.state.mapToData()
        if (customProvidersJBList.selectedIndex == 0) {
            updateFormData(0)
        } else {
            customProvidersJBList.selectedIndex = 0
        }
    }

    private fun updateTemplateHelpTextTooltip(template: CustomServiceTemplate) {
        templateHelpText.toolTipText = null
        try {
            HelpTooltip()
                .setTitle(template.providerName)
                .setBrowserLink(
                    CodeGPTBundle.get("settingsConfigurable.service.custom.openai.linkToDocs"),
                    URL(template.docsUrl)
                )
                .installOn(templateHelpText)
        } catch (e: MalformedURLException) {
            throw RuntimeException(e)
        }
    }
}
