package ee.carlrobert.codegpt.toolwindow.chat.structure.presentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureState
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal class PsiStructureViewModel(
    parentDisposable: Disposable,
    private val psiStructureRepository: PsiStructureRepository,
    private val encodingManager: EncodingManager,
    private val classStructureSerializer: ClassStructureSerializer,
) {

    private val coroutineScope = DisposableCoroutineScope()
    private val _state: MutableStateFlow<PsiStructureViewModelState> = MutableStateFlow(
        PsiStructureViewModelState.Progress(true)
    )
    val state: StateFlow<PsiStructureViewModelState> = _state.asStateFlow()

    init {
        Disposer.register(parentDisposable, coroutineScope)
        psiStructureRepository.structureState
            .onEach { structureState ->
                _state.value = when (structureState) {
                    is PsiStructureState.Content -> PsiStructureViewModelState.Content(
                        getPsiTokensCount(structureState.elements),
                        true
                    )

                    PsiStructureState.Disabled -> PsiStructureViewModelState.Content(0, false)
                    is PsiStructureState.UpdateInProgress -> PsiStructureViewModelState.Progress(true)
                }
            }
            .launchIn(coroutineScope)
    }

    fun disablePsiAnalyzer() {
        psiStructureRepository.disable()
    }

    fun enablePsiAnalyzer() {
        psiStructureRepository.enable()
    }

    private fun getPsiTokensCount(psiStructureSet: Set<ClassStructure>): Int =
        psiStructureSet
            .joinToString(separator = "\n\n") { psiStructure ->
                classStructureSerializer.serialize(psiStructure)
            }
            .let { serializedPsiStructure ->
                encodingManager.countTokens(serializedPsiStructure)
            }
}
