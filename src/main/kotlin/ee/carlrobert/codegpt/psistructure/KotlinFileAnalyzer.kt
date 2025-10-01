package ee.carlrobert.codegpt.psistructure

import com.intellij.openapi.roots.PackageIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import ee.carlrobert.codegpt.psistructure.models.ClassName
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.psistructure.models.ClassType
import ee.carlrobert.codegpt.psistructure.models.ConstructorStructure
import ee.carlrobert.codegpt.psistructure.models.EnumEntryName
import ee.carlrobert.codegpt.psistructure.models.FieldStructure
import ee.carlrobert.codegpt.psistructure.models.MethodStructure
import ee.carlrobert.codegpt.psistructure.models.ParameterInfo
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

class KotlinFileAnalyzer(
    private val psiFileQueue: PsiFileDepthQueue,
    private val ktFile: KtFile,
) {

    private val psiManager = PsiManager.getInstance(ktFile.project)
    private val kotlinPropertyAnalyzer = KotlinPropertyAnalyzer()

    private val filePackageTypes: Map<String, String> by lazy {
        val types = mutableListOf<String>()
        // Process files in our current packages
        ktFile.containingDirectory
            ?.children
            ?.forEach { file ->
                types.addAll(getDependencyFqNames(file))
            }

        importMap.entries
            .forEach { (importPath, importDirective) ->
                if (importDirective.isAllUnder) {
                    val packagePath = importPath.substringBeforeLast(".*")
                    types.addAll(getTypesInPackage(packagePath))
                } else {
                    types.add(importPath)
                }
            }

        val resultMap = mutableMapOf<String, String>()
        types.forEach { fqTypeString ->
            val parts = fqTypeString.split(".")
            // key com.example.domain.MergeRequestState.MyEnum.TWO -> MergeRequestState.MyEnum.TWO
            val key = parts.filter { it.isNotEmpty() && it[0].isUpperCase() }.joinToString(".")

            resultMap[key] = fqTypeString
        }

        return@lazy resultMap.toMap()
    }

    private fun getDependencyFqNames(file: PsiElement): List<String> {
        val types = mutableListOf<String>()
        when (file) {
            is KtFile -> PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java)
                .forEach { ktClassOrObject ->
                    types.add("${ktClassOrObject.fqName}")
                }

            is PsiJavaFile -> file.classes.forEach { javaClass ->
                types.add("${file.packageName}.${javaClass.name}")
            }
        }
        return types
    }

    private val importMap = ktFile.importDirectives
        .mapNotNull { import ->
            val path = import.importPath?.pathStr
            if (path != null) {
                path to import
            } else {
                null
            }
        }
        .toMap()

    fun analyze(): Set<ClassStructure> {
        val classStructures = mutableSetOf<ClassStructure>()

        val classes = PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject::class.java)
            .filter { it.parent is KtFile }
            .filterNot { it is KtEnumEntry }
            .toList()

        for (ktClass in classes) {
            getClassStructure(ktClass)?.also { classStructure ->
                classStructures.add(classStructure)
            }
        }

        return classStructures
    }

    private fun getClassStructure(ktClass: KtClassOrObject): ClassStructure? {
        val classNameString = ktClass.fqName?.asString() ?: return null
        val className = ClassName(classNameString)

        val classType = when {
            ktClass is KtClass && ktClass.isEnum() -> ClassType.ENUM
            ktClass is KtClass -> ClassType.CLASS
            ktClass is KtObjectDeclaration && ktClass.isCompanion() -> ClassType.COMPANION_OBJECT
            ktClass is KtObjectDeclaration -> ClassType.OBJECT
            else -> ClassType.CLASS
        }

        val classStructure = ClassStructure(
            name = className,
            simpleName = ClassName(ktClass.name.orEmpty()),
            classType = classType,
            modifierList = getModifiers(ktClass),
            packageName = ktClass.fqName?.parent()?.asString().orEmpty(),
            repositoryName = ktFile.project.name,
            virtualFile = ktFile.virtualFile,
        )

        analyzeSupertypes(
            ktClass, classStructure
        )

        ktClass.children.forEach { child ->
            analyzePsiElement(child, classStructure)
        }

        return classStructure
    }

    private fun getModifiers(ktModifierListOwner: KtModifierListOwner): List<String> {
        val modifiers = mutableListOf<String>()

        if (ktModifierListOwner.hasModifier(KtTokens.PUBLIC_KEYWORD)) modifiers.add("public")
        if (ktModifierListOwner.hasModifier(KtTokens.PRIVATE_KEYWORD)) modifiers.add("private")
        if (ktModifierListOwner.hasModifier(KtTokens.PROTECTED_KEYWORD)) modifiers.add("protected")
        if (ktModifierListOwner.hasModifier(KtTokens.INTERNAL_KEYWORD)) modifiers.add("internal")

        if (ktModifierListOwner.hasModifier(KtTokens.OVERRIDE_KEYWORD)) modifiers.add("override")
        if (ktModifierListOwner.hasModifier(KtTokens.OPEN_KEYWORD)) modifiers.add("open")
        if (ktModifierListOwner.hasModifier(KtTokens.FINAL_KEYWORD)) modifiers.add("final")
        if (ktModifierListOwner.hasModifier(KtTokens.ABSTRACT_KEYWORD)) modifiers.add("abstract")
        if (ktModifierListOwner.hasModifier(KtTokens.SEALED_KEYWORD)) modifiers.add("sealed")

        if (ktModifierListOwner.hasModifier(KtTokens.SUSPEND_KEYWORD)) modifiers.add("suspend")
        if (ktModifierListOwner.hasModifier(KtTokens.TAILREC_KEYWORD)) modifiers.add("tailrec")
        if (ktModifierListOwner.hasModifier(KtTokens.OPERATOR_KEYWORD)) modifiers.add("operator")
        if (ktModifierListOwner.hasModifier(KtTokens.INFIX_KEYWORD)) modifiers.add("infix")
        if (ktModifierListOwner.hasModifier(KtTokens.INLINE_KEYWORD)) modifiers.add("inline")
        if (ktModifierListOwner.hasModifier(KtTokens.EXTERNAL_KEYWORD)) modifiers.add("external")

        if (ktModifierListOwner.hasModifier(KtTokens.CONST_KEYWORD)) modifiers.add("const")
        if (ktModifierListOwner.hasModifier(KtTokens.LATEINIT_KEYWORD)) modifiers.add("lateinit")

        if (ktModifierListOwner.hasModifier(KtTokens.DATA_KEYWORD)) modifiers.add("data")
        if (ktModifierListOwner.hasModifier(KtTokens.INNER_KEYWORD)) modifiers.add("inner")
        if (ktModifierListOwner.hasModifier(KtTokens.COMPANION_KEYWORD)) modifiers.add("companion")

        when (ktModifierListOwner) {
            is KtProperty -> {
                if (ktModifierListOwner.isVar) {
                    modifiers.add("var")
                } else {
                    modifiers.add("val")
                }
            }

            is KtVariableDeclaration -> {
                if (ktModifierListOwner.isVar) {
                    modifiers.add("var")
                } else {
                    modifiers.add("val")
                }
            }

            is KtParameter -> {
                val valOrVarKeyword = ktModifierListOwner.valOrVarKeyword
                if (valOrVarKeyword != null) {
                    modifiers.add(valOrVarKeyword.text)
                }
            }
        }

        return modifiers
    }

    private fun analyzeSupertypes(ktClass: KtClassOrObject, classStructure: ClassStructure) {
        ktClass.superTypeListEntries.forEach { superTypeEntry ->
            val shortTypeName = superTypeEntry.typeReference?.text ?: "TypeUnknown"
            val resolvedSuperType = resolveType(shortTypeName)
            classStructure.supertypes.add(resolvedSuperType)
        }
    }

    private fun analyzePsiElement(
        element: PsiElement,
        classStructure: ClassStructure,
    ) {
        when (element) {
            is KtConstructor<*> -> {
                classStructure.constructors.add(analyzeConstructor(element))
            }

            is KtProperty -> {
                classStructure.fields.add(analyzeProperty(element))
            }

            is KtNamedFunction -> {
                classStructure.methods.add(analyzeFunction(element))
            }

            is KtEnumEntry -> {
                element.name?.also { entryName ->
                    classStructure.enumEntries.add(EnumEntryName(entryName))
                }
            }

            is KtClassBody -> {
                element.children.forEach { child ->
                    analyzePsiElement(child, classStructure)
                }
            }

            is KtClassOrObject -> {
                getClassStructure(element)?.also { includeClassStructure ->
                    classStructure.classes.add(includeClassStructure)
                }
            }
        }
    }

    private fun analyzeConstructor(
        constructor: KtConstructor<*>,
    ): ConstructorStructure {
        val parameters = constructor.valueParameters.map { parameter ->
            val type = parameter.typeReference?.text ?: "TypeUnknown"
            val resolvedType = resolveType(type)
            ParameterInfo(parameter.name.orEmpty(), resolvedType, getModifiers(parameter))
        }
        val modifierList = getModifiers(constructor)
        return ConstructorStructure(parameters, modifierList)
    }

    private fun analyzeProperty(property: KtProperty): FieldStructure {
        val type = property.typeReference?.text ?: kotlinPropertyAnalyzer.resolveInferredType(property)
        val resolvedType = resolveType(type)
        val modifierList = getModifiers(property)
        return FieldStructure(property.name ?: "", resolvedType, modifierList)
    }

    private fun analyzeFunction(function: KtFunction): MethodStructure {
        val returnType = function.typeReference?.text ?: "TypeUnknown"
        val resolvedReturnType = resolveType(returnType)
        val parameters = function.valueParameters.map { parameter ->
            val type = parameter.typeReference?.text ?: "TypeUnknown"
            val resolvedType = resolveType(type)
            ParameterInfo(parameter.name.orEmpty(), resolvedType, getModifiers(parameter))
        }
        val modifierList = getModifiers(function)
        return MethodStructure(function.name.orEmpty(), resolvedReturnType, parameters, modifierList)
    }

    private fun resolveType(shortType: String): ClassName {
        val resolvedType = filePackageTypes[shortType] ?: shortType

        putTypeToAnalyzeQueue(resolvedType)

        return ClassName(resolvedType)
    }

    private fun getTypesInPackage(packagePath: String?): List<String> {
        if (packagePath == null) return emptyList()

        val packageDirectories = PackageIndex.getInstance(ktFile.project)
            .getDirsByPackageName(packagePath, false)
            .toList()

        val types = mutableListOf<String>()
        for (directory in packageDirectories) {
            directory.children
                .mapNotNull { virtualFile ->
                    psiManager.findFile(virtualFile)
                }
                .forEach { psiFile ->
                    types.addAll(getDependencyFqNames(psiFile))
                }
        }

        return types
    }

    private fun putTypeToAnalyzeQueue(fqName: String) {
        val shortName = fqName.substringAfterLast('.')

        val classes = PsiShortNamesCache.getInstance(ktFile.project)
            .getClassesByName(shortName, GlobalSearchScope.projectScope(ktFile.project))

        val foundKtFiles = classes
            .mapNotNull { psiClass ->
                when {
                    psiClass is KtLightClass -> {
                        val classQualifiedName = psiClass.kotlinOrigin?.fqName?.asString()
                        if (classQualifiedName == fqName) {
                            psiClass.kotlinOrigin?.containingKtFile
                        } else {
                            null
                        }
                    }

                    else -> {
                        if (psiClass.qualifiedName == fqName) {
                            psiClass.containingFile
                        } else {
                            null
                        }
                    }
                }
            }

        foundKtFiles.forEach { psiFile ->
            psiFileQueue.put(psiFile, ktFile.name)
        }
    }
}
