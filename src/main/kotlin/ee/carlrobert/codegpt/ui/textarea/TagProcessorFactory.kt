package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.completions.CompletionRequestUtil
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.util.GitUtil
import git4idea.GitCommit

object TagProcessorFactory {

    fun getProcessor(project: Project, tagDetails: TagDetails): TagProcessor {
        return when (tagDetails) {
            is FileTagDetails -> FileTagProcessor(tagDetails)
            is SelectionTagDetails -> SelectionTagProcessor(tagDetails)
            is DocumentationTagDetails -> DocumentationTagProcessor(tagDetails)
            is PersonaTagDetails -> PersonaTagProcessor(tagDetails)
            is FolderTagDetails -> FolderTagProcessor(tagDetails)
            is WebTagDetails -> WebTagProcessor()
            is GitCommitTagDetails -> GitCommitTagProcessor(project, tagDetails)
            is CurrentGitChangesTagDetails -> CurrentGitChangesTagProcessor(project)
            is EditorSelectionTagDetails -> EditorSelectionTagProcessor(tagDetails)
            is EditorTagDetails -> EditorTagProcessor(tagDetails)
            is EmptyTagDetails -> TagProcessor { _, _ -> }
            is CodeAnalyzeTagDetails -> TagProcessor { _, _ -> }
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
    private val tagDetails: SelectionTagDetails,
) : TagProcessor {

    override fun process(message: Message, promptBuilder: StringBuilder) {
        if (tagDetails.selectedText.isNullOrEmpty()) {
            return
        }

        promptBuilder.append(
            CompletionRequestUtil.formatCode(
                tagDetails.selectedText ?: "",
                tagDetails.virtualFile.path
            )
        )

        tagDetails.selectionModel.let {
            if (it.hasSelection()) {
                it.removeSelection()
            }
        }
    }
}

class EditorSelectionTagProcessor(
    private val tagDetails: EditorSelectionTagDetails,
) : TagProcessor {
    override fun process(message: Message, promptBuilder: StringBuilder) {
        if (tagDetails.selectedText.isNullOrEmpty()) {
            return
        }

        promptBuilder.append(
            CompletionRequestUtil.formatCode(
                tagDetails.selectedText ?: "",
                tagDetails.virtualFile.path
            )
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