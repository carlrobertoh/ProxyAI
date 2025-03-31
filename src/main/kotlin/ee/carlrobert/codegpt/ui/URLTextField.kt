package ee.carlrobert.codegpt.ui

import com.intellij.ui.components.JBTextField
import org.jetbrains.annotations.Nls
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

/**
 * [JBTextField] that automatically removes all trailing "/" after losing focus.
 */
class URLTextField : JBTextField {
    constructor() : super()
    constructor(columns: Int) : super(columns)
    constructor(text: @Nls String?) : super(text)
    constructor(text: @Nls String?, columns: Int) : super(text, columns)

    init {
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                val text: String = getText()
                if (text.endsWith("/")) {
                    setText(text.trimEnd('/'))
                }
            }
        })
    }
}
