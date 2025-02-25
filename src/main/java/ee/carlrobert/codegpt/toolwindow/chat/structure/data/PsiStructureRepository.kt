package ee.carlrobert.codegpt.toolwindow.chat.structure.data

import com.intellij.psi.PsiFile
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PsiStructureRepository(
    private val psiStructureProvider: PsiStructureProvider,
    private val dispatchers: CoroutineDispatchers,
) {

    private val mutex = Mutex()

    private val structureState: MutableStateFlow<PsiStructureState> = MutableStateFlow(
        PsiStructureState.Content(emptySet())
    )

    internal suspend fun update(psiFiles: List<PsiFile>) {
        mutex.withLock {
            withContext(dispatchers.io()) {
                if (structureState.value == PsiStructureState.Disabled) return@withContext
                structureState.value = PsiStructureState.UpdateInProgress

                val result = psiStructureProvider.get(psiFiles)
                structureState.update { currentState ->
                    if (currentState == PsiStructureState.Disabled) {
                        currentState
                    } else {
                        PsiStructureState.Content(result)
                    }
                }
            }
        }
    }

    internal fun disable() {
        structureState.value = PsiStructureState.Disabled
    }

    internal fun enable() {
        structureState.value = PsiStructureState.Content(emptySet())
    }

    fun getStructureState(): StateFlow<PsiStructureState> = structureState.asStateFlow()
}