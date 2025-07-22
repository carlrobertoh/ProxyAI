package ee.carlrobert.codegpt.ui

import com.intellij.ui.components.JBTextField
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

/**
 * [JBTextField] that automatically removes all trailing "/" after loosing focus.
 */
class URLTextField : JBTextField {
    constructor() : super()
    constructor(columns: Int) : super(columns)
    constructor(text: String?) : super(text)
    constructor(text: String?, columns: Int) : super(text, columns)

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
