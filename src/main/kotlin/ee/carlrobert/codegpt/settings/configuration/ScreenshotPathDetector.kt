package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

object ScreenshotPathDetector {

    private val logger = thisLogger()

    fun getDefaultPaths(): List<String> {
        return when {
            SystemInfo.isWindows -> getWindowsDefaultPaths()
            SystemInfo.isMac -> getMacDefaultPaths()
            SystemInfo.isLinux -> getLinuxDefaultPaths()
            else -> listOf(getDesktopPath())
        }
    }

    fun isValidWatchPath(pathString: String): Boolean {
        return try {
            val path = Paths.get(pathString)
            path.exists() && path.toFile().canRead()
        } catch (e: Exception) {
            logger.warn("Error validating watch path: $pathString", e)
            false
        }
    }

    fun getDefaultFileExtensions(): List<String> {
        return listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    }

    private fun getWindowsDefaultPaths(): List<String> {
        val paths = mutableListOf<String>()
        paths.add(getDesktopPath())

        val screenshotsPath = Paths.get(System.getProperty("user.home"), "Pictures", "Screenshots")
        if (screenshotsPath.exists()) {
            paths.add(screenshotsPath.toString())
        }

        return paths
    }

    private fun getMacDefaultPaths(): List<String> {
        val paths = mutableListOf<String>()
        paths.add(getDesktopPath())

        val picturesPath = Paths.get(System.getProperty("user.home"), "Pictures")
        if (picturesPath.exists()) {
            paths.add(picturesPath.toString())
        }

        return paths
    }

    private fun getLinuxDefaultPaths(): List<String> {
        val paths = mutableListOf<String>()
        paths.add(getDesktopPath())

        val picturesPath = getXdgPicturesPath()
        if (picturesPath.exists()) {
            paths.add(picturesPath.toString())
        }

        val screenshotsPath = picturesPath.resolve("Screenshots")
        if (screenshotsPath.exists()) {
            paths.add(screenshotsPath.toString())
        }

        return paths
    }

    private fun getDesktopPath(): String {
        return Paths.get(System.getProperty("user.home"), "Desktop").toString()
    }

    private fun getXdgPicturesPath(): Path {
        val xdgPictures = System.getenv("XDG_PICTURES_DIR")
        return if (xdgPictures != null) {
            Paths.get(xdgPictures)
        } else {
            Paths.get(System.getProperty("user.home"), "Pictures")
        }
    }
}