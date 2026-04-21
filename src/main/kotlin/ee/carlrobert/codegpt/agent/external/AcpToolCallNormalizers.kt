package ee.carlrobert.codegpt.agent.external

import ee.carlrobert.codegpt.agent.external.events.AcpToolCallArgs
import ee.carlrobert.codegpt.agent.external.events.AcpSearchPreviewArgs

internal class ZedAdapterToolCallNormalizer : AcpToolCallNormalizer {
    override fun normalize(
        context: AcpToolCallContext,
        support: AcpToolCallDecodingSupport
    ): AcpResolvedToolCall? {
        support.decodeMcpPayload(context.rawInput)?.let { payload ->
            return fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "MCP",
                toolName = "MCP",
                typedArgs = AcpToolCallArgs.Mcp(support.decodeMcpArgs(payload))
            )
        }

        if (context.rawKind.equals("fetch", ignoreCase = true)) {
            support.resolveWebSearchCall(context.rawTitle, context.rawInput)?.let { return it }
        }

        support.decodeParsedCommand(context.rawInput)?.let { parsed ->
            return when {
                parsed.type.equals("read", ignoreCase = true) ->
                    fixedCall(
                        rawTitle = context.rawTitle,
                        defaultTitle = "Read",
                        toolName = "Read",
                        typedArgs = support.decodeReadArgsFromParsedCommandOrFallback(
                            context.rawInput,
                            context.locations
                        )
                    )

                parsed.type.equals("search", ignoreCase = true) ->
                    fixedCall(
                        rawTitle = context.rawTitle,
                        defaultTitle = "Search",
                        toolName = "IntelliJSearch",
                        typedArgs = support.decodeSearchArgsOrPreview(
                            context.rawTitle.ifBlank { "Search" },
                            null,
                            context.locations
                        )
                    )

                parsed.type.equals("list_files", ignoreCase = true) ->
                    fixedCall(
                        rawTitle = context.rawTitle,
                        defaultTitle = "Bash",
                        toolName = "Bash",
                        typedArgs = support.decodeBashArgsOrPreview(
                            context.rawTitle,
                            context.rawInput,
                            defaultTitle = "List files"
                        )
                    )

                else -> null
            }
        }

        support.decodeBashArgs(context.rawInput)?.let { args ->
            return fixedCall(context.rawTitle, "Bash", "Bash", args)
        }

        if (context.rawKind.equals("execute", ignoreCase = true)) {
            return fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "Bash",
                toolName = "Bash",
                typedArgs = support.decodeBashArgsOrPreview(
                    context.rawTitle,
                    context.rawInput,
                    defaultTitle = "Bash"
                )
            )
        }

        support.resolveWebCall(context.rawTitle, context.rawInput)?.let { return it }

        return editOrWriteCall(
            rawTitle = context.rawTitle,
            args = support.decodeEditOrWriteArgs(context.rawInput, context.locations, context.content)
        )
    }
}

internal class GeminiCliToolCallNormalizer : AcpToolCallNormalizer {
    override fun normalize(
        context: AcpToolCallContext,
        support: AcpToolCallDecodingSupport
    ): AcpResolvedToolCall? {
        return when {
            context.toolCallId.startsWith("google_web_search-") ->
                fixedCall(
                    context.rawTitle,
                    "Google web search",
                    "WebSearch",
                    support.decodeWebSearchArgs(context.rawInput)
                )

            context.toolCallId.startsWith("grep_search-") ->
                fixedCall(
                    context.rawTitle,
                    "Search",
                    "IntelliJSearch",
                    support.decodeSearchArgsOrPreview(
                        context.rawTitle.ifBlank { "Search" },
                        context.rawInput,
                        context.locations
                    )
                )

            context.toolCallId.startsWith("read_file-") ->
                fixedCall(
                    context.rawTitle,
                    "Read file",
                    "Read",
                    support.decodeReadArgs(context.rawInput, context.locations)
                )

            context.toolCallId.startsWith("list_directory-") ->
                fixedCall(
                    rawTitle = context.rawTitle,
                    defaultTitle = "List directory",
                    toolName = "ListDirectory",
                    typedArgs = AcpToolCallArgs.SearchPreview(
                        AcpSearchPreviewArgs(
                            title = "List directory",
                            path = context.rawTitle.trim().ifBlank { null }
                        )
                    )
                )

            context.toolCallId.startsWith("glob-") ->
                fixedCall(
                    rawTitle = context.rawTitle,
                    defaultTitle = "Find files",
                    toolName = "Glob",
                    typedArgs = AcpToolCallArgs.SearchPreview(
                        AcpSearchPreviewArgs(
                            title = "Find files",
                            pattern = context.rawTitle.trim().trim('\'').ifBlank { null }
                        )
                    )
                )

            context.toolCallId.startsWith("write_file-") ->
                editOrWriteCall(
                    rawTitle = context.rawTitle,
                    args = support.decodeEditOrWriteArgs(
                        context.rawInput,
                        context.locations,
                        context.content
                    )
                ) ?: fixedCall(
                    rawTitle = context.rawTitle,
                    defaultTitle = "Write file",
                    toolName = "Write"
                )

            context.toolCallId.startsWith("replace-") ->
                editOrWriteCall(
                    rawTitle = context.rawTitle,
                    args = support.decodeEditOrWriteArgs(
                        context.rawInput,
                        context.locations,
                        context.content
                    )
                ) ?: fixedCall(
                    rawTitle = context.rawTitle,
                    defaultTitle = "Replace text",
                    toolName = "Edit"
                )

            context.toolCallId.startsWith("run_shell_command-") ->
                fixedCall(
                    context.rawTitle,
                    "Run shell command",
                    "Bash",
                    support.decodeBashArgsOrPreview(
                        context.rawTitle,
                        context.rawInput,
                        defaultTitle = "Run shell command"
                    )
                )

            else -> null
        }
    }
}

internal class StandardSemanticToolCallNormalizer : AcpToolCallNormalizer {
    override fun normalize(
        context: AcpToolCallContext,
        support: AcpToolCallDecodingSupport
    ): AcpResolvedToolCall? {
        support.decodeMcpPayload(context.rawInput)?.let { payload ->
            return fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "MCP",
                toolName = "MCP",
                typedArgs = AcpToolCallArgs.Mcp(support.decodeMcpArgs(payload))
            )
        }

        support.resolveWebCall(context.rawTitle, context.rawInput)?.let { return it }

        return when (context.rawKind?.lowercase().orEmpty()) {
            "read" -> fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "Read",
                toolName = "Read",
                typedArgs = support.decodeReadArgs(context.rawInput, context.locations)
            )

            "edit" -> editOrWriteCall(
                rawTitle = context.rawTitle,
                args = support.decodeEditOrWriteArgs(context.rawInput, context.locations, context.content)
            )

            "execute", "terminal", "bash" -> fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "Bash",
                toolName = "Bash",
                typedArgs = support.decodeBashArgsOrPreview(
                    context.rawTitle,
                    context.rawInput,
                    defaultTitle = "Bash"
                )
            )

            "search" -> fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "Search",
                toolName = "IntelliJSearch",
                typedArgs = support.decodeSearchArgsOrPreview(
                    context.rawTitle,
                    context.rawInput,
                    context.locations
                )
            )

            "fetch" -> fixedCall(
                rawTitle = context.rawTitle,
                defaultTitle = "WebFetch",
                toolName = "WebFetch",
                typedArgs = support.decodeWebFetchArgs(context.rawInput)
            )

            else -> null
        }
    }
}

internal class FallbackToolCallNormalizer : AcpToolCallNormalizer {
    override fun normalize(
        context: AcpToolCallContext,
        support: AcpToolCallDecodingSupport
    ): AcpResolvedToolCall {
        val fallbackName = context.rawTitle.ifBlank {
            context.rawKind?.replaceFirstChar { it.uppercase() } ?: context.defaultTitle
        }
        return fixedCall(
            rawTitle = context.rawTitle,
            defaultTitle = fallbackName,
            toolName = fallbackName
        )
    }
}
