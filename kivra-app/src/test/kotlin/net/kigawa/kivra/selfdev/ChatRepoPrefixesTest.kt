package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatRepoPrefixesTest {
    @Test
    fun `unset or blank falls back to the self-development prefixes`() {
        assertNull(chatRepoPrefixes(emptyMap()))
        assertNull(chatRepoPrefixes(mapOf(ENV_CHAT_REPO_PREFIXES to "  ")))
        assertNull(chatRepoPrefixes(mapOf(ENV_CHAT_REPO_PREFIXES to " , ,")))
    }

    @Test
    fun `a list is split and trimmed`() {
        assertEquals(
            listOf("README.md", "docs/", "kivra-app/src/"),
            chatRepoPrefixes(mapOf(ENV_CHAT_REPO_PREFIXES to " README.md, docs/ ,kivra-app/src/ ")),
        )
    }
}
