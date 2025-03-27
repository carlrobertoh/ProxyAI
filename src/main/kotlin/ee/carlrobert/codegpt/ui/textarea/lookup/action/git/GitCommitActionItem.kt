package ee.carlrobert.codegpt.ui.textarea.lookup.action.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.GitCommitTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem
import git4idea.GitCommit

class GitCommitActionItem(
    private val gitCommit: GitCommit,
) : AbstractLookupActionItem() {

    val description: String = gitCommit.id.asString().take(6)

    override val displayName: String = gitCommit.subject
    override val icon = AllIcons.Vcs.CommitNode

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(GitCommitTagDetails(gitCommit))
    }
}