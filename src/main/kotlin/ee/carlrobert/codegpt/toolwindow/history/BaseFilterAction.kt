package ee.carlrobert.codegpt.toolwindow.history

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.Key
import javax.swing.Icon

abstract class BaseFilterAction(
    text: String,
    description: String? = null,
    icon: Icon? = null
) : AnAction(text, description, icon) {

    companion object {
        val KEY: Key<Boolean> = Key.create("SELECTED_STATE")
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.putClientProperty(KEY, isSelected())
    }

    abstract fun isSelected(): Boolean
}