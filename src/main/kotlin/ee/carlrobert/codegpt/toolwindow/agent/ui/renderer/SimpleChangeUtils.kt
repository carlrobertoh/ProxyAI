package ee.carlrobert.codegpt.toolwindow.agent.ui.renderer

import com.intellij.ui.JBColor
import java.awt.Color
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

object ChangeColors {
    val inserted: JBColor = JBColor(Color(0x2E7D32), Color(0x81C784))
    val deleted: JBColor = JBColor(Color(0xC62828), Color(0xEF9A9A))
    val modified: JBColor = JBColor(Color(0x1565C0), Color(0x90CAF9))
}

fun getFileContentWithFallback(path: String, charset: Charset = Charsets.UTF_8): String {
    return runCatching { Files.readString(Path.of(path), charset) }.getOrDefault("")
}

fun applyStringReplacement(
    original: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean
): String {
    if (oldString.isEmpty()) return original
    return if (replaceAll) original.replace(oldString, newString) else original.replaceFirst(oldString, newString)
}

fun lineDiffStats(before: String, after: String): Triple<Int, Int, Int> {
    if (before == after) return Triple(0, 0, 0)
    val a = before.split('\n')
    val b = after.split('\n')
    val lcs = longestCommonSubsequenceLength(a, b)
    val deletions = (a.size - lcs).coerceAtLeast(0)
    val insertions = (b.size - lcs).coerceAtLeast(0)
    val changed = 0
    return Triple(insertions, deletions, changed)
}

private fun longestCommonSubsequenceLength(a: List<String>, b: List<String>): Int {
    val n = a.size
    val m = b.size
    if (n == 0 || m == 0) return 0
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        val ai = a[i - 1]
        for (j in 1..m) {
            dp[i][j] = if (ai == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }
    return dp[n][m]
}
