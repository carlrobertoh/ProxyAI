package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.completions.CompletionRequestUtil
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.diagnostics.ProjectDiagnosticsService
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.GitUtil
import java.util.*

object TagProcessorFactory {

    fun getProcessor(project: Project, tagDetails: TagDetails): TagProcessor {
        return when (tagDetails) {
            is FileTagDetails -> FileTagProcessor(project, tagDetails)
            is SelectionTagDetails -> SelectionTagProcessor(project, tagDetails)
            is EditorSelectionTagDetails -> EditorSelectionTagProcessor(project, tagDetails)
            is HistoryTagDetails -> ConversationTagProcessor(tagDetails)
            is PersonaTagDetails -> PersonaTagProcessor(tagDetails)
            is FolderTagDetails -> FolderTagProcessor(project, tagDetails)
            is WebTagDetails -> WebTagProcessor()
            is McpTagDetails -> McpTagProcessor()
            is GitCommitTagDetails -> GitCommitTagProcessor(project, tagDetails)
            is CurrentGitChangesTagDetails -> CurrentGitChangesTagProcessor(project)
            is EditorTagDetails -> EditorTagProcessor(project, tagDetails)
            is ImageTagDetails -> ImageTagProcessor(tagDetails)
            is EmptyTagDetails -> TagProcessor { _, _ -> }
            is CodeAnalyzeTagDetails -> TagProcessor { _, _ -> }
            is DiagnosticsTagDetails -> DiagnosticsTagProcessor(project, tagDetails)
        }
    }
}

class FileTagProcessor(
    project: Project,
    private val tagDetails: FileTagDetails,
) : TagProcessor {
    private val settingsService = project.service<ProxyAISettingsService>()

    override fun process(message: Message, promptBuilder: StringBuilder) {
        if (!settingsService.isVirtualFileVisible(tagDetails.virtualFile)) {
            return
        }
        if (message.referencedFilePaths == null) {
            message.referencedFilePaths = mutableListOf()
        }
        message.referencedFilePaths?.add(tagDetails.virtualFile.path)
    }
}

class EditorTagProcessor(
    project: Project,
    private val tagDetails: EditorTagDetails,
) : TagProcessor {
    private val settingsService = project.service<ProxyAISettingsService>()

    override fun process(message: Message, promptBuilder: StringBuilder) {
        if (!settingsService.isVirtualFileVisible(tagDetails.virtualFile)) {
            return
        }
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

class PersonaTagProcessor(
    private val tagDetails: PersonaTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        message.personaName = tagDetails.personaDetails.name
    }
}

class FolderTagProcessor(
    project: Project,
    private val tagDetails: FolderTagDetails,
) : TagProcessor {
    private val settingsService = project.service<ProxyAISettingsService>()

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
            if (!settingsService.isVirtualFileVisible(child)) {
                return@forEach
            }
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
            .append(getDiffString(project, tagDetails.commitHash))
            .append("\n```\n")
    }

    private fun getDiffString(project: Project, commitHash: String): String {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously<String, Exception>(
            {
                val repository = GitUtil.getProjectRepository(project)
                    ?: return@runProcessWithProgressSynchronously ""

                val diff = GitUtil.getCommitDiffs(project, repository, commitHash)
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

class McpTagProcessor : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {}
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
    private val diagnosticsService = project.service<ProjectDiagnosticsService>()

    override fun process(message: Message, promptBuilder: StringBuilder) {
        val diagnostics = diagnosticsService.collect(tagDetails.virtualFile, tagDetails.filter)
        if (diagnostics.content.isBlank() && diagnostics.error == null) {
            return
        }

        promptBuilder
            .append("\n## ${tagDetails.virtualFile.name} Problems (${tagDetails.filter.displayName})\n")
            .append(diagnostics.error ?: diagnostics.content)
            .append("\n")
    }
}
