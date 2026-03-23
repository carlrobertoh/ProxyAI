package ee.carlrobert.codegpt.agent.external.host

import com.agentclientprotocol.model.*
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

class AcpFileHost(
    private val pathPolicy: AcpPathPolicy = AcpPathPolicy(),
    private val openDocumentReader: AcpOpenDocumentReader = IntelliJOpenDocumentReader(),
    private val writer: AcpTextFileWriter = IntelliJTextFileWriter()
) {

    fun clientCapabilities(includeTerminal: Boolean = false): ClientCapabilities {
        return ClientCapabilities(
            fs = FileSystemCapability(
                readTextFile = true,
                writeTextFile = true
            ),
            terminal = includeTerminal
        )
    }

    fun readTextFile(
        session: AcpHostSessionContext,
        request: ReadTextFileRequest
    ): ReadTextFileResponse {
        require(request.sessionId.value == session.sessionId) {
            "Read request session does not match host session"
        }
        val resolvedPath = pathPolicy.resolveWithinCwd(request.path, session.cwd)
        val content = readContent(resolvedPath)
        val trimmed = applyLineWindow(
            content = content.content,
            line = request.line,
            limit = request.limit
        )
        return ReadTextFileResponse(trimmed)
    }

    fun writeTextFile(
        session: AcpHostSessionContext,
        request: WriteTextFileRequest
    ): WriteTextFileResponse {
        require(request.sessionId.value == session.sessionId) {
            "Write request session does not match host session"
        }
        val resolvedPath = pathPolicy.resolveWithinCwd(request.path, session.cwd)
        writer.write(resolvedPath, request.content)
        return WriteTextFileResponse()
    }

    private fun readContent(path: Path): AcpTextFileReadResult {
        openDocumentReader.read(path)?.let { editorText ->
            return AcpTextFileReadResult(editorText, fromEditor = true)
        }
        return AcpTextFileReadResult(Files.readString(path), fromEditor = false)
    }

    private fun applyLineWindow(
        content: String,
        line: UInt?,
        limit: UInt?
    ): String {
        val startLine = line?.toInt()
        val maxLines = limit?.toInt()
        if (startLine == null || maxLines == null || startLine <= 0 || maxLines <= 0) {
            return content
        }

        return content.lineSequence()
            .drop(startLine - 1)
            .take(maxLines)
            .joinToString("\n")
    }
}

private class IntelliJOpenDocumentReader : AcpOpenDocumentReader {
    override fun read(path: Path): String? {
        return runReadAction<String?> {
            val virtualFile =
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
                    ?: return@runReadAction null
            FileDocumentManager.getInstance().getDocument(virtualFile)?.text
        }
    }
}

private class IntelliJTextFileWriter : AcpTextFileWriter {
    override fun write(path: Path, content: String) {
        if (path.parent != null && path.parent.notExists()) {
            path.parent.createDirectories()
        }
        Files.writeString(path, content, StandardCharsets.UTF_8)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
        if (virtualFile != null) {
            VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
        } else {
            path.parent?.toFile()?.let { parent ->
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)
                    ?.let { parentVf ->
                        VfsUtil.markDirtyAndRefresh(false, false, true, parentVf)
                    }
            }
        }
    }
}
