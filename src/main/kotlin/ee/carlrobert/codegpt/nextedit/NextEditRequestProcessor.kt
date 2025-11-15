package ee.carlrobert.codegpt.nextedit

import ee.carlrobert.codegpt.nextedit.NextEditPromptUtil

object NextEditRequestProcessor {
    const val EDITABLE_REGION_START = "<|editable_region_start|>"
    const val EDITABLE_REGION_END = "<|/editable_region_end|>"

    fun replaceEditableRegionStrict(originalFileWithMarkers: String, newRegionContent: String): String {
        val startIdx = originalFileWithMarkers.indexOf(EDITABLE_REGION_START)
        val endIdx = originalFileWithMarkers.indexOf(EDITABLE_REGION_END)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) {
            return originalFileWithMarkers
        }

        val prefix = originalFileWithMarkers.substring(0, startIdx)
        val suffix = originalFileWithMarkers.substring(endIdx + EDITABLE_REGION_END.length)

        val result = StringBuilder(prefix)
        if (!prefix.endsWith("\n")) {
            result.append('\n')
        }
        result.append(newRegionContent)
        if (!newRegionContent.endsWith("\n")) {
            result.append('\n')
        }
        result.append(if (suffix.startsWith("\n")) suffix.substring(1) else suffix)
        return result.toString()
    }

    fun buildFileWithMarkers(
        fileContent: String,
        caretOffset: Int,
        linesBefore: Int,
        linesAfter: Int,
        maxLines: Int
    ): String {
        val (startPos, endPos) = NextEditPromptUtil.determineEditableRegionByLines(
            fileContent,
            caretOffset,
            linesBefore,
            linesAfter,
            maxLines
        )
        val prefix = fileContent.substring(0, startPos)
        val regionContent = fileContent.substring(startPos, endPos)
        val suffix = fileContent.substring(endPos)

        val sb = StringBuilder()
        sb.append(prefix)
        if (!prefix.endsWith("\n")) sb.append('\n')
        sb.append(EDITABLE_REGION_START).append('\n')
        sb.append(regionContent)
        if (!regionContent.endsWith("\n")) sb.append('\n')
        sb.append(EDITABLE_REGION_END).append('\n')
        sb.append(suffix)
        return sb.toString()
    }
}