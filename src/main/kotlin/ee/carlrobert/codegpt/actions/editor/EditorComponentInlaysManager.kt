package ee.carlrobert.codegpt.actions.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.impl.view.FontLayoutService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Manages embedded component inlays in the main editor.
 */
class EditorComponentInlaysManager(val editor: EditorImpl) : Disposable {

    private val managedInlays = mutableMapOf<ComponentWrapper, Disposable>()
    private val editorWidthWatcher = EditorTextWidthWatcher()

    init {
        editor.scrollPane.viewport.addComponentListener(editorWidthWatcher)
        Disposer.register(this, Disposable {
            editor.scrollPane.viewport.removeComponentListener(editorWidthWatcher)
        })

        EditorUtil.disposeWithEditor(editor, this)
    }

    @RequiresEdt
    fun insert(offset: Int, component: JComponent, showAbove: Boolean = false): Disposable? {
        val wrappedComponent = ComponentWrapper(component)
        return EditorEmbeddedComponentManager.getInstance()
            .addComponent(
                editor,
                wrappedComponent,
                EditorEmbeddedComponentManager.Properties(
                    EditorEmbeddedComponentManager.ResizePolicy.any(),
                    null,
                    true,
                    showAbove,
                    0,
                    offset
                )
            )
            ?.also {
                managedInlays[wrappedComponent] = it
                Disposer.register(it, Disposable { managedInlays.remove(wrappedComponent) })
            }
    }

    private inner class ComponentWrapper(val component: JComponent) : JBScrollPane(component) {
        init {
            isOpaque = false
            viewport.isOpaque = false

            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()

            horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.preferredSize = Dimension(0, 0)
            setViewportView(component)

            isFocusable = false
            viewport.isFocusable = false

            cursor = Cursor.getDefaultCursor()
            viewport.cursor = Cursor.getDefaultCursor()

            isFocusTraversalPolicyProvider = true
            focusTraversalPolicy = object : java.awt.FocusTraversalPolicy() {
                override fun getFirstComponent(aContainer: Container?) = component
                override fun getLastComponent(aContainer: Container?) = component
                override fun getDefaultComponent(aContainer: Container?) = component
                override fun getComponentAfter(
                    aContainer: Container?,
                    aComponent: Component?
                ) = component

                override fun getComponentBefore(
                    aContainer: Container?,
                    aComponent: Component?
                ) = component
            }

            component.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
                    revalidate()
                    repaint()
                }
            })

            component.addPropertyChangeListener("preferredSize") { _ ->
                invalidate()
                revalidate()
                repaint()

                val newSize = component.preferredSize
                val oldPref = preferredSize

                if (newSize.height != oldPref.height) {
                    preferredSize = Dimension(getPreferredSize().width, newSize.height)

                    editor.contentComponent.invalidate()
                    editor.contentComponent.revalidate()
                    editor.contentComponent.repaint()
                }
            }
        }

        override fun requestFocus() {
            component.requestFocus()
        }

        override fun requestFocusInWindow(): Boolean {
            return component.requestFocusInWindow()
        }

        override fun getPreferredSize(): Dimension {
            val fixed =
                (component.getClientProperty("codegpt.fixedWidth") as? Int)?.let { JBUI.scale(it) }
            val height = component.preferredSize.height
            val width = fixed ?: run {
                val contentW = editor.contentComponent.visibleRect.width
                if (contentW > 0) contentW else editor.contentComponent.width
            }
            return Dimension(width, height)
        }

        override fun getMinimumSize(): Dimension {
            val fixed =
                (component.getClientProperty("codegpt.fixedWidth") as? Int)?.let { JBUI.scale(it) }
                    ?: 0
            return Dimension(fixed, component.minimumSize.height)
        }
    }

    override fun dispose() {
        managedInlays.values.forEach(Disposer::dispose)
    }

    private inner class EditorTextWidthWatcher : ComponentAdapter() {

        var editorTextWidth: Int = 0

        private val maximumEditorTextWidth: Int
        private val verticalScrollbarFlipped: Boolean

        init {
            val metrics = editor.getFontMetrics(Font.PLAIN)
            val spaceWidth = FontLayoutService.getInstance().charWidth2D(metrics, ' '.code)
            maximumEditorTextWidth =
                ceil(spaceWidth * (editor.settings.getRightMargin(editor.project)) - 4).toInt()

            val scrollbarFlip = editor.scrollPane.getClientProperty(JBScrollPane.Flip::class.java)
            verticalScrollbarFlipped =
                scrollbarFlip == JBScrollPane.Flip.HORIZONTAL || scrollbarFlip == JBScrollPane.Flip.BOTH
        }

        override fun componentResized(e: ComponentEvent) = updateWidthForAllInlays()
        override fun componentHidden(e: ComponentEvent) = updateWidthForAllInlays()
        override fun componentShown(e: ComponentEvent) = updateWidthForAllInlays()

        private fun updateWidthForAllInlays() {
            val newWidth = calcWidth()
            if (editorTextWidth == newWidth) return
            editorTextWidth = newWidth

            managedInlays.keys.forEach {
                it.dispatchEvent(ComponentEvent(it, ComponentEvent.COMPONENT_RESIZED))
                it.invalidate()
            }
        }

        private fun calcWidth(): Int {
            val visibleEditorTextWidth =
                editor.scrollPane.viewport.width - getVerticalScrollbarWidth() - getGutterTextGap()
            return min(max(visibleEditorTextWidth, 0), maximumEditorTextWidth)
        }

        private fun getVerticalScrollbarWidth(): Int {
            val width = editor.scrollPane.verticalScrollBar.width
            return if (!verticalScrollbarFlipped) width * 2 else width
        }

        private fun getGutterTextGap(): Int {
            return if (verticalScrollbarFlipped) {
                val gutter = (editor as EditorEx).gutterComponentEx
                gutter.width - gutter.whitespaceSeparatorOffset
            } else 0
        }
    }

    companion object {
        val INLAYS_KEY: Key<EditorComponentInlaysManager> = Key.create("InlineEditInlaysManager")

        fun from(editor: Editor): EditorComponentInlaysManager {
            return synchronized(editor) {
                val manager = editor.getUserData(INLAYS_KEY)
                if (manager == null) {
                    val newManager = EditorComponentInlaysManager(editor as EditorImpl)
                    editor.putUserData(INLAYS_KEY, newManager)
                    newManager
                } else manager
            }
        }
    }
}
