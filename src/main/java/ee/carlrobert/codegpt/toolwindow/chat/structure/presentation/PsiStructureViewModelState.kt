package ee.carlrobert.codegpt.toolwindow.chat.structure.presentation

import com.intellij.openapi.vfs.VirtualFile

internal sealed class PsiStructureViewModelState {

    abstract val enabled: Boolean
    abstract val currentlyAnalyzedFiles: Set<VirtualFile>

    data class Content(
        val psiStructureTokens: String,
        override val enabled: Boolean,
        override val currentlyAnalyzedFiles: Set<VirtualFile>,
    ) : PsiStructureViewModelState()

    data class Progress(
        override val enabled: Boolean,
        override val currentlyAnalyzedFiles: Set<VirtualFile>,
    ) : PsiStructureViewModelState()
}