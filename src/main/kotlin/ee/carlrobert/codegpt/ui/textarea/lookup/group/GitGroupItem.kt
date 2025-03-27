package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.GitCommitActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.IncludeCurrentChangesActionItem
import ee.carlrobert.codegpt.util.GitUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.Icon

class GitGroupItem(private val project: Project) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.git.displayName")
    override val icon: Icon = Icons.VCS

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        withContext(Dispatchers.Default) {
            GitUtil.getProjectRepository(project)?.let {
                GitUtil.visitRepositoryCommits(project, it) { commit ->
                    if (commit.id.asString().contains(searchText, true)
                        || commit.fullMessage.contains(searchText, true)
                    ) {
                        runInEdt {
                            LookupUtil.addLookupItem(lookup, GitCommitActionItem(commit))
                        }
                    }
                }
            }
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return withContext(Dispatchers.Default) {
            GitUtil.getProjectRepository(project)?.let {
                val recentCommits = GitUtil.getAllRecentCommits(project, it, searchText)
                    .take(10)
                    .map { commit -> GitCommitActionItem(commit) }
                listOf(IncludeCurrentChangesActionItem()) + recentCommits
            } ?: emptyList()
        }
    }
}