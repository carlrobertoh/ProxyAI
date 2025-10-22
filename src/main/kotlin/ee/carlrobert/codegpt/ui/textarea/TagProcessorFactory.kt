package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.completions.CompletionRequestUtil
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.GitUtil
import git4idea.GitCommit
import java.util.*

object TagProcessorFactory {

    fun getProcessor(project: Project, tagDetails: TagDetails): TagProcessor {
        return when (tagDetails) {
            is FileTagDetails -> FileTagProcessor(tagDetails)
            is SelectionTagDetails -> SelectionTagProcessor(project, tagDetails)
            is EditorSelectionTagDetails -> EditorSelectionTagProcessor(project, tagDetails)
            is HistoryTagDetails -> ConversationTagProcessor(tagDetails)
            is DocumentationTagDetails -> DocumentationTagProcessor(tagDetails)
            is PersonaTagDetails -> PersonaTagProcessor(tagDetails)
            is FolderTagDetails -> FolderTagProcessor(tagDetails)
            is WebTagDetails -> WebTagProcessor()
            is GitCommitTagDetails -> GitCommitTagProcessor(project, tagDetails)
            is CurrentGitChangesTagDetails -> CurrentGitChangesTagProcessor(project)
            is EditorTagDetails -> EditorTagProcessor(tagDetails)
            is ImageTagDetails -> ImageTagProcessor(tagDetails)
            is EmptyTagDetails -> TagProcessor { _, _ -> }
            is CodeAnalyzeTagDetails -> TagProcessor { _, _ -> }
            is DiagnosticsTagDetails -> DiagnosticsTagProcessor(project, tagDetails)
        }
    }
}

class FileTagProcessor(
    private val tagDetails: FileTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        if (message.referencedFilePaths == null) {
            message.referencedFilePaths = mutableListOf()
        }
        message.referencedFilePaths?.add(tagDetails.virtualFile.path)
    }
}

class EditorTagProcessor(
    private val tagDetails: EditorTagDetails,
) : TagProcessor {

    override fun process(message: Message, promptBuilder: StringBuilder) {
        if (message.referencedFilePaths == null) {
            message.referencedFilePaths = mutableListOf()
        }
        message.referencedFilePaths?.add(tagDetails.virtualFile.path)
    }
}

class SelectionTagProcessor(
    private val project: Project,
    private val tagDetails: SelectionTagDetails,
) : TagProcessor {

    override fun process(message: Message, promptBuilder: StringBuilder) {
        val selectedText = runReadAction { EditorUtil.getSelectedEditorSelectedText(project) }
        if (selectedText.isNullOrEmpty()) {
            return
        }

        promptBuilder.append(
            CompletionRequestUtil.formatCode(selectedText, tagDetails.virtualFile.path)
        )
    }
}

class EditorSelectionTagProcessor(
    private val project: Project,
    private val tagDetails: EditorSelectionTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        val selectedText = runReadAction { EditorUtil.getSelectedEditorSelectedText(project) }
        if (selectedText.isNullOrEmpty()) {
            return
        }

        promptBuilder.append(
            CompletionRequestUtil.formatCode(selectedText, tagDetails.virtualFile.path)
        )
    }
}

class DocumentationTagProcessor(
    private val tagDetails: DocumentationTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        message.documentationDetails = tagDetails.documentationDetails
    }
}

class PersonaTagProcessor(
    private val tagDetails: PersonaTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        message.personaName = tagDetails.personaDetails.name
    }
}

class FolderTagProcessor(
    private val tagDetails: FolderTagDetails,
) : TagProcessor {
    override fun process(
        message: Message,
        promptBuilder: StringBuilder
    ) {
        if (message.referencedFilePaths == null) {
            message.referencedFilePaths = mutableListOf()
        }

        processFolder(tagDetails.folder, message.referencedFilePaths ?: mutableListOf())
    }

    private fun processFolder(folder: VirtualFile, referencedFilePaths: MutableList<String>) {
        folder.children.forEach { child ->
            when {
                child.isDirectory -> processFolder(child, referencedFilePaths)
                else -> referencedFilePaths.add(child.path)
            }
        }
    }
}

class WebTagProcessor : TagProcessor {
    override fun process(
        message: Message,
        promptBuilder: StringBuilder
    ) {
        message.isWebSearchIncluded = true
    }
}

class GitCommitTagProcessor(
    private val project: Project,
    private val tagDetails: GitCommitTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        promptBuilder
            .append("\n```shell\n")
            .append(getDiffString(project, tagDetails.gitCommit))
            .append("\n```\n")
    }

    private fun getDiffString(project: Project, gitCommit: GitCommit): String {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously<String, Exception>(
            {
                val repository = GitUtil.getProjectRepository(project)
                    ?: return@runProcessWithProgressSynchronously ""

                val commitId = gitCommit.id.asString()
                val diff = GitUtil.getCommitDiffs(project, repository, commitId)
                    .joinToString("\n")

                service<EncodingManager>().truncateText(diff, 8192, true)
            },
            "Getting Commit Diff",
            true,
            project
        )
    }
}

class CurrentGitChangesTagProcessor(
    private val project: Project,
) : TagProcessor {

    override fun process(
        message: Message,
        promptBuilder: StringBuilder
    ) {
        ProgressManager.getInstance().runProcessWithProgressSynchronously<Unit, Exception>(
            {
                GitUtil.getCurrentChanges(project)?.let {
                    promptBuilder
                        .append("\n```shell\n")
                        .append(it)
                        .append("\n```\n")
                }
            },
            "Getting Current Changes",
            true,
            project
        )
    }
}

class ImageTagProcessor(
    private val tagDetails: ImageTagDetails
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        message.imageFilePath = tagDetails.imagePath
    }
}

class ConversationTagProcessor(
    private val tagDetails: HistoryTagDetails
) : TagProcessor {

    companion object {
        fun getConversation(conversationId: UUID) =
            ConversationsState.getCurrentConversation()?.takeIf {
                it.id.equals(conversationId)
            } ?: ConversationsState.getInstance().conversations.find {
                it.id.equals(conversationId)
            }

        fun formatConversation(conversation: Conversation): String {
            val stringBuilder = StringBuilder()
            stringBuilder.append(
                "# History\n\n"
            )
            stringBuilder.append(
                "## Conversation: ${HistoryActionItem.getConversationTitle(conversation)}\n\n"
            )

            conversation.messages.forEachIndexed { index, msg ->
                stringBuilder.append("**User**: ${msg.prompt}\n\n")
                stringBuilder.append("**Assistant**: ${msg.response}\n\n")
                stringBuilder.append("\n")
            }
            return stringBuilder.toString()
        }
    }

    override fun process(message: Message, stringBuilder: StringBuilder) {
        if (message.conversationsHistoryIds == null) {
            message.conversationsHistoryIds = mutableListOf()
        }
        message.conversationsHistoryIds?.add(tagDetails.conversationId)
    }
}

class DiagnosticsTagProcessor(
    private val project: Project,
    private val tagDetails: DiagnosticsTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        promptBuilder
            .append("\n## Current File Problems\n")
            .append(getDiagnosticsString(project, tagDetails.virtualFile))
            .append("\n")
    }

    private fun getDiagnosticsString(project: Project, virtualFile: VirtualFile): String {
        return try {
            var result = ""
            ApplicationManager.getApplication().invokeAndWait {
                result = ApplicationManager.getApplication().runWriteAction<String> {
                    DumbService.getInstance(project).runReadActionInSmartMode<String> {
                        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                            ?: return@runReadActionInSmartMode "No document found for file"

                        PsiDocumentManager.getInstance(project).commitDocument(document)

                        val psiManager = PsiManager.getInstance(project)
                        val psiFile = psiManager.findFile(virtualFile)
                            ?: return@runReadActionInSmartMode "No PSI file found for: ${virtualFile.path}"

                        val rangeHighlights =
                            DaemonCodeAnalyzerImpl.getHighlights(
                                document,
                                HighlightSeverity.WEAK_WARNING,
                                project
                            )
                        // TODO: Find a better solution
                        val fileLevel: List<HighlightInfo> = try {
                            val method = DaemonCodeAnalyzerImpl::class.java.methods.firstOrNull {
                                it.name == "getFileLevelHighlights" && it.parameterCount == 2
                            }
                            if (method != null) {
                                @Suppress("UNCHECKED_CAST")
                                method.invoke(null, project, psiFile) as? List<HighlightInfo> ?: emptyList()
                            } else {
                                emptyList()
                            }
                        } catch (_: Throwable) {
                            emptyList()
                        }

                        val highlights = (rangeHighlights.asSequence() + fileLevel.asSequence())
                            .distinctBy { Triple(it.description, it.startOffset, it.severity) }
                            .sortedWith(
                                compareBy<HighlightInfo>(
                                    { severityOrder(it.severity) },
                                    { it.startOffset.coerceAtLeast(0) }
                                )
                            )
                            .toList()

                        if (highlights.isEmpty()) {
                            return@runReadActionInSmartMode ""
                        }

                        val maxItems = 200
                        val overflow = (highlights.size - maxItems).coerceAtLeast(0)
                        val shown = highlights.take(maxItems)

                        buildString {
                            append("File: ${virtualFile.name}\n")
                            append("Path: ${virtualFile.path}\n\n")

                            shown.forEach { info ->
                                val startOffset = info.startOffset.coerceIn(0, document.textLength)
                                val lineColText =
                                    if (info.startOffset >= 0 && document.textLength > 0) {
                                        val line = document.getLineNumber(startOffset) + 1
                                        val col = startOffset - document.getLineStartOffset(line - 1) + 1
                                        "line $line, col $col"
                                    } else {
                                        "file-level"
                                    }

                                val rawMessage = info.description ?: info.toolTip ?: ""
                                val message = StringUtil.removeHtmlTags(rawMessage, false).trim()

                                val severityLabel = when (info.severity) {
                                    HighlightSeverity.ERROR -> "ERROR"
                                    HighlightSeverity.WARNING -> "WARNING"
                                    HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
                                    HighlightSeverity.INFORMATION -> "INFO"
                                    else -> info.severity.toString()
                                }

                                append("- [$severityLabel] $lineColText: $message\n")
                            }

                            if (overflow > 0) {
                                append("... ($overflow more not shown)\n")
                            }
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            "Error retrieving diagnostics: ${e.message}"
        }
    }

    private fun severityOrder(severity: HighlightSeverity): Int {
        return when (severity) {
            HighlightSeverity.ERROR -> 0
            HighlightSeverity.WARNING -> 1
            HighlightSeverity.WEAK_WARNING -> 2
            HighlightSeverity.INFORMATION -> 3
            else -> 4
        }
    }
}
