package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilesGroupItem(
    private val project: Project,
    private val tagManager: TagManager
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.files.displayName")
    override val icon = AllIcons.FileTypes.Any_type

    override suspend fun updateLookupItems(searchText: String): List<LookupItem> {
        return getFileItems(searchText)
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return getFileItems(searchText)
    }

    private suspend fun getFileItems(searchText: String): List<LookupActionItem> {
        return withContext(Dispatchers.IO) {
            val fileEditorManager = project.service<FileEditorManager>()
            val projectFileIndex = project.service<ProjectFileIndex>()

            val (activeFiles, openFiles) = readAction {
                val selectedFiles = fileEditorManager.selectedFiles
                    .filter { isValidFile(it, searchText, projectFileIndex) }

                val openFiles = fileEditorManager.openFiles.toList()

                val otherFiles = openFiles
                    .filter { it !in selectedFiles && isValidFile(it, searchText, projectFileIndex) }

                Pair(selectedFiles, otherFiles)
            }

            val editorFilesCount = activeFiles.size + openFiles.size
            val needFromFileSystem = maxOf(0, 30 - editorFilesCount)

            val filesFromSystem = mutableListOf<VirtualFile>()
            if (needFromFileSystem > 0) {
                val editorFilesSet = (activeFiles + openFiles).toSet()

                readAction {
                    projectFileIndex.iterateContent(
                        /* processor = */ { file ->
                            if (filesFromSystem.size >= needFromFileSystem) {
                                false
                            } else {
                                if (!editorFilesSet.contains(file)) {
                                    filesFromSystem.add(file)
                                }
                                true
                            }
                        },
                        /* filter = */ { file ->
                            !file.isDirectory &&
                                    isValidProjectFile(file, projectFileIndex) &&
                                    !containsTag(file) &&
                                    (searchText.isEmpty() || file.name.contains(searchText, ignoreCase = true))
                        }
                    )
                }
            }

            val allFiles = activeFiles + openFiles + filesFromSystem

            val result = allFiles
                .map { FileActionItem(project, it) }
                .toMutableList<LookupActionItem>()

            if (searchText.isEmpty()) {
                result.add(IncludeOpenFilesActionItem())
            }

            result.toList()
        }
    }

    private fun isValidProjectFile(file: VirtualFile, projectFileIndex: ProjectFileIndex): Boolean {
        return file.isFile &&
                !isExcludedFile(file) &&
                projectFileIndex.isInContent(file) &&
                !projectFileIndex.isInLibraryClasses(file) &&
                !projectFileIndex.isInLibrarySource(file) &&
                !projectFileIndex.isInGeneratedSources(file)
    }

    private fun isExcludedFile(file: VirtualFile): Boolean {
        return file.extension?.lowercase() in EXCLUDED_EXTENSIONS
    }

    private fun isValidFile(file: VirtualFile, searchText: String, projectFileIndex: ProjectFileIndex): Boolean {
        return isValidProjectFile(file, projectFileIndex) &&
                !containsTag(file) &&
                (searchText.isEmpty() || file.name.contains(searchText, ignoreCase = true))
    }

    private fun containsTag(file: VirtualFile): Boolean {
        val tags = tagManager.getTags()
        return tags.contains(FileTagDetails(file))
    }

    private companion object {
        val COMPILED_EXTENSIONS = setOf(
            // Java/JVM languages
            "class", "jar", "war", "ear", "aar",

            // C/C++/Objective-C
            "o", "obj", "so", "dll", "dylib", "a", "lib", "framework",

            // .NET/C#
            "exe", "pdb", "mdb",

            // Python
            "pyc", "pyo", "pyd",

            // Rust
            "rlib",

            // Go (compiled binaries often have no extension, but some cases)
            "a",

            // Android
            "dex", "apk",

            // iOS
            "ipa",

            // Pascal/Delphi
            "dcu", "dcp",

            // PHP
            "phar",

            // Archives and packages
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar",

            // Other binary formats
            "bin", "dat", "dump"
        )

        val TEMPORARY_EXTENSIONS = setOf(
            // Backup files
            "bak", "backup", "tmp", "temp", "swp", "swo",

            // IDE/Editor temporary files
            "idea", "iml", "ipr", "iws",

            // OS temporary files
            "ds_store", "thumbs.db", "desktop.ini",

            // Build artifacts
            "log", "cache"
        )

        val EXCLUDED_EXTENSIONS = COMPILED_EXTENSIONS + TEMPORARY_EXTENSIONS
    }
}