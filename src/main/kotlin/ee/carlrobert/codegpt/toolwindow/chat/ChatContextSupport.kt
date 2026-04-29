package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import java.util.*

object ChatContextSupport {

    @JvmStatic
    fun buildMessage(project: Project, text: String, appliedTags: List<TagDetails>): Message {
        val messageBuilder = MessageBuilder(project, text).withTags(appliedTags)

        val referencedFiles = getReferencedFiles(project, appliedTags)
        if (referencedFiles.isNotEmpty()) {
            messageBuilder.withReferencedFiles(referencedFiles)
        }

        val conversationHistoryIds = getConversationHistoryIds(appliedTags)
        if (conversationHistoryIds.isNotEmpty()) {
            messageBuilder.withConversationHistoryIds(conversationHistoryIds)
        }

        CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH.get(project)?.let(messageBuilder::withImage)

        return messageBuilder.build()
    }

    @JvmStatic
    fun getReferencedFiles(project: Project, tags: List<TagDetails>): List<ReferencedFile> {
        return getReferencedVirtualFiles(project, tags).map(ReferencedFile::from)
    }

    @JvmStatic
    fun getReferencedVirtualFiles(project: Project, tags: List<TagDetails>): List<VirtualFile> {
        return collectVisibleFiles(project, tags.mapNotNull(::getVirtualFile))
    }

    @JvmStatic
    fun collectVisibleFiles(project: Project, inputFiles: List<VirtualFile>): List<VirtualFile> {
        val settingsService = project.getService(ProxyAISettingsService::class.java)
        return collectVisibleFiles(inputFiles, settingsService)
    }

    @JvmStatic
    fun getHistory(tags: List<TagDetails>): List<Conversation> {
        return tags.mapNotNull { tag ->
            (tag as? HistoryTagDetails)?.conversationId?.let(ConversationTagProcessor.Companion::getConversation)
        }.distinct()
    }

    private fun getConversationHistoryIds(tags: List<TagDetails>): List<UUID> {
        return tags.mapNotNull { tag ->
            (tag as? HistoryTagDetails)?.conversationId
        }
    }

    private fun collectVisibleFiles(
        inputFiles: List<VirtualFile>,
        settingsService: ProxyAISettingsService
    ): List<VirtualFile> {
        val visibleFiles = LinkedHashSet<VirtualFile>()
        inputFiles.forEach { appendVisibleFiles(it, settingsService, visibleFiles) }
        return visibleFiles.toList()
    }

    private fun appendVisibleFiles(
        file: VirtualFile,
        settingsService: ProxyAISettingsService,
        output: LinkedHashSet<VirtualFile>
    ) {
        if (!file.isValid || !settingsService.isVirtualFileVisible(file)) {
            return
        }
        if (!file.isDirectory) {
            output.add(file)
            return
        }

        file.children.forEach { child ->
            appendVisibleFiles(child, settingsService, output)
        }
    }

    private fun getVirtualFile(tag: TagDetails): VirtualFile? {
        if (!tag.selected) {
            return null
        }

        return when (tag) {
            is FileTagDetails -> tag.virtualFile
            is EditorTagDetails -> tag.virtualFile
            is FolderTagDetails -> tag.folder
            else -> null
        }
    }
}
