package ee.carlrobert.codegpt.psistructure

import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.*

class KotlinPropertyAnalyzer {

    fun resolveInferredType(property: KtProperty): String {
        return try {
            val initializer = property.initializer
            if (initializer != null) {
                val bindingContext = initializer.analyze()
                val type = bindingContext.getType(initializer)?.toString()
                if (type != null) {
                    return type
                }
            }

            val delExpression = property.delegate?.expression
            if (delExpression != null) {
                val delegatedType = resolveDelegatedPropertyType(property)
                if (delegatedType != TYPE_UNKNOWN) {
                    return delegatedType
                }
            }

            val getterType = resolvePropertyWithGetter(property)
            if (getterType != TYPE_UNKNOWN) {
                return getterType
            }

            val expressionType = resolveExpressionType(property)
            if (expressionType != TYPE_UNKNOWN) {
                return expressionType
            }
            TYPE_UNKNOWN
        } catch (e: Exception) {
            TYPE_UNKNOWN
        }
    }


    private fun resolveDelegatedPropertyType(property: KtProperty): String {
        return property.delegate?.expression?.let { delegateExpr ->
            when (val initializer = getDelegateInitializer(delegateExpr)) {
                is KtLambdaExpression -> resolveLambdaReturnType(initializer)
                else -> resolveExpressionType(initializer)
            }
        } ?: TYPE_UNKNOWN
    }

    private fun getDelegateInitializer(expr: KtExpression): KtExpression? {
        return when (expr) {
            is KtCallExpression -> expr.lambdaArguments.firstOrNull()?.getLambdaExpression()
            is KtBinaryExpression -> expr.right?.let(::getDelegateInitializer)
            else -> null
        }
    }

    private fun resolveLambdaReturnType(lambda: KtLambdaExpression): String {
        return try {
            val lastExpr = lambda.bodyExpression?.statements?.lastOrNull()
            val bindingContext = lambda.analyze()
            lastExpr?.let { bindingContext.getType(it)?.toString() } ?: TYPE_UNKNOWN
        } catch (e: Exception) {
            TYPE_UNKNOWN
        }
    }

    private fun resolvePropertyWithGetter(property: KtProperty): String {
        return property.getter?.bodyExpression?.let { expr ->
            try {
                val bindingContext = expr.analyze()
                bindingContext.getType(expr)?.toString() ?: TYPE_UNKNOWN
            } catch (e: Exception) {
                TYPE_UNKNOWN
            }
        } ?: TYPE_UNKNOWN
    }

    private fun resolveExpressionType(expression: KtExpression?): String {
        if (expression == null) return TYPE_UNKNOWN

        if (expression is KtDotQualifiedExpression) {
            return resolveQualifiedChain(expression)
        }

        return try {
            val bindingContext = expression.analyze()
            val ktType = bindingContext.getType(expression)
            ktType?.toString() ?: TYPE_UNKNOWN
        } catch (e: Exception) {
            TYPE_UNKNOWN
        }
    }

    private fun resolveQualifiedChain(expr: KtDotQualifiedExpression): String {
        return buildString {
            var currentExpr: KtExpression? = expr
            while (currentExpr is KtDotQualifiedExpression) {
                val selector = currentExpr.selectorExpression?.text ?: break
                append(".").append(selector)
                currentExpr = currentExpr.receiverExpression
            }
            val rootType = currentExpr?.let(::resolveExpressionType) ?: TYPE_UNKNOWN
            replaceFirst(Regex("."), rootType)
        }
    }
}

private const val TYPE_UNKNOWN = "TypeUnknown"