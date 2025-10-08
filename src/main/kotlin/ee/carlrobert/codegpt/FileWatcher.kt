package ee.carlrobert.codegpt

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchService
import kotlin.concurrent.thread
import kotlin.io.path.exists

@Service(Service.Level.PROJECT)
class FileWatcher : Disposable {

    private val watchServices = mutableListOf<WatchService>()
    private val fileMonitors = mutableListOf<Thread>()
    private val logger = thisLogger()

    fun watchMultiplePaths(pathsToWatch: List<String>, onFileCreated: (Path, String) -> Unit) {
        dispose()

        pathsToWatch.forEach { pathString ->
            try {
                val path = Paths.get(pathString)
                if (path.exists()) {
                    val watchService = FileSystems.getDefault().newWatchService()
                    path.register(watchService, ENTRY_CREATE)
                    watchServices.add(watchService)
                    logger.info("Successfully registered watch service for path: $pathString (absolute: ${path.toAbsolutePath()})")

                    val monitor = thread {
                        try {
                            logger.info("File watch monitor thread started for path: $pathString")
                            generateSequence { watchService.take() }.forEach { key ->
                                logger.info("Watch event received for path: $pathString")
                                key.pollEvents().forEach { event ->
                                    val fileName = event.context() as Path
                                    val fullPath = path.resolve(fileName)
                                    logger.info("File event detected: ${event.kind()} - fileName=$fileName, fullPath=$fullPath")
                                    onFileCreated(fileName, pathString)
                                }
                                val resetResult = key.reset()
                                if (!resetResult) {
                                    logger.info("Watch key reset failed for path: $pathString - watch may have become invalid")
                                }
                            }
                        } catch (e: InterruptedException) {
                            logger.error("File watch monitor thread interrupted for path: $pathString", e)
                            Thread.currentThread().interrupt()
                        } catch (e: Exception) {
                            logger.error("Error in file watcher for path: $pathString", e)
                        }
                    }
                    fileMonitors.add(monitor)

                    logger.info("Started watching path: $pathString")
                } else {
                    logger.warn("Path does not exist or is not accessible: $pathString")
                }
            } catch (e: Exception) {
                logger.warn("Failed to set up watcher for path: $pathString", e)
            }
        }
    }

    override fun dispose() {
        logger.debug("Disposing FileWatcher - stopping ${fileMonitors.size} monitor threads and ${watchServices.size} watch services")
        fileMonitors.forEach { it.interrupt() }
        fileMonitors.clear()

        watchServices.forEach { watchService ->
            try {
                watchService.close()
            } catch (e: Exception) {
                logger.warn("Error closing watch service", e)
            }
        }
        watchServices.clear()
        logger.debug("FileWatcher disposal completed")
    }
}
