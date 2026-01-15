package ee.carlrobert.codegpt.toolwindow.chat.ui.textarea

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.util.TextRange
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.tokens.TokenComputationService
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import ee.carlrobert.codegpt.util.coroutines.withEdt
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import java.util.stream.Collectors
import javax.swing.Box
import javax.swing.JPanel

private const val LARGE_SELECTION_THRESHOLD_CHARS = 32_768

class TotalTokensPanel(
    conversation: Conversation,
    highlightedText: String?,
    parentDisposable: Disposable,
    psiStructureRepository: PsiStructureRepository
) : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {

    private val tokenService = TokenComputationService.getInstance()
    private val encodingManager = EncodingManager.getInstance()
    private val totalTokensDetails = createTokenDetails(conversation, highlightedText)
    private val label = JBLabel(getLabelHtml(totalTokensDetails.total))
    private val dispatchers = CoroutineDispatchers()
    private val scope = DisposableCoroutineScope(dispatchers.default())

    init {
        PsiStructureTotalTokenProvider(
            parentDisposable,
            ClassStructureSerializer,
            encodingManager,
            CoroutineDispatchers(),
            psiStructureRepository
        ) { psiTokens ->
            if (ConfigurationSettings.getState().chatCompletionSettings.psiStructureEnabled) {
                updatePsiTokenCount(psiTokens)
            } else {
                updatePsiTokenCount(0)
            }
        }

        border = JBUI.Borders.empty()
        isOpaque = false
        add(JBLabel(AllIcons.General.ContextHelp).apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    toolTipText = getIconToolTipText(buildToolTipHtml(totalTokensDetails))
                }
            })
        })
        add(Box.createHorizontalStrut(4))
        add(label)
        addSelectionListeners(parentDisposable)
        Disposer.register(parentDisposable, scope)
    }

    private fun addSelectionListeners(parentDisposable: Disposable) {
        val editorFactory = EditorFactory.getInstance()
        editorFactory.allEditors.forEach { addEditorSelectionListener(it) }
        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                addEditorSelectionListener(event.editor)
            }
        }, parentDisposable)
    }

    private fun addEditorSelectionListener(editor: Editor) {
        if (editor.editorKind == com.intellij.openapi.editor.EditorKind.MAIN_EDITOR) {
            editor.selectionModel.addSelectionListener(getSelectionListener())
        }
    }

    private var lastSelectionLength: Int = -1
    private fun getSelectionListener(): SelectionListener = object : SelectionListener {
        override fun selectionChanged(e: SelectionEvent) {
            val range: TextRange = e.newRange
            val length = range.length
            if (length == lastSelectionLength) return
            lastSelectionLength = length
            if (length > LARGE_SELECTION_THRESHOLD_CHARS) {
                updateHighlightedTokens(tokenService.estimateTokensByLength(length))
            } else {
                val text = e.editor.document.getText(range)
                updateHighlightedTokens(text)
            }
        }
    }

    fun getTokenDetails(): TotalTokensDetails = totalTokensDetails

    fun update() = update(totalTokensDetails.total)

    fun update(total: Int) {
        scope.launch {
            withEdt { label.text = getLabelHtml(total) }
        }
    }

    fun updatePsiTokenCount(psiTokenCount: Int) {
        scope.launch {
            totalTokensDetails.psiTokens = psiTokenCount
            update()
        }
    }

    fun updateConversationTokens(conversation: Conversation) {
        scope.launch {
            totalTokensDetails.conversationTokens = encodingManager.countConversationTokens(conversation)
            update()
        }
    }

    fun updateUserPromptTokens(userPrompt: String) {
        scope.launch {
            totalTokensDetails.userPromptTokens = tokenService.countTextTokens(userPrompt)
            update()
        }
    }

    fun updateHighlightedTokens(highlightedText: String) {
        scope.launch {
            totalTokensDetails.highlightedTokens = tokenService.countTextTokens(highlightedText)
            update()
        }
    }

    fun updateHighlightedTokens(tokenCount: Int) {
        scope.launch {
            totalTokensDetails.highlightedTokens = tokenCount
            update()
        }
    }

    fun updateReferencedFilesTokens(totalTokens: Int) {
        totalTokensDetails.referencedFilesTokens = totalTokens
        update()
    }

    fun updateReferencedFilesTokens(includedFileContents: List<String>) {
        scope.launch {
            var total = 0
            includedFileContents.forEach { if (it.isNotEmpty()) total += tokenService.countTextTokens(it) }
            totalTokensDetails.referencedFilesTokens = total
            update()
        }
    }

    private fun createTokenDetails(conversation: Conversation, highlightedText: String?): TotalTokensDetails {
        val tokenDetails = TotalTokensDetails(tokenService.countTextTokens(PromptsSettings.getSelectedPersonaSystemPrompt()))
        tokenDetails.conversationTokens = encodingManager.countConversationTokens(conversation)

        val mcpTokens = encodingManager.countMcpToolTokens(conversation)
        tokenDetails.mcpToolInputTokens = mcpTokens.inputTokens
        tokenDetails.mcpToolOutputTokens = mcpTokens.outputTokens

        if (highlightedText != null) {
            tokenDetails.highlightedTokens = tokenService.countTextTokens(highlightedText)
        }
        return tokenDetails
    }

    private fun buildToolTipHtml(details: TotalTokensDetails): String {
        val items = LinkedHashMap(mapOf(
            "System Prompt" to details.systemPromptTokens,
            "Conversation Tokens" to details.conversationTokens,
            "Input Tokens" to details.userPromptTokens,
            "Highlighted Tokens" to details.highlightedTokens,
            "Referenced Files Tokens" to details.referencedFilesTokens,
            "Dependency Structure Tokens" to details.psiTokens,
            "MCP Tool Input Tokens" to details.mcpToolInputTokens,
            "MCP Tool Output Tokens" to details.mcpToolOutputTokens
        ))
        return items.entries.stream()
            .map { (k, v) -> "<p style=\"margin: 0; padding: 0;\"><small>$k: <strong>$v</strong></small></p>" }
            .collect(Collectors.joining())
    }

    private fun getIconToolTipText(html: String): String {
        return if (ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT) != ServiceType.OPENAI) {
            """
            <html>
            <body style="margin: 0; padding: 0;">
            $html
            <p style="margin-top: 8px;">
            <small>
            <strong>Note:</strong> Output values might vary across different large language models due to variations in their encoding methods.
            </small>
            </p>
            </body>
            </html>
            """.trimIndent()
        } else {
            "<html$html</html>"
        }
    }

    private fun getLabelHtml(total: Int): String = "<html><small>Tokens: <strong>$total</strong></small></html>"
}
