package ee.carlrobert.codegpt.agent.external

import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.tools.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets

internal data class AcpDecodedToolCall(
    val id: String,
    val toolName: String,
    val args: Any?
)

internal data class AcpPermissionRequestData(
    val rawTitle: String,
    val toolName: String,
    val parsedArgs: Any?,
    val details: String,
    val options: JsonArray
)

private data class DiffContent(
    val path: String,
    val oldText: String?,
    val newText: String
)

private data class ResolvedToolCall(
    val rawTitle: String,
    val toolName: String,
    val args: Any?
)

internal class AcpToolCallDecoder(
    private val json: Json
) {

    fun decodeToolCall(metadata: JsonObject): AcpDecodedToolCall? {
        val toolCallId = metadata.string("toolCallId") ?: return null
        val tool = resolveToolCall(metadata)
        return AcpDecodedToolCall(
            id = toolCallId,
            toolName = tool.toolName,
            args = tool.args
        )
    }

    fun decodePermissionRequest(params: JsonObject): AcpPermissionRequestData {
        val toolCall = params["toolCall"] as? JsonObject ?: JsonObject(emptyMap())
        val tool = resolveToolCall(toolCall, defaultTitle = "Allow action?")
        return AcpPermissionRequestData(
            rawTitle = tool.rawTitle,
            toolName = tool.toolName,
            parsedArgs = tool.args,
            details = permissionDetails(toolCall),
            options = params["options"].asJsonArrayOrEmpty()
        )
    }

    fun decodeResult(
        toolName: String,
        args: Any?,
        status: AcpToolCallStatus,
        rawOutput: JsonElement?
    ): Any? {
        val payload = rawOutput.toPayloadString()
        ToolSpecs.decodeResultOrNull(json, toolName, payload)?.let { return it }

        if (status == AcpToolCallStatus.COMPLETED) {
            when (args) {
                is McpTool.Args -> return McpTool.Result(
                    serverId = args.serverId,
                    serverName = args.serverName,
                    toolName = args.toolName,
                    success = true,
                    output = payload.ifBlank { "MCP tool completed" }
                )

                is EditTool.Args -> return EditTool.Result.Success(
                    filePath = args.filePath,
                    replacementsMade = 1,
                    message = "Edit completed"
                )

                is WriteTool.Args -> return WriteTool.Result.Success(
                    filePath = args.filePath,
                    bytesWritten = args.content.toByteArray(StandardCharsets.UTF_8).size,
                    isNewFile = false,
                    message = "Write completed"
                )
            }
        }

        if (status == AcpToolCallStatus.FAILED || status == AcpToolCallStatus.CANCELLED) {
            val message = payload.ifBlank { "Tool ${status.wireValue}" }
            when (args) {
                is McpTool.Args -> return McpTool.Result.error(
                    toolName = args.toolName,
                    output = message,
                    serverId = args.serverId,
                    serverName = args.serverName
                )

                is EditTool.Args -> return EditTool.Result.Error(args.filePath, message)
                is WriteTool.Args -> return WriteTool.Result.Error(args.filePath, message)
            }
        }

        return payload.ifBlank { null }
    }

    private fun resolveToolCall(
        metadata: JsonObject,
        defaultTitle: String = "Tool"
    ): ResolvedToolCall {
        val rawTitle = metadata.string("title") ?: defaultTitle
        val rawKind = metadata.string("kind")
        val rawInput = metadata["rawInput"]
        val initialToolName = normalizeToolName(rawTitle, rawKind, rawInput)
        val parsedArgs = if (initialToolName == "MCP") {
            decodeMcpArgs(rawTitle, rawInput)
        } else {
            decodeToolArgs(initialToolName, rawInput, metadata)
        }
        return ResolvedToolCall(
            rawTitle = rawTitle,
            toolName = resolveToolName(initialToolName, parsedArgs),
            args = parsedArgs
        )
    }

    private fun permissionDetails(toolCall: JsonObject): String {
        return buildString {
            (toolCall["locations"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.string("path") }
                ?.takeIf { it.isNotEmpty() }
                ?.let { paths ->
                    appendLine("Locations:")
                    paths.forEach { path -> appendLine(path) }
                }

            toolCall["rawInput"]?.let { rawInput ->
                if (isNotBlank()) {
                    appendLine()
                }
                appendLine("Input:")
                append(rawInput.toString())
            }
        }.ifBlank { toolCall.toString() }
    }

    private fun normalizeToolName(
        rawTitle: String,
        kind: String?,
        rawInput: JsonElement?
    ): String {
        val normalizedKind = kind?.lowercase().orEmpty()
        val rawInputObject = rawInput.asJsonObjectOrNull(json)
        val actionType = (rawInputObject?.get("action") as? JsonObject)?.string("type")?.lowercase()
        val titleLower = rawTitle.lowercase()

        return when {
            looksLikeMcpToolName(rawTitle) -> "MCP"
            rawInputObject?.get("command") != null || rawInputObject?.get("cmd") != null -> "Bash"
            normalizedKind == "execute" || normalizedKind == "terminal" || normalizedKind == "bash" -> "Bash"
            actionType == "search" -> "WebSearch"
            actionType == "open_page" || actionType == "fetch" || actionType == "open" -> "WebFetch"
            normalizedKind == "edit" -> "Edit"
            normalizedKind == "read" -> "Read"
            normalizedKind == "search" -> "IntelliJSearch"
            normalizedKind == "fetch" && (
                titleLower == "searching the web" || titleLower.startsWith("searching for:")
                ) -> "WebSearch"
            normalizedKind == "fetch" || titleLower.startsWith("opening:") -> "WebFetch"
            else -> rawTitle.ifBlank { kind ?: "Tool" }
        }
    }

    private fun resolveToolName(initialToolName: String, args: Any?): String {
        return when (args) {
            is McpTool.Args -> "MCP"
            is WriteTool.Args -> "Write"
            is EditTool.Args -> "Edit"
            is ReadTool.Args -> "Read"
            is IntelliJSearchTool.Args -> "IntelliJSearch"
            is BashTool.Args -> "Bash"
            is WebSearchTool.Args -> "WebSearch"
            is WebFetchTool.Args -> "WebFetch"
            else -> initialToolName
        }
    }

    private fun decodeToolArgs(
        toolName: String,
        rawInput: JsonElement?,
        metadata: JsonObject? = null
    ): Any? {
        val payload = rawInput.toPayloadString()
        ToolSpecs.decodeArgsOrNull(json, toolName, payload)?.let { return it }

        val obj = rawInput.asJsonObjectOrNull(json) ?: JsonObject(emptyMap())
        return when (toolName) {
            "Edit" -> decodeEditOrWriteArgs(obj, metadata) ?: payload.ifBlank { null }
            "Write" -> decodeWriteArgs(obj, metadata) ?: payload.ifBlank { null }
            "Read" -> decodeReadArgs(obj, metadata) ?: payload.ifBlank { null }
            "IntelliJSearch" -> decodeSearchArgs(obj) ?: payload.ifBlank { null }
            "Bash" -> decodeBashArgs(obj) ?: payload.ifBlank { null }
            "WebSearch" -> decodeWebSearchArgs(obj, metadata) ?: payload.ifBlank { null }
            "WebFetch" -> decodeWebFetchArgs(obj, rawInput, metadata) ?: payload.ifBlank { null }
            else -> payload.ifBlank { null }
        }
    }

    private fun decodeEditOrWriteArgs(obj: JsonObject, metadata: JsonObject?): Any? {
        decodeDiffContent(metadata)?.let { diff ->
            return if (diff.oldText == null) {
                WriteTool.Args(
                    filePath = diff.path,
                    content = diff.newText
                )
            } else {
                EditTool.Args(
                    filePath = diff.path,
                    oldString = diff.oldText,
                    newString = diff.newText,
                    shortDescription = metadata?.string("title") ?: "ACP edit",
                    replaceAll = false
                )
            }
        }

        decodeWriteArgs(obj, metadata)?.let { return it }
        return decodeEditArgs(obj, metadata)
    }

    private fun decodeEditArgs(obj: JsonObject, metadata: JsonObject? = null): EditTool.Args? {
        val filePath = obj.string("file_path", "filePath", "path") ?: return null
        val oldString = obj.string("old_string", "oldString", "old_text", "oldText") ?: return null
        val newString = obj.string("new_string", "newString", "new_text", "newText") ?: return null
        val shortDescription = obj.string("short_description", "shortDescription", "description")
            ?: metadata?.string("title")
            ?: "ACP edit"
        val replaceAll = obj.boolean("replace_all", "replaceAll") ?: false
        return EditTool.Args(filePath, oldString, newString, shortDescription, replaceAll)
    }

    private fun decodeWriteArgs(
        obj: JsonObject,
        metadata: JsonObject? = null
    ): WriteTool.Args? {
        val filePath = obj.string("file_path", "filePath", "path")
            ?: metadata?.firstLocationPath()
            ?: metadata?.titlePath()
            ?: obj.firstChangePath()
            ?: return null
        val content = obj.string("content", "text")
            ?: obj.firstChangeContent()
            ?: decodeDiffContent(metadata)?.takeIf { it.oldText == null }?.newText
            ?: return null
        return WriteTool.Args(filePath, content)
    }

    private fun decodeReadArgs(obj: JsonObject, metadata: JsonObject? = null): ReadTool.Args? {
        val filePath = obj.string("file_path", "filePath", "path")
            ?: metadata?.firstLocationPath()
            ?: metadata?.titlePath()
            ?: return null
        return ReadTool.Args(
            filePath = filePath,
            offset = obj.int("offset", "line") ?: metadata?.int("line"),
            limit = obj.int("limit", "maxLinesCount")
        )
    }

    private fun decodeSearchArgs(obj: JsonObject): IntelliJSearchTool.Args? {
        val pattern = obj.string("pattern", "searchText", "query", "nameKeyword") ?: return null
        return IntelliJSearchTool.Args(
            pattern = pattern,
            scope = obj.string("scope"),
            path = obj.string("path", "directoryToSearch"),
            fileType = obj.string("fileType", "fileMask"),
            context = null,
            caseSensitive = obj.boolean("caseSensitive"),
            regex = obj.boolean("regex"),
            wholeWords = null,
            outputMode = null,
            limit = obj.int("limit", "maxUsageCount", "fileCountLimit")
        )
    }

    private fun decodeBashArgs(obj: JsonObject): BashTool.Args? {
        val command = obj.commandString() ?: return null
        return BashTool.Args(
            command = command,
            workingDirectory = obj.string("workingDirectory", "workdir", "cwd"),
            timeout = obj.int("timeout") ?: 60_000,
            description = obj.string("description", "title"),
            runInBackground = obj.boolean("run_in_background", "runInBackground")
        )
    }

    private fun decodeWebSearchArgs(
        obj: JsonObject,
        metadata: JsonObject? = null
    ): WebSearchTool.Args? {
        val action = obj["action"] as? JsonObject
        val query = obj.string("query", "q")
            ?: action?.string("query")
            ?: metadata?.string("query", "q")
            ?: return null
        return WebSearchTool.Args(query = query)
    }

    private fun decodeWebFetchArgs(
        obj: JsonObject,
        rawInput: JsonElement?,
        metadata: JsonObject? = null
    ): WebFetchTool.Args? {
        val action = obj["action"] as? JsonObject
        val payload = rawInput.toPayloadString()
        val url = obj.string("url", "uri", "href", "link")
            ?: action?.string("url", "uri", "href", "link")
            ?: metadata?.string("url", "uri")
            ?: extractFirstUrl(payload)
            ?: metadata?.string("title")?.let(::extractFirstUrl)
            ?: return null

        return WebFetchTool.Args(
            url = url,
            selector = obj.string("selector", "css_selector", "cssSelector")
                ?: action?.string("selector", "css_selector", "cssSelector"),
            timeoutMs = obj.int("timeout_ms", "timeoutMs", "timeout")
                ?: action?.int("timeout_ms", "timeoutMs", "timeout")
                ?: 10_000,
            offset = obj.int("offset", "start_line", "startLine")
                ?: action?.int("offset", "start_line", "startLine"),
            limit = obj.int("limit", "max_lines", "maxLines", "count")
                ?: action?.int("limit", "max_lines", "maxLines", "count")
        )
    }

    private fun decodeMcpArgs(rawTitle: String, rawInput: JsonElement?): McpTool.Args {
        val obj = rawInput.asJsonObjectOrNull(json) ?: JsonObject(emptyMap())
        val callName = rawTitle.ifBlank { obj.string("tool_name", "toolName") ?: "unknown" }
        val slashIndex = callName.indexOf('/')
        val serverName = if (slashIndex > 0) callName.substring(0, slashIndex) else obj.string(
            "server_name",
            "serverName"
        )
        val toolName = if (slashIndex > 0 && slashIndex < callName.length - 1) {
            callName.substring(slashIndex + 1)
        } else {
            obj.string("tool_name", "toolName") ?: callName.ifBlank { "unknown" }
        }
        val arguments = (obj["arguments"] as? JsonObject)?.toMap() ?: obj.toMap()
        return McpTool.Args(
            toolName = toolName,
            serverId = obj.string("server_id", "serverId"),
            serverName = serverName,
            arguments = arguments
        )
    }

    private fun decodeDiffContent(metadata: JsonObject?): DiffContent? {
        val diff = (metadata?.get("content") as? JsonArray)
            ?.firstOrNull { entry ->
                (entry as? JsonObject)?.string("type") == "diff"
            } as? JsonObject
            ?: return null
        val path = diff.string("path") ?: return null
        val newText = diff.string("newText", "new_text") ?: return null
        val oldText = diff.string("oldText", "old_text")
        return DiffContent(path, oldText, newText)
    }

    private fun looksLikeMcpToolName(rawTitle: String): Boolean {
        val candidate = rawTitle.trim()
        if (candidate.isBlank() || ' ' in candidate || candidate.startsWith("/")) {
            return false
        }
        val slashIndex = candidate.indexOf('/')
        return slashIndex > 0 && slashIndex < candidate.length - 1
    }

    private fun extractFirstUrl(text: String): String? {
        return URL_REGEX.find(text)?.value
    }

    private companion object {
        val URL_REGEX = Regex("""https?://[^\s"'<>]+""")
    }
}
