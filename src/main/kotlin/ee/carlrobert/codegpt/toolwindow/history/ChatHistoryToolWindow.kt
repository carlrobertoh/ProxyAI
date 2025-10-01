package ee.carlrobert.codegpt.toolwindow.history

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.actions.toolwindow.DeleteAllConversationsAction
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.util.ProjectPathUtils
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import kotlin.concurrent.thread

class ChatHistoryToolWindow(private val project: Project) : BorderLayoutPanel() {

    companion object {
        private val KEY: Key<Boolean> = Key.create("SELECTED_STATE")
        private const val SEARCH_DEBOUNCE_MS = 300
        private const val MAX_MESSAGES_TO_SEARCH = 5
    }

    private val conversationService = ConversationService.getInstance()
    private val chatHistoryListPanel = ChatHistoryListPanel()
    private val searchField = SearchTextField()
    private var allConversations = mutableListOf<Conversation>()
    private var sortOption = SortOption.UPDATED_DATE_DESC
    private var projectFilter: ProjectFilterState = ProjectFilterState.AllProjects
    private val statusLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8)
    }
    private var lastSearchText = ""
    private var lastFilteredConversations: List<Conversation>? = null
    private var isDataLoaded = false
    private var projectInfoCache: Map<String, ProjectInfo> = emptyMap()

    data class ProjectInfo(
        val path: String,
        val name: String,
        val conversationCount: Int
    )

    enum class SortOption(val propertyKey: String) {
        UPDATED_DATE_DESC("conversation.sortOption.recentlyUpdated"),
        UPDATED_DATE_ASC("conversation.sortOption.oldestFirst"),
        TITLE_ASC("conversation.sortOption.titleAscending"),
        TITLE_DESC("conversation.sortOption.titleDescending"),
        MESSAGE_COUNT_DESC("conversation.sortOption.mostMessages"),
        MESSAGE_COUNT_ASC("conversation.sortOption.leastMessages");

        val displayName: String
            get() = CodeGPTBundle.get(propertyKey)
    }

    sealed class ProjectFilterState {
        object AllProjects : ProjectFilterState()
        object CurrentProjectOnly : ProjectFilterState()
        data class SelectedProjects(val projectPaths: Set<String>) : ProjectFilterState()

        fun matches(conversationProjectPath: String?, currentProjectPath: String?): Boolean {
            return when (this) {
                is AllProjects -> true
                is CurrentProjectOnly -> conversationProjectPath == currentProjectPath
                is SelectedProjects -> projectPaths.contains(conversationProjectPath)
            }
        }

        fun getDisplayText(): String {
            return when (this) {
                is AllProjects -> "All Projects"
                is CurrentProjectOnly -> "Current Project Only"
                is SelectedProjects -> when (projectPaths.size) {
                    0 -> "No Projects"
                    1 -> "1 Project Selected"
                    else -> "${projectPaths.size} Projects Selected"
                }
            }
        }
    }

    init {
        setupUI()
        loadConversationsAsync()
        setupListeners()
    }

    private fun setupUI() {
        chatHistoryListPanel.apply {
            setOnConversationSelected { conversation ->
                ConversationsState.getInstance().setCurrentConversation(conversation)
            }

            setOnConversationDoubleClicked { conversation ->
                project.getService(ChatToolWindowContentManager::class.java)
                    .displayConversation(conversation)
            }

            setOnConversationDeleted { conversation ->
                deleteConversation(conversation)
            }
        }

        val searchPanel = createSearchPanel()

        val topPanel = panel {
            row {
                cell(searchPanel)
                    .align(AlignX.FILL)
            }.resizableRow()
            row {
                cell(statusLabel)
                    .align(AlignX.FILL)
                    .applyToComponent {
                        border = JBUI.Borders.empty(2, 8)
                    }
            }
            separator()
        }.apply {
            background = UIUtil.getPanelBackground()
        }

        addToTop(topPanel)
        addToCenter(chatHistoryListPanel)
    }

    private fun createSearchPanel(): JPanel {
        searchField.apply {
            textEditor.emptyText.text = CodeGPTBundle.get("conversation.searchField.placeholder")
            textEditor.border = JBUI.Borders.empty(2)
            textEditor.background = UIUtil.getPanelBackground().darker()
        }

        return panel {
            row {
                cell(searchField)
                    .align(AlignX.FILL)
                    .resizableColumn()
            }
        }.apply {
            border = JBUI.Borders.empty(4, 8, 4, 8)
            background = UIUtil.getPanelBackground()
        }
    }

    private fun updateSearchFieldState() {
        SwingUtilities.invokeLater {
            val hasText = searchField.text.isNotBlank()
            if (hasText && allConversations.isNotEmpty()) {
                val matchCount = countMatchingConversations(searchField.text)
                searchField.textEditor.emptyText.text = formatSearchResultMessage(matchCount)
            } else {
                searchField.textEditor.emptyText.text =
                    CodeGPTBundle.get("conversation.searchField.placeholder")
            }
        }
    }

    private fun countMatchingConversations(searchText: String): Int {
        return allConversations.count { conversation ->
            matchesSearchText(conversation, searchText)
        }
    }

    private fun formatSearchResultMessage(count: Int): String {
        return if (count == 1) {
            CodeGPTBundle.get("conversation.searchResult.singular", count)
        } else {
            CodeGPTBundle.get("conversation.searchResult.plural", count)
        }
    }

    private fun filterConversations(searchText: String): List<Conversation> {
        val projectFiltered = allConversations.filter { conversation ->
            projectFilter.matches(conversation.projectPath, project.basePath)
        }

        return if (searchText.isBlank()) {
            lastSearchText = ""
            lastFilteredConversations = null
            projectFiltered
        } else if (searchText == lastSearchText && lastFilteredConversations != null) {
            lastFilteredConversations?.filter { conversation ->
                projectFiltered.contains(conversation)
            }.orEmpty()
        } else {
            val startList = getOptimizedSearchStartList(searchText).filter { conversation ->
                projectFiltered.contains(conversation)
            }
            val filtered = startList.filter { conversation ->
                matchesSearchText(conversation, searchText)
            }
            cacheFilterResults(searchText, filtered)
            filtered
        }
    }

    private fun getOptimizedSearchStartList(searchText: String): List<Conversation> {
        return if (lastSearchText.isNotEmpty() && searchText.startsWith(lastSearchText) && lastFilteredConversations != null) {
            lastFilteredConversations.orEmpty()
        } else {
            allConversations
        }
    }

    private fun cacheFilterResults(searchText: String, filtered: List<Conversation>) {
        lastSearchText = searchText
        lastFilteredConversations = filtered
    }

    private fun getSortIcon(sortOption: SortOption) = when (sortOption) {
        SortOption.UPDATED_DATE_DESC -> AllIcons.ObjectBrowser.SortByType
        SortOption.UPDATED_DATE_ASC -> AllIcons.ObjectBrowser.SortByType
        SortOption.TITLE_ASC -> AllIcons.ObjectBrowser.Sorted
        SortOption.TITLE_DESC -> AllIcons.ObjectBrowser.Sorted
        SortOption.MESSAGE_COUNT_DESC -> AllIcons.Actions.ListFiles
        SortOption.MESSAGE_COUNT_ASC -> AllIcons.Actions.ListFiles
    }

    private fun createSortAction(): AnAction {
        val sortAction = object : AnAction(
            CodeGPTBundle.get("conversation.sortAction.title", sortOption.displayName),
            CodeGPTBundle.get("conversation.sortAction.description", sortOption.displayName),
            AllIcons.ObjectBrowser.Sorted
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val actionGroup = DefaultActionGroup().apply {
                    SortOption.entries.forEach { option ->
                        add(object : AnAction(option.displayName, null, getSortIcon(option)) {
                            override fun actionPerformed(e: AnActionEvent) {
                                sortOption = option
                                sortAndFilterConversations()
                            }

                            override fun update(e: AnActionEvent) {
                                e.presentation.putClientProperty(KEY, sortOption == option)
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread {
                                return ActionUpdateThread.BGT
                            }
                        })
                    }
                }

                val popup = JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        CodeGPTBundle.get("conversation.sortPopup.title"),
                        actionGroup,
                        e.dataContext,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true
                    )

                val component = e.inputEvent?.component
                if (component != null) {
                    popup.showUnderneathOf(component)
                } else {
                    popup.showInBestPositionFor(e.dataContext)
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.text =
                    CodeGPTBundle.get("conversation.sortAction.title", sortOption.displayName)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
        return sortAction
    }

    private fun createProjectFilterAction(): AnAction {
        return object : AnAction(
            projectFilter.getDisplayText(),
            "Filter conversations by project",
            AllIcons.General.Filter
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val actionGroup = DefaultActionGroup().apply {
                    add(object : BaseFilterAction("All Projects", null, AllIcons.General.Filter) {
                        override fun actionPerformed(e: AnActionEvent) {
                            projectFilter = ProjectFilterState.AllProjects
                            sortAndFilterConversations()
                        }

                        override fun isSelected(): Boolean {
                            return projectFilter is ProjectFilterState.AllProjects
                        }
                    })

                    add(object : BaseFilterAction("Current Project Only", null, null) {
                        override fun actionPerformed(e: AnActionEvent) {
                            projectFilter = ProjectFilterState.CurrentProjectOnly
                            sortAndFilterConversations()
                        }

                        override fun isSelected(): Boolean {
                            return projectFilter is ProjectFilterState.CurrentProjectOnly
                        }
                    })

                    addSeparator("Available Projects")

                    val projects = getProjectsWithConversations()
                    if (projects.isNotEmpty()) {
                        projects.forEach { projectInfo ->
                            val displayName =
                                "${projectInfo.name} (${projectInfo.conversationCount})"
                            val isCurrentProject = projectInfo.path == project.basePath
                            val icon = if (isCurrentProject) AllIcons.Nodes.HomeFolder else null

                            add(object : BaseFilterAction(displayName, projectInfo.path, icon) {
                                override fun actionPerformed(e: AnActionEvent) {
                                    projectFilter =
                                        ProjectFilterState.SelectedProjects(setOf(projectInfo.path))
                                    sortAndFilterConversations()
                                }

                                override fun isSelected(): Boolean {
                                    return when (val filter = projectFilter) {
                                        is ProjectFilterState.SelectedProjects -> filter.projectPaths.contains(
                                            projectInfo.path
                                        )
                                        is ProjectFilterState.CurrentProjectOnly -> isCurrentProject
                                        else -> false
                                    }
                                }
                            })
                        }
                    } else {
                        add(object : AnAction("No projects with conversations", null, null) {
                            override fun actionPerformed(e: AnActionEvent) {}
                            override fun update(e: AnActionEvent) {
                                e.presentation.isEnabled = false
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread {
                                return ActionUpdateThread.BGT
                            }
                        })
                    }
                }

                val popup = JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Filter by Project",
                        actionGroup,
                        e.dataContext,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true
                    )

                val component = e.inputEvent?.component
                if (component != null) {
                    popup.showUnderneathOf(component)
                } else {
                    popup.showInBestPositionFor(e.dataContext)
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.text = projectFilter.getDisplayText()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun setupListeners() {
        var searchTimer: Timer? = null
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateSearchFieldState()
                searchTimer?.stop()
                searchTimer = Timer(SEARCH_DEBOUNCE_MS) {
                    SwingUtilities.invokeLater {
                        sortAndFilterConversations()
                    }
                }
                searchTimer.isRepeats = false
                searchTimer.start()
            }
        })
    }

    fun getContent(): JPanel {
        val panel = SimpleToolWindowPanel(true)
        panel.setContent(this)
        val actionGroup = DefaultActionGroup("TOOLBAR_ACTION_GROUP", false).apply {
            add(object : AnAction(
                CodeGPTBundle.get("conversation.refreshAction.title"),
                CodeGPTBundle.get("conversation.refreshAction.description"),
                AllIcons.Actions.Refresh
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    refresh()
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.BGT
                }
            })
            add(createSortAction())
            add(createProjectFilterAction())
            addSeparator()
            add(DeleteAllConversationsAction { refresh() })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("NAVIGATION_BAR_TOOLBAR", actionGroup, true)
        toolbar.targetComponent = panel
        panel.toolbar = toolbar.component

        return panel
    }

    fun refresh() {
        loadConversationsAsync()
    }

    private fun loadConversationsAsync() {
        thread {
            val conversations = conversationService.sortedConversations
                .filter { it.messages.isNotEmpty() }
                .filter { !(it.messages.size == 1 && it.messages[0].response.isNullOrBlank()) }
                .toMutableList()
            SwingUtilities.invokeLater {
                allConversations = conversations
                projectInfoCache = discoverProjects(conversations)
                lastSearchText = ""
                lastFilteredConversations = null
                isDataLoaded = true
                sortAndFilterConversations()
            }
        }
    }

    private fun sortAndFilterConversations() {
        val searchText = searchField.text
        val filteredConversations = filterConversations(searchText)
        val sortedConversations = applySorting(filteredConversations)
        updateList(sortedConversations)
    }

    private fun applySorting(conversations: List<Conversation>): List<Conversation> {
        return when (sortOption) {
            SortOption.UPDATED_DATE_DESC -> conversations.sortedByDescending { it.updatedOn }
            SortOption.UPDATED_DATE_ASC -> conversations.sortedBy { it.updatedOn }
            SortOption.TITLE_ASC -> conversations.sortedBy { getConversationDisplayTitle(it).lowercase() }
            SortOption.TITLE_DESC -> conversations.sortedByDescending {
                getConversationDisplayTitle(
                    it
                ).lowercase()
            }

            SortOption.MESSAGE_COUNT_DESC -> conversations.sortedByDescending { it.messages.size }
            SortOption.MESSAGE_COUNT_ASC -> conversations.sortedBy { it.messages.size }
        }
    }

    private fun getConversationDisplayTitle(conversation: Conversation): String {
        return conversation.title?.takeIf { it.isNotBlank() }
            ?: conversation.messages.firstOrNull()?.prompt?.take(50)
            ?: CodeGPTBundle.get("conversation.defaultTitle")
    }

    private fun matchesSearchText(conversation: Conversation, searchText: String): Boolean {
        val searchLower = searchText.lowercase()

        if (conversation.title?.lowercase()?.contains(searchLower) == true) {
            return true
        }

        conversation.projectPath?.let { path ->
            val projectName = ProjectPathUtils.extractProjectName(path)?.lowercase()
            if (projectName?.contains(searchLower) == true) {
                return true
            }
        }

        return conversation.messages.take(MAX_MESSAGES_TO_SEARCH).any { message ->
            message.prompt?.lowercase()?.contains(searchLower) == true ||
                    message.response?.lowercase()?.contains(searchLower) == true
        }
    }


    private fun discoverProjects(conversations: List<Conversation>): Map<String, ProjectInfo> {
        return conversations
            .mapNotNull { conversation ->
                conversation.projectPath?.let { path ->
                    path to (ProjectPathUtils.extractProjectName(path) ?: path)
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (path, names) ->
                ProjectInfo(
                    path = path,
                    name = names.firstOrNull() ?: path,
                    conversationCount = conversations.count { it.projectPath == path }
                )
            }
    }

    private fun getProjectsWithConversations(): List<ProjectInfo> {
        return projectInfoCache.values.sortedByDescending { it.conversationCount }
    }

    private fun updateList(conversations: List<Conversation>) {
        SwingUtilities.invokeLater {
            chatHistoryListPanel.setConversations(conversations)
            statusLabel.text = createStatusMessage(conversations)
            updateSelectedConversation(conversations)
        }
    }

    private fun createStatusMessage(conversations: List<Conversation>): String {
        val searchText = searchField.text
        return buildString {
            append(getConversationCountMessage(conversations, searchText))
            append(" • ")
            append(CodeGPTBundle.get("conversation.status.sortedBy", sortOption.displayName))

            when (projectFilter) {
                is ProjectFilterState.CurrentProjectOnly -> {
                    append(" • ")
                    append("Current project only")
                }

                is ProjectFilterState.SelectedProjects -> {
                    append(" • ")
                    append(projectFilter.getDisplayText())
                }

                else -> {}
            }
        }
    }

    private fun getConversationCountMessage(
        conversations: List<Conversation>,
        searchText: String
    ): String {
        return if (searchText.isNotBlank()) {
            CodeGPTBundle.get(
                "conversation.status.searchResult",
                conversations.size,
                allConversations.size
            )
        } else {
            if (conversations.size == 1) {
                CodeGPTBundle.get("conversation.status.count.singular", conversations.size)
            } else {
                CodeGPTBundle.get("conversation.status.count.plural", conversations.size)
            }
        }
    }

    private fun updateSelectedConversation(conversations: List<Conversation>) {
        ConversationsState.getCurrentConversation()?.let { current ->
            conversations.find { it.id == current.id }?.let { conversation ->
                chatHistoryListPanel.setSelectedConversation(conversation)
            }
        }
    }

    private fun deleteConversation(conversation: Conversation) {
        val result = JOptionPane.showConfirmDialog(
            this,
            CodeGPTBundle.get("conversation.deleteConfirmation.message"),
            CodeGPTBundle.get("conversation.deleteConfirmation.title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result == JOptionPane.YES_OPTION) {
            conversationService.deleteConversation(conversation)
            refresh()
        }
    }
}
