package ee.carlrobert.codegpt.toolwindow.visualizer

object MermaidInputParser {

    private val mermaidFenceRegex = Regex(
        pattern = "(?is)```\\s*mermaid\\s*\\R([\\s\\S]*?)\\R```"
    )
    private val genericFenceRegex = Regex(
        pattern = "(?is)```[^\\n`]*\\R([\\s\\S]*?)\\R```"
    )
    private val edgeTokenRegex = Regex("-->|-.->|==>|---|--x|--o")
    private val collapsedStatementRegex = Regex(
        pattern = "\\s{2,}([A-Za-z_][A-Za-z0-9_:\\-]*\\s*(?:-->|-.->|==>|---|--x|--o))"
    )
    private val clickDirectiveRegex = Regex("(?m)^\\s*click\\s+.+$")
    private val diagramTypeLabels = linkedMapOf(
        "graph " to "Flowchart",
        "flowchart" to "Flowchart",
        "sequencediagram" to "Sequence Diagram",
        "classdiagram" to "Class Diagram",
        "statediagram" to "State Diagram",
        "statediagram-v2" to "State Diagram",
        "erdiagram" to "ER Diagram",
        "journey" to "Journey",
        "gantt" to "Gantt",
        "pie" to "Pie Chart",
        "quadrantchart" to "Quadrant Chart",
        "requirementdiagram" to "Requirement Diagram",
        "gitgraph" to "Git Graph",
        "mindmap" to "Mind Map",
        "timeline" to "Timeline",
        "zenuml" to "ZenUML",
        "sankey-beta" to "Sankey",
        "xychart-beta" to "XY Chart",
        "block-beta" to "Block Diagram",
        "packet-beta" to "Packet Diagram",
        "kanban" to "Kanban",
        "architecture-beta" to "Architecture Diagram",
        "radar-beta" to "Radar Chart",
        "treemap-beta" to "Treemap"
    )
    private val knownDiagramPrefixes = diagramTypeLabels.keys.toList()

    fun extractDiagramSource(input: String): String {
        if (input.isBlank()) {
            return ""
        }

        mermaidFenceRegex.find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        genericFenceRegex.find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return input.trim()
    }

    fun normalizeDiagramSource(source: String): String {
        if (source.isBlank()) {
            return ""
        }

        val normalizedLineEndings = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val output = mutableListOf<String>()
        normalizedLineEndings.lineSequence().forEach { line ->
            output += splitCollapsedStatements(line)
        }
        return output.joinToString("\n").trim()
    }

    fun sanitizeForRender(source: String): String {
        val normalized = normalizeDiagramSource(source)
        if (!supportsClickDirectives(normalized)) {
            return clickDirectiveRegex.replace(normalized, "").trim()
        }
        return normalized
    }

    fun supportsClickDirectives(source: String): Boolean {
        val directive = detectDiagramDirective(source) ?: return false
        return directive == "flowchart" || directive == "graph "
    }

    fun detectDiagramDirective(source: String): String? {
        val first = resolveDirectiveLine(source) ?: return null
        return knownDiagramPrefixes.firstOrNull { prefix ->
            first == prefix || first.startsWith(prefix)
        }
    }

    fun detectDiagramTypeLabel(source: String): String {
        val directive = detectDiagramDirective(source) ?: return "Mermaid Diagram"
        return diagramTypeLabels[directive] ?: "Mermaid Diagram"
    }

    private fun resolveDirectiveLine(source: String): String? {
        val normalized = normalizeDiagramSource(extractDiagramSource(source))
        return normalized.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.isNotBlank()
                        && !line.startsWith("%%")
            }
            ?.lowercase()
    }

    private fun splitCollapsedStatements(line: String): List<String> {
        if (line.isBlank()) {
            return listOf(line)
        }
        if (edgeTokenRegex.findAll(line).count() <= 1) {
            return listOf(line)
        }

        val parts = mutableListOf<String>()
        var remaining = line
        while (edgeTokenRegex.findAll(remaining).count() > 1) {
            val match = collapsedStatementRegex.find(remaining) ?: break
            val head = remaining.substring(0, match.range.first).trimEnd()
            if (head.isNotBlank()) {
                parts += head
            }
            remaining = remaining.substring(match.range.first).trimStart()
        }

        if (remaining.isNotBlank()) {
            parts += remaining
        }
        return if (parts.isEmpty()) listOf(line) else parts
    }
}
