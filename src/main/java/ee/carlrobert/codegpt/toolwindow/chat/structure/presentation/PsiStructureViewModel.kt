package ee.carlrobert.codegpt.toolwindow.chat.structure.presentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureState
import ee.carlrobert.codegpt.ui.textarea.header.tag.CurrentGitChangesTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.DocumentationTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorSelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EmptyTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FolderTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.GitCommitTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.PersonaTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.SelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManagerListener
import ee.carlrobert.codegpt.ui.textarea.header.tag.WebTagDetails
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal class PsiStructureViewModel(
    parentDisposable: Disposable,
    private val psiStructureRepository: PsiStructureRepository,
    private val tagManager: TagManager,
    private val encodingManager: EncodingManager,
    private val projectLocator: ProjectLocator,
    private val classStructureSerializer: ClassStructureSerializer,
    private val dispatchers: CoroutineDispatchers,
) {

    private val coroutineScope = DisposableCoroutineScope()
    private val _state: MutableStateFlow<PsiStructureViewModelState> = MutableStateFlow(
        PsiStructureViewModelState.Progress(
            true,
            tagManager.getTags().toVirtualFilesSet()
        )
    )
    val state: StateFlow<PsiStructureViewModelState> = _state.asStateFlow()

    private var updatePsiStructureJob: Job? = null

    private val tagsListener = object : TagManagerListener {
        override fun onTagAdded(tag: TagDetails) {
            val virtualFiles = tagManager.getTags().toVirtualFilesSet()
            if (isNeedUpdatePsiStructure(virtualFiles)) {
                updatePsiStructure(virtualFiles)
            }
        }

        override fun onTagRemoved(tag: TagDetails) {
            val virtualFiles = tagManager.getTags().toVirtualFilesSet()
            if (isNeedUpdatePsiStructure(virtualFiles)) {
                updatePsiStructure(virtualFiles)
            }
        }

        override fun onTagSelectionChanged(tag: TagDetails) {
            val virtualFiles = tagManager.getTags().toVirtualFilesSet()
            if (isNeedUpdatePsiStructure(virtualFiles)) {
                updatePsiStructure(virtualFiles)
            }
        }

        private fun isNeedUpdatePsiStructure(virtualFiles: Set<VirtualFile>): Boolean {
            val currentlyAnalyzedFiles = state.value.currentlyAnalyzedFiles
            return virtualFiles != currentlyAnalyzedFiles
        }
    }

    private val asyncFileListener = AsyncFileListener { events ->
        val currentFiles = state.value.currentlyAnalyzedFiles
        val hasRelevantChanges = events.any { event ->
            event.file?.let { it in currentFiles } == true
        }

        if (hasRelevantChanges) {
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    updatePsiStructure(currentFiles)
                }
            }
        } else {
            null
        }
    }

    init {
        Disposer.register(parentDisposable, coroutineScope)
        tagManager.addListener(tagsListener)
        VirtualFileManager.getInstance().addAsyncFileListener(asyncFileListener, parentDisposable)
        updatePsiStructure(_state.value.currentlyAnalyzedFiles)
    }

    fun disablePsiAnalyzer() {
        psiStructureRepository.disable()
        _state.update { currentState ->
            when (currentState) {
                is PsiStructureViewModelState.Content -> currentState.copy(enabled = false)
                is PsiStructureViewModelState.Progress -> currentState.copy(enabled = false)
            }
        }
    }

    fun enablePsiAnalyzer() {
        psiStructureRepository.enable()
        _state.update { currentState ->
            when (currentState) {
                is PsiStructureViewModelState.Content -> currentState.copy(enabled = true)
                is PsiStructureViewModelState.Progress -> currentState.copy(enabled = true)
            }
        }
    }

    private fun updatePsiStructure(virtualFiles: Set<VirtualFile>) {
        updatePsiStructureJob?.cancel()
        updatePsiStructureJob = coroutineScope.launch {
            _state.update { currentState ->
                PsiStructureViewModelState.Progress(currentState.enabled, virtualFiles)
            }
            withContext(dispatchers.io()) {
                val psiFiles = virtualFiles
                    .mapNotNull { virtualFile ->
                        yield()
                        projectLocator.getProjectsForFile(virtualFile)
                            .firstOrNull { it?.isDisposed == false }
                            ?.let { project ->
                                PsiManager.getInstance(project).findFile(virtualFile)
                            }
                    }

                psiStructureRepository.update(psiFiles)
                when (val psiStructureState = psiStructureRepository.getStructureState().value) {
                    is PsiStructureState.Content -> {
                        val psiTokens = psiStructureState
                            .elements
                            .joinToString(separator = "\n\n") { psiStructure ->
                                classStructureSerializer.serialize(psiStructure)
                            }
                            .let { serializedPsiStructure ->
                                encodingManager.countTokens(serializedPsiStructure)
                            }
                        _state.update { currentState ->
                            yield()
                            PsiStructureViewModelState.Content(psiTokens.toString(), currentState.enabled, virtualFiles)
                        }
                    }

                    PsiStructureState.Disabled -> _state.update { _ ->
                        PsiStructureViewModelState.Content("", false, virtualFiles)
                    }

                    PsiStructureState.UpdateInProgress -> _state.update { currentState ->
                        PsiStructureViewModelState.Progress(currentState.enabled, virtualFiles)
                    }
                }
            }
        }
    }

    private fun Set<TagDetails>.toVirtualFilesSet(): Set<VirtualFile> =
        mapNotNull { tagDetails ->
            when (tagDetails) {
                is SelectionTagDetails -> tagDetails.virtualFile
                is FileTagDetails -> tagDetails.virtualFile
                is EditorSelectionTagDetails -> tagDetails.virtualFile
                is EditorTagDetails -> tagDetails.virtualFile

                // Maybe need recursive find all files
                is FolderTagDetails -> null

                is DocumentationTagDetails -> null
                is CurrentGitChangesTagDetails -> null
                is GitCommitTagDetails -> null
                is PersonaTagDetails -> null
                is EmptyTagDetails -> null
                is WebTagDetails -> null
            }
        }
            .toSet()

}