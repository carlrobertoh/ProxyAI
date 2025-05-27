package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.IncludedFilesSettings
import ee.carlrobert.codegpt.util.file.FileUtil
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.util.stream.Collectors

object CompletionRequestUtil {

    private val psiStructureSerializer = ClassStructureSerializer

    private val PSI_STRUCTURE_TITLE = """
        The following is the structure of the file dependencies that were attached above. 
        The structure contains a description of classes with their methods, method arguments, and return types.  
        If the type is specified as TypeUnknown, then the analyzer could not identify the type, 
        try to take it out of context, if necessary for the response.
    """.trimIndent()

    @JvmStatic
    fun formatCode(code: String, filePath: String? = null): String {
        val header = filePath?.let { "${FileUtil.getFileExtension(it)}:$it" } ?: ""
        return buildString {
            append("```${header}\n")
            append("$code\n")
            append("```\n")
        }
    }

    @JvmStatic
    fun getPromptWithContext(
        referencedFiles: List<ReferencedFile>,
        userPrompt: String?,
        psiStructure: Set<ClassStructure>?
    ): String {
        val includedFilesSettings = service<IncludedFilesSettings>().state
        val repeatableContext = includedFilesSettings.repeatableContext
        val fileContext = referencedFiles.stream()
            .map { item: ReferencedFile ->
                formatCode(item.fileContent(), item.filePath())
            }
            .collect(Collectors.joining("\n\n"))

        val structureContext = psiStructure
            ?.map { structure: ClassStructure ->
                formatCode(
                    psiStructureSerializer.serialize(structure),
                    structure.virtualFile.path
                )
                repeatableContext
                    .replace("{FILE_PATH}", structure.virtualFile.path)
                    .replace(
                        "{FILE_CONTENT}",
                        formatCode(
                            psiStructureSerializer.serialize(structure),
                            structure.virtualFile.path
                        )
                    )
            }
            ?.ifNotEmpty {
                joinToString(
                    prefix = "\n\n" + PSI_STRUCTURE_TITLE + "\n\n",
                    separator = "\n\n"
                ) { it }
            }

        return includedFilesSettings.promptTemplate
            .replace("{REPEATABLE_CONTEXT}", fileContext + structureContext.orEmpty())
            .replace("{QUESTION}", userPrompt!!)
    }
}