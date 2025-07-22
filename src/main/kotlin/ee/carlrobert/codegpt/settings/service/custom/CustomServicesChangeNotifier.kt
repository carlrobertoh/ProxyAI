package ee.carlrobert.codegpt.settings.service.custom

import com.intellij.openapi.diagnostic.thisLogger

object CustomServicesChangeNotifier {
    private val logger = thisLogger()
    private val listeners = mutableListOf<() -> Unit>()
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
    
    fun notifyServicesChanged() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logger.error("Error notifying listener", e)
            }
        }
    }
}