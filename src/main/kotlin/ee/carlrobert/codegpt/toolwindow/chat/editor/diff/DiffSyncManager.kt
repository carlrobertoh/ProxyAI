package ee.carlrobert.codegpt.toolwindow.chat.editor.diff

import com.intellij.diff.util.Side
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.application
import com.intellij.util.concurrency.AppExecutorUtil
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel.Companion.RESPONSE_EDITOR_DIFF_VIEWER_KEY
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel.Companion.RESPONSE_EDITOR_DIFF_VIEWER_VALUE_PAIR_KEY
import java.util.concurrent.ConcurrentHashMap

object DiffSyncManager {

    private val fileToEditors = ConcurrentHashMap<String, MutableSet<EditorEx>>()
    private val fileToListener = ConcurrentHashMap<String, DocumentListener>()

    fun registerEditor(filePath: String, editor: EditorEx) {
        fileToEditors.compute(filePath) { _, set ->
            (set ?: mutableSetOf()).apply { add(editor) }
        }

        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                application.executeOnPooledThread {
                    val affectedEditors = fileToEditors[filePath] ?: emptyList()
                    for (editor in affectedEditors) {
                        val diffViewer = RESPONSE_EDITOR_DIFF_VIEWER_KEY.get(editor)
                        if (diffViewer != null) {
                            val leftSideDoc =
                                runReadAction { diffViewer.getDocument(Side.LEFT) }
                            val rightSideDoc =
                                runReadAction { diffViewer.getDocument(Side.RIGHT) }

                            if (leftSideDoc.text == rightSideDoc.text) {
                                continue
                            }

                            val entry = RESPONSE_EDITOR_DIFF_VIEWER_VALUE_PAIR_KEY.get(editor)
                            if (entry != null) {
                                val (search, replace) = entry
                                val newText = event.document.text
                                if (!newText.contains(replace.trim())) {
                                    val replacedText =
                                        newText.replace(search.trim(), replace.trim())
                                    runInEdt {
                                        if (replacedText.length != newText.length) {
                                            runUndoTransparentWriteAction {
                                                rightSideDoc.setText(
                                                    StringUtil.convertLineSeparators(
                                                        replacedText
                                                    )
                                                )
                                                diffViewer.scheduleRediff()
                                            }
                                        }
                                        diffViewer.rediff(true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val existing = fileToListener.putIfAbsent(filePath, listener)
        if (existing != null) {
            return
        }

        ReadAction.nonBlocking<Document?> {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            if (virtualFile == null) {
                null
            } else {
                FileDocumentManager.getInstance().getDocument(virtualFile)
            }
        }.finishOnUiThread(ModalityState.any()) { document ->
            if (document == null || fileToEditors[filePath].isNullOrEmpty()) {
                fileToListener.remove(filePath, listener)
                return@finishOnUiThread
            }

            if (fileToListener[filePath] != listener) {
                return@finishOnUiThread
            }

            document.addDocumentListener(listener)
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    fun unregisterEditor(filePath: String, editor: EditorEx) {
        fileToEditors[filePath]?.let { set ->
            set.remove(editor)
            if (set.isEmpty()) {
                fileToEditors.remove(filePath)
                val listener = fileToListener.remove(filePath)

                if (listener != null) {
                    ReadAction.nonBlocking<Document?> {
                        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                        if (virtualFile == null) {
                            null
                        } else {
                            FileDocumentManager.getInstance().getDocument(virtualFile)
                        }
                    }.finishOnUiThread(ModalityState.any()) { document ->
                        if (document != null) {
                            document.removeDocumentListener(listener)
                        }
                    }.submit(AppExecutorUtil.getAppExecutorService())
                }
            }
        }
    }
}
