package ee.carlrobert.codegpt.agent.external.events

import com.agentclientprotocol.model.*
import ee.carlrobert.codegpt.agent.external.AcpToolCallDecoder
import ee.carlrobert.codegpt.agent.external.AcpToolCallStatus
import ee.carlrobert.codegpt.agent.external.AcpToolEventFlavor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class AcpToolCallDecoderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val decoder = AcpToolCallDecoder(json)

    @Test
    fun decodeToolCallStartedProducesTypedTerminalContent() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tool-1"),
            title = "Run shell command",
            kind = ToolKind.EXECUTE,
            status = ToolCallStatus.IN_PROGRESS,
            content = listOf(ToolCallContent.Terminal("terminal-1")),
            locations = listOf(ToolCallLocation("src/Main.kt")),
            rawInput = json.parseToJsonElement("""{"command":"npm test"}""")
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.STANDARD, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("tool-1", started.toolCall.id)
        assertEquals("Bash", started.toolCall.toolName)
        val bashArgs = assertIs<AcpToolCallArgs.Bash>(started.toolCall.args)
        assertEquals("npm test", bashArgs.value.command)
        assertEquals(ToolKind.EXECUTE, started.toolCall.kind)
        assertEquals(AcpToolCallStatus.IN_PROGRESS, started.toolCall.status)
        val terminal = started.toolCall.content.single()
        assertIs<AcpToolCallContent.Terminal>(terminal)
        assertEquals("terminal-1", terminal.terminalId)
    }

    @Test
    fun decodeToolCallUpdateProducesTypedEditArgs() {
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tool-2"),
            title = "Edit file",
            kind = ToolKind.EDIT,
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Diff("src/Main.kt", "old", "new")),
            rawInput = json.parseToJsonElement(
                """{"file_path":"src/Main.kt","old_string":"old","new_string":"new"}"""
            )
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.STANDARD, update)

        val updated = assertIs<AcpExternalEvent.ToolCallUpdated>(event)
        assertEquals("Edit", updated.toolCall.toolName)
        val editArgs = assertIs<AcpToolCallArgs.Edit>(updated.toolCall.args)
        assertEquals("src/Main.kt", editArgs.value.filePath)
        assertEquals(AcpToolCallStatus.COMPLETED, updated.toolCall.status)
        val diff = updated.toolCall.content.single()
        assertIs<AcpToolCallContent.Diff>(diff)
        assertEquals("src/Main.kt", diff.path)
    }

    @Test
    fun decodePermissionRequestPreservesTypedToolCallSnapshot() {
        val request = RequestPermissionRequest(
            sessionId = SessionId("session-1"),
            toolCall = SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-3"),
                title = "Allow write",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(ToolCallContent.Diff("src/Main.kt", "old", "new")),
                locations = listOf(ToolCallLocation("src/Main.kt")),
                rawInput = json.parseToJsonElement(
                    """{"file_path":"src/Main.kt","old_string":"old","new_string":"new"}"""
                )
            ),
            options = listOf(
                PermissionOption(
                    optionId = PermissionOptionId("allow_once"),
                    name = "Allow once",
                    kind = PermissionOptionKind.ALLOW_ONCE
                )
            )
        )

        val permission = decoder.decodePermissionRequest(AcpToolEventFlavor.STANDARD, request)

        assertEquals("Allow write", permission.toolCall.title)
        assertEquals("Edit", permission.toolCall.toolName)
        val editArgs = assertIs<AcpToolCallArgs.Edit>(permission.toolCall.args)
        assertEquals("src/Main.kt", editArgs.value.filePath)
        assertTrue(permission.details.contains("src/Main.kt"))
        assertEquals(1, permission.options.size)
    }

    @Test
    fun decodeUsageUpdatePreservesUsageAndCostFields() {
        val update = SessionUpdate.UsageUpdate(
            used = 1_024,
            size = 128_000,
            cost = Cost(amount = 0.02, currency = "USD")
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.STANDARD, update)

        val usage = assertIs<AcpExternalEvent.UsageUpdate>(event)
        assertEquals(1_024, usage.used)
        assertEquals(128_000, usage.size)
        assertEquals(0.02, usage.cost?.amount)
        assertEquals("USD", usage.cost?.currency)
    }

    @Test
    fun decodeConfigOptionUpdatePreservesSelectAndBooleanOptions() {
        val update = SessionUpdate.ConfigOptionUpdate(
            configOptions = listOf(
                SessionConfigOption.select(
                    id = "reasoning_effort",
                    name = "Reasoning Effort",
                    currentValue = "high",
                    options = SessionConfigSelectOptions.Flat(
                        listOf(
                            SessionConfigSelectOption(SessionConfigValueId("medium"), "Medium"),
                            SessionConfigSelectOption(SessionConfigValueId("high"), "High")
                        )
                    )
                ),
                SessionConfigOption.boolean(
                    id = "sandbox",
                    name = "Sandbox",
                    currentValue = true
                )
            )
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.STANDARD, update)

        val configUpdate = assertIs<AcpExternalEvent.ConfigOptionUpdate>(event)
        assertEquals(2, configUpdate.configOptions.size)
        assertIs<SessionConfigOption.Select>(configUpdate.configOptions[0])
        assertIs<SessionConfigOption.BooleanOption>(configUpdate.configOptions[1])
    }

    @Test
    fun decodeSessionInfoUpdatePreservesTitleAndTimestamp() {
        val update = SessionUpdate.SessionInfoUpdate(
            title = "Fix ACP runtime",
            updatedAt = "2026-03-22T18:30:00Z"
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.STANDARD, update)

        val sessionInfo = assertIs<AcpExternalEvent.SessionInfoUpdate>(event)
        assertEquals("Fix ACP runtime", sessionInfo.title)
        assertEquals("2026-03-22T18:30:00Z", sessionInfo.updatedAt)
    }

    @Test
    fun decodeUnknownSessionUpdatePreservesRawPayload() {
        val rawJson = json.parseToJsonElement(
            """{"sessionUpdate":"future_update","flag":true,"count":3}"""
        ).jsonObject
        val update = SessionUpdate.UnknownSessionUpdate(
            sessionUpdateType = "future_update",
            rawJson = rawJson
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.STANDARD, update)

        val unknown = assertIs<AcpExternalEvent.UnknownSessionUpdate>(event)
        assertEquals("future_update", unknown.type)
        assertEquals(rawJson, unknown.rawJson)
    }

    @Test
    fun decodeGeminiSearchWithoutRawInputFallsBackToPreviewArgs() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("grep_search-1"),
            title = "Search",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("/tmp/README.md"))
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.GEMINI_CLI, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("IntelliJSearch", started.toolCall.toolName)
        val preview = assertIs<AcpToolCallArgs.SearchPreview>(started.toolCall.args).value
        assertEquals("Search", preview.title)
        assertEquals("/tmp/README.md", preview.path)
        assertEquals(null, preview.pattern)
    }

    @Test
    fun decodeGeminiShellWithoutRawInputKeepsBashToolIdentity() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("run_shell_command-1"),
            title = "",
            kind = ToolKind.EXECUTE,
            status = ToolCallStatus.IN_PROGRESS
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.GEMINI_CLI, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("Bash", started.toolCall.toolName)
        val preview = assertIs<AcpToolCallArgs.BashPreview>(started.toolCall.args).value
        assertEquals("Run shell command", preview.title)
        assertEquals(null, preview.command)
    }

    @Test
    fun decodeGeminiWriteUpdateFromDiffMapsToWriteTool() {
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("write_file-1"),
            title = "",
            kind = null,
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Diff("src/Main.kt", "new content"))
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.GEMINI_CLI, update)

        val updated = assertIs<AcpExternalEvent.ToolCallUpdated>(event)
        assertEquals("Write", updated.toolCall.toolName)
        val writeArgs = assertIs<AcpToolCallArgs.Write>(updated.toolCall.args).value
        assertEquals("src/Main.kt", writeArgs.filePath)
        assertEquals("new content", writeArgs.content)
    }

    @Test
    fun decodeGeminiGlobMapsToGlobTool() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("glob-1"),
            title = "'**/*.java'",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.IN_PROGRESS
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.GEMINI_CLI, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("Glob", started.toolCall.toolName)
        val preview = assertIs<AcpToolCallArgs.SearchPreview>(started.toolCall.args).value
        assertEquals("Find files", preview.title)
        assertEquals("**/*.java", preview.pattern)
    }

    @Test
    fun decodeGeminiListDirectoryMapsToListDirectoryTool() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("list_directory-1"),
            title = ".",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.IN_PROGRESS
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.GEMINI_CLI, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("ListDirectory", started.toolCall.toolName)
        val preview = assertIs<AcpToolCallArgs.SearchPreview>(started.toolCall.args).value
        assertEquals("List directory", preview.title)
        assertEquals(".", preview.path)
    }

    @Test
    fun decodeGeminiReplaceUpdateFromDiffMapsToEditTool() {
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("replace-1"),
            title = "",
            kind = null,
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Diff("src/Main.kt", "new", "old"))
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.GEMINI_CLI, update)

        val updated = assertIs<AcpExternalEvent.ToolCallUpdated>(event)
        assertEquals("Edit", updated.toolCall.toolName)
        val editArgs = assertIs<AcpToolCallArgs.Edit>(updated.toolCall.args).value
        assertEquals("src/Main.kt", editArgs.filePath)
        assertEquals("old", editArgs.oldString)
        assertEquals("new", editArgs.newString)
    }

    @Test
    fun decodeZedSearchWithoutQueryablePayloadFallsBackToPreviewArgs() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("zed-search-1"),
            title = "Search",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("/tmp/src")),
            rawInput = json.parseToJsonElement("""{"parsed_cmd":[{"type":"search","path":"/tmp/src"}]}""")
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.ZED_ADAPTER, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("IntelliJSearch", started.toolCall.toolName)
        val preview = assertIs<AcpToolCallArgs.SearchPreview>(started.toolCall.args).value
        assertEquals("Search", preview.title)
        assertEquals("/tmp/src", preview.path)
        assertEquals(null, preview.pattern)
    }

    @Test
    fun decodeZedExecuteWithoutPayloadKeepsBashToolIdentity() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("zed-bash-1"),
            title = "Run shell command",
            kind = ToolKind.EXECUTE,
            status = ToolCallStatus.IN_PROGRESS
        )

        val event = decoder.decodeExternalEvent(AcpToolEventFlavor.ZED_ADAPTER, update)

        val started = assertIs<AcpExternalEvent.ToolCallStarted>(event)
        assertEquals("Bash", started.toolCall.toolName)
        val preview = assertIs<AcpToolCallArgs.BashPreview>(started.toolCall.args).value
        assertEquals("Run shell command", preview.title)
        assertEquals(null, preview.command)
    }
}
