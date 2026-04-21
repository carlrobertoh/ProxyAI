package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallLocation
import ee.carlrobert.codegpt.agent.external.events.AcpToolCallArgs
import ee.carlrobert.codegpt.agent.external.events.AcpBashPreviewArgs
import ee.carlrobert.codegpt.agent.external.events.AcpSearchPreviewArgs
import ee.carlrobert.codegpt.agent.tools.BashTool
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.IntelliJSearchTool
import ee.carlrobert.codegpt.agent.tools.McpTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WebFetchTool
import ee.carlrobert.codegpt.agent.tools.WebSearchTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal data class AcpToolCallContext(
    val toolCallId: String,
    val rawTitle: String,
    val rawKind: String?,
    val rawInput: JsonElement?,
    val locations: List<ToolCallLocation>,
    val content: List<ToolCallContent>,
    val defaultTitle: String = "Tool"
)

internal data class AcpResolvedToolCall(
    val rawTitle: String,
    val toolName: String,
    val typedArgs: AcpToolCallArgs? = null
)

internal interface AcpToolCallNormalizer {
    fun normalize(context: AcpToolCallContext, support: AcpToolCallDecodingSupport): AcpResolvedToolCall?
}

internal data class DiffContent(
    val path: String,
    val oldText: String?,
    val newText: String
)

@Serializable
internal data class AcpMcpToolCallPayload(
    val server: String,
    val tool: String,
    val arguments: Map<String, JsonElement> = emptyMap(),
    @SerialName("server_id") val serverIdSnake: String? = null,
    val serverId: String? = null,
    @SerialName("server_name") val serverNameSnake: String? = null,
    val serverName: String? = null
) {
    val resolvedServerId: String?
        get() = serverId ?: serverIdSnake

    val resolvedServerName: String
        get() = serverName ?: serverNameSnake ?: server
}

@Serializable
internal data class AcpQueryPayload(
    val query: String? = null,
    val q: String? = null
) {
    val resolvedQuery: String?
        get() = query ?: q
}

@Serializable
internal data class AcpActionEnvelope(
    val action: AcpActionPayload
)

@Serializable
internal data class AcpActionPayload(
    val type: String,
    val query: String? = null,
    val q: String? = null,
    val url: String? = null,
    val uri: String? = null,
    val href: String? = null,
    val link: String? = null,
    val selector: String? = null,
    @SerialName("css_selector") val cssSelectorSnake: String? = null,
    val cssSelector: String? = null,
    @SerialName("timeout_ms") val timeoutMsSnake: Int? = null,
    val timeoutMs: Int? = null,
    val timeout: Int? = null,
    val offset: Int? = null,
    @SerialName("start_line") val startLineSnake: Int? = null,
    val startLine: Int? = null,
    val limit: Int? = null,
    @SerialName("max_lines") val maxLinesSnake: Int? = null,
    val maxLines: Int? = null,
    val count: Int? = null
) {
    val resolvedQuery: String?
        get() = query ?: q

    val resolvedUrl: String?
        get() = url ?: uri ?: href ?: link

    val resolvedSelector: String?
        get() = selector ?: cssSelector ?: cssSelectorSnake

    val resolvedTimeoutMs: Int
        get() = timeoutMs ?: timeoutMsSnake ?: timeout ?: 10_000

    val resolvedOffset: Int?
        get() = offset ?: startLine ?: startLineSnake

    val resolvedLimit: Int?
        get() = limit ?: maxLines ?: maxLinesSnake ?: count
}

@Serializable
internal data class AcpReadPayload(
    @SerialName("file_path") val filePathSnake: String? = null,
    val filePath: String? = null,
    val path: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val limit: Int? = null,
    @SerialName("maxLinesCount") val maxLinesCount: Int? = null
) {
    val resolvedPath: String?
        get() = filePath ?: filePathSnake ?: path

    val resolvedOffset: Int?
        get() = offset ?: line

    val resolvedLimit: Int?
        get() = limit ?: maxLinesCount
}

@Serializable
internal data class AcpWritePayload(
    @SerialName("file_path") val filePathSnake: String? = null,
    val filePath: String? = null,
    val path: String? = null,
    val content: String? = null,
    val text: String? = null
) {
    val resolvedPath: String?
        get() = filePath ?: filePathSnake ?: path

    val resolvedContent: String?
        get() = content ?: text
}

@Serializable
internal data class AcpEditPayload(
    @SerialName("file_path") val filePathSnake: String? = null,
    val filePath: String? = null,
    val path: String? = null,
    @SerialName("old_string") val oldStringSnake: String? = null,
    val oldString: String? = null,
    @SerialName("new_string") val newStringSnake: String? = null,
    val newString: String? = null,
    @SerialName("short_description") val shortDescriptionSnake: String? = null,
    val shortDescription: String? = null,
    @SerialName("replace_all") val replaceAllSnake: Boolean? = null,
    val replaceAll: Boolean? = null
) {
    val resolvedPath: String?
        get() = filePath ?: filePathSnake ?: path

    val resolvedOldString: String?
        get() = oldString ?: oldStringSnake

    val resolvedNewString: String?
        get() = newString ?: newStringSnake

    val resolvedDescription: String
        get() = shortDescription ?: shortDescriptionSnake ?: "ACP edit"

    val resolvedReplaceAll: Boolean
        get() = replaceAll ?: replaceAllSnake ?: false
}

@Serializable
internal data class AcpChangeSetPayload(
    val changes: Map<String, AcpChangePayload> = emptyMap()
) {
    fun firstWriteArgs(): AcpToolCallArgs? {
        val entry = changes.entries.firstOrNull() ?: return null
        val content = entry.value.content ?: return null
        return AcpToolCallArgs.Write(WriteTool.Args(entry.key, content))
    }
}

@Serializable
internal data class AcpChangePayload(
    val content: String? = null
)

@Serializable
internal data class AcpSearchPayload(
    val pattern: String? = null,
    @SerialName("searchText") val searchText: String? = null,
    val query: String? = null,
    @SerialName("nameKeyword") val nameKeyword: String? = null,
    val scope: String? = null,
    val path: String? = null,
    @SerialName("directoryToSearch") val directoryToSearch: String? = null,
    @SerialName("fileType") val fileType: String? = null,
    @SerialName("fileMask") val fileMask: String? = null,
    val caseSensitive: Boolean? = null,
    val regex: Boolean? = null,
    val limit: Int? = null,
    @SerialName("maxUsageCount") val maxUsageCount: Int? = null,
    @SerialName("fileCountLimit") val fileCountLimit: Int? = null
) {
    val resolvedPattern: String?
        get() = pattern ?: searchText ?: query ?: nameKeyword

    val resolvedPath: String?
        get() = path ?: directoryToSearch

    val resolvedFileType: String?
        get() = fileType ?: fileMask

    val resolvedLimit: Int?
        get() = limit ?: maxUsageCount ?: fileCountLimit
}

@Serializable
internal data class AcpCommandStringPayload(
    val command: String,
    val description: String? = null,
    val workingDirectory: String? = null,
    val workdir: String? = null,
    val cwd: String? = null,
    val timeout: Int? = null,
    @SerialName("run_in_background") val runInBackgroundSnake: Boolean? = null,
    val runInBackground: Boolean? = null
) {
    val resolvedWorkingDirectory: String?
        get() = workingDirectory ?: workdir ?: cwd

    val resolvedRunInBackground: Boolean
        get() = runInBackground ?: runInBackgroundSnake ?: false
}

@Serializable
internal data class AcpCommandArrayPayload(
    val command: List<String>,
    val description: String? = null,
    val workingDirectory: String? = null,
    val workdir: String? = null,
    val cwd: String? = null,
    val timeout: Int? = null,
    @SerialName("run_in_background") val runInBackgroundSnake: Boolean? = null,
    val runInBackground: Boolean? = null
) {
    val resolvedCommand: String
        get() = command.joinToString(" ")

    val resolvedWorkingDirectory: String?
        get() = workingDirectory ?: workdir ?: cwd

    val resolvedRunInBackground: Boolean
        get() = runInBackground ?: runInBackgroundSnake ?: false
}

@Serializable
internal data class AcpParsedCommandEnvelope(
    @SerialName("parsed_cmd") val parsedCommands: List<AcpParsedCommandPayload> = emptyList()
) {
    val firstParsedCommand: AcpParsedCommandPayload?
        get() = parsedCommands.firstOrNull()
}

@Serializable
internal data class AcpParsedCommandPayload(
    val type: String,
    val cmd: String? = null,
    val name: String? = null,
    val path: String? = null,
    val query: String? = null,
    val offset: Int? = null,
    val line: Int? = null
) {
    val resolvedOffset: Int?
        get() = offset ?: line
}

internal class AcpToolCallDecodingSupport(
    private val json: Json
) {
    inline fun <reified T> decode(rawInput: JsonElement?): T? = rawInput.decodeOrNull(json)

    fun decodeMcpArgs(payload: AcpMcpToolCallPayload): McpTool.Args {
        return McpTool.Args(
            toolName = payload.tool,
            serverId = payload.resolvedServerId,
            serverName = payload.resolvedServerName,
            arguments = payload.arguments
        )
    }

    fun decodeMcpPayload(rawInput: JsonElement?): AcpMcpToolCallPayload? {
        return decode(rawInput)
    }

    fun decodeParsedCommand(rawInput: JsonElement?): AcpParsedCommandPayload? {
        return decode<AcpParsedCommandEnvelope>(rawInput)?.firstParsedCommand
    }

    fun decodeDiffContent(content: List<ToolCallContent>): DiffContent? {
        val diff = content.filterIsInstance<ToolCallContent.Diff>().firstOrNull() ?: return null
        return DiffContent(diff.path, diff.oldText, diff.newText)
    }

    fun decodeEditOrWriteArgs(
        rawInput: JsonElement?,
        locations: List<ToolCallLocation>,
        content: List<ToolCallContent>
    ): AcpToolCallArgs? {
        decodeDiffContent(content)?.let { diff ->
            return if (diff.oldText == null) {
                AcpToolCallArgs.Write(WriteTool.Args(diff.path, diff.newText))
            } else {
                AcpToolCallArgs.Edit(
                    EditTool.Args(
                        filePath = diff.path,
                        oldString = diff.oldText,
                        newString = diff.newText,
                        shortDescription = "ACP edit",
                        replaceAll = false
                    )
                )
            }
        }

        decode<AcpChangeSetPayload>(rawInput)?.firstWriteArgs()?.let { return it }

        decode<AcpWritePayload>(rawInput)?.let { payload ->
            val path = payload.resolvedPath ?: locations.firstOrNull()?.path ?: return@let
            val text = payload.resolvedContent ?: return@let
            return AcpToolCallArgs.Write(WriteTool.Args(path, text))
        }

        decode<AcpEditPayload>(rawInput)?.let { payload ->
            val path = payload.resolvedPath ?: return@let
            val oldString = payload.resolvedOldString ?: return@let
            val newString = payload.resolvedNewString ?: return@let
            return AcpToolCallArgs.Edit(
                EditTool.Args(
                    filePath = path,
                    oldString = oldString,
                    newString = newString,
                    shortDescription = payload.resolvedDescription,
                    replaceAll = payload.resolvedReplaceAll
                )
            )
        }

        return null
    }

    fun decodeReadArgs(
        rawInput: JsonElement?,
        locations: List<ToolCallLocation>
    ): AcpToolCallArgs? {
        decode<AcpReadPayload>(rawInput)?.let { payload ->
            val path = payload.resolvedPath ?: locations.firstOrNull()?.path ?: return@let
            return AcpToolCallArgs.Read(
                ReadTool.Args(
                    filePath = path,
                    offset = payload.resolvedOffset ?: locations.firstOrNull()?.line?.toInt(),
                    limit = payload.resolvedLimit
                )
            )
        }

        return locations.firstOrNull()?.path?.let { path ->
            AcpToolCallArgs.Read(
                ReadTool.Args(
                    filePath = path,
                    offset = locations.firstOrNull()?.line?.toInt(),
                    limit = null
                )
            )
        }
    }

    fun decodeReadArgsFromParsedCommand(
        rawInput: JsonElement?,
        locations: List<ToolCallLocation>
    ): AcpToolCallArgs? {
        val parsed = decode<AcpParsedCommandEnvelope>(rawInput)?.firstParsedCommand ?: return null
        if (!parsed.type.equals("read", ignoreCase = true)) {
            return null
        }
        val path = parsed.path ?: locations.firstOrNull()?.path ?: return null
        return AcpToolCallArgs.Read(
            ReadTool.Args(
                filePath = path,
                offset = parsed.resolvedOffset ?: locations.firstOrNull()?.line?.toInt(),
                limit = null
            )
        )
    }

    fun decodeSearchArgs(rawInput: JsonElement?): AcpToolCallArgs? {
        val payload = decode<AcpSearchPayload>(rawInput) ?: return null
        val pattern = payload.resolvedPattern ?: return null
        return AcpToolCallArgs.IntelliJSearch(
            IntelliJSearchTool.Args(
                pattern = pattern,
                scope = payload.scope,
                path = payload.resolvedPath,
                fileType = payload.resolvedFileType,
                context = null,
                caseSensitive = payload.caseSensitive,
                regex = payload.regex,
                wholeWords = null,
                outputMode = null,
                limit = payload.resolvedLimit
            )
        )
    }

    fun decodeSearchArgsOrPreview(
        rawTitle: String,
        rawInput: JsonElement?,
        locations: List<ToolCallLocation>
    ): AcpToolCallArgs {
        decodeSearchArgsFromParsedCommand(rawInput)?.let { return it }
        decodeSearchArgs(rawInput)?.let { return it }

        val normalizedTitle = rawTitle.trim().ifBlank { "Search" }
        val searchPayload = decode<AcpSearchPayload>(rawInput)
        val path = searchPayload?.resolvedPath ?: locations.firstOrNull()?.path
        val pattern = searchPayload?.resolvedPattern ?: extractGeminiSearchPattern(normalizedTitle)
        val title = when {
            !pattern.isNullOrBlank() && isGenericSearchTitle(normalizedTitle) -> "Search for $pattern"
            else -> normalizedTitle
        }

        return AcpToolCallArgs.SearchPreview(
            AcpSearchPreviewArgs(
                title = title,
                path = path,
                pattern = pattern
            )
        )
    }

    fun decodeReadArgsFromParsedCommandOrFallback(
        rawInput: JsonElement?,
        locations: List<ToolCallLocation>
    ): AcpToolCallArgs? {
        return decodeReadArgsFromParsedCommand(rawInput, locations)
            ?: decodeReadArgs(rawInput, locations)
    }

    fun decodeSearchArgsFromParsedCommand(rawInput: JsonElement?): AcpToolCallArgs? {
        val parsed = decode<AcpParsedCommandEnvelope>(rawInput)?.firstParsedCommand ?: return null
        if (!parsed.type.equals("search", ignoreCase = true)) {
            return null
        }
        val pattern = parsed.query ?: return null
        return AcpToolCallArgs.IntelliJSearch(
            IntelliJSearchTool.Args(
                pattern = pattern,
                scope = null,
                path = parsed.path,
                fileType = null,
                context = null,
                caseSensitive = null,
                regex = null,
                wholeWords = null,
                outputMode = null,
                limit = null
            )
        )
    }

    fun decodeBashArgs(rawInput: JsonElement?): AcpToolCallArgs? {
        decode<AcpCommandStringPayload>(rawInput)?.let { payload ->
            return AcpToolCallArgs.Bash(
                BashTool.Args(
                    command = payload.command,
                    workingDirectory = payload.resolvedWorkingDirectory,
                    timeout = payload.timeout ?: 60_000,
                    description = payload.description,
                    runInBackground = payload.resolvedRunInBackground
                )
            )
        }

        decode<AcpCommandArrayPayload>(rawInput)?.let { payload ->
            return AcpToolCallArgs.Bash(
                BashTool.Args(
                    command = payload.resolvedCommand,
                    workingDirectory = payload.resolvedWorkingDirectory,
                    timeout = payload.timeout ?: 60_000,
                    description = payload.description,
                    runInBackground = payload.resolvedRunInBackground
                )
            )
        }

        return null
    }

    fun decodeBashArgsOrPreview(
        rawTitle: String,
        rawInput: JsonElement?,
        defaultTitle: String = "Run shell command"
    ): AcpToolCallArgs {
        decodeBashArgs(rawInput)?.let { return it }

        val normalizedTitle = rawTitle.trim().ifBlank { defaultTitle }
        val parsedCommand = decodeParsedCommand(rawInput)?.cmd?.trim()?.takeIf { it.isNotBlank() }
        return AcpToolCallArgs.BashPreview(
            AcpBashPreviewArgs(
                title = normalizedTitle,
                command = parsedCommand ?: extractGeminiCommand(normalizedTitle)
            )
        )
    }

    fun decodeWebSearchArgs(rawInput: JsonElement?): AcpToolCallArgs? {
        decode<AcpActionEnvelope>(rawInput)?.action?.let { action ->
            if (action.type.equals("search", ignoreCase = true)) {
                action.resolvedQuery?.let { return AcpToolCallArgs.WebSearch(WebSearchTool.Args(it)) }
            }
        }

        decode<AcpQueryPayload>(rawInput)?.resolvedQuery?.let { query ->
            return AcpToolCallArgs.WebSearch(WebSearchTool.Args(query))
        }

        return null
    }

    fun decodeWebFetchArgs(rawInput: JsonElement?): AcpToolCallArgs? {
        decode<AcpActionEnvelope>(rawInput)?.action?.let { action ->
            if (action.type.lowercase() in setOf("open_page", "fetch", "open")) {
                val url = action.resolvedUrl ?: return@let
                return AcpToolCallArgs.WebFetch(
                    WebFetchTool.Args(
                        url = url,
                        selector = action.resolvedSelector,
                        timeoutMs = action.resolvedTimeoutMs,
                        offset = action.resolvedOffset,
                        limit = action.resolvedLimit
                    )
                )
            }
        }

        return null
    }

    fun resolveWebSearchCall(rawTitle: String, rawInput: JsonElement?): AcpResolvedToolCall? {
        return decodeWebSearchArgs(rawInput)?.let { args ->
            fixedCall(rawTitle, "WebSearch", "WebSearch", args)
        }
    }

    fun resolveWebFetchCall(rawTitle: String, rawInput: JsonElement?): AcpResolvedToolCall? {
        return decodeWebFetchArgs(rawInput)?.let { args ->
            fixedCall(rawTitle, "WebFetch", "WebFetch", args)
        }
    }

    fun resolveWebCall(rawTitle: String, rawInput: JsonElement?): AcpResolvedToolCall? {
        return resolveWebSearchCall(rawTitle, rawInput)
            ?: resolveWebFetchCall(rawTitle, rawInput)
    }
}

internal fun fixedCall(
    rawTitle: String,
    defaultTitle: String,
    toolName: String,
    typedArgs: AcpToolCallArgs? = null
): AcpResolvedToolCall {
    return AcpResolvedToolCall(
        rawTitle = rawTitle.ifBlank { defaultTitle },
        toolName = toolName,
        typedArgs = typedArgs
    )
}

internal fun editOrWriteCall(rawTitle: String, args: AcpToolCallArgs?): AcpResolvedToolCall? {
    return when (args) {
        is AcpToolCallArgs.Write -> fixedCall(rawTitle, "Write", "Write", args)
        is AcpToolCallArgs.Edit -> fixedCall(rawTitle, "Edit", "Edit", args)
        else -> null
    }
}

private fun extractGeminiSearchPattern(title: String): String? {
    val match = Regex("(?i)^(?:search|grep)(?:\\s+for)?\\s*:?\\s+(.+)$").find(title) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun isGenericSearchTitle(title: String): Boolean {
    return title.isBlank() || title.equals("search", ignoreCase = true)
}

private fun extractGeminiCommand(title: String): String? {
    val match = Regex(
        "(?i)^(?:run(?:\\s+shell)?\\s+command|execute(?:\\s+shell)?\\s+command)\\s*:?\\s+(.+)$"
    ).find(title) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}
