package net.kigawa.kivra.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeAccountSettingsTest {
    @Test
    fun `unset and an empty array both provide no accounts`() {
        assertEquals(emptyList(), ClaudeAccountSettings.fromEnv(emptyMap()).available)
        assertEquals(emptyList(), settings("[]").available)
    }

    @Test
    fun `valid options keep their order and accept IDs of one or 63 characters`() {
        val longId = "9" + "a-".repeat(31)
        val accounts = settings("""[{"id":"a","label":"最初"},{"id":"$longId","label":"Second"}]""")
        assertEquals(listOf(ClaudeAccountOption("a", "最初"), ClaudeAccountOption(longId, "Second")), accounts.available)
    }

    @Test
    fun `invalid configuration identifies the environment variable in the startup error`() {
        val invalidValues = listOf(
            "",
            "[",
            "{}",
            """[{"id":1,"label":"First"}]""",
            """[{"id":"demo","label":false}]""",
            """[{"id":"demo"}]""",
            """[{"id":"demo","label":"  "}]""",
            """[{"id":"Demo","label":"First"}]""",
            """[{"id":"-demo","label":"First"}]""",
            """[{"id":"demo/a","label":"First"}]""",
            """[{"id":"${"a".repeat(64)}","label":"First"}]""",
            """[{"id":"demo","label":"First"},{"id":"demo","label":"Second"}]""",
        )
        for (value in invalidValues) {
            val error = assertFailsWith<IllegalStateException>(value) { settings(value) }
            assertTrue(error.message.orEmpty().startsWith(ClaudeAccountSettings.ENV_ACCOUNTS))
        }
    }

    private fun settings(value: String) = ClaudeAccountSettings.fromEnv(mapOf(ClaudeAccountSettings.ENV_ACCOUNTS to value))
}
