package ee.carlrobert.codegpt.toolwindow.chat.structure.presentation

internal sealed class PsiStructureViewModelState {

    abstract val enabled: Boolean

    data class Content(
        val psiStructureTokens: Int,
        override val enabled: Boolean,
    ) : PsiStructureViewModelState()

    data class Progress(
        override val enabled: Boolean,
    ) : PsiStructureViewModelState()
}