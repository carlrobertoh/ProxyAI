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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitWorkflowUi
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.util.ThinkingOutputParser
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.CommitWorkflowChanges
import ee.carlrobert.codegpt.util.GitUtil.getProjectRepository
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import git4idea.repo.GitRepository
import okhttp3.sse.EventSource
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
        val requestAllowed = CompletionRequestService.isRequestAllowed()
        runInEdt {
            event.presentation.isEnabled =
                requestAllowed && CommitWorkflowChanges(commitWorkflowUi).isFilesSelected
            event.presentation.text = getTitle(commitWorkflowUi)
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return

        val gitDiff: String = getDiff(event, project)
        val tokenCount: Int = service<EncodingManager>().countTokens(gitDiff)
        if (tokenCount > MAX_TOKEN_COUNT_WARNING
            && OverlayUtil.showTokenSoftLimitWarningDialog(tokenCount) != Messages.OK
        ) {
            return
        }
        val commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        performAction(project, commitWorkflowUi, gitDiff)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    private fun getDiff(event: AnActionEvent, project: Project): String {
        val commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
            ?: throw IllegalStateException("Could not retrieve commit workflow ui.")

        return generateDiff(
            project,
            commitWorkflowUi.getIncludedChanges(),
            getRepository(project).root.toNioPath()
        )
    }

    private fun getRepository(project: Project): GitRepository {
        return runCatching {
            ApplicationManager.getApplication()
                .executeOnPooledThread<GitRepository?> { getProjectRepository(project) }
                .get()
        }.getOrNull() ?: throw IllegalStateException("No repository found for the project.")
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
) : CompletionEventListener<String> {

    private val messageBuilder = StringBuilder()
    private val thinkingOutputParser = ThinkingOutputParser()

    override fun onMessage(message: String, eventSource: EventSource) {
        val processedChunk = thinkingOutputParser.processChunk(message)
        if (processedChunk.isNotEmpty() && thinkingOutputParser.isFinished) {
            messageBuilder.append(message)
            updateCommitMessage(messageBuilder.toString())
        }
    }

    override fun onComplete(result: StringBuilder) {
        if (messageBuilder.isEmpty()) {
            updateCommitMessage(result.toString())
        }
        stopLoading()
    }

    override fun onError(error: ErrorDetails, ex: Throwable) {
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

    private fun stopLoading() {
        CompletionProgressNotifier.update(project, false)
    }

    private fun updateCommitMessage(message: String?) {
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                commitWorkflowUi.commitMessageUi.setText(message)
            }
        }
    }
}