package ee.carlrobert.codegpt.util

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ChangeListManager
import ee.carlrobert.codegpt.codecompletions.truncateText
import git4idea.GitCommit
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.StringWriter

object GitUtil {

    private val logger = thisLogger()

    @Throws(VcsException::class)
    @JvmStatic
    fun getProjectRepository(project: Project): GitRepository? {
        val repositoryManager = project.service<GitRepositoryManager>()
        return repositoryManager.getRepositoryForFile(project.guessProjectDir())
            ?: repositoryManager.repositories.firstOrNull()
    }

    fun getCurrentChanges(project: Project): String? {
        return getProjectRepository(project)?.let { repository ->
            try {
                val repoRootPath = repository.root.toNioPath()
                val changes = ChangeListManager.getInstance(project).allChanges
                    .filter { change ->
                        change.virtualFile?.let { !it.fileType.isBinary } ?: false
                    }
                    .sortedBy { it.virtualFile?.timeStamp }

                val patches = IdeaTextPatchBuilder.buildPatch(
                    project, changes, repoRootPath, false, true
                )
                val diffWriter = StringWriter()
                UnifiedDiffWriter.write(
                    null, repoRootPath, patches, diffWriter, "\n\n", null, null
                )
                diffWriter.toString().cleanDiff().truncateText(1024, false)
            } catch (e: VcsException) {
                logger.error("Failed to get git context", e)
                null
            }
        }
    }

    @Throws(VcsException::class)
    fun getCommitsForHashes(
        project: Project,
        repository: GitRepository,
        commitHashes: List<String>
    ): List<GitCommit> {
        val result = mutableListOf<GitCommit>()

        GitHistoryUtils
            .loadDetails(project, repository.root, { commit ->
                if (commitHashes.contains(commit.id.asString())) {
                    result.add(commit)
                }
            })

        return result
    }

    @Throws(VcsException::class)
    fun getCommitDiffs(
        project: Project,
        gitRepository: GitRepository,
        commitHash: String
    ): List<String> {
        val handler = GitLineHandler(project, gitRepository.root, GitCommand.SHOW)
        handler.addParameters(
            commitHash,
            "--unified=2",
            "--no-prefix",
            "--no-color"
        )

        val commandResult = Git.getInstance().runCommand(handler)
        return filterDiffOutput(commandResult.output)
    }

    @Throws(VcsException::class)
    fun visitRepositoryCommits(
        project: Project,
        repository: GitRepository,
        onVisit: (GitCommit) -> Unit
    ) {
        try {
            GitHistoryUtils.loadDetails(project, repository.root, { onVisit(it) })
        } catch (e: VcsException) {
            logger.error("Error fetching commit history: {}", e.message)
        }
    }

    @Throws(VcsException::class)
    fun getAllRecentCommits(
        project: Project,
        repository: GitRepository,
        searchText: String? = "",
        limit: Int = 250
    ): List<GitCommit> {
        val result = mutableListOf<GitCommit>()

        try {
            GitHistoryUtils
                .loadDetails(project, repository.root, { commit ->
                    if (searchText.isNullOrEmpty()) {
                        result.add(commit)
                    } else {
                        if (commit.id.asString().contains(searchText, true)
                            || commit.fullMessage.contains(searchText, true)
                        ) {
                            result.add(commit)
                        }
                    }
                }, "-n", "$limit")
        } catch (e: VcsException) {
            logger.error("Error fetching commit history: {}", e.message)
        }

        return result
    }

    private fun filterDiffOutput(output: List<String>): List<String> {
        return output.filter {
            !it.startsWith("diff --git") &&
                    !it.startsWith("index ") &&
                    !it.startsWith("---") &&
                    !it.startsWith("+++") &&
                    !it.startsWith("- ") &&
                    !it.startsWith("commit ")
        }
    }

    private fun String.cleanDiff(showContext: Boolean = false): String =
        lineSequence()
            .filterNot { line ->
                line.startsWith("index ") ||
                        line.startsWith("diff --git") ||
                        line.startsWith("---") ||
                        line.startsWith("+++") ||
                        line.startsWith("===") ||
                        line.contains("\\ No newline at end of file")
                        (!showContext && line.startsWith(" "))
            }
            .joinToString("\n")
}
