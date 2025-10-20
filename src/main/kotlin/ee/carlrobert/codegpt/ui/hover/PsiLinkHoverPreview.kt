package ee.carlrobert.codegpt.ui.hover

import com.intellij.codeInsight.documentation.DocumentationHintEditorPane
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.DocumentationImageResolver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.psi.PsiElement
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.util.NavigationResolverFactory
import java.awt.Dimension
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import kotlin.math.min

object PsiLinkHoverPreview {
    private val logger = thisLogger()

    private const val PSI_PREFIX = "psi_element://"
    private const val FILE_PREFIX = "file://"
    private const val HOVER_DELAY_MS = 250L
    private const val EXIT_CLOSE_DELAY_MS = 120L

    @JvmStatic
    fun install(project: Project, textPane: JTextPane) {
        val manager = HoverManager(project, textPane)
        textPane.addHyperlinkListener(manager)
        textPane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                manager.cancelAndHide()
            }
        })
    }

    private class HoverManager(
        private val project: Project,
        private val textPane: JTextPane
    ) : HyperlinkListener {

        private var pending: ScheduledFuture<*>? = null
        private var scheduledClose: ScheduledFuture<*>? = null
        private var popup: JBPopup? = null
        private var popupComponent: JComponent? = null
        private var lastDesc: String? = null

        private val scheduledExecutor = AppExecutorUtil.getAppScheduledExecutorService()
        private val executor = AppExecutorUtil.getAppExecutorService()

        override fun hyperlinkUpdate(e: HyperlinkEvent) {
            when (e.eventType) {
                HyperlinkEvent.EventType.ENTERED -> onEntered(e)
                HyperlinkEvent.EventType.EXITED -> onExited()
                else -> {}
            }
        }

        private fun onExited() {
            scheduledClose?.cancel(true)
            scheduledClose = scheduledExecutor.schedule({
                ApplicationManager.getApplication().invokeLater({
                    cancelAndHide()
                }, ModalityState.any())
            }, EXIT_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS)
        }

        fun cancelAndHide() {
            scheduledClose?.cancel(true)
            scheduledClose = null

            val comp = popupComponent
            if (comp != null && isMouseOverComponent(comp)) return

            pending?.cancel(true)
            pending = null

            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    popup?.cancel()
                    popup = null
                    popupComponent = null
                } else {
                    ApplicationManager.getApplication().invokeLater({
                        popup?.cancel()
                        popup = null
                        popupComponent = null
                    }, ModalityState.any())
                }
            } catch (t: Throwable) {
                logger.warn("Failed while cancelling popup", t)
            }
        }

        private fun onEntered(e: HyperlinkEvent) {
            val desc = e.description ?: return
            lastDesc = desc
            scheduledClose?.cancel(true)
            scheduledClose = null
            pending?.cancel(true)
            val point = mouseRelativePointBelow(textPane)
            pending = scheduledExecutor.schedule({
                resolveAndShow(desc, point)
            }, HOVER_DELAY_MS, TimeUnit.MILLISECONDS)
        }

        private fun resolveAndShow(desc: String, where: RelativePoint) {
            try {
                when {
                    desc.startsWith(PSI_PREFIX) || desc.startsWith(FILE_PREFIX) -> {
                        val prefix = if (desc.startsWith(PSI_PREFIX)) PSI_PREFIX else FILE_PREFIX
                        val target = decode(desc.removePrefix(prefix))
                        resolvePsiLikeAndShow(prefix, target, desc, where)
                    }

                    else -> {
                        if (desc != lastDesc) return
                        val html = "<html><body>${escape(desc)}</body></html>"
                        showDocHint(where, html)
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to resolve hover target: $desc", t)
            }
        }

        private fun resolvePsiLikeAndShow(
            prefix: String,
            target: String,
            originalDesc: String,
            where: RelativePoint
        ) {
            ReadAction.nonBlocking<String?> {
                val resolver = NavigationResolverFactory.create(prefix)
                val element = resolver.resolve(target)
                buildPsiHtmlOffEdt(element)
            }
                .expireWith(project)
                .finishOnUiThread(ModalityState.any()) { html ->
                    if (originalDesc != lastDesc || html.isNullOrBlank()) return@finishOnUiThread
                    showDocHint(where, html)
                }
                .submit(executor)
        }

        private fun showDocHint(where: RelativePoint, html: String) {
            scheduledClose?.cancel(true)
            scheduledClose = null
            cancelAndHide()

            val imageResolver = object : DocumentationImageResolver {
                override fun resolveImage(p0: String): Image? = null
            }

            val safeHtml = if (html.contains("<body", ignoreCase = true)) {
                html.replaceFirst(
                    Regex("<body(\\s|>)", RegexOption.IGNORE_CASE),
                    "<body style=\"margin:0;padding:0\"$1"
                )
            } else {
                "<html><body style=\"margin:0;padding:0\">$html</body></html>"
            }

            val editor = DocumentationHintEditorPane(project, emptyMap(), imageResolver).apply {
                isEditable = false
                contentType = "text/html"
                text = safeHtml
                caretPosition = 0
                font = JBFont.label()
                background = UIUtil.getPanelBackground()
                foreground = UIUtil.getLabelForeground()
                isOpaque = true
                margin = JBUI.insets(8)
            }

            val scroll = ScrollPaneFactory.createScrollPane(editor).apply {
                border = JBUI.Borders.empty()
                viewportBorder = null
                viewport.background = UIUtil.getPanelBackground()
                background = UIUtil.getPanelBackground()
            }

            val maxW = JBUI.scale(600)
            val maxH = JBUI.scale(400)
            editor.size = Dimension(maxW, Int.MAX_VALUE)
            val pref = editor.preferredSize
            val w = min(pref.width.coerceAtLeast(200), maxW)
            val h = min(pref.height.coerceAtLeast(50), maxH)
            scroll.preferredSize = Dimension(w, h)

            val newPopup = PopupFactoryImpl.getInstance()
                .createComponentPopupBuilder(scroll, null)
                .setRequestFocus(false)
                .setResizable(true)
                .setMovable(false)
                .setShowShadow(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .createPopup()

            editor.setHint(newPopup)

            popup = newPopup
            popupComponent = scroll

            ApplicationManager.getApplication().invokeLater({
                try {
                    popup?.show(where)
                } catch (t: Throwable) {
                    logger.warn("Failed to show popup", t)
                }
            }, ModalityState.any())
        }

        private fun mouseRelativePointBelow(component: JComponent): RelativePoint {
            val mp = component.mousePosition ?: Point(component.width / 2, 0)
            val fm = component.getFontMetrics(component.font)
            val offset = fm.height + JBUI.scale(4)
            val p = Point(mp.x, mp.y + offset)
            return RelativePoint(component, p)
        }

        private fun isMouseOverComponent(component: JComponent): Boolean {
            return try {
                val pointer = MouseInfo.getPointerInfo()?.location ?: return false
                val compPt = Point(pointer.x, pointer.y)
                SwingUtilities.convertPointFromScreen(compPt, component)
                component.contains(compPt)
            } catch (t: Throwable) {
                logger.debug("Failed to determine mouse-over state", t)
                false
            }
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            value
        }

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun buildPsiHtmlOffEdt(element: Any?): String? {
            if (element !is PsiElement) return null

            val provider = DocumentationManager.getProviderFromElement(element)
            return try {
                provider.generateDoc(element, element.containingFile)
            } catch (t: Throwable) {
                logger.warn("Failed to generate doc for ${element.text}", t)
                null
            }
        }
    }
}
