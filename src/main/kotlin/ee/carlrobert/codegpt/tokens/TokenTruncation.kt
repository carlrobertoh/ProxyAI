package ee.carlrobert.codegpt.tokens

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.EncodingManager

/**
 * Utilities to enforce token-based truncation on strings passed back to the model.
 * Applies a head+tail gap strategy by default to preserve the beginning and the end.
 */
object ToolResultTruncationConfig {
    const val MAX_TOKENS: Int = 16_000
    const val DEFAULT_HEAD_RATIO: Double = 0.85 // keep more head than tail by default
    const val GAP_MARKER: String = "\n...\n"
}

fun String.countTokens(): Int = service<EncodingManager>().countTokens(this)

fun String.truncateTokens(maxTokens: Int, fromStart: Boolean = true): String =
    service<EncodingManager>().truncateText(this, maxTokens, fromStart)

fun String.truncateTokensWithGap(
    maxTokens: Int = ToolResultTruncationConfig.MAX_TOKENS,
    headRatio: Double = ToolResultTruncationConfig.DEFAULT_HEAD_RATIO,
    gapMarker: String = ToolResultTruncationConfig.GAP_MARKER,
): String {
    if (this.isEmpty()) return this
    val total = this.countTokens()
    if (total <= maxTokens) return this

    val safeHeadRatio = headRatio.coerceIn(0.1, 0.9)
    val headTokens = (maxTokens * safeHeadRatio).toInt().coerceAtLeast(1)
    val tailTokens = (maxTokens - headTokens).coerceAtLeast(1)

    val head = this.truncateTokens(headTokens, fromStart = true)
    val tail = this.truncateTokens(tailTokens, fromStart = false)
    return buildString(head.length + gapMarker.length + tail.length) {
        append(head)
        append(gapMarker)
        append(tail)
    }
}

fun String.truncateToolResult(maxTokens: Int = ToolResultTruncationConfig.MAX_TOKENS): String =
    this.truncateTokensWithGap(maxTokens)

