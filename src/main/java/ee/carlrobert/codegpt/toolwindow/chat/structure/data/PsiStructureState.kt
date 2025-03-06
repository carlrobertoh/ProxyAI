package ee.carlrobert.codegpt.toolwindow.chat.structure.data

import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails

sealed class PsiStructureState {

    data class UpdateInProgress(
        val currentlyAnalyzedTags: Set<TagDetails>,
    ) : PsiStructureState()

    data object Disabled : PsiStructureState()

    data class Content(
        val currentlyAnalyzedTags: Set<TagDetails>,
        val elements: Set<ClassStructure>
    ) : PsiStructureState()
}