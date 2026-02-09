package ee.carlrobert.codegpt.settings.service.custom

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.util.ApplicationUtil
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CustomServiceHeaderPropertyDialog(
    existingRow: CustomServiceHeaderRow?,
    private val existingKeys: Set<String>,
) : DialogWrapper(true) {

    private val keyField = JBTextField()
    private val valueField = JBTextField()

    init {
        title = if (existingRow == null) "Add Header" else "Edit Header"
        if (existingRow != null) {
            keyField.text = existingRow.key
            valueField.text = existingRow.value
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Key:") {
                cell(keyField)
                    .validationOnInput {
                        val key = it.text.trim()
                        if (key.isEmpty()) {
                            error("Key is required")
                        } else if (key in existingKeys) {
                            error("A header with this key already exists")
                        } else {
                            isOKActionEnabled = true
                            null
                        }
                    }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Value:") {
                cell(valueField)
                    .validationOnInput {
                        isOKActionEnabled = true
                        null
                    }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val key = keyField.text.trim()
        if (key.isEmpty()) {
            return ValidationInfo("Key is required.", keyField)
        }
        if (key in existingKeys) {
            return ValidationInfo("A header with this key already exists.", keyField)
        }
        return null
    }

    val headerRow: CustomServiceHeaderRow
        get() = CustomServiceHeaderRow(keyField.text.trim(), valueField.text)
}

class CustomServiceBodyPropertyDialog(
    existingRow: CustomServiceBodyRow?,
    private val existingKeys: Set<String>,
) : DialogWrapper(true) {

    private val keyField = JBTextField()
    private val typeComboBox = ComboBox(CustomServiceBodyValueType.entries.toTypedArray())

    private val stringValueField by lazy { JBTextField() }
    private val numberSpinner by lazy {
        JSpinner(SpinnerNumberModel(0.0, null, null, 0.1))
    }
    private val booleanComboBox by lazy { ComboBox(arrayOf("true", "false")) }
    private val placeholderComboBox by lazy {
        ComboBox(CustomServicePlaceholders.all.toTypedArray())
    }

    @Suppress("UsePropertyAccessSyntax")
    private val jsonEditor by lazy {
        EditorTextField("", resolveEditorProject(), resolveJsonFileType()).apply {
            setOneLineMode(false)
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(120))
            addSettingsProvider { editor ->
                editor.settings.apply {
                    isLineNumbersShown = true
                    isRightMarginShown = false
                    isUseSoftWraps = false
                    isWhitespacesShown = false
                    isFoldingOutlineShown = true
                    isAllowSingleLogicalLineFolding = true
                    isAutoCodeFoldingEnabled = true
                }

                (editor as EditorEx).apply {
                    highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                        resolveEditorProject(),
                        PsiFileFactory.getInstance(resolveEditorProject()).createFileFromText(
                            "proxyai-custom-openai.json",
                            resolveJsonFileType(),
                            text,
                            System.currentTimeMillis(),
                            true,
                        ).virtualFile,
                    )
                    backgroundColor = colorsScheme.defaultBackground
                }
            }
        }
    }

    private val isJsonSelected = selectedTypePredicate { it?.jsonType == true }
    private val isStringSelected = selectedTypePredicate { it == CustomServiceBodyValueType.STRING }
    private val isPlaceholderSelected =
        selectedTypePredicate { it == CustomServiceBodyValueType.PLACEHOLDER }
    private val isNumberSelected = selectedTypePredicate { it == CustomServiceBodyValueType.NUMBER }
    private val isBooleanSelected =
        selectedTypePredicate { it == CustomServiceBodyValueType.BOOLEAN }
    private val isNullSelected = selectedTypePredicate { it == CustomServiceBodyValueType.NULL }

    private fun selectedTypePredicate(
        predicate: (CustomServiceBodyValueType?) -> Boolean
    ): ComponentPredicate = object : ComponentPredicate() {
        override fun addListener(listener: (Boolean) -> Unit) {
            typeComboBox.addItemListener { listener(invoke()) }
        }

        override fun invoke(): Boolean = predicate(selectedType)
    }

    var lastSelectedType: CustomServiceBodyValueType = CustomServiceBodyValueType.STRING
        private set

    private val placeholderDescriptionLabel by lazy {
        JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    }

    init {
        title = if (existingRow == null) "Add Body Property" else "Edit Body Property"

        existingRow?.let { row ->
            keyField.text = row.key
            typeComboBox.selectedItem = row.type
            populateValueForType(row.type, row.value)
        }

        if (existingRow == null) {
            typeComboBox.selectedItem = CustomServiceBodyValueType.STRING
            stringValueField.text = CustomServiceBodyValueType.STRING.templateValue
            numberSpinner.value =
                CustomServiceBodyValueType.NUMBER.templateValue.toDoubleOrNull() ?: 0.0
            booleanComboBox.selectedItem = CustomServiceBodyValueType.BOOLEAN.templateValue
            placeholderComboBox.selectedItem = CustomServicePlaceholders.all.firstOrNull()
        }

        typeComboBox.addItemListener { onTypeChanged() }
        placeholderComboBox.addItemListener { updatePlaceholderDescription() }
        updatePlaceholderDescription()

        onTypeChanged(forceTemplateUpdate = existingRow == null)

        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Key:") {
                cell(keyField)
                    .validationOnInput {
                        val key = it.text.trim()
                        if (key.isEmpty()) {
                            error("Key is required")
                        } else if (key in existingKeys) {
                            error("A property with this key already exists")
                        } else {
                            isOKActionEnabled = true
                            null
                        }
                    }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }

            row("Type:") {
                cell(typeComboBox)
                    .validationRequestor { callback ->
                        typeComboBox.addItemListener { callback() }
                    }
                    .onChanged {
                        isOKActionEnabled = true
                    }
                    .validationOnInput {
                        if (selectedType == null) error("Type is required")
                        else null
                    }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }

            row {
                scrollCell(jsonEditor)
                    .validationRequestor { callback ->
                        jsonEditor.document.addDocumentListener(object : DocumentListener {
                            override fun documentChanged(event: DocumentEvent) {
                                runInEdt {
                                    callback.invoke()
                                }
                            }
                        })
                    }
                    .validationOnInput {
                        selectedType?.let { type ->
                            if (!type.jsonType) return@let null
                            val text = jsonEditor.text
                            if (text.isBlank()) {
                                error("Value is required")
                            } else {
                                try {
                                    CustomServiceJsonUtils.parseBodyValue(type, text)
                                    isOKActionEnabled = true
                                    null
                                } catch (e: IllegalArgumentException) {
                                    error(e.message?.takeIf { it.isNotBlank() } ?: "Invalid JSON")
                                }
                            }
                        }
                    }
                    .label("Value:", LabelPosition.TOP)
                    .align(Align.FILL)
                    .resizableColumn()
            }.resizableRow().visibleIf(isJsonSelected)

            row("Value:") {
                cell(stringValueField)
                    .validationOnInput {
                        if (selectedType == CustomServiceBodyValueType.STRING && it.text.isBlank()) {
                            error("Value is required")
                        } else {
                            isOKActionEnabled = true
                            null
                        }
                    }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }.visibleIf(isStringSelected)

            row("Value:") {
                cell(placeholderComboBox)
                    .validationRequestor { callback ->
                        placeholderComboBox.addItemListener { callback() }
                    }
                    .validationOnInput {
                        if (selectedType == CustomServiceBodyValueType.PLACEHOLDER) {
                            val placeholder = (it.selectedItem as? CustomServicePlaceholder)?.code
                            if (placeholder.isNullOrBlank()) {
                                error("Placeholder is required")
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }.visibleIf(isPlaceholderSelected)

            row("Value:") {
                val spinnerTextField = (numberSpinner.editor as? JSpinner.DefaultEditor)?.textField
                cell(numberSpinner)
                    .validationRequestor { callback ->
                        spinnerTextField?.addKeyListener(object : KeyAdapter() {
                            override fun keyReleased(e: KeyEvent) {
                                runInEdt {
                                    callback.invoke()
                                }
                            }
                        })
                    }
                    .validationOnInput { spinner ->
                        val type = selectedType
                        if (type != CustomServiceBodyValueType.NUMBER) {
                            return@validationOnInput null
                        }

                        try {
                            val text = spinnerTextField?.text?.trim().orEmpty()
                            CustomServiceJsonUtils.parseBodyValue(
                                type,
                                text.ifEmpty { spinner.value.toString() }
                            )
                            null
                        } catch (e: IllegalArgumentException) {
                            error(e.message?.takeIf { it.isNotBlank() } ?: "Invalid number")
                        }
                    }
                    .resizableColumn()
            }.visibleIf(isNumberSelected)

            row("Value:") {
                cell(booleanComboBox)
                    .validationRequestor { callback ->
                        booleanComboBox.addItemListener { callback() }
                    }
                    .validationOnInput { comboBox ->
                        val type = selectedType
                        if (type != CustomServiceBodyValueType.BOOLEAN) {
                            return@validationOnInput null
                        }

                        try {
                            CustomServiceJsonUtils.parseBodyValue(
                                type,
                                comboBox.selectedItem?.toString().orEmpty()
                            )
                            null
                        } catch (e: IllegalArgumentException) {
                            error(e.message?.takeIf { it.isNotBlank() } ?: "Invalid boolean")
                        }
                    }
                    .resizableColumn()
            }.visibleIf(isBooleanSelected)

            row {
                cell(placeholderDescriptionLabel)
            }.visibleIf(object : ComponentPredicate() {
                override fun addListener(listener: (Boolean) -> Unit) {
                    typeComboBox.addItemListener { listener(invoke()) }
                    placeholderComboBox.addItemListener { listener(invoke()) }
                }

                override fun invoke(): Boolean =
                    selectedType == CustomServiceBodyValueType.PLACEHOLDER &&
                            placeholderDescriptionLabel.text.isNotEmpty()
            })

            row {
                cell(JBLabel("Value is ignored when type is Null.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }.visibleIf(isNullSelected)
        }.apply {
            val dialogSize = Dimension(JBUI.scale(360), JBUI.scale(240))
            preferredSize = dialogSize
            minimumSize = dialogSize
        }
    }

    override fun doValidate(): ValidationInfo? {
        val key = keyField.text.trim()
        if (key.isEmpty()) {
            return ValidationInfo("Key is required.", keyField)
        }
        if (key in existingKeys) {
            return ValidationInfo("A property with this key already exists.", keyField)
        }

        val type = selectedType ?: return ValidationInfo("Type is required.", typeComboBox)

        val value = getValueForType(type)
        if (value.isNullOrBlank()) {
            return ValidationInfo("Value is required.", getValueComponent(type))
        }

        return try {
            CustomServiceJsonUtils.parseBodyValue(type, value)
            null
        } catch (exception: IllegalArgumentException) {
            ValidationInfo(
                exception.message?.takeIf { it.isNotBlank() } ?: "Invalid value.",
                getValueComponent(type)
            )
        }
    }

    val bodyRow: CustomServiceBodyRow
        get() {
            val type = selectedType ?: CustomServiceBodyValueType.STRING
            val value = getValueForType(type).orEmpty()
            return CustomServiceBodyRow(
                key = keyField.text.trim(),
                type = type,
                value = value,
            )
        }

    private val selectedType: CustomServiceBodyValueType?
        get() = typeComboBox.selectedItem as? CustomServiceBodyValueType

    private val CustomServiceBodyValueType.jsonType: Boolean
        get() = this == CustomServiceBodyValueType.OBJECT || this == CustomServiceBodyValueType.ARRAY

    private fun onTypeChanged(forceTemplateUpdate: Boolean = false) {
        val newType = selectedType ?: return

        if (forceTemplateUpdate || shouldApplyTemplateValue(newType)) {
            applyTemplateValueForType(newType)
        }

        lastSelectedType = newType
    }

    private fun shouldApplyTemplateValue(newType: CustomServiceBodyValueType): Boolean {
        if (newType == CustomServiceBodyValueType.NULL) return false
        if (newType == CustomServiceBodyValueType.PLACEHOLDER) return false

        val currentValue = getValueForType(newType)
        if (currentValue.isNullOrBlank()) return true
        if (currentValue == lastSelectedType.templateValue) return true

        if (newType.jsonType && lastSelectedType.jsonType && newType != lastSelectedType) {
            return true
        }

        return false
    }

    private fun applyTemplateValueForType(type: CustomServiceBodyValueType) {
        when (type) {
            CustomServiceBodyValueType.STRING -> {
                stringValueField.text = type.templateValue
                stringValueField.caretPosition = stringValueField.text.length
            }

            CustomServiceBodyValueType.NUMBER -> {
                numberSpinner.value = type.templateValue.toDoubleOrNull() ?: 0.0
            }

            CustomServiceBodyValueType.BOOLEAN -> {
                booleanComboBox.selectedItem = type.templateValue
            }

            CustomServiceBodyValueType.OBJECT, CustomServiceBodyValueType.ARRAY -> {
                jsonEditor.text = type.templateValue
            }

            CustomServiceBodyValueType.NULL,
            CustomServiceBodyValueType.PLACEHOLDER -> Unit
        }
    }

    private fun updatePlaceholderDescription() {
        placeholderDescriptionLabel.text =
            (placeholderComboBox.selectedItem as? CustomServicePlaceholder)
                ?.description.orEmpty()
    }

    private fun populateValueForType(type: CustomServiceBodyValueType, value: String) {
        when (type) {
            CustomServiceBodyValueType.STRING -> stringValueField.text = value
            CustomServiceBodyValueType.NUMBER -> {
                value.toDoubleOrNull()?.let { numberSpinner.value = it }
                (numberSpinner.editor as? JSpinner.DefaultEditor)?.textField?.text = value
            }

            CustomServiceBodyValueType.BOOLEAN -> {
                booleanComboBox.selectedItem =
                    if (value.equals("true", ignoreCase = true)) "true" else "false"
            }

            CustomServiceBodyValueType.PLACEHOLDER -> {
                placeholderComboBox.selectedItem = CustomServicePlaceholders.findByCode(value)
                    ?: CustomServicePlaceholders.all.firstOrNull()
            }

            CustomServiceBodyValueType.OBJECT, CustomServiceBodyValueType.ARRAY -> {
                jsonEditor.text = value
            }

            CustomServiceBodyValueType.NULL -> Unit
        }
    }

    private fun getValueForType(type: CustomServiceBodyValueType): String? {
        return when (type) {
            CustomServiceBodyValueType.STRING -> stringValueField.text
            CustomServiceBodyValueType.NUMBER -> getNumberValueText()
            CustomServiceBodyValueType.BOOLEAN -> booleanComboBox.selectedItem?.toString()
            CustomServiceBodyValueType.PLACEHOLDER ->
                (placeholderComboBox.selectedItem as? CustomServicePlaceholder)?.code

            CustomServiceBodyValueType.OBJECT, CustomServiceBodyValueType.ARRAY -> jsonEditor.text
            CustomServiceBodyValueType.NULL -> ""
        }
    }

    private fun getValueComponent(type: CustomServiceBodyValueType): JComponent {
        return when (type) {
            CustomServiceBodyValueType.STRING -> stringValueField
            CustomServiceBodyValueType.NUMBER -> numberSpinner
            CustomServiceBodyValueType.BOOLEAN -> booleanComboBox
            CustomServiceBodyValueType.PLACEHOLDER -> placeholderComboBox
            CustomServiceBodyValueType.OBJECT, CustomServiceBodyValueType.ARRAY -> jsonEditor
            CustomServiceBodyValueType.NULL -> typeComboBox
        }
    }

    private fun getNumberValueText(): String {
        val textField = (numberSpinner.editor as? JSpinner.DefaultEditor)?.textField
        val text = textField?.text?.trim().orEmpty()
        return text.ifEmpty { numberSpinner.value.toString() }
    }

    private fun resolveEditorProject(): Project {
        val project = ApplicationUtil.findCurrentProject()
        return if (project != null && !project.isDisposed) project else ProjectManager.getInstance().defaultProject
    }

    private fun resolveJsonFileType() = FileTypeManager.getInstance().getFileTypeByExtension("json")
}
