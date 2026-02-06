package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ee.carlrobert.codegpt.conversations.Conversation
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import kotlin.test.Test

class AgentCheckpointConversationMapperTest {
    private val actualUserTurn = "Actual user prompt" to "Actual response"

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

        val conversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)

        assertThat(conversation.messages)
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

        val conversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)
        val mapped = conversation.messages.single()

        assertThat(mapped.response).isEqualTo("Working on it")
        assertThat(mapped.toolCalls.orEmpty())
            .extracting("function.name", "function.arguments")
            .containsExactly(tuple("Bash", """{"command":"ls"}"""))
        assertThat(mapped.toolCallResults).containsEntry("tool-1", "file1.txt")
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

        val conversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = checkpoint,
            projectInstructions = projectInstructions
        )

        assertSingleTurn(conversation, actualUserTurn)
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

        val conversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = checkpoint,
            projectInstructions = null
        )

        assertSingleTurn(conversation, actualUserTurn)
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

        val conversation = AgentCheckpointConversationMapper.toConversation(checkpoint, null)

        assertThat(conversation.messages.single().toolCallResults)
            .containsEntry("tool-1", "file1.txt\n\nfile2.txt")
    }

    private fun cacheableMetaInfo(): RequestMetaInfo {
        return RequestMetaInfo(
            timestamp = Clock.System.now(),
            metadata = JsonObject(mapOf("cacheable" to JsonPrimitive(true)))
        )
    }

    private fun assertSingleTurn(
        conversation: Conversation,
        expectedTurn: Pair<String, String>
    ) {
        assertThat(conversation.messages)
            .extracting("prompt", "response")
            .containsExactly(tuple(expectedTurn.first, expectedTurn.second))
    }

    private fun checkpoint(history: List<Message>): AgentCheckpointData {
        return AgentCheckpointData(
            checkpointId = "checkpoint-1",
            createdAt = Instant.parse("2026-02-04T00:00:00Z"),
            nodePath = "agent/single_run/nodeExecuteTool",
            lastInput = JsonNull,
            messageHistory = history,
            version = 0
        )
    }
}
