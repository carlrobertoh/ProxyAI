package ee.carlrobert.codegpt.util.coroutines

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal class DisposableCoroutineScope(
    scopeDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : Disposable, CoroutineScope {

    private val coroutineScope = CoroutineScope(SupervisorJob() + scopeDispatcher)

    fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        coroutineScope.launch { block() }


    override fun dispose() {
        coroutineScope.cancel()
    }

    override val coroutineContext: CoroutineContext
        get() = coroutineScope.coroutineContext
}