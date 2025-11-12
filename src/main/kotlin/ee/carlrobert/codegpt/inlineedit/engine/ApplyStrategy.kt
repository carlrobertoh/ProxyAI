package ee.carlrobert.codegpt.inlineedit.engine

interface ApplyStrategy {
    fun apply(ctx: ApplyContext)
}

