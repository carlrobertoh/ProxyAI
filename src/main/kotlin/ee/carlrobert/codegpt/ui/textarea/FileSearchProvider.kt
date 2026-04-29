package ee.carlrobert.codegpt.ui.textarea

import com.intellij.ide.util.gotoByName.*
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FileSearchSource {
    NATIVE,
    OPEN,
    RECENT
}

data class FileSearchCandidate(
    val file: VirtualFile,
    val source: FileSearchSource
)

internal fun VirtualFile.isHiddenFileOrInHiddenDirectory(): Boolean {
    var current: VirtualFile? = this
    while (current != null) {
        if (current.name.startsWith(".") || current.`is`(VFileProperty.HIDDEN)) {
            return true
        }
        current = current.parent
    }
    return false
}

interface FileSearchProvider {
    suspend fun search(searchText: String, limit: Int): List<FileSearchCandidate>
}

class NativeFileSearchProvider(private val project: Project) : FileSearchProvider {
    override suspend fun search(
        searchText: String,
        limit: Int
    ): List<FileSearchCandidate> {
        val normalizedSearchText = searchText.trim()
        if (normalizedSearchText.isEmpty()) {
            return emptyList()
        }

        return withContext(Dispatchers.Default) {
            readAction {
                val model = GotoFileModel(project)
                val provider = model.getItemProvider(null)
                val viewModel = object : ChooseByNameViewModel {
                    override fun getProject(): Project = project
                    override fun getModel() = model
                    override fun isSearchInAnyPlace(): Boolean = false
                    override fun transformPattern(pattern: String): String {
                        return ChooseByNamePopup.getTransformedPattern(pattern, model)
                    }

                    override fun canShowListForEmptyPattern(): Boolean = false
                    override fun getMaximumListSizeLimit(): Int = limit
                }
                val searchScope = GlobalSearchScope.projectScope(project)
                val parameters = FindSymbolParameters.wrap(normalizedSearchText, searchScope)
                val progressIndicator = EmptyProgressIndicator()
                val seenPaths = LinkedHashSet<String>()
                val matches = mutableListOf<FileSearchCandidate>()

                fun appendCandidate(file: VirtualFile): Boolean {
                    if (!seenPaths.add(file.path)) {
                        return true
                    }
                    matches.add(
                        FileSearchCandidate(
                            file = file,
                            source = FileSearchSource.NATIVE
                        )
                    )
                    return matches.size < limit
                }

                when (provider) {
                    is ChooseByNameInScopeItemProvider -> {
                        provider.filterElementsWithWeights(
                            viewModel,
                            parameters,
                            progressIndicator,
                            Processor { descriptor ->
                                ProgressManager.checkCanceled()
                                val file = descriptor.item.toVirtualFile() ?: return@Processor true
                                appendCandidate(file)
                            }
                        )
                    }

                    is ChooseByNameWeightedItemProvider -> {
                        provider.filterElementsWithWeights(
                            viewModel,
                            normalizedSearchText,
                            false,
                            progressIndicator,
                            Processor { descriptor ->
                                ProgressManager.checkCanceled()
                                val file = descriptor.item.toVirtualFile() ?: return@Processor true
                                appendCandidate(file)
                            }
                        )
                    }

                    else -> {
                        provider.filterElements(
                            viewModel,
                            normalizedSearchText,
                            false,
                            progressIndicator,
                            Processor { item ->
                                ProgressManager.checkCanceled()
                                val file = item.toVirtualFile() ?: return@Processor true
                                appendCandidate(file)
                            }
                        )
                    }
                }

                matches
            }
        }
    }
}

private fun Any?.toVirtualFile(): VirtualFile? {
    return when (this) {
        is VirtualFile -> this
        is PsiFileSystemItem -> virtualFile
        else -> null
    }
}
