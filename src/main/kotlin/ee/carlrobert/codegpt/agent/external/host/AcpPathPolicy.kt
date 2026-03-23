package ee.carlrobert.codegpt.agent.external.host

import java.net.URI
import java.nio.file.Path

class AcpHostPathBoundaryException(message: String) : IllegalArgumentException(message)

class AcpPathPolicy {

    fun resolveWithinCwd(rawPath: String, cwd: Path): Path {
        val normalizedCwd = cwd.toAbsolutePath().normalize()
        val checkedCwd = canonicalizeForBoundaryCheck(normalizedCwd)
        val requestedPath = parsePath(rawPath)
        val candidate = if (requestedPath.isAbsolute) {
            requestedPath
        } else {
            normalizedCwd.resolve(requestedPath)
        }.toAbsolutePath().normalize()
        val checkedCandidate = canonicalizeForBoundaryCheck(candidate)

        if (!checkedCandidate.startsWith(checkedCwd)) {
            throw AcpHostPathBoundaryException(
                "Path escapes the session cwd: $rawPath"
            )
        }

        return candidate
    }

    private fun parsePath(rawPath: String): Path {
        return if (rawPath.startsWith("file://")) {
            Path.of(URI.create(rawPath))
        } else {
            Path.of(rawPath)
        }
    }

    private fun canonicalizeForBoundaryCheck(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val deferredSegments = mutableListOf<String>()
        var current: Path? = absolute

        while (current != null) {
            val realPath = runCatching { current.toRealPath() }.getOrNull()
            if (realPath != null) {
                return deferredSegments.foldRight(realPath) { segment, acc ->
                    acc.resolve(segment)
                }.normalize()
            }

            current.fileName?.toString()?.let(deferredSegments::add)
            current = current.parent
        }

        return absolute
    }
}
