package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import kotlin.math.max
import kotlin.math.round

object TokenCounter {
    private val logger = thisLogger()

    fun countForEntries(entries: List<RunEntry>): Int = entries.sumOf { countForEntry(it) }

    private fun countForEntry(entry: RunEntry): Int = when (entry) {
        is RunEntry.ReadEntry -> when (val r = entry.result) {
            is ReadTool.Result.Success -> count(r.content)
            is ReadTool.Result.Error -> count(r.error)
            else -> 0
        }

        is RunEntry.IntelliJSearchEntry -> entry.result?.let { count(it.output) } ?: 0
        is RunEntry.BashEntry -> entry.result?.let { count(it.output) } ?: 0
        is RunEntry.WebEntry -> entry.result?.let { res ->
            val titles = res.results.joinToString("\n") { it.title + "\n" + it.content }
            count(res.query) + count(titles)
        } ?: 0

        is RunEntry.WriteEntry -> when (val r = entry.result) {
            is WriteTool.Result.Success -> count(r.message)
            is WriteTool.Result.Error -> count(r.error)
            else -> 0
        }

        is RunEntry.EditEntry -> when (val r = entry.result) {
            is EditTool.Result.Success -> count(r.message)
            is EditTool.Result.Error -> count(r.error)
            else -> 0
        }

        else -> 0
    }

    private fun count(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return try {
            EncodingManager.getInstance().countTokens(text)
        } catch (e: Exception) {
            logger.warn(
                "Something went wrong when calculating tokens. Using fallback calculation logic.",
                e
            )
            val approx = round(text.length / 4.0).toInt()
            max(approx, 1)
        }
    }
}
