package ee.carlrobert.codegpt.codecompletions.psi

import com.intellij.openapi.editor.VisualPosition
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import ee.carlrobert.codegpt.util.file.FileUtil.getResourceContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore

@Ignore
class PythonContextFinderTest : BasePlatformTestCase() {

    private val contextFinder = PythonContextFinder()

    fun testFindEnclosingElementMethod() {
        val file = myFixture.configureByText(
            "util.py",
            getResourceContent("/codecompletions/psi/python.txt")
        )
        val psiElement =
            file.findElementAt(myFixture.editor.visualPositionToOffset(VisualPosition(11, 1)))
        val enclosingElement = contextFinder.findEnclosingElement(psiElement!!)
        assertThat(enclosingElement)
            .isInstanceOf(PyFunction::class.java)
            .extracting("name")
            .isEqualTo("randomStrings")
    }

    fun testFindEnclosingElementClass() {
        val file = myFixture.configureByText(
            "util.py",
            getResourceContent("/codecompletions/psi/python.txt")
        )
        val psiElement =
            file.findElementAt(myFixture.editor.visualPositionToOffset(VisualPosition(6, 2)))
        val contextFinder = contextFinder
        val enclosingElement = contextFinder.findEnclosingElement(psiElement!!)
        assertThat(enclosingElement)
            .isInstanceOf(PyClass::class.java)
            .extracting("name")
            .isEqualTo("Util")
    }

    fun testFindRelevantElements() {
        val file = myFixture.configureByText(
            "util.py",
            getResourceContent("/codecompletions/psi/python.txt")
        )
        val psiMethod =
            (file as PyFile).topLevelClasses[0].methods[1]
        val relevantElements = contextFinder.findRelevantElements(psiMethod, psiMethod)
        assertThat(relevantElements)
            .hasSize(7)
            .extracting("name")
            .containsExactly(
                "test",
                "int",
                "List",
                "str",
                "randint",
                "range",
                "n",
            )
    }

}
