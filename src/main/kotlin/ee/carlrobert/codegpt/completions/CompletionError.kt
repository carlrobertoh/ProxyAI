package ee.carlrobert.codegpt.completions

data class CompletionError @JvmOverloads constructor(
    val message: String,
    val code: String? = null
)
