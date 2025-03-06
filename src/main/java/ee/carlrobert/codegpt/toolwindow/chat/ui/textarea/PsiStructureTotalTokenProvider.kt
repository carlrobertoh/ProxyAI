package ee.carlrobert.codegpt.toolwindow.chat.ui.textarea

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureState
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class PsiStructureTotalTokenProvider(
    parentDisposable: Disposable,
    private val classStructureSerializer: ClassStructureSerializer,
    private val encodingManager: EncodingManager,
    dispatchers: CoroutineDispatchers,
    psiStructureRepository: PsiStructureRepository,
    onPsiTokenHandled: (Int) -> Unit
) {

    private val coroutineScope = DisposableCoroutineScope()

    init {
        Disposer.register(parentDisposable, coroutineScope)
        psiStructureRepository.structureState
            .map { structureState ->
                when (structureState) {
                    is PsiStructureState.Content -> {
                        getPsiTokensCount(structureState.elements)
                    }

                    PsiStructureState.Disabled -> 0

                    is PsiStructureState.UpdateInProgress -> 0
                }
            }
            .flowOn(dispatchers.io())
            .onEach { psiTokens ->
                onPsiTokenHandled(psiTokens)
            }
            .launchIn(coroutineScope)
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