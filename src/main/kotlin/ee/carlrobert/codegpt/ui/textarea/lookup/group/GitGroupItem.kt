package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.GitCommitActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.IncludeCurrentChangesActionItem
import ee.carlrobert.codegpt.util.GitUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.Icon

class GitGroupItem(private val project: Project) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.git.displayName")
    override val icon: Icon = Icons.VCS

    private var allAvailableItems: List<LookupItem> = emptyList()

    override suspend fun updateLookupItems(searchText: String): List<LookupItem> {
        return withContext(Dispatchers.Default) {
            val items = mutableListOf<LookupItem>()
            GitUtil.getProjectRepository(project)?.let { repository ->
                GitUtil.visitRepositoryCommits(project, repository) { commit ->
                    if (commit.id.asString().contains(searchText, true)
                        || commit.fullMessage.contains(searchText, true)
                    ) {
                        items.add(GitCommitActionItem(commit))
                    }
                    items.size < 50 // Ограничиваем количество результатов
                }
            }
            items
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return withContext(Dispatchers.Default) {
            GitUtil.getProjectRepository(project)?.let { repository ->
                val recentCommits = if (searchText.isEmpty()) {
                    GitUtil.getAllRecentCommits(project, repository, "")
                        .take(10)
                        .map { commit -> GitCommitActionItem(commit) }
                } else {
                    GitUtil.getAllRecentCommits(project, repository, searchText)
                        .take(10)
                        .map { commit -> GitCommitActionItem(commit) }
                }
                listOf(IncludeCurrentChangesActionItem()) + recentCommits
            } ?: emptyList()
        }
    }
}