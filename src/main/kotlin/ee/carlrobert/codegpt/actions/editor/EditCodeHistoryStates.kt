package ee.carlrobert.codegpt.actions.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.CollectionBean
import com.intellij.util.xmlb.annotations.Transient
import ee.carlrobert.codegpt.util.trimToSize
import kotlin.properties.Delegates

/**
 * Persistent plugin states.
 */
@State(name = "CodeGPT_EditCodeHistory", storages = [(Storage("CodeGPT_EditCodeHistory.xml"))])
@Service
class EditCodeHistoryStates : PersistentStateComponent<EditCodeHistoryStates> {

    @CollectionBean
    private val histories: MutableList<String> = ArrayList(DEFAULT_HISTORY_SIZE)

    var maxHistorySize by Delegates.vetoable(DEFAULT_HISTORY_SIZE) { _, oldValue: Int, newValue: Int ->
        if (oldValue == newValue || newValue < 0) {
            return@vetoable false
        }

        trimHistoriesSize(newValue)
        true
    }

    @Transient
    private val dataChangePublisher: HistoriesChangedListener =
        ApplicationManager.getApplication().messageBus.syncPublisher(HistoriesChangedListener.TOPIC)

    override fun getState(): EditCodeHistoryStates = this

    override fun loadState(state: EditCodeHistoryStates) {
        XmlSerializerUtil.copyBean(state, this)
    }

    private fun trimHistoriesSize(maxSize: Int) {
        if (histories.trimToSize(maxSize)) {
            dataChangePublisher.onHistoriesChanged()
        }
    }

    fun getHistories(): List<String> = histories

    fun addHistory(query: String) {
        val maxSize = maxHistorySize
        if (maxSize <= 0) {
            return
        }

        histories.run {
            val index = indexOf(query)
            if (index != 0) {
                if (index > 0) {
                    removeAt(index)
                }

                add(0, query)
                trimToSize(maxSize)
                dataChangePublisher.onHistoryItemChanged(query)
            }
        }
    }

    fun clearHistories() {
        if (histories.isNotEmpty()) {
            histories.clear()
            dataChangePublisher.onHistoriesChanged()
        }
    }

    companion object {
        private const val DEFAULT_HISTORY_SIZE = 50

        /**
         * Get the instance of [EditCodeHistoryStates].
         */
        fun getInstance(): EditCodeHistoryStates {
            return service<EditCodeHistoryStates>().state
        }
    }
}

interface HistoriesChangedListener {

    fun onHistoriesChanged()

    fun onHistoryItemChanged(newHistory: String)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<HistoriesChangedListener> =
            Topic.create("TranslateHistoriesChanged", HistoriesChangedListener::class.java)
    }
}
