package ee.carlrobert.codegpt.toolwindow.agent

import ee.carlrobert.codegpt.agent.history.CheckpointRef
import javax.swing.Icon
import javax.swing.JComponent
import ai.koog.prompt.message.Message as PromptMessage

internal data class RunTimelinePoint(
    val cacheKey: String,
    val checkpointRef: CheckpointRef?,
    val title: String,
    val subtitle: String,
    val runLabel: String? = null,
    val icon: Icon? = null,
    val nonSystemMessageCount: Int? = null,
    val outputText: String? = null,
    val toolCallId: String? = null,
    val kind: TimelinePointKind = TimelinePointKind.ASSISTANT,
    val runIndex: Int = 0
)

internal enum class TimelinePointKind {
    USER,
    ASSISTANT,
    REASONING,
    TOOL_CALL
}

internal data class TimelineRunGroup(
    val runNumber: Int,
    val points: List<RunTimelinePoint>,
    val toolCallCount: Int
)

internal data class TimelineTreeEntry(
    val title: String,
    val subtitle: String = "",
    val icon: Icon? = null,
    val point: RunTimelinePoint? = null,
    val runNumber: Int? = null
)

internal data class HistoricalRollbackOperation(
    val filePath: String,
    val searchText: String,
    val replacementText: String,
    val replaceAll: Boolean,
    val sourceTool: HistoricalRollbackSourceTool
)

internal enum class HistoricalRollbackSourceTool(
    val displayName: String,
    val symbol: String
) {
    EDIT(displayName = "Edit", symbol = "E"),
    WRITE(displayName = "Write", symbol = "W")
}

internal data class SessionContextSnapshot(
    val baseHistory: List<PromptMessage>
)

internal data class TimelineDialogUi(
    val component: JComponent,
    val getSelectedPoint: () -> RunTimelinePoint?,
    val getCheckedNonSystemMessageCounts: () -> Set<Int>,
    val refresh: () -> Unit,
    val setEditMode: (Boolean) -> Unit,
    val isEditMode: () -> Boolean
)
