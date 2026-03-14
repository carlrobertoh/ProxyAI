package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.diagnostics.DiagnosticsFilter
import ee.carlrobert.codegpt.diagnostics.ProjectDiagnosticsService
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.textarea.header.tag.DiagnosticsTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.action.selectedContextFiles
import ee.carlrobert.codegpt.ui.textarea.lookup.group.DiagnosticsGroupItem
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class DiagnosticsIntegrationTest : IntegrationTest() {

    fun `test diagnostics service should return errors for broken file`() {
        val brokenFile = myFixture.configureByText(
            "Broken.java",
            "class Broken { void test() { int value = ; } }"
        ).virtualFile
        myFixture.doHighlighting()

        val report = project.service<ProjectDiagnosticsService>()
            .collect(brokenFile, DiagnosticsFilter.ERRORS_ONLY)

        assertThat(report.hasDiagnostics).isTrue()
        assertThat(report.content).contains("[ERROR]")
        assertThat(report.content).contains("Broken.java")
    }

    fun `test diagnostics service should return empty for clean file`() {
        val cleanFile = myFixture.configureByText(
            "Clean.java",
            "class Clean { void test() { int value = 1; } }"
        ).virtualFile
        myFixture.doHighlighting()

        val report = project.service<ProjectDiagnosticsService>()
            .collect(cleanFile, DiagnosticsFilter.ERRORS_ONLY)

        assertThat(report.hasDiagnostics).isFalse()
        assertThat(report.content).isBlank()
        assertThat(report.error).isNull()
    }

    fun `test selectedContextFiles should use selected file context only`() {
        val firstFile = myFixture.configureByText("First.java", "class First {}").virtualFile
        val secondFile = myFixture.configureByText("Second.java", "class Second {}").virtualFile
        val unselectedEditorTag = EditorTagDetails(secondFile).apply { selected = false }

        val contextFiles = selectedContextFiles(
            listOf(
                FileTagDetails(firstFile),
                DiagnosticsTagDetails(firstFile, DiagnosticsFilter.ALL),
                EditorTagDetails(secondFile),
                unselectedEditorTag
            )
        )

        assertThat(contextFiles.map { it.path })
            .containsExactly(firstFile.path, secondFile.path)
    }

    fun `test diagnostics group should be available in agent mode with file context`() {
        val file = myFixture.configureByText("AgentFile.java", "class AgentFile {}").virtualFile
        val tagManager = TagManager().apply {
            addTag(FileTagDetails(file))
        }

        val groups = SearchManager(project, tagManager, FeatureType.AGENT).getDefaultGroups()
        val diagnosticsGroup = groups.filterIsInstance<DiagnosticsGroupItem>().single()
        val actions = runBlocking { diagnosticsGroup.getLookupItems("") }

        assertThat(diagnosticsGroup.enabled).isTrue()
        assertThat(actions.map { it.displayName }).containsExactly("Errors only", "All")
    }
}
