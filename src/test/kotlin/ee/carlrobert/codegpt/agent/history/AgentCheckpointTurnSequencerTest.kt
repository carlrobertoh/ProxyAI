package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.JSONObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test

class AgentCheckpointTurnSequencerTest {

    @Test
    fun `preserves assistant and tool event order within a turn`() {
        val history = listOf(
            Message.User("Prompt 1", RequestMetaInfo.Empty),
            Message.Assistant("Assistant 1", ResponseMetaInfo.Empty),
            Message.Tool.Call(
                id = "tool-1",
                tool = "Bash",
                content = """{"command":"ls"}""",
                metaInfo = ResponseMetaInfo.Empty
            ),
            Message.Assistant("Assistant 2", ResponseMetaInfo.Empty),
            Message.Tool.Result(
                id = "tool-1",
                tool = "Bash",
                content = "file.txt",
                metaInfo = RequestMetaInfo.Empty
            ),
            Message.Reasoning(content = "Reasoning 1", metaInfo = ResponseMetaInfo.Empty),
            Message.User("Prompt 2", RequestMetaInfo.Empty),
            Message.Assistant("Assistant 3", ResponseMetaInfo.Empty)
        )

        val actualTurns = AgentCheckpointTurnSequencer.toVisibleTurns(
            history = history,
            projectInstructions = null
        )

        assertThat(actualTurns).hasSize(2)
        assertThat(actualTurns[0].prompt).isEqualTo("Prompt 1")
        assertThat(actualTurns[0].userNonSystemMessageCount).isEqualTo(1)
        assertThat(actualTurns[0].events.map(::eventSignature))
            .containsExactly(
                "assistant:Assistant 1",
                "tool-call:Bash:tool-1",
                "assistant:Assistant 2",
                "tool-result:Bash:tool-1",
                "reasoning:Reasoning 1"
            )
        assertThat(actualTurns[0].events.map { it.nonSystemMessageCount })
            .containsExactly(2, 3, 4, 5, 6)
        assertThat(actualTurns[1].prompt).isEqualTo("Prompt 2")
        assertThat(actualTurns[1].userNonSystemMessageCount).isEqualTo(7)
        assertThat(actualTurns[1].events.map(::eventSignature))
            .containsExactly("assistant:Assistant 3")
        assertThat(actualTurns[1].events.map { it.nonSystemMessageCount })
            .containsExactly(8)
    }

    @Test
    fun `filters hidden and synthetic user turns and internal tools`() {
        val history = listOf(
            Message.User("Visible prompt 1", RequestMetaInfo.Empty),
            Message.Assistant("Visible response 1", ResponseMetaInfo.Empty),
            Message.User("Hidden cacheable prompt", cacheableMetaInfo()),
            Message.Assistant("Hidden response", ResponseMetaInfo.Empty),
            Message.User("I haven't created a todo list yet.", RequestMetaInfo.Empty),
            Message.Assistant("Synthetic response", ResponseMetaInfo.Empty),
            Message.User("Visible prompt 2", RequestMetaInfo.Empty),
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
                content = "file.txt",
                metaInfo = RequestMetaInfo.Empty
            ),
            Message.Assistant("Visible response 2", ResponseMetaInfo.Empty)
        )

        val actualTurns = AgentCheckpointTurnSequencer.toVisibleTurns(
            history = history,
            projectInstructions = null
        )

        assertThat(actualTurns).hasSize(2)
        assertThat(actualTurns.map { it.prompt })
            .containsExactly("Visible prompt 1", "Visible prompt 2")
        assertThat(actualTurns.map { it.userNonSystemMessageCount })
            .containsExactly(1, 7)
        assertThat(actualTurns[0].events.map(::eventSignature))
            .containsExactly("assistant:Visible response 1")
        assertThat(actualTurns[0].events.map { it.nonSystemMessageCount })
            .containsExactly(2)
        assertThat(actualTurns[1].events.map(::eventSignature))
            .containsExactly(
                "tool-call:Bash:tool-1",
                "tool-result:Bash:tool-1",
                "assistant:Visible response 2"
            )
        assertThat(actualTurns[1].events.map { it.nonSystemMessageCount })
            .containsExactly(10, 11, 12)
    }

    @Test
    fun `mapper and sequencer keep same visible turn prompts`() {
        val history = listOf(
            Message.User("Visible prompt 1", RequestMetaInfo.Empty),
            Message.Assistant("Visible response 1", ResponseMetaInfo.Empty),
            Message.User("Hidden cacheable prompt", cacheableMetaInfo()),
            Message.Assistant("Hidden response", ResponseMetaInfo.Empty),
            Message.User("Visible prompt 2", RequestMetaInfo.Empty),
            Message.Tool.Call(
                id = "tool-1",
                tool = "Bash",
                content = """{"command":"ls"}""",
                metaInfo = ResponseMetaInfo.Empty
            ),
            Message.Tool.Result(
                id = "tool-1",
                tool = "Bash",
                content = "file.txt",
                metaInfo = RequestMetaInfo.Empty
            )
        )

        val actualTurns = AgentCheckpointTurnSequencer.toVisibleTurns(history, null)
        val actualConversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = AgentCheckpointData(
                checkpointId = "checkpoint-1",
                createdAt = Instant.parse("2026-02-04T00:00:00Z"),
                nodePath = "agent/single_run/nodeExecuteTool",
                lastOutput = JSONObject(emptyMap()),
                messageHistory = history,
                version = 0
            ),
            projectInstructions = null
        )

        assertThat(actualConversation.messages.map { it.prompt })
            .containsExactlyElementsOf(actualTurns.map { it.prompt })
    }

    @Test
    fun `keeps events after synthetic todo user message within the active visible turn`() {
        val history = listOf(
            Message.User("Implement feature X", RequestMetaInfo.Empty),
            Message.Assistant("Starting implementation", ResponseMetaInfo.Empty),
            Message.Tool.Call(
                id = "bash-1",
                tool = "Bash",
                content = """{"command":"ls"}""",
                metaInfo = ResponseMetaInfo.Empty
            ),
            Message.Tool.Result(
                id = "bash-1",
                tool = "Bash",
                content = "file.txt",
                metaInfo = RequestMetaInfo.Empty
            ),
            Message.User(
                "It seems that you haven't created a todo list yet. If the task on hand requires multiple steps then create a todo list to track your changes.",
                RequestMetaInfo.Empty
            ),
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
                id = "read-1",
                tool = "Read",
                content = """{"path":"src/Main.kt"}""",
                metaInfo = ResponseMetaInfo.Empty
            ),
            Message.Tool.Result(
                id = "read-1",
                tool = "Read",
                content = "content",
                metaInfo = RequestMetaInfo.Empty
            ),
            Message.Assistant("Done", ResponseMetaInfo.Empty)
        )

        val actualTurns = AgentCheckpointTurnSequencer.toVisibleTurns(
            history = history,
            projectInstructions = null,
            preserveSyntheticContinuation = true
        )

        assertThat(actualTurns).hasSize(1)
        assertThat(actualTurns.single().prompt).isEqualTo("Implement feature X")
        assertThat(actualTurns.single().events.map(::eventSignature))
            .containsExactly(
                "assistant:Starting implementation",
                "tool-call:Bash:bash-1",
                "tool-result:Bash:bash-1",
                "tool-call:Read:read-1",
                "tool-result:Read:read-1",
                "assistant:Done"
            )
    }

    private fun eventSignature(event: AgentCheckpointTurnSequencer.TurnEvent): String {
        return when (event) {
            is AgentCheckpointTurnSequencer.TurnEvent.Assistant -> "assistant:${event.content}"
            is AgentCheckpointTurnSequencer.TurnEvent.Reasoning -> "reasoning:${event.content}"
            is AgentCheckpointTurnSequencer.TurnEvent.ToolCall -> "tool-call:${event.tool}:${event.id}"
            is AgentCheckpointTurnSequencer.TurnEvent.ToolResult -> "tool-result:${event.tool}:${event.id}"
        }
    }

    private fun cacheableMetaInfo(): RequestMetaInfo {
        return RequestMetaInfo(
            timestamp = Clock.System.now(),
            metadata = JsonObject(mapOf("cacheable" to JsonPrimitive(true)))
        )
    }
}
