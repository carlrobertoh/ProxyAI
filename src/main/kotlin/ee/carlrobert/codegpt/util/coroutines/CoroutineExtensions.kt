package ee.carlrobert.codegpt.util.coroutines

import kotlinx.coroutines.CancellationException

inline fun <T> runCatchingCancellable(action: () -> T): Result<T> {
    return try {
        Result.success(action())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}