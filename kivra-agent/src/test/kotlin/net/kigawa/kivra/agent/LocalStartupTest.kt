package net.kigawa.kivra.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalStartupTest {
    @Test
    fun secondLockOnTheSameWorkDirIsRefused() {
        val dir = Files.createTempDirectory("kivra-agent-lock")
        WorkDirLock.acquire(dir).use {
            assertFailsWith<AlreadyRunningException> { WorkDirLock.acquire(dir) }
        }
        // Released: a new lock can be taken again.
        WorkDirLock.acquire(dir).close()
    }

    @Test
    fun secretFileReadableByOthersIsRefusedAndTheMessageHasNoPath() {
        val file = Files.createTempFile("kivra-agent-sec", ".txt")
        file.writeSecret("value", mode = "rw-r--r--")
        val e = assertFailsWith<ConfigException> { StartupChecks.checkSecretFile(file) }
        assertTrue(e.message!!.startsWith(AgentConfig.ENV_CLIENT_SECRET_FILE))
        assertFalse(e.message!!.contains(file.toString()))
    }

    @Test
    fun ownerOnlySecretFileIsAccepted() {
        val file = Files.createTempFile("kivra-agent-sec", ".txt").writeSecret("value", mode = "rw-------")
        StartupChecks.checkSecretFile(file)
    }

    @Test
    fun missingWorkDirIsRefused() {
        val e = assertFailsWith<ConfigException> { StartupChecks.checkWorkDir(Files.createTempDirectory("x").resolve("absent")) }
        assertEquals(AgentConfig.ENV_WORK_DIR, e.message!!.substringBefore(" "))
    }
}
