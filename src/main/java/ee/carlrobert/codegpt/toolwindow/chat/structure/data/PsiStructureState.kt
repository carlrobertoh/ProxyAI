package ee.carlrobert.codegpt.toolwindow.chat.structure.data

import ee.carlrobert.codegpt.psistructure.models.ClassStructure

sealed class PsiStructureState {

    data object UpdateInProgress : PsiStructureState()

    data object Disabled : PsiStructureState()

    data class Content(val elements: Set<ClassStructure>) : PsiStructureState()
}