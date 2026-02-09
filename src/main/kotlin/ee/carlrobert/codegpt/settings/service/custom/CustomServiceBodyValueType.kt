package ee.carlrobert.codegpt.settings.service.custom

enum class CustomServiceBodyValueType(
    private val label: String,
    val templateValue: String,
) {
    STRING("String", ""),
    PLACEHOLDER("Placeholder", $$"$OPENAI_MESSAGES"),
    NUMBER("Number", "0"),
    BOOLEAN("Boolean", "false"),
    NULL("Null", ""),
    OBJECT("Object", "{\n  \"key\": \"value\"\n}"),
    ARRAY("Array", "[\n  \"value\"\n]");

    override fun toString(): String = label

    companion object {
        fun infer(value: Any?): CustomServiceBodyValueType = when (value) {
            null -> NULL
            is String -> if (CustomServicePlaceholders.containsCode(value.trim())) PLACEHOLDER else STRING
            is Map<*, *> -> OBJECT
            is List<*> -> ARRAY
            is Boolean -> BOOLEAN
            is Number -> NUMBER
            else -> STRING
        }
    }
}
