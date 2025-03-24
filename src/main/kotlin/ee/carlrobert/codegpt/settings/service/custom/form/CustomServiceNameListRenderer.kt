package ee.carlrobert.codegpt.settings.service.custom.form

import com.intellij.ui.render.LabelBasedRenderer
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceSettingsData
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

internal class CustomServiceNameListRenderer : LabelBasedRenderer(), ListCellRenderer<CustomServiceSettingsData> {
    private val delegate = List<CustomServiceSettingsData>()

    override fun getListCellRendererComponent(
        list: JList<out CustomServiceSettingsData>,
        value: CustomServiceSettingsData?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component =
        delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            .also { component ->
                (component as? JLabel)?.text = value?.name ?: ""
            }
}