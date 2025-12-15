package ee.carlrobert.codegpt.toolwindow.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.IdeBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.MinimizeButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.events.WebSearchEventDetails
import org.cef.browser.CefRendering
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class WebpageList(model: DefaultListModel<WebSearchEventDetails>) :
    JBList<WebSearchEventDetails>(model) {

    init {
        setModel(model)
        setupUI()
        setupMouseListener()
    }

    override fun getPreferredSize(): Dimension {
        val parentWidth = parent?.width ?: super.getPreferredSize().width
        return Dimension(parentWidth, super.getPreferredSize().height)
    }

    private fun setupUI() {
        border = JBUI.Borders.emptyBottom(8)
        cellRenderer = WebpageListCellRenderer()
        isOpaque = false
        setEmptyText("")
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        if (model.size == 0) {
            g.font = emptyText.component.font
            g.color = JBColor.gray
            if (g is Graphics2D) {
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )
            }
            g.drawString(
                CodeGPTBundle.get("shared.loading"),
                insets.left,
                insets.top + g.fontMetrics.ascent + 5
            )
        }
    }

    private fun setupMouseListener() {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                handleMouseClick(e)
            }

            override fun mouseExited(e: MouseEvent) {
                putClientProperty("hoveredIndex", -1)
                repaint()
            }
        })
    }

    private fun handleMouseClick(e: MouseEvent) {
        val index = locationToIndex(e.point)
        if (index >= 0) {
            val details = model.getElementAt(index)
            val browser = JBCefApp.getInstance().createClient()
                .cefClient
                .createBrowser(details.url, CefRendering.DEFAULT, false, null)

            val popupPanel = BorderLayoutPanel().apply {
                add(browser.uiComponent, BorderLayout.CENTER)
                add(panel {
                    row {
                        text(CodeGPTBundle.get("shared.escToCancel")).applyToComponent {
                            font = JBUI.Fonts.smallFont()
                        }
                        cell(ActionLink(CodeGPTBundle.get("shared.website")) {
                            BrowserUtil.open(details.url)
                        }
                            .apply {
                                setExternalLinkIcon()
                                font = JBUI.Fonts.smallFont()
                            })
                            .align(AlignX.RIGHT)
                    }
                }.apply {
                    border = JBUI.Borders.empty(0, 8)
                }, BorderLayout.SOUTH)
                preferredSize = Dimension(800, 600)
            }

            service<JBPopupFactory>()
                .createComponentPopupBuilder(popupPanel, null)
                .setTitle(details.name)
                .setMovable(true)
                .setCancelKeyEnabled(true)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .setRequestFocus(true)
                .setCancelButton(MinimizeButton(IdeBundle.message("tooltip.hide")))
                .setMinSize(Dimension(800, 600))
                .setResizable(true)
                .createPopup()
                .showInFocusCenter()
            e.consume()
        }
    }
}

class WebpageListCellRenderer : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
            setOpaque(false)
        }.let { component ->
            if (component is JLabel && value is WebSearchEventDetails) {
                component.apply {
                    icon = AllIcons.General.Web
                    iconTextGap = 4
                    font = JBUI.Fonts.smallFont()
                    text = value.name
                }
                panel {
                    row {
                        cell(component).gap(RightGap.SMALL)
                        cell(JBLabel(value.displayUrl)
                            .withFont(JBUI.Fonts.miniFont())
                            .apply { foreground = JBColor.gray })
                    }
                }.apply {
                    isOpaque = false
                }
            } else {
                component
            }
        }
}
