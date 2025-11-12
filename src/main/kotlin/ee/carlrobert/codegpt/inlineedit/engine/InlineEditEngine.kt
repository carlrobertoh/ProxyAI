package ee.carlrobert.codegpt.inlineedit.engine

interface InlineEditEngine {
    fun apply(ctx: ApplyContext)
}

class InlineEditEngineImpl : InlineEditEngine {
    override fun apply(ctx: ApplyContext) {
        InlineEditApplyStrategyFactory.get().apply(ctx)
    }
}

