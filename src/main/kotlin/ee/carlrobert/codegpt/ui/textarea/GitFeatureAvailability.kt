package ee.carlrobert.codegpt.ui.textarea

object GitFeatureAvailability {
    val isAvailable: Boolean by lazy {
        runCatching {
            Class.forName("git4idea.GitCommit", false, GitFeatureAvailability::class.java.classLoader)
            true
        }.getOrDefault(false)
    }
}
