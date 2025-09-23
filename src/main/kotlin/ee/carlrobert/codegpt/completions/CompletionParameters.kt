package ee.carlrobert.codegpt.completions

import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.util.file.FileUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

interface CompletionParameters

class ChatCompletionParameters private constructor(
    val conversation: Conversation,
    val conversationType: ConversationType,
    val message: Message,
    var sessionId: UUID?,
    var retry: Boolean,
    var imageDetails: ImageDetails?,
    var history: List<Conversation>?,
    var referencedFiles: List<ReferencedFile>?,
    var personaDetails: PersonaDetails?,
    var psiStructure: Set<ClassStructure>?,
    var chatMode: ChatMode = ChatMode.ASK,
    var featureType: FeatureType = FeatureType.CHAT
) : CompletionParameters {

    fun toBuilder(): Builder {
        return Builder(conversation, message).apply {
            sessionId(this@ChatCompletionParameters.sessionId)
            conversationType(this@ChatCompletionParameters.conversationType)
            retry(this@ChatCompletionParameters.retry)
            imageDetails(this@ChatCompletionParameters.imageDetails)
            referencedFiles(this@ChatCompletionParameters.referencedFiles)
            personaDetails(this@ChatCompletionParameters.personaDetails)
            psiStructure(this@ChatCompletionParameters.psiStructure)
            chatMode(this@ChatCompletionParameters.chatMode)
            featureType(this@ChatCompletionParameters.featureType)
        }
    }

    class Builder(private val conversation: Conversation, private val message: Message) {
        private var sessionId: UUID? = null
        private var conversationType: ConversationType = ConversationType.DEFAULT
        private var retry: Boolean = false
        private var imageDetails: ImageDetails? = null
        private var history: List<Conversation>? = null
        private var referencedFiles: List<ReferencedFile>? = null
        private var personaDetails: PersonaDetails? = null
        private var psiStructure: Set<ClassStructure>? = null
        private var gitDiff: String = ""
        private var chatMode: ChatMode = ChatMode.ASK
        private var featureType: FeatureType = FeatureType.CHAT

        fun sessionId(sessionId: UUID?) = apply { this.sessionId = sessionId }
        fun conversationType(conversationType: ConversationType) =
            apply { this.conversationType = conversationType }

        fun retry(retry: Boolean) = apply { this.retry = retry }
        fun imageDetails(imageDetails: ImageDetails?) = apply { this.imageDetails = imageDetails }
        fun imageDetailsFromPath(path: String?) = apply {
            if (!path.isNullOrEmpty()) {
                this.imageDetails = ImageDetails(
                    FileUtil.getImageMediaType(path),
                    Files.readAllBytes(Path.of(path))
                )
            }
        }

        fun gitDiff(gitDiff: String) = apply { this.gitDiff = gitDiff }

        fun history(history: List<Conversation>?) = apply { this.history = history }

        fun referencedFiles(referencedFiles: List<ReferencedFile>?) =
            apply { this.referencedFiles = referencedFiles }

        fun personaDetails(personaDetails: PersonaDetails?) = apply { this.personaDetails = personaDetails }

        fun psiStructure(psiStructure: Set<ClassStructure>?) = apply { this.psiStructure = psiStructure }

        fun chatMode(chatMode: ChatMode) = apply { this.chatMode = chatMode }

        fun featureType(featureType: FeatureType) = apply { this.featureType = featureType }

        fun build(): ChatCompletionParameters {
            return ChatCompletionParameters(
                conversation,
                conversationType,
                message,
                sessionId,
                retry,
                imageDetails,
                history,
                referencedFiles,
                personaDetails,
                psiStructure,
                chatMode,
                featureType
            )
        }
    }

    companion object {
        @JvmStatic
        fun builder(conversation: Conversation, message: Message) = Builder(conversation, message)
    }
}

data class CommitMessageCompletionParameters(
    val gitDiff: String,
    val systemPrompt: String,
    val featureType: FeatureType = FeatureType.COMMIT_MESSAGE
) : CompletionParameters

data class LookupCompletionParameters(
    val prompt: String,
    val featureType: FeatureType = FeatureType.LOOKUP
) : CompletionParameters

data class AutoApplyParameters(
    val source: String, 
    val destination: VirtualFile,
    val chatMode: ChatMode = ChatMode.ASK,
    val featureType: FeatureType = FeatureType.AUTO_APPLY
)

data class InlineEditCompletionParameters(
    val prompt: String,
    val selectedText: String? = null,
    val filePath: String? = null,
    val fileExtension: String? = null,
    val projectBasePath: String? = null,
    val referencedFiles: List<ReferencedFile>? = null,
    val gitDiff: String? = null,
    val conversation: Conversation? = null,
    val conversationHistory: List<Conversation>? = null,
    val diagnosticsInfo: String? = null,
    val cursorOffset: Int? = null
) : CompletionParameters

data class ImageDetails(
    val mediaType: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageDetails) return false

        if (mediaType != other.mediaType) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mediaType.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
