package ee.carlrobert.codegpt.util.coroutines

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

class EdtCoroutineDispatcher(
    private val modalityState: ModalityState = ModalityState.defaultModalityState()
) : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val app = ApplicationManager.getApplication()

        if (app.isDispatchThread) {
            block.run()
        } else {
            app.invokeLater({ block.run() }, modalityState)
        }
    }
}