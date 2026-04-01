package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.ui.textarea.header.tag.FolderTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.FilesGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.FoldersGroupItem
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class IgnoreRulesTagManagerIntegrationTest : IntegrationTest() {

    fun `test files group should not suggest ignored files`() {
        myFixture.addFileToProject("app/src/main/Hidden.kt", "class Hidden")
        val visibleFile = myFixture.addFileToProject("app/src/test/Visible.kt", "class Visible")
            .virtualFile
        writeSettings(ignoreEntries = listOf("app/src/main/**"))
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(visibleFile, true)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())

        val fileSuggestions = runBlocking { filesGroupItem.getLookupItems("kt") }
            .filterIsInstance<FileActionItem>()

        assertThat(fileSuggestions.map { it.file.path })
            .noneMatch { it.contains("/app/src/main/") }
            .anyMatch { it.contains("/app/src/test/Visible.kt") }
    }

    fun `test files group should not suggest ignored open files`() {
        val ignoredOpenFile =
            myFixture.addFileToProject("app/src/main/OpenHidden.kt", "class OpenHidden")
                .virtualFile
        val visibleOpenFile =
            myFixture.addFileToProject("app/src/test/OpenVisible.kt", "class OpenVisible")
                .virtualFile
        writeSettings(ignoreEntries = listOf("app/src/main/**"))
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(ignoredOpenFile, true)
            FileEditorManager.getInstance(project).openFile(visibleOpenFile, true)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())

        val fileSuggestions = runBlocking { filesGroupItem.getLookupItems("open") }
            .filterIsInstance<FileActionItem>()

        assertThat(fileSuggestions.map { it.file.path })
            .noneMatch { it.contains("/app/src/main/OpenHidden.kt") }
            .anyMatch { it.contains("/app/src/test/OpenVisible.kt") }
    }

    fun `test files group should only suggest open files`() {
        val openFile = myFixture.addFileToProject("app/src/test/Open.kt", "class Open").virtualFile
        myFixture.addFileToProject("app/src/test/Closed.kt", "class Closed")
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(openFile, true)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())

        val fileSuggestions = runBlocking { filesGroupItem.getLookupItems("") }
            .filterIsInstance<FileActionItem>()

        assertThat(fileSuggestions.map { it.file.path })
            .anyMatch { it.endsWith("/app/src/test/Open.kt") }
            .noneMatch { it.endsWith("/app/src/test/Closed.kt") }
    }

    fun `test files group typed search should include closed project files even when files are open`() {
        val openFile =
            myFixture.addFileToProject("app/src/test/OpenDocument.kt", "class OpenDocument")
                .virtualFile
        myFixture.addFileToProject("app/src/test/NeedleMatch.kt", "class NeedleMatch")
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(openFile, true)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())

        val fileSuggestions = runBlocking { filesGroupItem.getLookupItems("Needle") }
            .filterIsInstance<FileActionItem>()

        assertThat(fileSuggestions.map { it.file.path })
            .anyMatch { it.endsWith("/app/src/test/NeedleMatch.kt") }
    }

    fun `test files group should show include open files action first without icon`() {
        val openFile = myFixture.addFileToProject("app/src/test/Open.kt", "class Open").virtualFile
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(openFile, true)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())

        val suggestions = runBlocking { filesGroupItem.getLookupItems("") }

        assertThat(suggestions.first()).isInstanceOf(IncludeOpenFilesActionItem::class.java)
        assertThat(suggestions.first().icon).isNull()
    }

    fun `test include open files should stay in files group but not unrelated global search`() {
        val openFile = myFixture.addFileToProject("app/src/test/Open.kt", "class Open").virtualFile
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(openFile, true)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())
        val searchManager = SearchManager(project, TagManager())

        val fileGroupSuggestions = runBlocking { filesGroupItem.getLookupItems("needle") }
        val globalSearchResults = runBlocking { searchManager.performGlobalSearch("needle") }

        assertThat(fileGroupSuggestions.first()).isInstanceOf(IncludeOpenFilesActionItem::class.java)
        assertThat(globalSearchResults)
            .noneMatch { it is IncludeOpenFilesActionItem }
    }

    fun `test files group should fall back to recent files when no files are open`() {
        val recentFile =
            myFixture.addFileToProject("app/src/test/Recent.kt", "class Recent").virtualFile
        ApplicationManager.getApplication().invokeAndWait {
            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.openFile(recentFile, true)
            fileEditorManager.closeFile(recentFile)
        }
        val filesGroupItem = FilesGroupItem(project, TagManager())

        val fileSuggestions = runBlocking { filesGroupItem.getLookupItems("Recent") }
            .filterIsInstance<FileActionItem>()

        assertThat(fileSuggestions.map { it.file.path })
            .anyMatch { it.endsWith("/app/src/test/Recent.kt") }
    }

    fun `test folders group should not suggest ignored folders`() {
        myFixture.addFileToProject("app/src/main/Hidden.kt", "class Hidden")
        myFixture.addFileToProject("app/src/test/Visible.kt", "class Visible")
        writeSettings(ignoreEntries = listOf("app/src/main/**"))
        val foldersGroupItem = FoldersGroupItem(project, TagManager())

        val folderSuggestions = runBlocking { foldersGroupItem.getLookupItems("src") }
            .filterIsInstance<FolderActionItem>()
            .mapNotNull { extractFolderPath(it) }

        assertThat(folderSuggestions)
            .noneMatch { it.endsWith("/app/src/main") }
            .anyMatch { it.endsWith("/app/src/test") }
    }

    fun `test folder tag processor should skip ignored child files`() {
        val hiddenFile =
            myFixture.addFileToProject("app/src/main/Hidden.kt", "class Hidden").virtualFile
        myFixture.addFileToProject("app/src/test/Visible.kt", "class Visible")
        writeSettings(ignoreEntries = listOf("app/src/main/**"))
        val folderTagProcessor = FolderTagProcessor(
            project = project,
            tagDetails = FolderTagDetails(hiddenFile.parent.parent.parent.parent)
        )
        val message = Message("prompt")

        folderTagProcessor.process(message, StringBuilder())

        assertThat(message.referencedFilePaths.orEmpty())
            .noneMatch { it.endsWith("/app/src/main/Hidden.kt") }
            .anyMatch { it.endsWith("/app/src/test/Visible.kt") }
    }

    private fun extractFolderPath(item: FolderActionItem): String? {
        return runCatching {
            val field = FolderActionItem::class.java.getDeclaredField("folder")
            field.isAccessible = true
            (field.get(item) as? com.intellij.openapi.vfs.VirtualFile)?.path
        }.getOrNull()
    }

    private fun writeSettings(ignoreEntries: List<String>): File {
        val ignoreJson = ignoreEntries.joinToString(",") { "\"$it\"" }
        val file = File(project.basePath, ".proxyai/settings.json")
        file.parentFile.mkdirs()
        file.writeText(
            """{"ignore":[$ignoreJson],"permissions":{"allow":[],"ask":[],"deny":[]},"hooks":{}}"""
        )
        Files.setLastModifiedTime(
            file.toPath(),
            FileTime.fromMillis(System.currentTimeMillis() + 1000)
        )
        return file
    }
}
