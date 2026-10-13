package net.kigawa.kivra.exkes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KivraNamingTest {
    private val runId = "01HZZZZZZZZZZZZZZZZZZZZZZZ"

    @Test
    fun `the run id round-trips through the workspace name`() {
        assertEquals(runId, KivraNaming.runIdOfWorkspaceName(KivraNaming.workspaceName(runId)))
    }

    @Test
    fun `names that are not exactly kivra-run plus a ULID are not Kivra workspaces`() {
        assertNull(KivraNaming.runIdOfWorkspaceName("someone-elses-sandbox"))
        assertNull(KivraNaming.runIdOfWorkspaceName("kivra-run-$runId-manual"))
        assertNull(KivraNaming.runIdOfWorkspaceName("kivra-run-01hzzzzzzzzzzzzzzzzzzzzzzz"))
        assertNull(KivraNaming.runIdOfWorkspaceName("kivra-run-"))
        assertNull(KivraNaming.runIdOfWorkspaceName("xkivra-run-$runId"))
    }
}
