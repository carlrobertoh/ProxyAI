package ee.carlrobert.codegpt.completions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AsyncRequestContext(
    private val onCancel: () -> Unit = {}
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cancelled = AtomicBoolean(false)
    private val jobRef = AtomicReference<Job?>()

    val cancellableRequest = CancellableRequest {
        cancelled.set(true)
        onCancel()
        jobRef.get()?.cancel(CancellationException("Cancelled by user"))
    }

    fun attach(job: Job) {
        jobRef.set(job)
    }

    fun isCancelled(): Boolean = cancelled.get()
}
