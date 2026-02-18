package ee.carlrobert.codegpt.toolwindow.visualizer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class MermaidInputParserTest {

    @Test
    fun shouldExtractMermaidFencedCodeBlock() {
        val input = """
            Some text
            ```mermaid
            flowchart TD
              A --> B
            ```
            More text
        """.trimIndent()

        assertThat(MermaidInputParser.extractDiagramSource(input)).isEqualTo(
            """
            flowchart TD
              A --> B
            """.trimIndent()
        )
    }

    @Test
    fun shouldFallbackToGenericFencedCodeBlock() {
        val input = """
            ```text
            sequenceDiagram
              Alice->>Bob: Hello Bob
            ```
        """.trimIndent()

        assertThat(MermaidInputParser.extractDiagramSource(input)).isEqualTo(
            """
            sequenceDiagram
              Alice->>Bob: Hello Bob
            """.trimIndent()
        )
    }

    @Test
    fun shouldReturnTrimmedInputWhenNoFencedBlockExists() {
        val input = "classDiagram\n  Animal <|-- Duck"

        assertThat(MermaidInputParser.extractDiagramSource(input)).isEqualTo(
            """
            classDiagram
              Animal <|-- Duck
            """.trimIndent()
        )
    }

    @Test
    fun shouldNormalizeCollapsedStatementsIntoSeparateLines() {
        val input = """
            flowchart TD
              W --> BV[backend/.venv/ (present)]  W --> WX[worker]
        """.trimIndent()

        val normalized = MermaidInputParser.normalizeDiagramSource(input)

        assertThat(normalized).isEqualTo(
            """
            flowchart TD
            W --> BV[backend/.venv/ (present)]
            W --> WX[worker]
            """.trimIndent()
        )
    }

    @Test
    fun shouldDetectHumanReadableDiagramTypeLabel() {
        val input = """
            sequenceDiagram
              Alice->>Bob: Hello Bob
        """.trimIndent()

        assertThat(MermaidInputParser.detectDiagramTypeLabel(input)).isEqualTo("Sequence Diagram")
    }

    @Test
    fun shouldFallbackToGenericDiagramLabelWhenDirectiveIsMissing() {
        val input = """
            A --> B
        """.trimIndent()

        assertThat(MermaidInputParser.detectDiagramTypeLabel(input)).isEqualTo("Mermaid Diagram")
    }

    @Test
    fun shouldDetectDiagramTypeWhenCommentAppearsBeforeDirective() {
        val input = """
            %% Flowchart (graph)
            graph TD
              A[Start] --> B{Decision}
        """.trimIndent()

        assertThat(MermaidInputParser.detectDiagramTypeLabel(input)).isEqualTo("Flowchart")
    }

    @Test
    fun shouldDetectDiagramTypeFromFencedMermaidInput() {
        val input = """
            ```mermaid
            %% Git Graph (gitgraph)
            gitGraph
              commit id: "init"
            ```
        """.trimIndent()

        assertThat(MermaidInputParser.detectDiagramTypeLabel(input)).isEqualTo("Git Graph")
    }
}
