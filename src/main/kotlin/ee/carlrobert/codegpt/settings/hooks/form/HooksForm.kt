package ee.carlrobert.codegpt.settings.hooks.form

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.hooks.*
import ee.carlrobert.codegpt.ui.OverlayUtil
import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

class HooksForm(private val project: Project) {
    private val settingsService = project.service<ProxyAISettingsService>()
    private val tableModel =
        DefaultTableModel(arrayOf("Event", "Command", "Matcher", "Timeout(s)", "Enabled"), 0)
    private val table = JBTable(tableModel).apply {
        setupTableColumns()
    }
    private var originalConfiguration: HookConfiguration = HookConfiguration()

    companion object {
        val logger = thisLogger()
    }

    init {
        loadHooks()
    }

    fun createPanel(): DialogPanel {
        return panel {
            row {
                val decorator = ToolbarDecorator.createDecorator(table)
                    .setAddAction { handleAddHook() }
                    .setEditAction { handleEditHook() }
                    .setRemoveAction { handleRemoveHook() }
                    .addExtraAction(object :
                        AnAction("Generate", "Generate hook from natural language", Icons.Sparkle) {
                        override fun getActionUpdateThread(): ActionUpdateThread =
                            ActionUpdateThread.EDT

                        override fun actionPerformed(e: AnActionEvent) {
                            handleGenerateHook()
                        }
                    })
                    .disableUpDownActions()

                cell(decorator.createPanel())
                    .align(Align.FILL)
                    .resizableColumn()
                    .applyToComponent {
                        preferredSize = Dimension(600, 300)
                    }
            }
            row {
                comment("Hooks are stored in .proxyai/settings.json and apply to this project only.")
            }
        }
    }

    fun isModified(): Boolean {
        return currentConfiguration() != originalConfiguration
    }

    fun applyChanges() {
        val config = currentConfiguration()
        validateConfiguration(config)
        settingsService.saveHooks(config)
        originalConfiguration = config
    }

    fun resetChanges() {
        loadHooks()
    }

    private fun loadHooks() {
        originalConfiguration = settingsService.getHooks()
        tableModel.rowCount = 0

        addHooksToTable(originalConfiguration.beforeToolUse, HookEventType.BEFORE_TOOL_USE)
        addHooksToTable(originalConfiguration.afterToolUse, HookEventType.AFTER_TOOL_USE)
        addHooksToTable(originalConfiguration.subagentStart, HookEventType.SUBAGENT_START)
        addHooksToTable(originalConfiguration.subagentStop, HookEventType.SUBAGENT_STOP)
        addHooksToTable(
            originalConfiguration.beforeShellExecution,
            HookEventType.BEFORE_BASH_EXECUTION
        )
        addHooksToTable(
            originalConfiguration.afterShellExecution,
            HookEventType.AFTER_BASH_EXECUTION
        )
        addHooksToTable(originalConfiguration.beforeReadFile, HookEventType.BEFORE_READ_FILE)
        addHooksToTable(originalConfiguration.afterFileEdit, HookEventType.AFTER_FILE_EDIT)
        addHooksToTable(originalConfiguration.stop, HookEventType.STOP)
    }

    private fun currentConfiguration(): HookConfiguration {
        val config = HookConfiguration()
        for (row in 0 until tableModel.rowCount) {
            val event = tableModel.getValueAt(row, 0) as String
            val command = tableModel.getValueAt(row, 1) as String
            val matcher = (tableModel.getValueAt(row, 2) as String).ifBlank { null }
            val timeout = (tableModel.getValueAt(row, 3) as String).toIntOrNull()
            val enabled = tableModel.getValueAt(row, 4) as String == "Yes"

            val hook = HookConfig(
                command = command,
                matcher = matcher,
                timeout = timeout,
                enabled = enabled
            )

            val eventType = HookEventType.entries.find { it.eventName == event }!!
            when (eventType) {
                HookEventType.BEFORE_TOOL_USE -> config.beforeToolUse += hook
                HookEventType.AFTER_TOOL_USE -> config.afterToolUse += hook
                HookEventType.SUBAGENT_START -> config.subagentStart += hook
                HookEventType.SUBAGENT_STOP -> config.subagentStop += hook
                HookEventType.BEFORE_BASH_EXECUTION -> config.beforeShellExecution += hook
                HookEventType.AFTER_BASH_EXECUTION -> config.afterShellExecution += hook
                HookEventType.BEFORE_READ_FILE -> config.beforeReadFile += hook
                HookEventType.AFTER_FILE_EDIT -> config.afterFileEdit += hook
                HookEventType.STOP -> config.stop += hook
            }
        }
        return config
    }

    private fun addHooksToTable(hooks: List<HookConfig>, eventType: HookEventType) {
        hooks.forEach { hook ->
            tableModel.addRow(
                arrayOf(
                    eventType.eventName,
                    hook.command,
                    hook.matcher ?: "",
                    hook.timeout?.toString() ?: "",
                    if (hook.enabled) "Yes" else "No"
                )
            )
        }
    }

    private fun handleAddHook() {
        val dialog = HookDialog()
        if (dialog.showAndGet()) {
            addHookToTable(dialog.selectedEvent, dialog.hookConfig)
            selectLastRow()
        }
    }

    private fun handleGenerateHook() {
        val input = showGenerateHookPromptDialog() ?: return
        table.isEnabled = false

        var error: String? = null
        var generated: GeneratedHookResult? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                generated = HookGenerator.generateBlocking(input, project)
            } catch (t: Throwable) {
                error = t.message ?: "Generation failed"
            }
        }, "Generating Hook...", true, project)

        runInEdt(ModalityState.any()) {
            if (generated != null) {
                val summaryDialog = HookGenerationSummaryDialog(generated)
                if (summaryDialog.showAndGet()) {
                    val hook = summaryDialog.getSelectedHook()
                    saveGeneratedScripts(hook.scripts)
                    addHookToTable(hook.event, hook.config)
                    selectLastRow()
                    OverlayUtil.showNotification("Hook generated and added. Scripts saved to .proxyai/hooks/")
                }
            } else if (error != null) {
                OverlayUtil.showNotification(
                    "Failed to generate hook: $error",
                    NotificationType.ERROR
                )
            }
            table.isEnabled = true
        }
    }

    private fun saveGeneratedScripts(scripts: List<HookScript>) {
        val basePath = project.basePath ?: run {
            OverlayUtil.showNotification(
                "Cannot save scripts: Project path not available",
                NotificationType.ERROR
            )
            return
        }

        val hooksDir = File(basePath, ".proxyai/hooks")

        if (!hooksDir.exists()) {
            if (!hooksDir.mkdirs()) {
                OverlayUtil.showNotification(
                    "Failed to create hooks directory: ${hooksDir.absolutePath}",
                    NotificationType.ERROR
                )
                return
            }
        }

        var savedCount = 0
        var errorCount = 0

        scripts.forEach { script ->
            try {
                val scriptFile = File(hooksDir, script.name)
                scriptFile.writeText(script.content)

                val filePermissions = mutableSetOf<PosixFilePermission>()
                filePermissions.add(PosixFilePermission.OWNER_READ)
                filePermissions.add(PosixFilePermission.OWNER_WRITE)
                filePermissions.add(PosixFilePermission.OWNER_EXECUTE)
                filePermissions.add(PosixFilePermission.GROUP_READ)
                filePermissions.add(PosixFilePermission.GROUP_EXECUTE)
                filePermissions.add(PosixFilePermission.OTHERS_READ)
                filePermissions.add(PosixFilePermission.OTHERS_EXECUTE)

                try {
                    Files.setPosixFilePermissions(scriptFile.toPath(), filePermissions)
                } catch (e: UnsupportedOperationException) {
                    // Windows doesn't support POSIX permissions
                    logger.error("Failed to setPosixFilePermissions: ${e.message}")
                }

                savedCount++
            } catch (e: Exception) {
                errorCount++
                OverlayUtil.showNotification(
                    "Failed to save script ${script.name}: ${e.message}",
                    NotificationType.WARNING
                )
            }
        }

        if (errorCount > 0) {
            OverlayUtil.showNotification(
                "Saved $savedCount scripts, $errorCount failed",
                if (savedCount > 0) NotificationType.WARNING else NotificationType.ERROR
            )
        }
    }

    private fun showGenerateHookPromptDialog(): String? {
        val dialog = GenerateHookDialog()
        return if (dialog.showAndGet()) {
            dialog.inputValue.takeIf { it.isNotBlank() }
        } else null
    }

    private fun handleEditHook() {
        val row = table.selectedRow
        if (row == -1) return

        val event = tableModel.getValueAt(row, 0) as String
        val command = tableModel.getValueAt(row, 1) as String
        val matcher = (tableModel.getValueAt(row, 2) as String).ifBlank { null }
        val timeout = (tableModel.getValueAt(row, 3) as String).toIntOrNull()
        val enabled = (tableModel.getValueAt(row, 4) as String) == "Yes"

        val eventType = HookEventType.entries.find { it.eventName == event }!!
        val dialog = HookDialog(
            eventType,
            HookConfig(command, matcher, timeout, null, enabled)
        )
        if (dialog.showAndGet()) {
            updateTableRow(row, dialog.selectedEvent, dialog.hookConfig)
        }
    }

    private fun handleRemoveHook() {
        val row = table.selectedRow
        if (row == -1) return
        tableModel.removeRow(row)

        val nextSelectedRow = if (row > 0) row - 1 else 0
        if (table.rowCount > 0) {
            table.setRowSelectionInterval(nextSelectedRow, nextSelectedRow)
        }
    }

    private fun addHookToTable(eventType: HookEventType, hook: HookConfig) {
        tableModel.addRow(
            arrayOf(
                eventType.eventName,
                hook.command,
                hook.matcher ?: "",
                hook.timeout?.toString() ?: "",
                if (hook.enabled) "Yes" else "No"
            )
        )
    }

    private fun updateTableRow(row: Int, eventType: HookEventType, hook: HookConfig) {
        tableModel.setValueAt(eventType.eventName, row, 0)
        tableModel.setValueAt(hook.command, row, 1)
        tableModel.setValueAt(hook.matcher ?: "", row, 2)
        tableModel.setValueAt(hook.timeout?.toString() ?: "", row, 3)
        tableModel.setValueAt(if (hook.enabled) "Yes" else "No", row, 4)
    }

    private fun selectLastRow() {
        val lastRow = table.rowCount - 1
        table.setRowSelectionInterval(lastRow, lastRow)
        table.scrollRectToVisible(table.getCellRect(lastRow, 0, true))
    }

    private fun validateConfiguration(config: HookConfiguration) {
        config.beforeToolUse.forEach { validateHook("beforeToolUse", it) }
        config.afterToolUse.forEach { validateHook("afterToolUse", it) }
        config.subagentStart.forEach { validateHook("subagentStart", it) }
        config.subagentStop.forEach { validateHook("subagentStop", it) }
        config.beforeShellExecution.forEach { validateHook("beforeShellExecution", it) }
        config.afterShellExecution.forEach { validateHook("afterShellExecution", it) }
        config.beforeReadFile.forEach { validateHook("beforeReadFile", it) }
        config.afterFileEdit.forEach { validateHook("afterFileEdit", it) }
        config.stop.forEach { validateHook("stop", it) }
    }

    private fun validateHook(eventType: String, hook: HookConfig) {
        if (hook.command.isBlank()) {
            throw ConfigurationException("Hook command is required for $eventType.")
        }
    }

    private fun JBTable.setupTableColumns() {
        columnModel.apply {
            getColumn(0).preferredWidth = 180
            getColumn(1).preferredWidth = 250
            getColumn(2).preferredWidth = 150
            getColumn(3).preferredWidth = 80
            getColumn(4).preferredWidth = 70
        }
    }
}

private class HookDialog(
    defaultEvent: HookEventType = HookEventType.BEFORE_BASH_EXECUTION,
    defaultHook: HookConfig = HookConfig(command = "")
) : DialogWrapper(true) {
    private val eventCombo =
        JComboBox(HookEventType.entries.map { it.eventName }.toTypedArray()).apply {
            selectedItem = defaultEvent.eventName
        }
    private val commandField = JBTextField(defaultHook.command).apply { columns = 40 }
    private val matcherField = JBTextField(defaultHook.matcher ?: "").apply { columns = 40 }
    private val enabledCheck = JBCheckBox("Enabled", defaultHook.enabled)
    private val timeoutField = JBTextField(defaultHook.timeout?.toString().orEmpty())
    private val loopLimitField = JBTextField(defaultHook.loopLimit?.toString().orEmpty())

    val selectedEvent: HookEventType
        get() = HookEventType.entries.find { it.eventName == eventCombo.selectedItem as String }!!

    val hookConfig: HookConfig
        get() = HookConfig(
            command = commandField.text.trim(),
            matcher = matcherField.text.trim().ifBlank { null },
            timeout = parseInt(timeoutField.text),
            loopLimit = parseInt(loopLimitField.text),
            enabled = enabledCheck.isSelected
        )

    init {
        title = "Hook Configuration"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Event:") {
                cell(eventCombo).resizableColumn().align(Align.FILL)
            }
            row("Command:") {
                cell(commandField)
                    .resizableColumn()
                    .align(Align.FILL)
                    .comment("Executable path to a hook script (for example ./approve-network.sh).")
            }
            row("Matcher:") {
                cell(matcherField)
                    .resizableColumn()
                    .align(Align.FILL)
                    .comment("Optional regex or substring to match against target string.")
            }
            row("Timeout (seconds):") {
                cell(timeoutField).resizableColumn().align(Align.FILL)
            }
            row("Loop Limit:") {
                cell(loopLimitField).resizableColumn().align(Align.FILL)
                    .comment("Maximum executions for loop-limit events (stop, subagentStop).")
            }
            row { cell(enabledCheck).resizableColumn().align(Align.FILL) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (commandField.text.trim().isEmpty()) {
            return ValidationInfo("Command is required.", commandField)
        }
        if (timeoutField.text.isNotBlank() && parseInt(timeoutField.text) == null) {
            return ValidationInfo("Timeout must be a number.", timeoutField)
        }
        if (loopLimitField.text.isNotBlank() && parseInt(loopLimitField.text) == null) {
            return ValidationInfo("Loop limit must be a number.", loopLimitField)
        }
        return null
    }

    private fun parseInt(value: String): Int? =
        value.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
}

private class GenerateHookDialog : DialogWrapper(true) {
    private val inputField = JBTextField()

    val inputValue: String
        get() = inputField.text

    init {
        title = "Generate Hook"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("Describe the hook:")
            }
            row {
                cell(inputField)
                    .resizableColumn()
                    .align(Align.FILL)
                    .focused()
            }
            row {
                comment("Example: Log every tool execution to a file for auditing")
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        return if (inputField.text.trim().isEmpty()) {
            ValidationInfo("Description cannot be empty", inputField)
        } else null
    }
}