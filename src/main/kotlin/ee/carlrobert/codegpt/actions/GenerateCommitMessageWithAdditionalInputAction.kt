package ee.carlrobert.codegpt.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.vcs.commit.CommitWorkflowUi
import ee.carlrobert.codegpt.completions.CommitMessageCompletionParameters
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.settings.prompts.CommitMessageTemplate

class GenerateCommitMessageWithAdditionalInputAction : BaseCommitWorkflowAction() {

    override fun getTitle(commitWorkflowUi: CommitWorkflowUi): String {
        return "Generate Message with Additional Input"
    }

    override fun performAction(
        project: Project,
        commitWorkflowUi: CommitWorkflowUi,
        gitDiff: String
    ) {
        val userInput = Messages.showMultilineInputDialog(
            project,
            "Enter additional input for the commit message:",
            "Additional Input",
            commitWorkflowUi.commitMessageUi.text,
            Messages.getQuestionIcon(),
            null
        )
        if (userInput != null) {
            val systemPrompt =
                project.getService(CommitMessageTemplate::class.java).getSystemPrompt()
            CompletionRequestService.getInstance().getCommitMessageAsync(
                CommitMessageCompletionParameters(
                    gitDiff,
                    "$systemPrompt\n\nUser input: $userInput"
                ),
                CommitMessageEventListener(project, commitWorkflowUi)
            )
        }
    }
}