package ee.carlrobert.codegpt.psistructure

import ee.carlrobert.codegpt.psistructure.models.*
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty

object ClassStructureSerializer {

    private const val INDENTATION = "    "

    /**
     * The original class:
     * package org.example.package1
     *
     * import org.example.package2.ClassInPackage2
     *
     * class ClassInPackage1 {
     *
     *     fun someMethod1(classInPackage2: ClassInPackage2): String = classInPackage2.someMethod2()
     * }
     *
     * Serialized representation of the structure:
     * package org.example.package1
     *
     * class ClassInPackage1 {
     *       fun someMethod1(classInPackage2: org.example.package2.ClassInPackage2): String
     * }
     */
    fun serialize(classStructure: ClassStructure): String =
        serializeInternal(classStructure)

    private fun serializeInternal(classStructure: ClassStructure, level: Int = 1): String {
        val currentBodyIndention = INDENTATION.repeat(level)
        val currentClassIndention = INDENTATION.repeat(level - 1)

        val modifiers = classStructure.modifierList.ifNotEmpty { joinToString(" ", postfix = " ") }.orEmpty()
        val classType = classStructure.classType.name.lowercase()
        val className = classStructure.simpleName.value
        val supertypes = classStructure.supertypes.joinToString(", ") { it.value }
        val packageName = classStructure.packageName

        val primaryConstructor = classStructure.constructors.firstOrNull()?.let { serializePrimaryConstructor(it) }
        val secondaryConstructors = classStructure.constructors
            .drop(1)
            .ifNotEmpty {
                joinToString("\n$currentBodyIndention", prefix = currentBodyIndention) {
                    serializeSecondaryConstructor(it)
                }
            }
            .orEmpty()

        val fields = classStructure.fields
            .ifNotEmpty {
                joinToString(
                    "\n$currentBodyIndention",
                    prefix = currentBodyIndention
                ) { serializeField(it) }
            }
            .orEmpty()

        val methods = classStructure.methods
            .ifNotEmpty {
                joinToString(
                    "\n$currentBodyIndention",
                    prefix = currentBodyIndention
                ) { serializeMethod(it) }
            }
            .orEmpty()

        val enumEntries = classStructure.enumEntries
            .ifNotEmpty {
                joinToString(",\n$currentBodyIndention", prefix = currentBodyIndention) { it.value }
            }
            .orEmpty()

        val innerClasses = classStructure.classes
            .ifNotEmpty {
                joinToString("\n\n") {
                    serializeInternal(it, level + 1)
                }
            }
            .orEmpty()

        return buildString {
            if (level == 1) {
                append("package ${packageName.ifEmpty { "Unknown" }}\n\n")
            }

            if (classStructure.classType == ClassType.COMPANION_OBJECT) {
                append("$currentClassIndention${modifiers} object")
            } else {
                append("$currentClassIndention$modifiers$classType $className")
            }

            if (primaryConstructor != null) {
                append("($primaryConstructor)")
            }

            if (supertypes.isNotEmpty()) {
                append(" : $supertypes")
            }
            append(" {\n")

            if (classStructure.classType == ClassType.ENUM) {
                append("$enumEntries\n")
            }

            if (secondaryConstructors.isNotEmpty()) {
                append("$currentBodyIndention$secondaryConstructors\n")
            }

            if (fields.isNotEmpty()) {
                append("$fields\n")
            }

            if (methods.isNotEmpty()) {
                append("$methods\n")
            }

            if (innerClasses.isNotEmpty()) {
                append("$innerClasses\n")
            }

            append("$currentClassIndention}")
        }
    }

    private fun serializePrimaryConstructor(constructor: ConstructorStructure): String {
        val parameters = constructor.parameters.joinToString(", ") { serializeParameter(it) }
        return parameters
    }

    private fun serializeSecondaryConstructor(constructor: ConstructorStructure): String {
        val parameters = constructor.parameters.joinToString(", ") { serializeParameter(it) }
        val modifiers = constructor.modifiers.joinToString(" ")
        return "$modifiers constructor($parameters)"
    }

    private fun serializeField(field: FieldStructure): String {
        val modifiers = field.modifiers.joinToString(" ")
        val name = field.name
        val type = field.type.value
        return "$modifiers $name: $type"
    }

    private fun serializeMethod(method: MethodStructure): String {
        val modifiers = method.modifiers.ifNotEmpty { joinToString(" ", postfix = " ") }.orEmpty()
        val name = method.name
        val returnType = method.returnType.value
        val parameters = method.parameters.joinToString(", ") { serializeParameter(it) }
        return "${modifiers}fun $name($parameters): $returnType"
    }

    private fun serializeParameter(parameter: ParameterInfo): String {
        val name = parameter.name
        val type = parameter.type.value
        val modifiers = parameter.modifiers.joinToString(" ")
        return "$modifiers $name: $type"
    }
}
