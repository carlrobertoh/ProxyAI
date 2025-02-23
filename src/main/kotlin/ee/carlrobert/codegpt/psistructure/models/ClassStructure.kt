package ee.carlrobert.codegpt.psistructure.models

data class ClassStructure(
    val name: ClassName,
    val simpleName: ClassName,
    val classType: ClassType,
    val modifierList: List<String>,
    val packageName: String,
    val repositoryName: String,
    val lang: ClassLanguage = ClassLanguage.KOTLIN,
    val constructors: MutableList<ConstructorStructure> = mutableListOf(),
    val fields: MutableList<FieldStructure> = mutableListOf(),
    val methods: MutableList<MethodStructure> = mutableListOf(),
    val supertypes: MutableList<ClassName> = mutableListOf(),
    val enumEntries: MutableList<EnumEntryName> = mutableListOf(),
    val classes: MutableList<ClassStructure> = mutableListOf()
)
