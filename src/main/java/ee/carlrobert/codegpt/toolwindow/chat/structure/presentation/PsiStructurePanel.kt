package ee.carlrobert.codegpt.toolwindow.chat.structure.presentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.selected
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.awt.BorderLayout
import javax.swing.JPanel


class PsiStructurePanel(
    parentDisposable: Disposable,
    psiStructureRepository: PsiStructureRepository,
    tagManager: TagManager
) : JPanel() {

    private val coroutineScope = DisposableCoroutineScope()

    private val viewModel = PsiStructureViewModel(
        parentDisposable,
        psiStructureRepository,
        tagManager,
        EncodingManager.getInstance(),
        ProjectLocator.getInstance(),
        ClassStructureSerializer,
        CoroutineDispatchers(),
    )

    init {
        Disposer.register(parentDisposable, coroutineScope)
        layout = BorderLayout()
        add(
            panel {
                row {
                    buildPsiTokenInfo()

                    checkBox("Use PSI")
                        .align(AlignX.RIGHT)
                        .align(AlignY.TOP)
                        .onChanged { component ->
                            if (component.selected()) {
                                viewModel.enablePsiAnalyzer()
                            } else {
                                viewModel.disablePsiAnalyzer()
                            }
                        }
                        .apply {
                            viewModel.state
                                .map { currentState ->
                                    selected(currentState.enabled)
                                }
                                .launchIn(coroutineScope)
                        }
                }
            })
    }

    private fun Row.buildPsiTokenInfo() {
        panel {
            row {
                viewModel.state
                    .map { state ->
                        enabled(state.enabled)
                    }
                    .launchIn(coroutineScope)

                label("PSI token:")
                    .align(AlignX.RIGHT)

                val spinner = AnimatedIcon.Default()
                icon(spinner)
                    .align(AlignX.RIGHT)
                    .apply {
                        viewModel.state
                            .onEach { state ->
                                val isVisible = when (state) {
                                    is PsiStructureViewModelState.Content -> false
                                    is PsiStructureViewModelState.Progress -> true
                                }
                                visible(isVisible)
                            }
                            .launchIn(coroutineScope)
                    }
                label("")
                    .align(AlignX.RIGHT)
                    .apply {
                        viewModel.state
                            .onEach { state ->
                                component.text = when (state) {
                                    is PsiStructureViewModelState.Content -> state.psiStructureTokens
                                    is PsiStructureViewModelState.Progress -> ""
                                }
                                val isVisible = when (state) {
                                    is PsiStructureViewModelState.Content -> true
                                    is PsiStructureViewModelState.Progress -> false
                                }
                                visible(isVisible)
                            }
                            .launchIn(coroutineScope)
                    }
            }
        }
    }
}