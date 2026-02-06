package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.intellij.openapi.components.service
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import testsupport.IntegrationTest
import kotlin.io.path.Path
import java.util.UUID

class AgentCheckpointHistoryServiceTest : IntegrationTest() {

    fun testListsThreadsAndPicksLatestNonTombstoneCheckpoint() {
        runBlocking {
            val storage = storage()
            val agentAId = "agent-a-${UUID.randomUUID()}"
            val agentBId = "agent-b-${UUID.randomUUID()}"

            storage.saveCheckpoint(
                agentAId,
                checkpoint(
                    checkpointId = "a-1",
                    createdAt = Instant.parse("2026-02-04T00:00:00Z"),
                    nodePath = "$agentAId/single_run/__finish__",
                    history = listOf(
                        Message.User("Initial prompt", RequestMetaInfo.Empty),
                        Message.Assistant("Initial response", ResponseMetaInfo.Empty)
                    )
                )
            )
            storage.saveCheckpoint(
                agentAId,
                checkpoint(
                    checkpointId = "a-2",
                    createdAt = Instant.parse("2026-02-04T00:05:00Z"),
                    nodePath = "$agentAId/single_run/nodeExecuteTool",
                    history = listOf(
                        Message.User("Latest prompt", RequestMetaInfo.Empty),
                        Message.Assistant("Latest response", ResponseMetaInfo.Empty)
                    )
                )
            )
            storage.saveCheckpoint(
                agentAId,
                checkpoint(
                    checkpointId = "a-3-tombstone",
                    createdAt = Instant.parse("2026-02-04T00:06:00Z"),
                    nodePath = "$agentAId/single_run/__finish__",
                    history = emptyList(),
                    tombstone = true
                )
            )
            storage.saveCheckpoint(
                agentBId,
                checkpoint(
                    checkpointId = "b-1",
                    createdAt = Instant.parse("2026-02-04T00:01:00Z"),
                    nodePath = "$agentBId/single_run/__start__",
                    history = listOf(Message.User("Thread B", RequestMetaInfo.Empty))
                )
            )

            val scopedThreads = historyService()
                .listThreads()
                .filter { it.agentId == agentAId || it.agentId == agentBId }

            assertThat(scopedThreads)
                .extracting(
                    "agentId",
                    "latest.checkpointId",
                    "status",
                    "runCount",
                    "title",
                    "preview"
                )
                .containsExactlyInAnyOrder(
                    tuple(
                        agentAId,
                        "a-2",
                        ThreadStatus.PARTIAL,
                        2,
                        "Latest prompt",
                        "Latest response"
                    ),
                    tuple(agentBId, "b-1", ThreadStatus.UNKNOWN, 1, "Thread B", "")
                )
        }
    }

    fun testLoadsCheckpointByCheckpointReference() {
        runBlocking {
            val storage = storage()
            val agentId = "agent-z-${UUID.randomUUID()}"
            val persisted = checkpoint(
                checkpointId = "a-9",
                createdAt = Instant.parse("2026-02-04T00:15:00Z"),
                nodePath = "$agentId/single_run/__finish__",
                history = listOf(Message.User("Prompt", RequestMetaInfo.Empty))
            )
            storage.saveCheckpoint(agentId, persisted)

            val loaded = historyService().loadCheckpoint(CheckpointRef(agentId, "a-9"))

            assertThat(loaded).isNotNull
            assertThat(loaded!!.checkpointId).isEqualTo("a-9")
            assertThat(loaded.messageHistory).hasSize(1)
        }
    }

    fun testLoadResumeCheckpointFallsBackWhenLatestIsFinishNode() {
        runBlocking {
            val storage = storage()
            val agentId = "agent-r-${UUID.randomUUID()}"
            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "r-1",
                    createdAt = Instant.parse("2026-02-04T01:00:00Z"),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    history = listOf(Message.User("First", RequestMetaInfo.Empty))
                )
            )
            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "r-2",
                    createdAt = Instant.parse("2026-02-04T01:01:00Z"),
                    nodePath = "$agentId/single_run/__finish__",
                    history = listOf(Message.Assistant("Done", ResponseMetaInfo.Empty))
                )
            )

            val resume = historyService().loadResumeCheckpoint(CheckpointRef(agentId, "r-2"))

            assertThat(resume).isNotNull
            assertThat(resume!!.checkpointId).isEqualTo("r-1")
        }
    }

    fun testLoadLatestResumeCheckpointPrefersResumableNodeWhenLatestIsFinish() {
        runBlocking {
            val storage = storage()
            val agentId = "agent-latest-resume-${UUID.randomUUID()}"
            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "latest-resumable",
                    createdAt = Instant.parse("2026-02-04T01:00:00Z"),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    history = listOf(Message.User("First", RequestMetaInfo.Empty))
                )
            )
            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "latest-finish",
                    createdAt = Instant.parse("2026-02-04T01:01:00Z"),
                    nodePath = "$agentId/single_run/__finish__",
                    history = listOf(Message.Assistant("Done", ResponseMetaInfo.Empty))
                )
            )

            val latestResume = historyService().loadLatestResumeCheckpoint(agentId)

            assertThat(latestResume).isNotNull
            assertThat(latestResume!!.checkpointId).isEqualTo("latest-resumable")
        }
    }

    fun testPrefersLatestTodoWriteTitleForThreadTitle() {
        runBlocking {
            val storage = storage()
            val agentId = "agent-todo-${UUID.randomUUID()}"

            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "todo-1",
                    createdAt = Instant.parse("2026-02-04T01:02:00Z"),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    history = listOf(
                        Message.User("Please debug the crash", RequestMetaInfo.Empty),
                        Message.Tool.Call(
                            id = "tool-1",
                            tool = "TodoWrite",
                            content = """{"title":"Fix crash flow","todos":[]}""",
                            metaInfo = ResponseMetaInfo.Empty
                        ),
                        Message.User("Also handle retries", RequestMetaInfo.Empty)
                    )
                )
            )

            val thread = historyService().listThreads().first { it.agentId == agentId }

            assertThat(thread.title).isEqualTo("Fix crash flow")
        }
    }

    fun testFallsBackToLatestUserMessageWhenNoTodoWriteTitleExists() {
        runBlocking {
            val storage = storage()
            val agentId = "agent-user-${UUID.randomUUID()}"

            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "user-1",
                    createdAt = Instant.parse("2026-02-04T01:03:00Z"),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    history = listOf(
                        Message.User("First request", RequestMetaInfo.Empty),
                        Message.Tool.Call(
                            id = "tool-2",
                            tool = "TodoWrite",
                            content = """{"title":" ","todos":[]}""",
                            metaInfo = ResponseMetaInfo.Empty
                        ),
                        Message.User("Latest request", RequestMetaInfo.Empty)
                    )
                )
            )

            val thread = historyService().listThreads().first { it.agentId == agentId }

            assertThat(thread.title).isEqualTo("Latest request")
        }
    }

    fun testSkipsCacheableUserMessagesWhenResolvingThreadTitle() {
        runBlocking {
            val storage = storage()
            val agentId = "agent-cacheable-${UUID.randomUUID()}"

            storage.saveCheckpoint(
                agentId,
                checkpoint(
                    checkpointId = "cacheable-1",
                    createdAt = Instant.parse("2026-02-04T01:04:00Z"),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    history = listOf(
                        Message.User(
                            "Project-level instruction payload",
                            RequestMetaInfo(
                                timestamp = Clock.System.now(),
                                metadata = JsonObject(mapOf("cacheable" to JsonPrimitive(true)))
                            )
                        ),
                        Message.User("Real user request", RequestMetaInfo.Empty)
                    )
                )
            )

            val thread = historyService().listThreads().first { it.agentId == agentId }

            assertThat(thread.title).isEqualTo("Real user request")
        }
    }

    private fun storage(): JVMFilePersistenceStorageProvider {
        return JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
    }

    private fun historyService(): AgentCheckpointHistoryService {
        return project.service()
    }

    private fun checkpoint(
        checkpointId: String,
        createdAt: Instant,
        nodePath: String,
        history: List<Message>,
        tombstone: Boolean = false
    ): AgentCheckpointData {
        val properties = if (tombstone) {
            mapOf("tombstone" to JsonPrimitive(true))
        } else {
            null
        }

        return AgentCheckpointData(
            checkpointId = checkpointId,
            createdAt = createdAt,
            nodePath = nodePath,
            lastInput = JsonNull,
            messageHistory = history,
            version = 0,
            properties = properties
        )
    }
}
