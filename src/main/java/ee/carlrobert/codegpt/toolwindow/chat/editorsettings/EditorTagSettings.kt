package ee.carlrobert.codegpt.toolwindow.chat.editorsettings

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.layout.selected
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import java.awt.BorderLayout
import javax.swing.JPanel

class EditorTagSettings(parentDisposable: Disposable, tagManager: TagManager) : JPanel() {

    private val coroutineScope = DisposableCoroutineScope()
    private val viewModel = EditorTagSettingsViewModel(tagManager)

    init {
        Disposer.register(parentDisposable, coroutineScope)

        layout = BorderLayout()
        add(
            panel {
                row {
                    checkBox("Use editor context")
                        .align(AlignX.RIGHT)
                        .align(AlignY.TOP)
                        .onChanged { component ->
                            if (component.selected()) {
                                viewModel.enableEditorTags()
                            } else {
                                viewModel.disableEditorTags()
                            }
                        }
                        .apply {
                            viewModel.state
                                .map { currentState ->
                                    selected(currentState.editorTagsEnabled)
                                }
                                .launchIn(coroutineScope)
                        }
                }
            }
        )
    }
}