package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

internal object AgentSessionTimelineDialogBuilder {

    fun build(
        points: List<RunTimelinePoint>,
        onSelect: (RunTimelinePoint) -> Unit,
        reloadPoints: (suspend () -> List<RunTimelinePoint>)?,
        contextSelectionEnabled: Boolean,
        onNonCopyMenuAction: (() -> Unit)?,
        onSelectionStateChanged: (() -> Unit)?,
        timelinePointDisplayLabel: (RunTimelinePoint) -> String,
        canRollbackTimelinePoint: (RunTimelinePoint) -> Boolean,
        rollbackToTimelinePoint: (RunTimelinePoint, String) -> Unit,
        canCopyTimelinePointOutput: (RunTimelinePoint) -> Boolean,
        copyTimelinePointOutput: (RunTimelinePoint, String) -> Unit,
        launchBackground: (suspend () -> Unit) -> Unit
    ): TimelineDialogUi {
        fun runNodeKey(runNumber: Int): String = "run:$runNumber"
        fun checkpointNodeKey(point: RunTimelinePoint): String = "cp:${point.cacheKey}"

        fun buildTreeRoot(
            dataPoints: List<RunTimelinePoint>,
            checkedNodeState: Map<String, Boolean>,
            withCheckboxes: Boolean
        ): CheckedTreeNode {
            val groups = buildTimelineRunGroups(dataPoints)
            val root = CheckedTreeNode(TimelineTreeEntry("Session"))
            groups.forEach { group ->
                val toolCallSuffix = if (group.toolCallCount == 1) "tool call" else "tool calls"
                val runEntry = TimelineTreeEntry(
                    title = "Run ${group.runNumber}",
                    subtitle = "${group.toolCallCount} $toolCallSuffix",
                    icon = AllIcons.Vcs.History,
                    runNumber = group.runNumber
                )
                val runNode: DefaultMutableTreeNode = if (withCheckboxes) {
                    CheckedTreeNode(runEntry).apply {
                        isChecked = checkedNodeState[runNodeKey(group.runNumber)] ?: true
                    }
                } else {
                    DefaultMutableTreeNode(runEntry)
                }
                group.points.forEach { point ->
                    val pointEntry = TimelineTreeEntry(
                        title = point.title,
                        subtitle = point.subtitle.trim(),
                        icon = point.icon,
                        point = point
                    )
                    val pointNode: DefaultMutableTreeNode = if (withCheckboxes) {
                        val parentChecked = (runNode as? CheckedTreeNode)?.isChecked ?: true
                        CheckedTreeNode(pointEntry).apply {
                            isChecked = checkedNodeState[checkpointNodeKey(point)] ?: parentChecked
                        }
                    } else {
                        DefaultMutableTreeNode(pointEntry)
                    }
                    runNode.add(pointNode)
                }
                root.add(runNode)
            }
            return root
        }

        fun collectCheckedNonSystemMessageCounts(root: DefaultMutableTreeNode): Set<Int> {
            val checkedNonSystemMessageCounts = mutableSetOf<Int>()
            for (index in 0 until root.childCount) {
                val runNode = root.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                for (childIndex in 0 until runNode.childCount) {
                    val childNode =
                        runNode.getChildAt(childIndex) as? DefaultMutableTreeNode ?: continue
                    val childEntry = childNode.userObject as? TimelineTreeEntry ?: continue
                    val point = childEntry.point ?: continue
                    val nonSystemMessageCount = point.nonSystemMessageCount ?: continue
                    if (childNode is CheckedTreeNode && childNode.isChecked) {
                        checkedNonSystemMessageCounts.add(nonSystemMessageCount)
                    }
                }
            }
            return checkedNonSystemMessageCounts
        }

        fun captureCheckedRunState(root: DefaultMutableTreeNode): Map<String, Boolean> {
            val state = mutableMapOf<String, Boolean>()
            for (index in 0 until root.childCount) {
                val runNode = root.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                val runEntry = runNode.userObject as? TimelineTreeEntry
                val runNumber = runEntry?.runNumber
                if (runNode is CheckedTreeNode && runNumber != null) {
                    state[runNodeKey(runNumber)] = runNode.isChecked
                }

                for (childIndex in 0 until runNode.childCount) {
                    val childNode =
                        runNode.getChildAt(childIndex) as? DefaultMutableTreeNode ?: continue
                    val childEntry = childNode.userObject as? TimelineTreeEntry ?: continue
                    val point = childEntry.point ?: continue
                    if (childNode is CheckedTreeNode) {
                        state[checkpointNodeKey(point)] = childNode.isChecked
                    }
                }
            }
            return state
        }

        fun ensureCheckedStateDefaults(
            dataPoints: List<RunTimelinePoint>,
            checkedNodeState: MutableMap<String, Boolean>
        ) {
            if (!contextSelectionEnabled) return
            buildTimelineRunGroups(dataPoints).forEach { group ->
                checkedNodeState.putIfAbsent(runNodeKey(group.runNumber), true)
            }
            dataPoints.forEach { point ->
                checkedNodeState.putIfAbsent(checkpointNodeKey(point), true)
            }
        }

        fun expandAllRows(tree: JTree) {
            var row = 0
            while (row < tree.rowCount) {
                tree.expandRow(row)
                row++
            }
        }

        var latestPoints = points
        var editMode = false
        val groups = buildTimelineRunGroups(latestPoints)
        val checkedNodeState = if (contextSelectionEnabled) {
            buildMap {
                groups.forEach { group -> put(runNodeKey(group.runNumber), true) }
                latestPoints.forEach { point -> put(checkpointNodeKey(point), true) }
            }.toMutableMap()
        } else {
            mutableMapOf()
        }
        ensureCheckedStateDefaults(latestPoints, checkedNodeState)

        fun headerText(): String {
            return when {
                !contextSelectionEnabled ->
                    "Choose a point in this session timeline."

                editMode ->
                    "Right-click row for rollback/new/copy. Check runs or checkpoints to keep in context."

                else ->
                    "Right-click row for rollback/new/copy. Click Edit to modify current context."
            }
        }

        val header = JBLabel(headerText()).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.emptyBottom(5)
        }

        lateinit var tree: CheckboxTree

        fun captureCurrentCheckedState() {
            if (!contextSelectionEnabled || !editMode) return
            val currentRoot = tree.model.root as? DefaultMutableTreeNode ?: return
            checkedNodeState.clear()
            checkedNodeState.putAll(captureCheckedRunState(currentRoot))
            ensureCheckedStateDefaults(latestPoints, checkedNodeState)
        }

        fun rebuildTree(
            dataPoints: List<RunTimelinePoint>,
            captureCurrentSelectionState: Boolean
        ) {
            if (captureCurrentSelectionState) {
                captureCurrentCheckedState()
            }
            latestPoints = dataPoints
            ensureCheckedStateDefaults(latestPoints, checkedNodeState)
            tree.model = DefaultTreeModel(
                buildTreeRoot(
                    dataPoints = latestPoints,
                    checkedNodeState = checkedNodeState,
                    withCheckboxes = contextSelectionEnabled && editMode
                )
            )
            expandAllRows(tree)
            tree.revalidate()
            tree.repaint()
            if (contextSelectionEnabled && editMode) {
                onSelectionStateChanged?.invoke()
            }
        }

        tree = object : CheckboxTree(
            object : CheckboxTree.CheckboxTreeCellRenderer() {
                override fun customizeRenderer(
                    tree: JTree,
                    value: Any?,
                    selected: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean
                ) {
                    val node = value as? DefaultMutableTreeNode ?: return
                    val entry = node.userObject as? TimelineTreeEntry ?: return
                    textRenderer.icon = entry.icon
                    if (entry.point == null) {
                        textRenderer.append(
                            entry.title,
                            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        )
                        toolTipText = null
                        if (entry.subtitle.isNotBlank()) {
                            textRenderer.append(
                                "  ${entry.subtitle}",
                                SimpleTextAttributes.GRAYED_ATTRIBUTES
                            )
                        }
                    } else {
                        textRenderer.append(entry.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        if (entry.subtitle.isNotBlank()) {
                            textRenderer.append(
                                "  ${entry.subtitle}",
                                SimpleTextAttributes.GRAYED_ATTRIBUTES
                            )
                            toolTipText = entry.subtitle
                        } else {
                            toolTipText = null
                        }
                    }
                }
            },
            buildTreeRoot(
                dataPoints = latestPoints,
                checkedNodeState = checkedNodeState,
                withCheckboxes = contextSelectionEnabled && editMode
            )
        ) {}.apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 0
            border = JBUI.Borders.empty(2, 0)

            expandAllRows(this)

            fun selectCurrentTimelinePoint() {
                val selectedNode = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val selectedEntry = selectedNode.userObject as? TimelineTreeEntry ?: return
                val point = selectedEntry.point ?: return
                onSelect(point)
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount >= 2) {
                        selectCurrentTimelinePoint()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    maybeShowTimelineContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    maybeShowTimelineContextMenu(e)
                    if (contextSelectionEnabled && editMode && !e.isPopupTrigger && e.button == MouseEvent.BUTTON1) {
                        SwingUtilities.invokeLater {
                            onSelectionStateChanged?.invoke()
                        }
                    }
                }

                private fun maybeShowTimelineContextMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return

                    val path = getPathForLocation(e.x, e.y) ?: return
                    selectionPath = path
                    val selectedNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val selectedEntry = selectedNode.userObject as? TimelineTreeEntry ?: return
                    val point = selectedEntry.point
                    val selectedLabel =
                        point?.let { timelinePointDisplayLabel(it) } ?: selectedEntry.title

                    val group = DefaultActionGroup(
                        object : DumbAwareAction(
                            "Rollback",
                            "Rollback to checkpoint",
                            AllIcons.Actions.Undo
                        ) {
                            override fun actionPerformed(event: AnActionEvent) {
                                val timelinePoint = point ?: return
                                onNonCopyMenuAction?.invoke()
                                rollbackToTimelinePoint(timelinePoint, selectedLabel)
                            }

                            override fun update(event: AnActionEvent) {
                                event.presentation.isEnabled =
                                    point != null && canRollbackTimelinePoint(point)
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread =
                                ActionUpdateThread.EDT
                        },
                        object : DumbAwareAction(
                            "Continue From New Session",
                            "Start new session from given checkpoint",
                            AllIcons.General.Add
                        ) {
                            override fun actionPerformed(event: AnActionEvent) {
                                val timelinePoint = point ?: return
                                onNonCopyMenuAction?.invoke()
                                onSelect(timelinePoint)
                            }

                            override fun update(event: AnActionEvent) {
                                event.presentation.isEnabled = point != null
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread =
                                ActionUpdateThread.EDT
                        },
                        object : DumbAwareAction(
                            "Copy Output",
                            "Copies the Tool or Assistant's output",
                            AllIcons.Actions.Copy
                        ) {
                            override fun actionPerformed(event: AnActionEvent) {
                                val timelinePoint = point ?: return
                                copyTimelinePointOutput(timelinePoint, selectedLabel)
                            }

                            override fun update(event: AnActionEvent) {
                                event.presentation.isEnabled =
                                    point != null && canCopyTimelinePointOutput(point)
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread =
                                ActionUpdateThread.EDT
                        }
                    )
                    ActionManager.getInstance()
                        .createActionPopupMenu("AgentTimeline.CheckpointMenu", group)
                        .component
                        .show(e.component, e.x, e.y)
                }
            })

            addKeyListener(object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) {
                    if (contextSelectionEnabled && editMode && e.keyCode == KeyEvent.VK_SPACE) {
                        onSelectionStateChanged?.invoke()
                    }
                }
            })

            inputMap.put(KeyStroke.getKeyStroke("ENTER"), "timeline.select")
            actionMap.put("timeline.select", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    selectCurrentTimelinePoint()
                }
            })
        }

        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        SwingUtilities.invokeLater {
            if (tree.rowCount > 0) {
                tree.scrollRowToVisible(tree.rowCount - 1)
                scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
            }
        }

        val popupWidth = JBUI.scale(620)
        val minPopupWidth = JBUI.scale(560)
        val maxPopupWidth = JBUI.scale(760)
        val popupHeight =
            JBUI.scale(computeTimelineDialogHeight(groups.sumOf { it.points.size + 1 }))

        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            preferredSize = Dimension(popupWidth, popupHeight)
            minimumSize = Dimension(minPopupWidth, popupHeight)
            maximumSize = Dimension(maxPopupWidth, popupHeight)
            add(header, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        return TimelineDialogUi(
            component = container,
            getSelectedPoint = {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val entry = node?.userObject as? TimelineTreeEntry
                entry?.point
            },
            getCheckedNonSystemMessageCounts = {
                val root = tree.model.root as? DefaultMutableTreeNode
                if (root == null) emptySet() else collectCheckedNonSystemMessageCounts(root)
            },
            refresh = {
                reloadPoints?.let { loader ->
                    launchBackground {
                        val refreshedPoints = loader()
                        runInEdt {
                            rebuildTree(refreshedPoints, captureCurrentSelectionState = true)
                        }
                    }
                }
            },
            setEditMode = { value ->
                if (contextSelectionEnabled && editMode != value) {
                    if (editMode) captureCurrentCheckedState()
                    editMode = value
                    header.text = headerText()
                    rebuildTree(latestPoints, captureCurrentSelectionState = false)
                }
            },
            isEditMode = { editMode }
        )
    }

    private fun buildTimelineRunGroups(points: List<RunTimelinePoint>): List<TimelineRunGroup> {
        if (points.isEmpty()) return emptyList()

        val grouped = linkedMapOf<Int, MutableList<RunTimelinePoint>>()
        points.forEach { point ->
            val key = if (point.runIndex <= 0) 1 else point.runIndex
            grouped.getOrPut(key) { mutableListOf() }.add(point)
        }

        return grouped.values.mapIndexed { index, runPoints ->
            TimelineRunGroup(
                runNumber = index + 1,
                points = runPoints,
                toolCallCount = runPoints.count { it.kind == TimelinePointKind.TOOL_CALL }
            )
        }
    }

    private fun computeTimelineDialogHeight(estimatedRowCount: Int): Int {
        val visibleRows = estimatedRowCount.coerceIn(4, 16)
        val rowsHeight = visibleRows * 26
        return (rowsHeight + 44).coerceIn(160, 460)
    }
}
