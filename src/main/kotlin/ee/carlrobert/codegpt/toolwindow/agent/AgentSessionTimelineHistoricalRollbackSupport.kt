package ee.carlrobert.codegpt.toolwindow.agent

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message as PromptMessage
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.agent.ToolName
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Paths
import java.util.ArrayDeque

internal class AgentSessionTimelineHistoricalRollbackSupport(
    private val project: Project,
    private val historyService: AgentCheckpointHistoryService,
    private val replayJson: Json
) {

    suspend fun collectOperations(point: RunTimelinePoint): List<HistoricalRollbackOperation> {
        val checkpointRef = point.checkpointRef ?: return emptyList()
        val latestCheckpoint: AgentCheckpointData =
            historyService.listCheckpoints(checkpointRef.agentId).firstOrNull()
                ?: historyService.loadLatestResumeCheckpoint(checkpointRef.agentId)
                ?: historyService.loadCheckpoint(checkpointRef)
                ?: return emptyList()
        val cutoff = point.nonSystemMessageCount ?: return emptyList()
        val history = latestCheckpoint.messageHistory.filterNot { it is PromptMessage.System }
        if (history.isEmpty() || cutoff >= history.size) return emptyList()

        data class PendingCall(val index: Int, val call: PromptMessage.Tool.Call)

        val pendingById = mutableMapOf<String, PendingCall>()
        val pendingWithoutId = ArrayDeque<PendingCall>()
        val latestKnownContentByFile = mutableMapOf<String, String>()
        val operations = mutableListOf<HistoricalRollbackOperation>()

        history.forEachIndexed { index, message ->
            when (message) {
                is PromptMessage.Tool.Call -> {
                    val callId = message.id?.takeIf { it.isNotBlank() }
                    if (callId != null) {
                        pendingById[callId] = PendingCall(index, message)
                    } else {
                        pendingWithoutId.addLast(PendingCall(index, message))
                    }
                }

                is PromptMessage.Tool.Result -> {
                    val pendingCall = message.id
                        ?.takeIf { it.isNotBlank() }
                        ?.let { pendingById.remove(it) }
                        ?: pendingWithoutId.pollFirst()
                        ?: return@forEachIndexed
                    val call = pendingCall.call

                    val tool = HistoricalRollbackCompatibility.resolveSupportedTool(call.tool)
                        ?: return@forEachIndexed
                    if (!HistoricalRollbackCompatibility.isSuccessfulResult(tool, message.content, replayJson)) {
                        return@forEachIndexed
                    }

                    when (tool) {
                        ToolName.READ -> {
                            val args = decodeReadArgs(call.tool, call.content) ?: return@forEachIndexed
                            val filePath = normalizeToolFilePath(args.filePath)
                                ?: return@forEachIndexed
                            val content =
                                decodeReadToolResultContent(message.content) ?: return@forEachIndexed
                            latestKnownContentByFile[filePath] = content
                            return@forEachIndexed
                        }

                        ToolName.EDIT -> {
                            val args = decodeEditArgs(call.tool, call.content) ?: return@forEachIndexed
                            val filePath = normalizeToolFilePath(args.filePath)
                                ?: return@forEachIndexed
                            val oldString = args.oldString
                            val newString = args.newString
                            val replaceAll = args.replaceAll
                            if (oldString.isEmpty() || newString.isEmpty() || oldString == newString) return@forEachIndexed

                            if (pendingCall.index >= cutoff) {
                                operations.add(
                                    HistoricalRollbackOperation(
                                        filePath = filePath,
                                        searchText = newString,
                                        replacementText = oldString,
                                        replaceAll = replaceAll,
                                        sourceTool = HistoricalRollbackSourceTool.EDIT
                                    )
                                )
                            }

                            latestKnownContentByFile[filePath]?.let { known ->
                                if (known.contains(oldString)) {
                                    latestKnownContentByFile[filePath] =
                                        if (replaceAll) known.replace(oldString, newString)
                                        else known.replaceFirst(oldString, newString)
                                }
                            }
                            return@forEachIndexed
                        }

                        ToolName.WRITE -> {
                            val args = decodeWriteArgs(call.tool, call.content) ?: return@forEachIndexed
                            val filePath = normalizeToolFilePath(args.filePath)
                                ?: return@forEachIndexed
                            val newContent = args.content
                            val previousContent = latestKnownContentByFile[filePath]
                            if (pendingCall.index >= cutoff && previousContent != null && previousContent != newContent) {
                                operations.add(
                                    HistoricalRollbackOperation(
                                        filePath = filePath,
                                        searchText = newContent,
                                        replacementText = previousContent,
                                        replaceAll = false,
                                        sourceTool = HistoricalRollbackSourceTool.WRITE
                                    )
                                )
                            }
                            latestKnownContentByFile[filePath] = newContent
                        }

                        else -> Unit
                    }
                }

                else -> Unit
            }
        }

        return operations
    }

    fun buildConfirmationText(
        selectedLabel: String,
        operations: List<HistoricalRollbackOperation>
    ): String {
        val ordered = operations.asReversed()
        val previewLimit = 12
        val listed = ordered.take(previewLimit).joinToString(separator = "\n") { operation ->
            "${operation.sourceTool.symbol} ${toProjectRelativePath(operation.filePath)}"
        }
        val remaining = ordered.size - previewLimit
        val suffix = if (remaining > 0) "\n...and $remaining more operation(s)." else ""
        return """
            This rollback will return the session to:
            $selectedLabel

            It will also replay ${operations.size} file operation(s) in reverse order:

            $listed$suffix
        """.trimIndent()
    }

    fun applyOperations(operations: List<HistoricalRollbackOperation>): List<String> {
        val errors = mutableListOf<String>()
        operations.asReversed().forEach { operation ->
            val filePath = operation.filePath
            val currentText = readFileText(filePath)
            if (currentText == null) {
                errors.add("File not found: ${toProjectRelativePath(filePath)}")
                return@forEach
            }
            if (!currentText.contains(operation.searchText)) {
                errors.add(
                    "Expected content not found in ${toProjectRelativePath(filePath)} for ${operation.sourceTool.displayName} rollback."
                )
                return@forEach
            }

            val updatedText = if (operation.replaceAll) {
                currentText.replace(operation.searchText, operation.replacementText)
            } else {
                currentText.replaceFirst(operation.searchText, operation.replacementText)
            }
            if (updatedText == currentText) {
                errors.add("No changes applied to ${toProjectRelativePath(filePath)}")
                return@forEach
            }

            val writeOk = writeFileText(filePath, updatedText)
            if (!writeOk) {
                errors.add("Failed to write ${toProjectRelativePath(filePath)}")
            }
        }
        return errors
    }

    private fun parseToolArgs(rawArgs: String): Map<String, JsonElement>? {
        return runCatching { replayJson.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
    }

    private fun decodeReadArgs(rawToolName: String, rawArgs: String): ReadTool.Args? {
        val typed = ToolSpecs.decodeArgsOrNull(rawToolName, rawArgs) as? ReadTool.Args
        if (typed != null) return typed

        val args = parseToolArgs(rawArgs) ?: return null
        val filePath = stringValue(args["file_path"])
            ?: stringValue(args["path"])
            ?: stringValue(args["pathInProject"])
            ?: return null
        return ReadTool.Args(filePath = filePath)
    }

    private fun decodeEditArgs(rawToolName: String, rawArgs: String): EditTool.Args? {
        val typed = ToolSpecs.decodeArgsOrNull(rawToolName, rawArgs) as? EditTool.Args
        if (typed != null) return typed

        val args = parseToolArgs(rawArgs) ?: return null
        val filePath = stringValue(args["file_path"]) ?: return null
        val oldString = stringValue(args["old_string"]) ?: return null
        val newString = stringValue(args["new_string"]) ?: return null
        val shortDescription = stringValue(args["short_description"]) ?: "Recovered historical edit"
        val replaceAll = booleanValue(args["replace_all"]) ?: false

        return EditTool.Args(
            filePath = filePath,
            oldString = oldString,
            newString = newString,
            shortDescription = shortDescription,
            replaceAll = replaceAll
        )
    }

    private fun decodeWriteArgs(rawToolName: String, rawArgs: String): WriteTool.Args? {
        val typed = ToolSpecs.decodeArgsOrNull(rawToolName, rawArgs) as? WriteTool.Args
        if (typed != null) return typed

        val args = parseToolArgs(rawArgs) ?: return null
        val filePath = stringValue(args["file_path"]) ?: return null
        val content = stringValue(args["content"]) ?: return null
        return WriteTool.Args(filePath = filePath, content = content)
    }

    private fun booleanValue(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content.toBooleanStrictOrNull() else primitive.booleanOrNull
    }

    private fun stringValue(element: JsonElement?): String? {
        if (element == null) return null
        val primitive = element as? JsonPrimitive
        return if (primitive != null && primitive.isString) primitive.content else element.toString()
    }

    private fun normalizeToolFilePath(rawPath: String?): String? {
        val trimmed = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = trimmed.replace("\\", "/")
        val file = File(normalized)
        if (file.isAbsolute) {
            return file.toPath().normalize().toString().replace("\\", "/")
        }

        val basePath = project.basePath ?: return file.absolutePath.replace("\\", "/")
        return Paths.get(basePath).resolve(normalized).normalize().toString().replace("\\", "/")
    }

    private fun decodeReadToolResultContent(content: String): String? {
        if (content.isBlank()) return ""

        val numberedLines = content.lineSequence().mapNotNull { line ->
            val tabIndex = line.indexOf('\t')
            if (tabIndex <= 0) return@mapNotNull null
            val prefix = line.substring(0, tabIndex)
            if (!prefix.all { it.isDigit() }) return@mapNotNull null
            line.substring(tabIndex + 1)
        }.toList()
        if (numberedLines.isNotEmpty()) return numberedLines.joinToString(separator = "\n")

        if (content.startsWith("Error reading file", ignoreCase = true)) return null
        return content
    }

    private fun readFileText(path: String): String? {
        val virtualFile =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return null
        val documentText = runReadAction {
            FileDocumentManager.getInstance().getDocument(virtualFile)?.text
        }
        if (documentText != null) return documentText
        return runCatching { String(virtualFile.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
    }

    private fun writeFileText(path: String, content: String): Boolean {
        val virtualFile =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return false
        val document = runReadAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
        return runCatching {
            runInEdt {
                runWriteAction {
                    if (document != null) {
                        document.setText(content)
                        FileDocumentManager.getInstance().saveDocument(document)
                    } else {
                        virtualFile.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }.isSuccess
    }

    private fun toProjectRelativePath(path: String): String {
        val basePath = project.basePath ?: return path
        return runCatching {
            val absolute = Paths.get(path).normalize()
            val base = Paths.get(basePath).normalize()
            if (absolute.startsWith(base)) {
                base.relativize(absolute).toString().replace("\\", "/")
            } else {
                path
            }
        }.getOrDefault(path)
    }
}
