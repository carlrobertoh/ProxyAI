package ee.carlrobert.codegpt.ui.textarea

import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class TagManagerIntegrationTest : IntegrationTest() {

    fun `test adding third unselected editor tag removes oldest unselected editor tag only`() {
        val attachedFileOne = myFixture.configureByText("AttachedOne.kt", "class AttachedOne").virtualFile
        val attachedFileTwo = myFixture.configureByText("AttachedTwo.kt", "class AttachedTwo").virtualFile
        val editorFileOne = myFixture.configureByText("EditorOne.kt", "class EditorOne").virtualFile
        val editorFileTwo = myFixture.configureByText("EditorTwo.kt", "class EditorTwo").virtualFile
        val editorFileThree = myFixture.configureByText("EditorThree.kt", "class EditorThree").virtualFile

        val attachedTagOne = FileTagDetails(attachedFileOne).apply { selected = false }
        val attachedTagTwo = FileTagDetails(attachedFileTwo).apply { selected = false }
        val editorTagOne = EditorTagDetails(editorFileOne).apply { selected = false }
        val editorTagTwo = EditorTagDetails(editorFileTwo).apply { selected = false }
        val editorTagThree = EditorTagDetails(editorFileThree).apply { selected = false }

        val tagManager = TagManager().apply {
            addTag(attachedTagOne)
            addTag(attachedTagTwo)
            addTag(editorTagOne)
            addTag(editorTagTwo)
            addTag(editorTagThree)
        }

        val tags = tagManager.getTags()

        assertThat(tags).contains(attachedTagOne, attachedTagTwo, editorTagTwo, editorTagThree)
        assertThat(tags).doesNotContain(editorTagOne)
    }
}
