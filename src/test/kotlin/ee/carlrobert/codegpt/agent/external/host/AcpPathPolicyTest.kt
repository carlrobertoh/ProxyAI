package ee.carlrobert.codegpt.agent.external.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcpPathPolicyTest {

    private val policy = AcpPathPolicy()

    @Test
    fun `resolveWithinCwd keeps relative paths inside cwd`() {
        val cwd = Files.createTempDirectory("acp-host-policy")

        val resolved = policy.resolveWithinCwd("src/Main.kt", cwd)

        assertEquals(cwd.resolve("src/Main.kt").toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `resolveWithinCwd allows absolute path inside cwd`() {
        val cwd = Files.createTempDirectory("acp-host-policy")
        val inside = cwd.resolve("nested/file.txt")

        val resolved = policy.resolveWithinCwd(inside.toString(), cwd)

        assertEquals(inside.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `resolveWithinCwd rejects path traversal`() {
        val cwd = Files.createTempDirectory("acp-host-policy")

        assertFailsWith<AcpHostPathBoundaryException> {
            policy.resolveWithinCwd("../secret.txt", cwd)
        }
    }
}
