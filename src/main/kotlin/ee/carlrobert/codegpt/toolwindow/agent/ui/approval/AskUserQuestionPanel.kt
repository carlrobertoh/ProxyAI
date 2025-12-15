package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.agent.tools.AskUserQuestionTool
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

class AskUserQuestionPanel(
    private val model: AskUserQuestionTool.AskUserQuestionsModel,
    private val onSubmit: (Map<String, String>) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(BorderLayout()) {

    private var index = 0
    private val answers = model.prefilledAnswers.toMutableMap()

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(8)
            )
        )
        render()
    }

    private fun render() {
        removeAll()
        val q = model.questions[index]
        val isFirst = index == 0
        val isLast = index == model.questions.lastIndex

        val otherField = JTextField()
        val radios = mutableListOf<JRadioButton>()
        val checks = mutableListOf<JCheckBox>()

        val content = panel {
            row {
                val chip = JBLabel(q.header).apply { border = EmptyBorder(2, 6, 2, 6) }
                cell(chip)
            }
            row {
                comment(q.question)
            }

            if (q.multiSelect) {
                q.options.forEach { opt ->
                    row {
                        val cb = JCheckBox(opt.label)
                        checks.add(cb)
                        cell(cb)
                        comment(opt.description)
                    }
                }
                row {
                    val cbOther = JCheckBox("Other")
                    checks.add(cbOther)
                    otherField.columns = 24
                    otherField.isEnabled = false
                    cbOther.addActionListener { otherField.isEnabled = cbOther.isSelected }
                    cell(cbOther)
                    cell(otherField).align(Align.FILL)
                }
            } else {
                buttonsGroup {
                    q.options.forEach { opt ->
                        row {
                            val rb = radioButton(opt.label).component
                            radios.add(rb)
                            comment(opt.description)
                        }
                    }
                    row {
                        val rbOther = radioButton("Other").component
                        radios.add(rbOther)
                        otherField.columns = 24
                        otherField.isEnabled = false
                        rbOther.addActionListener { otherField.isEnabled = rbOther.isSelected }
                        cell(otherField).align(Align.FILL)
                    }
                }
            }

            row {
                if (!isFirst) link("Back") { saveAndBack(q, radios, checks, otherField) }
                if (!isFirst) text("|").applyToComponent { foreground = JBUI.CurrentTheme.Label.disabledForeground() }
                if (!isLast) link("Next") { saveAndNext(q, radios, checks, otherField) }
                if (!isLast) text("|").applyToComponent { foreground = JBUI.CurrentTheme.Label.disabledForeground() }
                if (isLast) link("Submit") { saveAndSubmit(q, radios, checks, otherField) }
                if (isLast) text("|").applyToComponent { foreground = JBUI.CurrentTheme.Label.disabledForeground() }
                link("Cancel") { cancel() }
            }.topGap(TopGap.SMALL)
        }

        prefill(q, radios, checks, otherField)
        if (!q.multiSelect && radios.isNotEmpty() && radios.none { it.isSelected }) {
            radios.first().isSelected = true
        }
        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun collect(q: AskUserQuestionTool.Question, radios: List<JRadioButton>, checks: List<JCheckBox>, other: JTextField): String {
        return if (q.multiSelect) {
            val selected = checks.filter { it.isSelected }.map { it.text }.toMutableList()
            if (checks.isNotEmpty() && checks.last().isSelected) {
                val t = other.text.trim()
                if (t.isNotEmpty()) {
                    selected.removeIf { it == "Other" }
                    selected.add(t)
                }
            }
            selected.joinToString(", ")
        } else {
            val rb = radios.firstOrNull { it.isSelected }
            if (rb != null) {
                if (rb.text == "Other") other.text.trim() else rb.text
            } else ""
        }
    }

    private fun prefill(q: AskUserQuestionTool.Question, radios: List<JRadioButton>, checks: List<JCheckBox>, other: JTextField) {
        val ans = answers[q.header] ?: return
        if (q.multiSelect) {
            val parts = ans.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            q.options.forEachIndexed { idx, opt -> if (parts.contains(opt.label)) checks.getOrNull(idx)?.isSelected = true }
            val extra = parts.filter { p -> q.options.none { it.label == p } }
            if (extra.isNotEmpty()) {
                checks.lastOrNull()?.isSelected = true
                other.text = extra.joinToString(", ")
                other.isEnabled = true
            }
        } else {
            val idx = q.options.indexOfFirst { it.label == ans }
            if (idx >= 0) {
                radios.getOrNull(idx)?.isSelected = true
            } else if (ans.isNotBlank()) {
                radios.lastOrNull()?.isSelected = true
                other.text = ans
                other.isEnabled = true
            }
        }
    }

    private fun saveAndNext(q: AskUserQuestionTool.Question, radios: List<JRadioButton>, checks: List<JCheckBox>, other: JTextField) {
        answers[q.header] = collect(q, radios, checks, other)
        index += 1
        render()
    }

    private fun saveAndBack(q: AskUserQuestionTool.Question, radios: List<JRadioButton>, checks: List<JCheckBox>, other: JTextField) {
        answers[q.header] = collect(q, radios, checks, other)
        index -= 1
        render()
    }

    private fun saveAndSubmit(q: AskUserQuestionTool.Question, radios: List<JRadioButton>, checks: List<JCheckBox>, other: JTextField) {
        answers[q.header] = collect(q, radios, checks, other)
        onSubmit(answers)
        removeSelf()
    }

    private fun cancel() {
        onCancel()
        removeSelf()
    }

    private fun removeSelf() {
        isVisible = false
        parent?.remove(this)
        parent?.revalidate()
        parent?.repaint()
    }
}
