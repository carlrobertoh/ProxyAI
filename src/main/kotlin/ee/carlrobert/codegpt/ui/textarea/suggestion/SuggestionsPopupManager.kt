package ee.carlrobert.codegpt.ui.textarea.suggestion

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.vcsUtil.showAbove
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.DocumentationSuggestionGroupItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.FileSuggestionGroupItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.FolderSuggestionGroupItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.GitSuggestionGroupItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.PersonaSuggestionGroupItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.SuggestionActionItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.SuggestionGroupItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.SuggestionItem
import ee.carlrobert.codegpt.ui.textarea.suggestion.item.WebSearchActionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Point
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class SuggestionsPopupManager(
    private val project: Project,
    private val userInputPanel: UserInputPanel,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var selectedActionGroup: SuggestionGroupItem? = null
    private var popup: JBPopup? = null

    private val listModel = DefaultListModel<SuggestionItem>().apply {
        addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = adjustPopupSize()
            override fun intervalRemoved(e: ListDataEvent) {}
            override fun contentsChanged(e: ListDataEvent) {}
        })
    }
    private val list = SuggestionList(listModel, userInputPanel) {
        handleSuggestionItemSelection(it)
    }
    private val defaultActions: MutableList<SuggestionItem> = mutableListOf(
        FileSuggestionGroupItem(project),
        FolderSuggestionGroupItem(project),
        GitSuggestionGroupItem(project),
        PersonaSuggestionGroupItem(project),
        DocumentationSuggestionGroupItem(project),
        WebSearchActionItem(project),
    )

    fun showPopup(component: JComponent? = null) {
        popup = SuggestionsPopupBuilder()
            .setPreferableFocusComponent(component)
            .setOnCancel {
                true
            }
            .build(list)

        popup?.showAbove(userInputPanel)

        reset(true)
        selectNext()
    }

    fun hidePopup() {
        popup?.cancel()
    }

    fun isPopupVisible(): Boolean {
        return popup?.isVisible ?: false
    }

    fun selectNext() {
        list.selectNext()
        list.requestFocus()
    }

    fun selectPrevious() {
        list.selectPrevious()
        list.requestFocus()
    }

    fun updateSuggestions(searchText: String? = null) {
        scope.launch {
            val suggestions = withContext(Dispatchers.Default) {
                selectedActionGroup?.getSuggestions(searchText) ?: emptyList()
            }

            withContext(Dispatchers.Main) {
                listModel.clear()
                listModel.addAll(suggestions)
                list.revalidate()
                list.repaint()
            }
        }
    }

    fun reset(clearPrevious: Boolean = true) {
        if (clearPrevious) {
            listModel.clear()
        }
        listModel.addAll(defaultActions)
    }

    private fun handleSuggestionItemSelection(item: SuggestionItem) {
        when (item) {
            is SuggestionActionItem -> {
                hidePopup()
                scope.launch {
                    withContext(Dispatchers.Main) {
                        item.execute(project, userInputPanel)
                    }
                }
            }

            is SuggestionGroupItem -> {
                selectedActionGroup = item
                updateSuggestions()
                userInputPanel.requestFocus()
            }
        }
    }

    private fun adjustPopupSize() {
        val maxVisibleRows = 15
        val newRowCount = minOf(listModel.size(), maxVisibleRows)
        list.setVisibleRowCount(newRowCount)
        list.revalidate()
        list.repaint()

        popup?.size = Dimension(list.preferredSize.width, list.preferredSize.height + 32)

        val bounds = userInputPanel.bounds
        val locationOnScreen = userInputPanel.locationOnScreen

        val deviceConfiguration = userInputPanel.graphicsConfiguration
        val screenBounds = deviceConfiguration.bounds

        val popupSize = popup?.size ?: Dimension(0, 0)
        val newY = locationOnScreen.y - popupSize.height

        val adjustedY = if (newY < screenBounds.y) locationOnScreen.y + bounds.height else newY

        popup?.setLocation(Point(locationOnScreen.x, adjustedY))
    }
}
