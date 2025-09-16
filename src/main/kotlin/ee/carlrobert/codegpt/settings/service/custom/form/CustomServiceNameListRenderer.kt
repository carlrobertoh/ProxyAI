package ee.carlrobert.codegpt.settings.service.custom.form

import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceSettingsData
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

internal class CustomServiceNameListRenderer : JLabel(), ListCellRenderer<CustomServiceSettingsData> {

    override fun getListCellRendererComponent(
        list: JList<out CustomServiceSettingsData>,
        value: CustomServiceSettingsData?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        text = value?.name ?: ""
        isOpaque = true
        background = if (isSelected) list.selectionBackground else list.background
        foreground = if (isSelected) list.selectionForeground else list.foreground
        font = list.font
        return this
    }
}
