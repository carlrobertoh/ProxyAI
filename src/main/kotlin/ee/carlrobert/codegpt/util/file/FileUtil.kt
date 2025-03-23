package ee.carlrobert.codegpt.util.file

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil.createDirectory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings.getLlamaModelsPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.DecimalFormat
import java.util.*
import java.util.regex.Pattern

object FileUtil {

    private val logger = thisLogger()

    @JvmStatic
    fun readContent(file: File): String {
        try {
            return String(Files.readAllBytes(Paths.get(file.path)))
        } catch (e: IOException) {
            logger.error("Failed to read file content", e)
            return ""
        }
    }

    @JvmStatic
    fun readContent(virtualFile: VirtualFile): String {
        try {
            return VfsUtilCore.loadText(virtualFile)
        } catch (e: IOException) {
            logger.error("Failed to read virtual file content", e)
            return ""
        }
    }

    @JvmStatic
    fun createFile(directoryPath: Any, fileName: String?, fileContent: String?): File {
        requireNotNull(fileContent) { "fileContent null" }
        require(!fileName.isNullOrBlank()) { "fileName null or blank" }
        val path = when (directoryPath) {
            is Path -> directoryPath
            is File -> directoryPath.toPath()
            is String -> Path.of(directoryPath)
            else -> throw IllegalArgumentException("directoryPath must be Path, File or String: $directoryPath")
        }
        try {
            tryCreateDirectory(path)
            return Files.writeString(
                path.resolve(fileName),
                fileContent,
                StandardOpenOption.CREATE
            ).toFile()
        } catch (e: IOException) {
            throw RuntimeException("Failed to create file", e)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFileWithProgress(
        fileName: String,
        url: URL,
        bytesRead: LongArray,
        fileSize: Long,
        indicator: ProgressIndicator
    ) {
        tryCreateDirectory(getLlamaModelsPath())

        Channels.newChannel(url.openStream()).use { readableByteChannel ->
            FileOutputStream(
                getLlamaModelsPath().resolve(fileName).toFile()
            ).use { fileOutputStream ->
                val buffer = ByteBuffer.allocateDirect(1024 * 10)
                while (readableByteChannel.read(buffer) != -1) {
                    if (indicator.isCanceled) {
                        readableByteChannel.close()
                        break
                    }
                    buffer.flip()
                    bytesRead[0] += fileOutputStream.channel.write(buffer).toLong()
                    buffer.clear()
                    indicator.fraction = bytesRead[0].toDouble() / fileSize
                }
            }
        }
    }

    private fun tryCreateDirectory(directoryPath: Path) {
        Files.exists(directoryPath).takeUnless { it } ?: return
        try {
            createDirectory(directoryPath.toFile())
        } catch (e: IOException) {
            throw RuntimeException("Failed to create directory", e)
        }.takeIf { it } ?: throw RuntimeException("Failed to create directory: $directoryPath")
    }

    @JvmStatic
    fun getFileExtension(filename: String?): String {
        val pattern = Pattern.compile("[^.]+$")
        val matcher = filename?.let { pattern.matcher(it) }

        if (matcher?.find() == true) {
            return matcher.group()
        }
        return ""
    }

    @JvmStatic
    fun findLanguageExtensionMapping(language: String): Map.Entry<String, String> {
        val defaultValue = mapOf("Text" to ".txt").entries.first()
        val mapper = ObjectMapper()

        val extensionToLanguageMappings: List<FileExtensionLanguageDetails>
        val languageToExtensionMappings: List<LanguageFileExtensionDetails>
        try {
            extensionToLanguageMappings = mapper.readValue(
                getResourceContent("/fileExtensionLanguageMappings.json"),
                object : TypeReference<List<FileExtensionLanguageDetails>>() {
                })
            languageToExtensionMappings = mapper.readValue(
                getResourceContent("/languageFileExtensionMappings.json"),
                object : TypeReference<List<LanguageFileExtensionDetails>>() {
                })
        } catch (e: JsonProcessingException) {
            logger.error("Unable to extract file extension", e)
            return defaultValue
        }

        return findFirstExtension(languageToExtensionMappings, language)
            .or {
                extensionToLanguageMappings.stream()
                    .filter { it.extension.equals(language, ignoreCase = true) }
                    .findFirst()
                    .flatMap { findFirstExtension(languageToExtensionMappings, it.value) }
            }.orElse(defaultValue)
    }

    fun isUtf8File(filePath: String?): Boolean {
        val path = filePath?.let { Paths.get(it) }
        try {
            Files.newBufferedReader(path).use { reader ->
                val c = reader.read()
                if (c >= 0) {
                    reader.transferTo(Writer.nullWriter())
                }
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    @JvmStatic
    fun getImageMediaType(fileName: String?): String {
        return when (val fileExtension = getFileExtension(fileName)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> throw IllegalArgumentException("Unsupported image type: $fileExtension")
        }
    }

    @JvmStatic
    fun getResourceContent(filePath: String?): String {
        try {
            Objects.requireNonNull(filePath?.let { FileUtil::class.java.getResourceAsStream(it) })
                .use { stream ->
                    return String(stream.readAllBytes(), StandardCharsets.UTF_8)
                }
        } catch (e: IOException) {
            throw RuntimeException("Unable to read resource", e)
        }
    }

    @JvmStatic
    fun convertFileSize(fileSizeInBytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var unitIndex = 0
        var fileSize = fileSizeInBytes.toDouble()

        while (fileSize >= 1024 && unitIndex < units.size - 1) {
            fileSize /= 1024.0
            unitIndex++
        }

        return DecimalFormat("#.##").format(fileSize) + " " + units[unitIndex]
    }

    @JvmStatic
    fun convertLongValue(value: Long): String {
        if (value >= 1000000) {
            return (value / 1000000).toString() + "M"
        }
        if (value >= 1000) {
            return (value / 1000).toString() + "K"
        }

        return value.toString()
    }

    @JvmStatic
    fun findFirstExtension(
        languageFileExtensionMappings: List<LanguageFileExtensionDetails>,
        language: String
    ): Optional<Map.Entry<String, String>> {
        return languageFileExtensionMappings.stream()
            .filter {
                language.equals(it.name, ignoreCase = true)
                        && it.extensions != null
                        && it.extensions.stream().anyMatch(String::isNotBlank)
            }
            .findFirst()
            .map {
                java.util.Map.entry(
                    it.name,
                    it.extensions?.stream()?.filter(String::isNotBlank)?.findFirst()?.orElse("")
                        ?: ""
                )
            }
    }

    fun searchProjectFiles(
        project: Project,
        query: String,
        maxResults: Int = 6,
    ): List<VirtualFile> {
        val results = mutableListOf<SearchResult>()
        val fileIndex = project.service<ProjectFileIndex>()

        fileIndex.iterateContent({ file ->
            if (results.size > 9) {
                return@iterateContent false
            }

            val score = calculateScore(file, query)
            if (score > 0) {
                results.add(SearchResult(file, score))
            }
            true
        }, object : VirtualFileFilter {
            override fun accept(file: VirtualFile): Boolean {
                return !file.isDirectory && fileIndex.isInContent(file)
            }

            override fun toString(): String {
                return "NONE"
            }
        })

        return results
            .sortedByDescending { it.score }
            .take(maxResults)
            .map { it.file }
    }

    private fun calculateScore(file: VirtualFile, query: String): Int {
        val fileName = file.nameWithoutExtension.lowercase()
        val lowercaseQuery = query.lowercase()

        return when {
            fileName == lowercaseQuery -> 100
            fileName.startsWith(lowercaseQuery) -> 50
            lowercaseQuery in fileName -> 25
            fileName.length < lowercaseQuery.length && lowercaseQuery.startsWith(fileName) -> 15
            else -> 0
        }
    }

}

data class SearchResult(val file: VirtualFile, val score: Int)
