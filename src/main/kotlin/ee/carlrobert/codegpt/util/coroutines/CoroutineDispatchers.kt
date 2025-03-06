package ee.carlrobert.codegpt.util.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher

class CoroutineDispatchers {
    fun default(): CoroutineDispatcher = Dispatchers.Default

    fun main(): MainCoroutineDispatcher = Dispatchers.Main

    fun unconfined(): CoroutineDispatcher = Dispatchers.Unconfined

    fun io(): CoroutineDispatcher = Dispatchers.IO
}
