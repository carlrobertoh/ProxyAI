package ee.carlrobert.codegpt.util.coroutines

import com.intellij.openapi.application.ModalityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class CoroutineDispatchers {
    fun default(): CoroutineDispatcher = Dispatchers.Default

    fun unconfined(): CoroutineDispatcher = Dispatchers.Unconfined

    fun io(): CoroutineDispatcher = Dispatchers.IO

    fun edtNonModal(): CoroutineDispatcher = EdtDispatchers.NonModal

    fun edtCurrent(): CoroutineDispatcher = EdtDispatchers.Current

    fun edtDefault(): CoroutineDispatcher = EdtDispatchers.Default
}

object EdtDispatchers {
    val NonModal = EdtCoroutineDispatcher(ModalityState.nonModal())
    val Default = EdtCoroutineDispatcher()
    val Current = EdtCoroutineDispatcher(ModalityState.current())

    fun withModalityState(modalityState: ModalityState): CoroutineDispatcher {
        return EdtCoroutineDispatcher(modalityState)
    }
}