package ee.carlrobert.codegpt.predictions

enum class HunkKind { INSERTION, DELETION, REPLACEMENT }

enum class Scope { SINGLE_LINE, MULTI_LINE }

data class Hunk(
    val start: Int,
    val end: Int,
    val oldSlice: String,
    val newSlice: String,
    val deletionOnly: Boolean
)

data class ClassifiedHunk(
    val hunk: Hunk,
    val kind: HunkKind,
    val scope: Scope
)

object NextEditClassifier {
    fun classify(h: Hunk): ClassifiedHunk {
        val oldEff = h.oldSlice.trimEnd('\n', '\r')
        val newEff = h.newSlice.trimEnd('\n', '\r')
        val kind = when {
            oldEff.isEmpty() && newEff.isNotEmpty() -> HunkKind.INSERTION
            newEff.isEmpty() && oldEff.isNotEmpty() -> HunkKind.DELETION
            else -> HunkKind.REPLACEMENT
        }
        val scope = if (!oldEff.contains('\n') && !newEff.contains('\n')) Scope.SINGLE_LINE else Scope.MULTI_LINE
        return ClassifiedHunk(h, kind, scope)
    }
}

