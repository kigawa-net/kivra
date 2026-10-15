package net.kigawa.kivra.developer

import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.patch.PatchSetParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeDeveloperTest {

    @Test
    fun `returns the configured result and records each call`() = runBlocking {
        val patch = (PatchSetParser.parse(VALID_PATCH_JSON) as net.kigawa.kivra.core.patch.ParseResult.Parsed).patch
        val fake = FakeDeveloper.returning(DeveloperResult.Proposed(patch, DeveloperUsage(1, net.kigawa.kivra.modelgateway.Usage.ZERO, emptyList())))

        val first = fake.propose(taskRequest(runId = "r1"))
        val second = fake.propose(taskRequest(runId = "r2"))

        assertIs<DeveloperResult.Proposed>(first)
        assertIs<DeveloperResult.Proposed>(second)
        assertEquals(2, fake.callCount)
        assertEquals(listOf("r1", "r2"), fake.requests.map { it.runId })
    }

    @Test
    fun `can return refusal and unknown`() = runBlocking {
        val fake = FakeDeveloper.returning(DeveloperResult.Refused(DeveloperRefusal.BUDGET))
        assertEquals(DeveloperResult.Refused(DeveloperRefusal.BUDGET), fake.propose(taskRequest()))

        val unknown = FakeDeveloper { DeveloperResult.Unknown(DeveloperUnknown.TIMEOUT) }
        assertEquals(DeveloperResult.Unknown(DeveloperUnknown.TIMEOUT), unknown.propose(taskRequest()))
        assertEquals(1, unknown.callCount)
        assertTrue(unknown.requests.single().runId.isNotEmpty())
    }
}
