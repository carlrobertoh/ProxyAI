package ee.carlrobert.codegpt.util.coroutines

import com.intellij.openapi.application.ModalityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

suspend fun <T> withEdt(
    modalityState: ModalityState = ModalityState.defaultModalityState(),
    block: suspend CoroutineScope.() -> T,
): T {
    return withContext(EdtDispatchers.withModalityState(modalityState), block)
}

suspend fun <T> withCurrentEdt(block: suspend CoroutineScope.() -> T): T {
    return withContext(EdtDispatchers.Current, block)
}
