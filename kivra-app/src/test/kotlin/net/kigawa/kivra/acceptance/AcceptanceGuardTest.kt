package net.kigawa.kivra.acceptance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceGuardTest {
    @Test
    fun `test database settings are allowed, production credential names are not`() {
        assertEquals(emptyList(), AcceptanceGuard.leakedNames(mapOf("KIVRA_TEST_DB" to "mariadb", "PATH" to "/bin")))
        assertEquals(
            listOf("EXKES_TOKEN", "GITHUB_PAT_TOKEN", "KIVRA_DB_PASSWORD"),
            AcceptanceGuard.leakedNames(mapOf("GITHUB_PAT_TOKEN" to "x", "KIVRA_DB_PASSWORD" to "y", "EXKES_TOKEN" to "z", "HOME" to "/h")),
        )
    }

    @Test
    fun `the guard fails without printing any value`() {
        val secret = "super-secret-value-123"
        val e = assertFailsWith<IllegalStateException> { AcceptanceGuard.require(mapOf("GITHUB_PAT_TOKEN" to secret)) }
        assertTrue(e.message.orEmpty().contains("GITHUB_PAT_TOKEN"))
        assertFalse(e.message.orEmpty().contains(secret))
    }

    @Test
    fun `a barrier with no arrival times out instead of hanging`() {
        val e = assertFailsWith<IllegalStateException> { NamedBarriers(timeoutSeconds = 0).awaitReached("never-reached") }
        assertTrue(e.message.orEmpty().contains("never-reached"))
    }
}
