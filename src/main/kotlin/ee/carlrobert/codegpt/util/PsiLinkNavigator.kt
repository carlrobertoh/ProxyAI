package ee.carlrobert.codegpt.util

import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.ide.util.gotoByName.GotoSymbolModel2
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PsiLinkNavigator {
    private val logger = thisLogger()

    private const val PSI_ELEMENT_PREFIX = "psi_element://"
    private const val FILE_PREFIX = "file://"

    private val SUPPORTED_PROTOCOLS = listOf(PSI_ELEMENT_PREFIX, FILE_PREFIX)

    @JvmStatic
    fun handle(description: String): Boolean {
        if (!isValidNavigationLink(description)) {
            return false
        }

        val protocol = extractProtocol(description)
        val target = extractTarget(description)

        return try {
            ReadAction.nonBlocking<Navigatable?> {
                val resolver = NavigationResolverFactory.create(protocol)
                resolver.resolve(target)
            }
                .finishOnUiThread(ModalityState.nonModal()) { navigatable ->
                    navigatable?.let { navigate(it) }
                }
                .submit(AppExecutorUtil.getAppExecutorService())
            true
        } catch (t: Throwable) {
            logger.warn("Failed to schedule navigation for: $target", t)
            false
        }
    }

    @JvmStatic
    fun isValidNavigationLink(description: String?): Boolean {
        if (description.isNullOrBlank()) return false
        return SUPPORTED_PROTOCOLS.any { description.startsWith(it) }
    }

    private fun extractProtocol(description: String): String {
        return SUPPORTED_PROTOCOLS.first { description.startsWith(it) }
    }

    private fun extractTarget(description: String): String {
        val protocol = extractProtocol(description)
        val raw = description.removePrefix(protocol)
        return decode(raw)
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        logger.error("Failed to decode URL: $value", e)
        value
    }

    private fun navigate(target: Navigatable) {
        if (target.canNavigate()) {
            target.navigate(true)
        } else {
            logger.warn("Cannot navigate to target: $target")
        }
    }
}

abstract class NavigationResolver {
    abstract fun resolve(target: String): Navigatable?

    protected val logger = thisLogger()
    protected fun getProject(): Project? = ApplicationUtil.findCurrentProject()
}

class PsiElementResolver : NavigationResolver() {
    override fun resolve(target: String): Navigatable? {
        val project = getProject() ?: return null

        findByJavaFQN(project, target)?.let { return it }

        return findByMemberSeparation(project, target)
    }

    private fun findByJavaFQN(project: Project, target: String): Navigatable? {
        try {
            val memberSeparatorIndex = target.indexOf('#')
            val className = if (memberSeparatorIndex > 0) {
                target.substring(0, memberSeparatorIndex)
            } else {
                target
            }

            val javaPsiFacade = JavaPsiFacade.getInstance(project)
            val projectScope = GlobalSearchScope.projectScope(project)
            val allScope = GlobalSearchScope.allScope(project)

            val psiClass = javaPsiFacade.findClass(className, projectScope)
                ?: javaPsiFacade.findClass(className, allScope)

            if (psiClass != null) {
                val memberName = if (memberSeparatorIndex > 0) {
                    target.substring(memberSeparatorIndex + 1)
                } else {
                    null
                }

                if (memberName != null) {
                    findMemberInClass(psiClass, memberName)?.let { return it }
                }
                return psiClass
            }

            if (className.contains('$')) {
                val innerClassFQN = className.replace('$', '.')
                val innerClass = javaPsiFacade.findClass(innerClassFQN, projectScope)
                    ?: javaPsiFacade.findClass(innerClassFQN, allScope)
                if (innerClass != null) {
                    return innerClass
                }
            }

            return null
        } catch (t: Throwable) {
            logger.warn("Failed to resolve Java FQN: $target", t)
            return null
        }
    }

    private fun findByMemberSeparation(project: Project, target: String): Navigatable? {
        val memberSeparatorIndex = target.indexOf('#')
        val owner =
            if (memberSeparatorIndex > 0) target.substring(0, memberSeparatorIndex) else target

        searchInModels(project, owner)?.let { ownerElement ->
            val member = target.substring(memberSeparatorIndex + 1)
            if (memberSeparatorIndex > 0) {
                if (ownerElement is PsiClass) {
                    findMemberInClass(ownerElement, member)?.let { return it }
                }
                if (ownerElement is PsiElement) {
                    ownerElement.findNavigatableChildElement(member)?.let { return it }
                }
            }
            return ownerElement
        }

        return null
    }

    private fun findMemberInClass(psiClass: PsiClass, memberName: String): Navigatable? {
        psiClass.findMethodsByName(memberName, false).firstOrNull()?.let { return it }
        psiClass.findFieldByName(memberName, false)?.let { return it }
        psiClass.findInnerClassByName(memberName, false)?.let { return it }

        return psiClass.findNavigatableChildElement(memberName)
    }

    private fun searchInModels(project: Project, searchTerm: String): Navigatable? {
        try {
            val classModel = GotoClassModel2(project)
            classModel.getElementsByName(searchTerm, true, searchTerm)
                .filterIsInstance<Navigatable>()
                .firstOrNull { it.canNavigate() }
                ?.let { return it }

            val symbolModel = GotoSymbolModel2(project, project)
            symbolModel.getElementsByName(searchTerm, true, searchTerm)
                .filterIsInstance<Navigatable>()
                .firstOrNull { it.canNavigate() }
                ?.let { return it }
        } catch (e: Exception) {
            logger.warn("Search failed for term: $searchTerm", e)
        }
        return null
    }
}

class FileResolver : NavigationResolver() {

    override fun resolve(target: String): Navigatable? {
        val project = getProject() ?: return null

        val memberSeparatorIndex = target.indexOf('#')
        val lineSeparatorIndex = target.indexOf(':')

        val filePath: String
        val line: Int?
        val column: Int?
        val memberName: String?

        when {
            memberSeparatorIndex > 0 && target.substring(memberSeparatorIndex + 1)
                .contains(',') -> {
                filePath = target.substring(0, memberSeparatorIndex)
                val lineColumn = target.substring(memberSeparatorIndex + 1)
                val parts = lineColumn.split(',')
                line = parts[0].toIntOrNull()
                column = parts.getOrNull(1)?.toIntOrNull()
                memberName = null
            }

            memberSeparatorIndex > 0 && target.substring(memberSeparatorIndex + 1).firstOrNull()
                ?.isDigit() == true -> {
                filePath = target.substring(0, memberSeparatorIndex)
                line = target.substring(memberSeparatorIndex + 1).toIntOrNull()
                column = null
                memberName = null
            }

            lineSeparatorIndex > 0 -> {
                filePath = target.substring(0, lineSeparatorIndex)
                line = target.substring(lineSeparatorIndex + 1).toIntOrNull()
                column = null
                memberName = null
            }

            memberSeparatorIndex > 0 -> {
                filePath = target.substring(0, memberSeparatorIndex)
                line = null
                column = null
                memberName = target.substring(memberSeparatorIndex + 1)
            }

            else -> {
                filePath = target
                line = null
                column = null
                memberName = null
            }
        }

        val fileName = filePath.substringAfterLast('/')
        val matchingVirtualFile =
            FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                .firstOrNull { vf ->
                    vf.path.endsWith(filePath) || vf.name == fileName
                }

        if (matchingVirtualFile != null) {
            if (line != null) {
                return OpenFileDescriptor(
                    project,
                    matchingVirtualFile,
                    (line - 1).coerceAtLeast(0),
                    column ?: 0
                )
            }

            val psiFile = PsiManager.getInstance(project).findFile(matchingVirtualFile)
            if (psiFile != null) {
                if (memberName != null) {
                    val memberElement = psiFile.findNavigatableChildElement(memberName)
                    return memberElement ?: psiFile
                }
                return psiFile
            }
        }

        return try {
            val fileNameWithoutExtension = fileName.takeWhile { it == '.' }
            searchInModels(project, fileNameWithoutExtension)?.let { ownerElement ->
                if (memberName != null) {
                    if (ownerElement is PsiElement) {
                        ownerElement.findNavigatableChildElement(memberName)?.let { return it }
                    }
                }
                return ownerElement
            }
        } catch (t: Throwable) {
            logger.warn("File search failed for: $target", t)
            null
        }
    }

    private fun searchInModels(project: Project, searchTerm: String): Navigatable? {
        try {
            val classModel = GotoClassModel2(project)
            classModel.getElementsByName(searchTerm, true, searchTerm)
                .filterIsInstance<Navigatable>()
                .firstOrNull { it.canNavigate() }
                ?.let { return it }

            val symbolModel = GotoSymbolModel2(project, project)
            symbolModel.getElementsByName(searchTerm, true, searchTerm)
                .filterIsInstance<Navigatable>()
                .firstOrNull { it.canNavigate() }
                ?.let { return it }
        } catch (e: Exception) {
            logger.warn("Search failed for term: $searchTerm", e)
        }
        return null
    }
}

object NavigationResolverFactory {
    fun create(protocol: String): NavigationResolver {
        return when (protocol) {
            "psi_element://" -> PsiElementResolver()
            "file://" -> FileResolver()
            else -> throw IllegalArgumentException("Unsupported protocol: $protocol")
        }
    }
}

private fun PsiElement.findNavigatableChildElement(memberName: String): Navigatable? {
    return PsiTreeUtil.findChildrenOfType(this, PsiNamedElement::class.java)
        .firstOrNull { it.name == memberName } as? Navigatable
}