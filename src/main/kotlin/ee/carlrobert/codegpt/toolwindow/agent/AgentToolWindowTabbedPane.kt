package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.agent.AgentService
import ee.carlrobert.codegpt.conversations.Conversation
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.*
import javax.swing.*

class AgentToolWindowTabbedPane(private val project: Project) : JBTabbedPane(), Disposable {

    private val activeTabMapping = TreeMap<String, AgentToolWindowTabPanel>(
        Comparator { o1, o2 ->
            val nums1 = o1.replace("\\D".toRegex(), "")
            val nums2 = o2.replace("\\D".toRegex(), "")

            if (nums1.isNotEmpty() && nums2.isNotEmpty()) {
                val n1 = nums1.toInt()
                val n2 = nums2.toInt()
                n1.compareTo(n2)
            } else {
                when {
                    nums1.isNotEmpty() -> -1
                    nums2.isNotEmpty() -> 1
                    else -> o1.compareTo(o2, ignoreCase = true)
                }
            }
        }
    )

    private var isTabActive = true

    init {
        tabComponentInsets = null
        setComponentPopupMenu(TabPopupMenu())
        addChangeListener { refreshTabState() }
    }

    enum class TabStatus {
        RUNNING,
        STOPPED,
        APPROVAL,
        COMPLETED_UNSEEN
    }

    private data class TabState(
        var status: TabStatus = TabStatus.STOPPED,
        var unseen: Boolean = false
    )

    private val sessionStates = mutableMapOf<String, TabState>()

    fun updateStatusForSession(sessionId: String, status: TabStatus) {
        val state = sessionStates.getOrPut(sessionId) { TabState() }
        state.status = status
        applyIconForSession(sessionId)
    }

    fun onAgentCompleted(sessionId: String) {
        val state = sessionStates.getOrPut(sessionId) { TabState() }
        state.status = TabStatus.STOPPED

        val selectedSessionId = tryFindActiveTabPanel()
            .map { it.getSessionId() }
            .orElse(null)

        state.unseen = selectedSessionId != sessionId

        applyIconForSession(sessionId)
    }

    fun onTabHidden() {
        isTabActive = false
    }

    private fun statusIcon(status: TabStatus): Icon? = when (status) {
        TabStatus.RUNNING -> CircleIcon(JBColor(0x43A047, 0x66BB6A), 8)
        TabStatus.STOPPED -> null
        TabStatus.APPROVAL -> CircleIcon(JBColor(0xFB8C00, 0xFFA726), 8)
        TabStatus.COMPLETED_UNSEEN -> CircleIcon(JBColor(0x2196F3, 0x42A5F5), 8)
    }

    private fun computeIcon(state: TabState): Icon? {
        return if (state.unseen) {
            CircleIcon(JBColor(0x2196F3, 0x42A5F5), 8)
        } else {
            statusIcon(state.status)
        }
    }

    private fun applyIconForSession(sessionId: String) {
        tryFindTabTitle(sessionId).ifPresent { title ->
            val index = indexOfTab(title)
            if (index >= 0) {
                val state = sessionStates[sessionId] ?: return@ifPresent
                val icon = computeIcon(state)
                val custom = getTabComponentAt(index)
                if (custom is JPanel) {
                    val comps = custom.components
                    if (comps.isNotEmpty() && comps[0] is JBLabel) {
                        val label = comps[0] as JBLabel
                        label.icon = icon
                        label.iconTextGap = 6
                        label.revalidate()
                        label.repaint()
                    }
                } else {
                    setIconAt(index, icon)
                }
            }
        }
    }

    fun addNewTab(toolWindowPanel: AgentToolWindowTabPanel) {
        addNewTab(toolWindowPanel, true)
    }

    fun addNewTab(toolWindowPanel: AgentToolWindowTabPanel, select: Boolean) {
        val tabIndices = activeTabMapping.keys.toTypedArray()
        var nextIndex = 0

        for (title in tabIndices) {
            if (title.matches("Agent \\d+".toRegex())) {
                val numberPart = title.replace("\\D+".toRegex(), "")
                val tabNum = numberPart.toInt()
                if (tabNum - 1 == nextIndex) {
                    nextIndex++
                } else {
                    break
                }
            }
        }

        val title = getTitle(toolWindowPanel, nextIndex)
        val sessionId = toolWindowPanel.getSessionId()
        toolWindowPanel.getAgentSession().displayName = title

        super.insertTab(title, null, toolWindowPanel, null, nextIndex)
        activeTabMapping[title] = toolWindowPanel
        if (select) {
            selectedIndex = nextIndex
        }

        sessionStates[sessionId] = TabState(status = TabStatus.STOPPED, unseen = false)

        setTabComponentAt(nextIndex, createTabButtonPanel(title, nextIndex > 0, TabStatus.STOPPED))
        toolWindowPanel.requestFocusForTextArea()

        Disposer.register(this, toolWindowPanel)
    }

    private fun getTitle(toolWindowTabPanel: AgentToolWindowTabPanel, nextIndex: Int): String {
        val session = toolWindowTabPanel.getAgentSession()
        val customName = session.displayName

        return if (customName.isNotBlank()) {
            ensureUniqueName(customName, null)
        } else {
            "Agent ${nextIndex + 1}"
        }
    }

    fun tryFindTabTitle(sessionId: String): Optional<String> {
        return activeTabMapping.entries.stream()
            .filter { entry -> entry.value.getSessionId() == sessionId }
            .findFirst()
            .map { it.key }
    }

    fun tryFindActiveTabPanel(): Optional<AgentToolWindowTabPanel> {
        val selectedIndex = selectedIndex
        if (selectedIndex == -1) {
            return Optional.empty()
        }

        return Optional.ofNullable(activeTabMapping[getTitleAt(selectedIndex)])
    }

    fun clearAll() {
        removeAll()
        activeTabMapping.clear()
    }

    fun renameTab(tabIndex: Int, newName: String) {
        if (tabIndex !in 0..<tabCount) {
            return
        }

        val oldTitle = getTitleAt(tabIndex)
        val panel = activeTabMapping[oldTitle] ?: return

        val uniqueName = ensureUniqueName(newName, oldTitle)

        setTitleAt(tabIndex, uniqueName)

        val sessionId = panel.getSessionId()
        val currentStatus = if (project.service<AgentService>().isSessionRunning(sessionId)) {
            TabStatus.RUNNING
        } else {
            TabStatus.STOPPED
        }

        setTabComponentAt(tabIndex, createTabButtonPanel(uniqueName, tabIndex > 0, currentStatus))

        activeTabMapping.remove(oldTitle)
        activeTabMapping[uniqueName] = panel

        panel.getAgentSession().displayName = uniqueName

        applyIconForSession(sessionId)
    }

    private fun ensureUniqueName(desiredName: String, currentTitle: String?): String {
        val baseName = desiredName.trim()
        var uniqueName = baseName
        var counter = 2

        while (activeTabMapping.containsKey(uniqueName) && uniqueName != currentTitle) {
            uniqueName = "$baseName ($counter)"
            counter++
        }

        return uniqueName
    }

    private fun refreshTabState() {
        val selectedIndex = selectedIndex
        if (selectedIndex == -1) {
            return
        }

        isTabActive = true

        tryFindActiveTabPanel().ifPresent { selectedPanel ->
            val selectedSessionId = selectedPanel.getSessionId()
            val selectedState = sessionStates[selectedSessionId]
            if (selectedState != null && selectedState.unseen) {
                selectedState.unseen = false
                applyIconForSession(selectedSessionId)
            }
        }

        for (i in 0 until tabCount) {
            val title = getTitleAt(i)
            val panel = activeTabMapping[title]
            panel?.let {
                val sessionId = it.getSessionId()
                val state = sessionStates[sessionId] ?: TabState()

                if (project.service<AgentService>().isSessionRunning(sessionId)) {
                    state.status = TabStatus.RUNNING
                    state.unseen = false
                }

                applyIconForSession(sessionId)
            }
        }
    }

    private fun renameAgentSession(tabIndex: Int) {
        if (tabIndex <= 0) {
            return
        }

        val currentTitle = getTitleAt(tabIndex)
        val newName = Messages.showInputDialog(
            "Enter new title:",
            "Rename Agent Session",
            Messages.getQuestionIcon(),
            currentTitle,
            AgentSessionNameValidator()
        )

        if (newName != null && newName != currentTitle) {
            renameTab(tabIndex, newName)
        }
    }

    private class AgentSessionNameValidator : InputValidator {
        private val maxNameLength = 50

        override fun checkInput(inputString: String?): Boolean {
            if (inputString == null || inputString.trim().isEmpty()) {
                return false
            }
            if (inputString.length > maxNameLength) {
                return false
            }
            return !inputString.contains("\n") && !inputString.contains("\t")
        }

        override fun canClose(inputString: String?): Boolean {
            return checkInput(inputString)
        }
    }

    fun resetCurrentlyActiveTabPanel() {
        tryFindActiveTabPanel().ifPresent { tabPanel ->
            val oldSessionId = tabPanel.getSessionId()
            val oldDisplayName = tabPanel.getAgentSession().displayName
            Disposer.dispose(tabPanel)
            activeTabMapping.remove(getTitleAt(selectedIndex))
            removeTabAt(selectedIndex)
            sessionStates.remove(oldSessionId)

            project.service<AgentToolWindowContentManager>().removeSession(oldSessionId)
            val newSession = AgentSession(
                UUID.randomUUID().toString(),
                Conversation(),
                displayName = oldDisplayName
            )
            project.service<AgentToolWindowContentManager>().createNewAgentTab(newSession)
            repaint()
            revalidate()
        }
    }

    private fun createTabButtonPanel(
        title: String,
        closeable: Boolean,
        status: TabStatus = TabStatus.STOPPED
    ): JPanel {
        val titleLabel = JBLabel(title).apply {
            icon = statusIcon(status)
            iconTextGap = 4
        }

        val panel = JBUI.Panels.simplePanel(4, 0)
            .addToLeft(titleLabel)

        if (closeable) {
            val closeIcon = AllIcons.Actions.Close
            val button = JButton(closeIcon).apply {
                addActionListener(CloseActionListener(title))
                preferredSize = Dimension(closeIcon.iconWidth, closeIcon.iconHeight)
                border = BorderFactory.createEmptyBorder()
                isContentAreaFilled = false
                toolTipText = "Close Agent"
                rolloverIcon = AllIcons.Actions.CloseHovered
            }
            panel.addToRight(button)
        }

        return panel.andTransparent()
    }

    private class CircleIcon(private val color: Color, private val diameter: Int = 8) :
        Icon {
        override fun getIconWidth(): Int = diameter
        override fun getIconHeight(): Int = diameter
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = g as? Graphics2D ?: return
            val oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, diameter, diameter)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
        }
    }

    private inner class CloseActionListener(private val title: String) : ActionListener {
        override fun actionPerformed(evt: ActionEvent) {
            val tabIndex = indexOfTab(title)
            if (tabIndex >= 0) {
                activeTabMapping[title]?.let { panel ->
                    sessionStates.remove(panel.getSessionId())
                    project.service<AgentToolWindowContentManager>().removeSession(panel.getSessionId())
                    Disposer.dispose(panel)
                }
                removeTabAt(tabIndex)
                activeTabMapping.remove(title)
            }
        }
    }

    private inner class TabPopupMenu : JPopupMenu() {
        private var selectedPopupTabIndex = -1

        init {
            add(createPopupMenuItem("Rename Title") {
                if (selectedPopupTabIndex > 0) {
                    renameAgentSession(selectedPopupTabIndex)
                }
            })
            addSeparator()
            add(createPopupMenuItem("Close") {
                if (selectedPopupTabIndex > 0) {
                    val title = getTitleAt(selectedPopupTabIndex)
                    activeTabMapping[title]?.let { panel ->
                        sessionStates.remove(panel.getSessionId())
                        project.service<AgentToolWindowContentManager>().removeSession(panel.getSessionId())
                        Disposer.dispose(panel)
                    }
                    removeTabAt(selectedPopupTabIndex)
                    activeTabMapping.remove(title)
                }
            })
            add(createPopupMenuItem("Close Other Tabs") {
                val selectedPopupTabTitle = getTitleAt(selectedPopupTabIndex)
                val tabPanel = activeTabMapping[selectedPopupTabTitle]
                val keepSessionId = tabPanel?.getSessionId()
                sessionStates.keys
                    .filter { it != keepSessionId }
                    .forEach { sessionStates.remove(it) }
                activeTabMapping.values
                    .map { it.getSessionId() }
                    .filter { it != keepSessionId }
                    .forEach { project.service<AgentToolWindowContentManager>().removeSession(it) }

                clearAll()
                tabPanel?.let { addNewTab(it) }
            })
        }

        override fun show(invoker: Component, x: Int, y: Int) {
            selectedPopupTabIndex = this@AgentToolWindowTabbedPane.getUI()
                .tabForCoordinate(this@AgentToolWindowTabbedPane, x, y)
            if (selectedPopupTabIndex > 0) {
                super.show(invoker, x, y)
            }
        }

        private fun createPopupMenuItem(label: String, listener: ActionListener): JBMenuItem {
            return JBMenuItem(label).apply {
                addActionListener(listener)
            }
        }
    }

    override fun dispose() {
        clearAll()
    }
}
