package ee.carlrobert.codegpt.toolwindow.chat.structure.presentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManagerListener
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope

internal class PsiStructureViewModel(
    parentDisposable: Disposable,
    private val psiStructureRepository: PsiStructureRepository,
    private val tagManager: TagManager,
) {

    private val coroutineScope = DisposableCoroutineScope()

    private val tagsListener = object : TagManagerListener {
        override fun onTagAdded(tag: TagDetails) {
            println("sssssss onTagAdded $tag")
        }

        override fun onTagRemoved(tag: TagDetails) {
            println("sssssss onTagRemoved $tag")
        }

        override fun onTagSelectionChanged(tag: TagDetails) {
            println("sssssss onTagSelectionChanged $tag")
        }
    }

    init {
        Disposer.register(parentDisposable, coroutineScope)
        tagManager.addListener(tagsListener)
    }

    fun disablePsiAnalyzer() {
        psiStructureRepository.disable()
    }

    fun enablePsiAnalyzer() {
        psiStructureRepository.enable()
    }

}