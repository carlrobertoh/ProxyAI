package ee.carlrobert.codegpt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.GitCommitTagDetails
import ee.carlrobert.codegpt.util.GitUtil
import git4idea.GitCommit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExplainGitCommitAction : AnAction(
    CodeGPTBundle.get("action.explainGitCommit.title"),
    CodeGPTBundle.get("action.explainGitCommit.description"),
    Icons.DefaultSmall
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        scope.launch {
            val gitCommits =
                getCommitsForRevisions(project, e.getData(VcsDataKeys.VCS_REVISION_NUMBERS))
                    .map { GitCommitTagDetails(it.id.asString(), it.fullMessage) }

            project.service<ChatToolWindowContentManager>().apply {
                runInEdt {
                    displayChatTab()
                    tryFindActiveChatTabPanel()
                        .ifPresent {
                            it.addCommitReferences(gitCommits)
                        }
                }
            }
        }
    }

    private fun getCommitsForRevisions(
        project: Project,
        revisionNumbers: Array<VcsRevisionNumber>?
    ): List<GitCommit> {
        if (revisionNumbers == null) {
            throw IllegalArgumentException("No commit revisions found")
        }

        val gitCommits = GitUtil.getProjectRepository(project)?.let { repository ->
            GitUtil.getCommitsForHashes(
                project,
                repository,
                revisionNumbers.map { it.asString() })
        } ?: throw IllegalStateException("Unable to find git repository")

        if (gitCommits.isEmpty()) {
            throw IllegalStateException(
                "Unable to find commits for given revisions: ${
                    revisionNumbers.joinToString(",") { it.asString() }
                }"
            )
        }

        return gitCommits
    }
}
