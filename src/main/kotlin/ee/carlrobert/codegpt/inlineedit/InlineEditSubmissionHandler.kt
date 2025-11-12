package ee.carlrobert.codegpt.inlineedit

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.ui.OverlayUtil
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicReference

class InlineEditSubmissionHandler(
    private val editor: Editor,
    private val observableProperties: ObservableProperties,
    private val sessionConversation: Conversation,
) {

    private val previousSourceRef = AtomicReference<String?>(null)
    private val previousPromptRef = AtomicReference<String?>(null)
    private val currentEventSourceRef = AtomicReference<EventSource?>(null)
    private val logger = Logger.getInstance(InlineEditSubmissionHandler::class.java)

    fun getSessionConversation(): Conversation = sessionConversation

    fun handleSubmit(
        userPrompt: String,
        referencedFiles: List<ReferencedFile>? = null,
        gitDiff: String? = null,
        conversationHistory: List<Conversation>? = null,
        diagnosticsInfo: String? = null,
        forceApply: Boolean = false
    ) {
        prepareSubmission(userPrompt)

        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val editorEx = editor as? EditorEx ?: return
        val inlay = editorEx.getUserData(InlineEditInlay.INLAY_KEY) ?: return
        val selectedService =
            ModelSelectionService.getInstance().getServiceForFeature(FeatureType.INLINE_EDIT)

        if (!forceApply && inlay.isQuickQuestionEnabled()) {
            handleAskSubmission(
                userPrompt,
                referencedFiles,
                conversationHistory,
                editorEx,
                inlay,
                file,
                selectedService
            )
            return
        }

        handleInlineEditSubmission(
            userPrompt,
            referencedFiles,
            gitDiff,
            conversationHistory,
            diagnosticsInfo,
            editorEx,
            inlay,
            file
        )
    }

    private fun prepareSubmission(userPrompt: String) {
        editor.project?.let { CompletionProgressNotifier.update(it, true) }
        observableProperties.loading.set(true)
        observableProperties.submitted.set(true)
        previousPromptRef.getAndSet(userPrompt)
        previousSourceRef.getAndSet(editor.document.text)
        runInEdt { editor.selectionModel.removeSelection() }
    }

    private fun handleAskSubmission(
        userPrompt: String,
        referencedFiles: List<ReferencedFile>?,
        conversationHistory: List<Conversation>?,
        editorEx: EditorEx,
        inlay: InlineEditInlay,
        file: com.intellij.openapi.vfs.VirtualFile?,
        selectedService: ServiceType
    ) {
        val message = Message(userPrompt)
        sessionConversation.addMessage(message)

        inlay.resetAskContainer()

        val withCurrentFile = buildReferencedFilesWithCurrent(file, referencedFiles)

        val params = ChatCompletionParameters
            .builder(sessionConversation, message)
            .project(editor.project)
            .referencedFiles(withCurrentFile)
            .history(conversationHistory)
            .chatMode(ChatMode.ASK)
            .featureType(FeatureType.CHAT)
            .build()

        sendAskRequest(editorEx, inlay, selectedService, params)
    }

    private fun buildReferencedFilesWithCurrent(
        file: com.intellij.openapi.vfs.VirtualFile?,
        referencedFiles: List<ReferencedFile>?
    ): List<ReferencedFile> {
        val list = referencedFiles?.toMutableList() ?: mutableListOf()
        file?.let { vf ->
            val currentRef = ReferencedFile.from(vf)
            if (list.none { it.filePath == currentRef.filePath }) {
                list.add(currentRef)
            }
        }
        return list.toList()
    }

    private fun sendAskRequest(
        editorEx: EditorEx,
        inlay: InlineEditInlay,
        selectedService: ServiceType,
        params: ChatCompletionParameters
    ) {
        try {
            currentEventSourceRef.getAndSet(null)?.cancel()

            val project = editor.project ?: return
            val request = CompletionRequestFactory
                .getFactory(selectedService)
                .createInlineEditQuestionRequest(params)
            val listener = ChatCompletionEventListener(
                project,
                params,
                InlineAskResponseListener(project, inlay)
            )

            val eventSource = service<CompletionRequestService>()
                .getChatCompletionAsync(request, listener, selectedService)
            currentEventSourceRef.set(eventSource)
        } catch (ex: Exception) {
            logger.warn("InlineAsk: request dispatch failed", ex)
            runInEdt {
                OverlayUtil.showNotification(
                    ex.message ?: CodeGPTBundle.get("error.askRequestFailed"),
                    NotificationType.ERROR
                )
                observableProperties.loading.set(false)
                observableProperties.submitted.set(false)
                editorEx.getUserData(InlineEditInlay.INLAY_KEY)
                    ?.setThinkingVisible(false)
            }
        }
    }

    private fun handleInlineEditSubmission(
        userPrompt: String,
        referencedFiles: List<ReferencedFile>?,
        gitDiff: String?,
        conversationHistory: List<Conversation>?,
        diagnosticsInfo: String?,
        editorEx: EditorEx,
        inlay: InlineEditInlay,
        file: com.intellij.openapi.vfs.VirtualFile?
    ) {
        sessionConversation.addMessage(Message(userPrompt))

        val parameters = InlineEditCompletionParameters(
            userPrompt,
            runReadAction { editor.selectionModel.selectedText },
            file?.path,
            file?.extension,
            editor.project?.basePath,
            referencedFiles,
            gitDiff,
            sessionConversation,
            conversationHistory,
            diagnosticsInfo,
            runReadAction { editor.caretModel.offset }
        )

        val requestId = System.nanoTime()
        editorEx.putUserData(InlineEditSearchReplaceListener.REQUEST_ID_KEY, requestId)

        val listener = InlineEditSearchReplaceListener(
            editorEx,
            observableProperties,
            TextRange(
                runReadAction { editor.selectionModel.selectionStart },
                runReadAction { editor.selectionModel.selectionEnd },
            ),
            requestId,
            sessionConversation
        )

        editorEx.putUserData(InlineEditSearchReplaceListener.LISTENER_KEY, listener)

        inlay.apply {
            setInlineEditControlsVisible(false)
            setThinkingVisible(true)
        }

        sendInlineEditRequest(editorEx, parameters, listener)
    }

    private fun sendInlineEditRequest(
        editorEx: EditorEx,
        parameters: InlineEditCompletionParameters,
        listener: InlineEditSearchReplaceListener
    ) {
        try {
            currentEventSourceRef.getAndSet(null)?.cancel()

            val eventSource = service<CompletionRequestService>().getInlineEditCompletionAsync(
                parameters,
                listener
            )
            currentEventSourceRef.set(eventSource)
        } catch (ex: Exception) {
            logger.warn("InlineEdit: request dispatch failed", ex)
            runInEdt {
                OverlayUtil.showNotification(
                    ex.message ?: CodeGPTBundle.get("error.inlineEditRequestFailed"),
                    NotificationType.ERROR
                )
                observableProperties.loading.set(false)
                observableProperties.submitted.set(false)
                editorEx.getUserData(InlineEditInlay.INLAY_KEY)
                    ?.setThinkingVisible(false)
            }
        }
    }

    fun handleStop() {
        cancelActiveRequest()
        val editorEx = editor as? EditorEx ?: return

        runInEdt {
            editorEx.getUserData(InlineEditInlay.INLAY_KEY)?.setThinkingVisible(false)

            val existingListener =
                editorEx.getUserData(InlineEditSearchReplaceListener.LISTENER_KEY)
            existingListener?.stopGenerating()

            val session = editorEx.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
            if (session != null && session.hasPendingHunks()) {
                editorEx.getUserData(InlineEditInlay.INLAY_KEY)?.setInlineEditControlsVisible(true)
            } else {
                editorEx.getUserData(InlineEditInlay.INLAY_KEY)?.setInlineEditControlsVisible(false)
            }

            observableProperties.loading.set(false)
            observableProperties.submitted.set(false)
            editor.project?.let { project ->
                CompletionProgressNotifier.Companion.update(project, false)
            }
            editorEx.getUserData(InlineEditInlay.INLAY_KEY)?.onCompletionFinished()
        }
    }

    fun handleReject() {
        cancelActiveRequest()
        (editor as? EditorEx)?.getUserData(InlineEditInlay.INLAY_KEY)?.setThinkingVisible(false)
        val prevSource = previousSourceRef.get()
        if (!observableProperties.accepted.get() && prevSource != null) {
            revertAllChanges(prevSource)
        }

        restorePreviousPrompt()
        runInEdt {
            val editorEx = editor as? EditorEx
            val existingListener =
                editorEx?.getUserData(InlineEditSearchReplaceListener.LISTENER_KEY)
            existingListener?.dispose()
            editorEx?.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.dispose()
            editorEx?.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION, null)
            editorEx?.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.dispose()
            editorEx?.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER, null)
            editorEx?.putUserData(InlineEditSearchReplaceListener.LISTENER_KEY, null)

            observableProperties.loading.set(false)
            observableProperties.submitted.set(false)
            editor.project?.let { project ->
                CompletionProgressNotifier.Companion.update(project, false)
            }
            editorEx?.getUserData(InlineEditInlay.INLAY_KEY)?.onCompletionFinished()
        }
    }

    private fun cancelActiveRequest() {
        val editorEx = editor as? EditorEx
        val newRequestId = System.nanoTime()
        editorEx?.putUserData(InlineEditSearchReplaceListener.REQUEST_ID_KEY, newRequestId)

        currentEventSourceRef.getAndSet(null)?.cancel()
    }

    private fun revertAllChanges(prevSource: String) {
        editor.project?.let { project ->
            runInEdt {
                WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.replaceString(
                        0,
                        editor.document.textLength,
                        StringUtil.convertLineSeparators(prevSource)
                    )
                }
            }
        }
    }

    fun restorePreviousPrompt() {
        val prevPrompt = previousPromptRef.get()
        if (prevPrompt != null) {
            (editor as? EditorEx)
                ?.getUserData(InlineEditInlay.INLAY_KEY)
                ?.restorePromptAndFocus(prevPrompt)
        }
    }

}
