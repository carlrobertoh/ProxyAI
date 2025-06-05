/*
package ee.carlrobert.codegpt.codecompletions.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.findParentOfType
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.resolve.PyResolveUtil
import com.jetbrains.python.psi.types.TypeEvalContext
import ee.carlrobert.codegpt.codecompletions.ContextElement
import ee.carlrobert.codegpt.codecompletions.InfillContext

class PythonContextFinder : LanguageContextFinder {

    */
/**
     * Finds enclosing [PyFunction] or [PyClass] of [psiElement] and
     * determines source code elements of all used [PyReferenceExpression]s for the context.
     *//*

    override fun findContext(psiElement: PsiElement): InfillContext {
        val enclosingElement = findEnclosingElement(psiElement)
        val referenceExpressions = findRelevantElements(enclosingElement, enclosingElement)
        val declarations =
            referenceExpressions.map { findDeclarations(it, psiElement.containingFile.project) }.flatten().distinct()
                .filter {
                    // Filter out elements whose source code is inside the enclosingElement
                    // e.g. for something like this: [i for i in range(10)]  findRelevantElements()
                    // would return a "PyReferenceExpression: i" which is irrelevant
                    !it.containingFile.equals(enclosingElement.containingFile) || !enclosingElement.textRange.contains(it.textRange)
                }
        val sourceElements = declarations.mapNotNull { findSourceElement(it) }
        return InfillContext(
            ContextElement(enclosingElement),
            sourceElements.map { ContextElement(it) }.toSet()
        )
    }

    fun findEnclosingElement(psiElement: PsiElement): PsiElement = findEnclosingContext(psiElement)
        ?: PsiTreeUtil.prevCodeLeaf(psiElement)?.let { findEnclosingContext(it) } ?: psiElement

    private fun findEnclosingContext(psiElement: PsiElement) =
        PsiTreeUtil.findFirstContext(psiElement, true) { it is PyFunction || it is PyClass }

    private fun findRelevantElements(
        psiElement: Collection<PsiElement>,
        rootElement: PsiElement
    ): Set<PyReferenceExpression> =
        psiElement.map { findRelevantElements(it, rootElement) }.flatten().distinctBy { it.name }
            .toSet()

    */
/**
     * Finds [PyReferenceExpression]s inside of [psiElement].
     * If [psiElement] is a [PyFunction] inside of a [PyClass] it also adds all [PyReferenceExpression] of any class/instance fields.
     *//*

    fun findRelevantElements(
        psiElement: PsiElement,
        rootElement: PsiElement
    ): Set<PyReferenceExpression> {
        val resultSet = mutableSetOf<PyReferenceExpression>()
        psiElement.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is PyReferenceExpression -> resultSet.add(element)
                    is PyFunction -> {
                        // If the cursor was not inside a PyFunction but inside a PyClass, do not look into the functions
                        if (rootElement is PyClass) {
                            return
                        }
                        val enclosingContext = findEnclosingContext(element)
                        if (enclosingContext is PyClass) {
                            // add class and instance fields of enclosing class
                            // TODO: class fields declarations have to be present in the infillPrompt
                            //  (same file as enclosingElement) as well
                            resultSet.addAll(
                                findRelevantElements(enclosingContext.classAttributes.mapNotNull {
                                    findTargetExpressionAssignment(
                                        it
                                    )
                                }, rootElement)
                            )
                            resultSet.addAll(
                                findRelevantElements(
                                    enclosingContext.instanceAttributes.mapNotNull {
                                        findTargetExpressionAssignment(it)
                                    },
                                    rootElement
                                )
                            )
                        }
                        super.visitElement(element)
                    }

                    else -> super.visitElement(element)
                }
            }
        })
        return resultSet.distinctBy { it.name }.toSet()
    }

    private fun findTargetExpressionAssignment(targetExpression: PyTargetExpression): PyExpression? {
        return targetExpression.findParentOfType<PyAssignmentStatement>()?.assignedValue
    }

    private fun findDeclarations(
        pyReference: PyReferenceExpression,
        project: Project
    ): Set<PsiElement> {
        // see https://github.com/JetBrains/intellij-community/blob/ae5290861a1f41b93c48c475239a52faa94b97b0/python/python-psi-impl/src/com/jetbrains/python/codeInsight/PyTargetElementEvaluator.java#L51-L54
        return PyResolveUtil.resolveDeclaration(
            pyReference.reference,
            PyResolveContext.defaultContext(
                TypeEvalContext.codeAnalysis(
                    project,
                    pyReference.containingFile
                )
            )
        )?.let {
            if (PyBuiltinCache.getInstance(pyReference).isBuiltin(it) || it.filePath().contains("/stdlib/")) {
                null
            } else {
                setOf(it)
            }
        } ?: emptySet()
    }

    private fun findSourceElement(psiElement: PsiElement): PsiElement? {
        val navigationElement = psiElement.navigationElement
        val file = navigationElement.containingFile.virtualFile
        return if (file.isInLocalFileSystem) {
            navigationElement
        } else {
            null
        }

    }
}
*/
