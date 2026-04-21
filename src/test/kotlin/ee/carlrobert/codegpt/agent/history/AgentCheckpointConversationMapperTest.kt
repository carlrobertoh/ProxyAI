package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.JSONObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test

class AgentCheckpointConversationMapperTest {

    @Test
    fun `maps user and assistant turns into conversation messages`() {
        val checkpoint = checkpoint(
            listOf(
                Message.System("System", RequestMetaInfo.Empty),
                Message.User("First prompt", RequestMetaInfo.Empty),
                Message.Assistant("First response", ResponseMetaInfo.Empty),
                Message.User("Second prompt", RequestMetaInfo.Empty),
                Message.Assistant("Second response", ResponseMetaInfo.Empty)
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)

        assertThat(actualConversation.messages)
            .extracting("prompt", "response")
            .containsExactly(
                tuple("First prompt", "First response"),
                tuple("Second prompt", "Second response")
            )
    }

    @Test
    fun `maps tool calls and results into structured message fields`() {
        val checkpoint = checkpoint(
            listOf(
                Message.User("Run the command", RequestMetaInfo.Empty),
                Message.Assistant("Working on it", ResponseMetaInfo.Empty),
                Message.Tool.Call(
                    id = "tool-1",
                    tool = "Bash",
                    content = """{"command":"ls"}""",
                    metaInfo = ResponseMetaInfo.Empty
                ),
                Message.Tool.Result(
                    id = "tool-1",
                    tool = "Bash",
                    content = "file1.txt",
                    metaInfo = RequestMetaInfo.Empty
                )
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)
        val actualMessage = actualConversation.messages.single()

        assertThat(actualMessage.response).isEqualTo("Working on it")
        assertThat(actualMessage.toolCalls.orEmpty())
            .extracting("function.name", "function.arguments")
            .containsExactly(tuple("Bash", """{"command":"ls"}"""))
        assertThat(actualMessage.toolCallResults).containsEntry("tool-1", "file1.txt")
    }

    @Test
    fun `filters project instructions from user turns`() {
        val projectInstructions = "Follow project instructions carefully."
        val checkpoint = checkpoint(
            listOf(
                Message.User(projectInstructions, RequestMetaInfo.Empty),
                Message.User("Actual user prompt", RequestMetaInfo.Empty),
                Message.Assistant("Actual response", ResponseMetaInfo.Empty)
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = checkpoint,
            projectInstructions = projectInstructions
        )

        assertThat(actualConversation.messages)
            .extracting("prompt", "response")
            .containsExactly(tuple("Actual user prompt", "Actual response"))
    }

    @Test
    fun `filters cacheable user messages from user turns`() {
        val checkpoint = checkpoint(
            listOf(
                Message.User(
                    content = "Project-level instruction payload",
                    metaInfo = cacheableMetaInfo()
                ),
                Message.User("Actual user prompt", RequestMetaInfo.Empty),
                Message.Assistant("Actual response", ResponseMetaInfo.Empty)
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = checkpoint,
            projectInstructions = null
        )

        assertThat(actualConversation.messages)
            .extracting("prompt", "response")
            .containsExactly(tuple("Actual user prompt", "Actual response"))
    }

    @Test
    fun `does not merge hidden user turn output into previous visible turn`() {
        val checkpoint = checkpoint(
            listOf(
                Message.User("Visible prompt 1", RequestMetaInfo.Empty),
                Message.Assistant("Visible response 1", ResponseMetaInfo.Empty),
                Message.User(
                    content = "Hidden cacheable instruction",
                    metaInfo = cacheableMetaInfo()
                ),
                Message.Assistant("Hidden response", ResponseMetaInfo.Empty),
                Message.User("Visible prompt 2", RequestMetaInfo.Empty),
                Message.Assistant("Visible response 2", ResponseMetaInfo.Empty)
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = checkpoint,
            projectInstructions = null
        )

        assertThat(actualConversation.messages)
            .extracting("prompt", "response")
            .containsExactly(
                tuple("Visible prompt 1", "Visible response 1"),
                tuple("Visible prompt 2", "Visible response 2")
            )
    }

    @Test
    fun `merges multiple tool results for the same tool call`() {
        val checkpoint = checkpoint(
            listOf(
                Message.User("Run the command", RequestMetaInfo.Empty),
                Message.Tool.Call(
                    id = "tool-1",
                    tool = "Bash",
                    content = """{"command":"ls"}""",
                    metaInfo = ResponseMetaInfo.Empty
                ),
                Message.Tool.Result(
                    id = "tool-1",
                    tool = "Bash",
                    content = "file1.txt",
                    metaInfo = RequestMetaInfo.Empty
                ),
                Message.Tool.Result(
                    id = "tool-1",
                    tool = "Bash",
                    content = "file2.txt",
                    metaInfo = RequestMetaInfo.Empty
                )
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)

        assertThat(actualConversation.messages.single().toolCallResults)
            .containsEntry("tool-1", "file1.txt\n\nfile2.txt")
    }

    @Test
    fun `filters synthetic timeline user turn and its output`() {
        val checkpoint = checkpoint(
            listOf(
                Message.User("Visible prompt 1", RequestMetaInfo.Empty),
                Message.Assistant("Visible response 1", ResponseMetaInfo.Empty),
                Message.User("I haven't created a todo list yet.", RequestMetaInfo.Empty),
                Message.Assistant("Synthetic response", ResponseMetaInfo.Empty),
                Message.User("Visible prompt 2", RequestMetaInfo.Empty),
                Message.Assistant("Visible response 2", ResponseMetaInfo.Empty)
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = checkpoint,
            projectInstructions = null
        )

        assertThat(actualConversation.messages)
            .extracting("prompt", "response")
            .containsExactly(
                tuple("Visible prompt 1", "Visible response 1"),
                tuple("Visible prompt 2", "Visible response 2")
            )
    }

    @Test
    fun `filters internal timeline tools from mapped message`() {
        val checkpoint = checkpoint(
            listOf(
                Message.User("Visible prompt", RequestMetaInfo.Empty),
                Message.Tool.Call(
                    id = "todo-1",
                    tool = "TodoWrite",
                    content = """{"todos":[{"content":"x","status":"pending"}]}""",
                    metaInfo = ResponseMetaInfo.Empty
                ),
                Message.Tool.Result(
                    id = "todo-1",
                    tool = "TodoWrite",
                    content = "ok",
                    metaInfo = RequestMetaInfo.Empty
                ),
                Message.Tool.Call(
                    id = "tool-1",
                    tool = "Bash",
                    content = """{"command":"ls"}""",
                    metaInfo = ResponseMetaInfo.Empty
                ),
                Message.Tool.Result(
                    id = "tool-1",
                    tool = "Bash",
                    content = "file1.txt",
                    metaInfo = RequestMetaInfo.Empty
                )
            )
        )

        val actualConversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)

        val actualMessage = actualConversation.messages.single()
        assertThat(actualMessage.toolCalls.orEmpty())
            .extracting("function.name")
            .containsExactly("Bash")
        assertThat(actualMessage.toolCallResults)
            .containsOnlyKeys("tool-1")
    }

    private fun cacheableMetaInfo(): RequestMetaInfo {
        return RequestMetaInfo(
            timestamp = Clock.System.now(),
            metadata = JsonObject(mapOf("cacheable" to JsonPrimitive(true)))
        )
    }

    private fun checkpoint(history: List<Message>): AgentCheckpointData {
        return AgentCheckpointData(
            checkpointId = "checkpoint-1",
            createdAt = Instant.parse("2026-02-04T00:00:00Z"),
            nodePath = "agent/single_run/nodeExecuteTool",
            lastOutput = JSONObject(emptyMap()),
            messageHistory = history,
            version = 0
        )
    }
}
