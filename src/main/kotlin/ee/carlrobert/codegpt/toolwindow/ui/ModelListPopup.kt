package ee.carlrobert.codegpt.toolwindow.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.MenuItemPresentationFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.ui.popup.PopupFactoryImpl.ActionItem
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTKeys.CODEGPT_USER_DETAILS
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTModel
import ee.carlrobert.llm.client.codegpt.PricingPlan.ANONYMOUS
import ee.carlrobert.llm.client.codegpt.PricingPlan.INDIVIDUAL
import java.awt.BorderLayout
import javax.swing.*

class ModelListPopup(
    actionGroup: ActionGroup,
    context: DataContext
) : PopupFactoryImpl.ActionGroupPopup(
    null,
    actionGroup,
    context,
    false,
    false,
    true,
    false,
    null,
    -1,
    null,
    null,
    MenuItemPresentationFactory(),
    false
) {

    override fun getListElementRenderer(): ListCellRenderer<*> {
        return object : PopupListElementRenderer<Any>(this) {
            private lateinit var secondaryLabel: SimpleColoredComponent

            override fun createLabel() {
                super.createLabel()
                secondaryLabel = SimpleColoredComponent()
            }

            override fun createItemComponent(): JComponent? {
                createLabel()
                val panel = JPanel(BorderLayout()).apply {
                    add(myTextLabel, BorderLayout.WEST)
                    add(secondaryLabel, BorderLayout.EAST)
                }
                myIconBar = createIconBar()
                return layoutComponent(panel)
            }

            override fun createIconBar(): JComponent? {
                return Box.createHorizontalBox().apply {
                    border = JBUI.Borders.emptyRight(JBUI.CurrentTheme.ActionsList.elementIconGap())
                    add(myIconLabel)
                }
            }

            override fun customizeComponent(
                list: JList<out Any>?,
                value: Any?,
                isSelected: Boolean
            ) {
                super.customizeComponent(list, value, isSelected)
                setupSecondaryLabel()

                (value as? ActionItem)?.action?.let { action ->
                    if (action is CodeGPTModelsListPopupAction) {
                        updateSecondaryLabel(action)
                    }
                }
            }

            private fun setupSecondaryLabel() {
                secondaryLabel.apply {
                    font = JBUI.Fonts.toolbarSmallComboBoxFont()
                    border = JBUI.Borders.emptyLeft(8)
                    clear()
                }
            }

            private fun updateSecondaryLabel(action: CodeGPTModelsListPopupAction) {
                val userPricingPlan = CODEGPT_USER_DETAILS.get(project)?.pricingPlan
                if (userPricingPlan != INDIVIDUAL && action.model.pricingPlan == INDIVIDUAL) {
                    secondaryLabel.append("PRO", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES, true)
                }
            }
        }
    }
}

class CodeGPTModelsListPopupAction(
    val model: CodeGPTModel,
    private val comboBoxPresentation: Presentation,
    private val onModelChanged: Runnable?
) : DumbAwareAction(model.name, "", model.icon) {

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = shouldEnableAction(event)
    }

    private fun shouldEnableAction(event: AnActionEvent): Boolean {
        val project = event.project ?: return false
        val notSelected = event.presentation.text != comboBoxPresentation.text
        val pricingPlan = CODEGPT_USER_DETAILS[project]?.pricingPlan

        if (pricingPlan == null || pricingPlan == ANONYMOUS) {
            return notSelected && model.pricingPlan == ANONYMOUS
        }
        return notSelected
    }

    override fun actionPerformed(e: AnActionEvent) {
        onModelChanged?.run()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
