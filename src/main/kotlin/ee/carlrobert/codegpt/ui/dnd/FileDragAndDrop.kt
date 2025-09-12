package ee.carlrobert.codegpt.ui.dnd

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.JComponent

object FileDragAndDrop {
    fun install(component: JComponent, onFilesDropped: (List<VirtualFile>) -> Unit) {
        install(component, component, onFilesDropped)
    }

    fun install(
        component: JComponent,
        highlightTarget: JComponent,
        onFilesDropped: (List<VirtualFile>) -> Unit
    ) {
        val appHeadless = try {
            ApplicationManager.getApplication()?.isHeadlessEnvironment == true
        } catch (_: Throwable) {
            false
        }
        if (GraphicsEnvironment.isHeadless() || appHeadless) return
        DropTarget(component, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun dragEnter(dragEvent: DropTargetDragEvent) {
                if (canImport(dragEvent.currentDataFlavors)) {
                    dragEvent.acceptDrag(DnDConstants.ACTION_COPY)
                    setHighlight(highlightTarget, true)
                } else dragEvent.rejectDrag()
            }

            override fun drop(dropEvent: DropTargetDropEvent) {
                val files = extractVirtualFiles(dropEvent.transferable)
                if (files.isNotEmpty()) {
                    dropEvent.acceptDrop(DnDConstants.ACTION_COPY)
                    onFilesDropped(files)
                    dropEvent.dropComplete(true)
                } else dropEvent.rejectDrop()
                setHighlight(highlightTarget, false)
            }

            override fun dragExit(dte: DropTargetEvent) {
                setHighlight(highlightTarget, false)
            }
        }, true)
    }

    private fun canImport(flavors: Array<DataFlavor>): Boolean {
        return flavors.any {
            it == DataFlavor.javaFileListFlavor
                    || it == DataFlavor.stringFlavor
                    || isUriListFlavor(it)
        }
    }

    private fun isUriListFlavor(flavor: DataFlavor): Boolean {
        return flavor.primaryType.equals("text", true) && flavor.subType.equals("uri-list", true)
    }

    private fun extractVirtualFiles(transferable: java.awt.datatransfer.Transferable): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                list?.mapNotNull { it as? File }?.forEach { addIfExists(out, it) }
            }
        } catch (_: Exception) {
        }
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val s = transferable.getTransferData(DataFlavor.stringFlavor) as? String
                if (!s.isNullOrBlank()) parseUriList(s).forEach { addIfExists(out, it) }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    private fun parseUriList(data: String): List<File> {
        return data.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull {
                try {
                    if (it.startsWith("file:")) File(
                        URLDecoder.decode(
                            URI.create(it).path,
                            StandardCharsets.UTF_8
                        )
                    ) else File(it)
                } catch (_: Exception) {
                    null
                }
            }
            .toList()
    }

    private fun addIfExists(out: MutableList<VirtualFile>, file: File) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { out += it }
    }

    private fun setHighlight(component: JComponent, enabled: Boolean) {
        if (component is UserInputPanel) {
            component.setDragActive(enabled)
            return
        }
        val key = "codegpt.dnd.prev.border"
        if (enabled) {
            if (component.getClientProperty(key) == null) component.putClientProperty(
                key,
                component.border
            )
            val focusColor = JBUI.CurrentTheme.Focus.defaultButtonColor()
            val overlay = JBUI.Borders.customLine(focusColor, 1)
            val base = component.getClientProperty(key) as? javax.swing.border.Border
            component.border = JBUI.Borders.merge(base, overlay, true)
        } else {
            val prev = component.getClientProperty(key) as? javax.swing.border.Border
            if (prev != null) {
                component.border = prev
                component.putClientProperty(key, null)
            }
        }
        component.revalidate()
        component.repaint()
    }
}
