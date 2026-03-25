package ee.carlrobert.codegpt.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitWorkflowUi
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.completions.CompletionService
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.CommitWorkflowChanges
import ee.carlrobert.codegpt.util.GitUtil.getProjectRepository
import ee.carlrobert.codegpt.util.ThinkingOutputParser
import git4idea.repo.GitRepository
import java.io.StringWriter
import java.nio.file.Path

abstract class BaseCommitWorkflowAction : DumbAwareAction() {

    companion object {
        const val MAX_TOKEN_COUNT_WARNING: Int = 16392
    }

    abstract fun getTitle(commitWorkflowUi: CommitWorkflowUi): String

    abstract fun performAction(
        project: Project,
        commitWorkflowUi: CommitWorkflowUi,
        gitDiff: String
    )

    override fun update(event: AnActionEvent) {
        val commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val requestAllowed = CompletionService.isRequestAllowed(FeatureType.COMMIT_MESSAGE)
        runInEdt {
            event.presentation.isEnabled =
                requestAllowed && CommitWorkflowChanges(commitWorkflowUi).isFilesSelected
            event.presentation.text = getTitle(commitWorkflowUi)
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        val commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val includedChanges = commitWorkflowUi.getIncludedChanges()

        object : Task.Backgroundable(project, "Preparing Commit Diff", true) {
            private var gitDiff: String? = null
            private var tokenCount: Int = 0

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Generating diff for selected changes"
                gitDiff = getDiff(project, includedChanges)
                tokenCount = service<EncodingManager>().countTokens(gitDiff.orEmpty())
            }

            override fun onSuccess() {
                val diff = gitDiff ?: return
                if (tokenCount > MAX_TOKEN_COUNT_WARNING
                    && OverlayUtil.showTokenSoftLimitWarningDialog(tokenCount) != Messages.OK
                ) {
                    return
                }

                performAction(project, commitWorkflowUi, diff)
            }

            override fun onThrowable(error: Throwable) {
                Notifications.Bus.notify(
                    Notification(
                        "proxyai.notification.group",
                        "ProxyAI",
                        error.message ?: "Unable to create git diff",
                        NotificationType.ERROR
                    ),
                    project
                )
            }
        }.queue()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    private fun getDiff(project: Project, includedChanges: List<Change>): String {
        return generateDiff(
            project,
            includedChanges,
            getRepository(project).root.toNioPath()
        )
    }

    private fun getRepository(project: Project): GitRepository {
        return getProjectRepository(project)
            ?: throw IllegalStateException("No repository found for the project.")
    }

    private fun generateDiff(
        project: Project,
        includedChanges: List<Change>,
        repositoryPath: Path
    ): String = runCatching {
        val filePatches = IdeaTextPatchBuilder.buildPatch(
            project,
            includedChanges,
            repositoryPath,
            false,
            true
        )

        StringWriter().apply {
            UnifiedDiffWriter.write(
                null,
                repositoryPath,
                filePatches,
                this,
                "\n",
                null,
                null
            )
        }.toString()
    }.getOrElse { e ->
        throw RuntimeException("Unable to create git diff", e)
    }
}

class CommitMessageEventListener(
    private val project: Project,
    private val commitWorkflowUi: CommitWorkflowUi
) : CompletionStreamEventListener {

    private val messageBuilder = StringBuilder()
    private val thinkingOutputParser = ThinkingOutputParser()

    override fun onOpen() {
        withCommitMessageUi {
            it.startLoading()
        }
    }

    override fun onMessage(message: String) {
        val processedChunk = thinkingOutputParser.processChunk(message)
        if (processedChunk.isNotEmpty()) {
            messageBuilder.append(processedChunk)
            updateCommitMessage(messageBuilder.toString())
        }
    }

    override fun onComplete(messageBuilder: StringBuilder) {
        if (this.messageBuilder.isEmpty() && messageBuilder.isNotEmpty()) {
            val processedMessage = ThinkingOutputParser().processChunk(messageBuilder.toString())
            if (processedMessage.isNotEmpty()) {
                this.messageBuilder.append(processedMessage)
            }
        }

        if (this.messageBuilder.isNotEmpty()) {
            updateCommitMessage(this.messageBuilder.toString())
        }
        stopLoading()
    }

    override fun onError(error: CompletionError, ex: Throwable) {
        Notifications.Bus.notify(
            Notification(
                "proxyai.notification.group",
                "ProxyAI",
                error.message,
                NotificationType.ERROR
            )
        )
        stopLoading()
    }

    override fun onCancelled(messageBuilder: StringBuilder) {
        stopLoading()
    }

    private fun stopLoading() {
        withCommitMessageUi {
            it.stopLoading()
            CompletionProgressNotifier.update(project, false)
        }
    }

    private fun updateCommitMessage(message: String?) {
        withCommitMessageUi {
            WriteCommandAction.runWriteCommandAction(project) {
                it.setText(message)
            }
        }
    }

    private fun withCommitMessageUi(action: (com.intellij.vcs.commit.CommitMessageUi) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            action(commitWorkflowUi.commitMessageUi)
        }
    }
}
