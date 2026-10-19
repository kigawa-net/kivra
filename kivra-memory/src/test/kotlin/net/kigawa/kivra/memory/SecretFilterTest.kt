package net.kigawa.kivra.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretFilterTest {
    @Test
    fun `detects known secret formats`() {
        val samples = mapOf(
            SecretKind.PRIVATE_KEY to "-----BEGIN OPENSSH PRIVATE KEY-----\nabc",
            SecretKind.CLOUD_KEY to "key AKIAIOSFODNN7EXAMPLE here",
            SecretKind.VCS_TOKEN to "token ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789",
            SecretKind.CHAT_TOKEN to "xoxb-1234567890-abcdefghij",
            SecretKind.JWT to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
            SecretKind.BEARER to "Authorization: Bearer abcdef1234567890abcdef",
            SecretKind.URL_CREDENTIALS to "clone https://user:hunter2pass@github.com/x/y.git",
            SecretKind.ENV_ASSIGNMENT to "DB_PASSWORD=correcthorsebattery",
        )
        for ((kind, text) in samples) {
            assertTrue(SecretFilter.scan(text).any { it.kind == kind }, "expected $kind in: $text")
        }
    }

    @Test
    fun `detects a high entropy token but not ordinary prose, identifiers or hashes of words`() {
        assertTrue(SecretFilter.containsSecret("value Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1Gs0YaE9uT"))
        assertFalse(SecretFilter.containsSecret("Build kivra with JDK 21 because the wrapper does not run on JDK 25"))
        assertFalse(SecretFilter.containsSecret("see kivra-memory/src/main/kotlin/net/kigawa/kivra/memory/Model.kt for details"))
        assertFalse(SecretFilter.containsSecret("a_very_long_snake_case_identifier_for_a_function_name_here"))
        assertFalse(SecretFilter.containsSecret("https://github.com/kigawa-net/kivra/issues/37"))
    }

    /** ADR 0016 P0: the forms that were not covered before (Anthropic and OpenAI keys, client secrets, Basic auth, key ends, base64). */
    @Test
    fun `detects the forms added for the self-development memory`() {
        val samples = mapOf(
            SecretKind.API_KEY to "key sk-ant-api03-Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1Gs0YaE9uTxQwEr here",
            SecretKind.API_KEY to "key sk-proj-Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1Gs0YaE9uTxQwEr here",
            SecretKind.CLIENT_SECRET to "client_secret=Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1G",
            SecretKind.CLIENT_SECRET to "{\"client_secret\": \"Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1G\"}",
            SecretKind.BASIC_AUTH to "Authorization: Basic dXNlcjpwYXNzd29yZA==",
            SecretKind.PRIVATE_KEY to "-----END RSA PRIVATE KEY-----",
            SecretKind.HIGH_ENTROPY to "blob Zk3Qm9Xp2Lw8/Vt5Rb7Nc4Hd6Jf1Gs0YaE9uTxQwEr",
        )
        for ((kind, text) in samples) {
            assertTrue(SecretFilter.scan(text).any { it.kind == kind }, "expected $kind in: $text")
        }
    }

    /** The false-positive side: ordinary text near the new patterns must stay allowed. */
    @Test
    fun `does not flag ordinary text that only resembles the new forms`() {
        val prose = listOf(
            "sk-ant is a prefix; the key itself is never written here",
            "rotate the client secret every quarter, see the runbook",
            "the client_secret field is required; set it from the vault",
            "Authorization: Basic auth is not used on this endpoint",
            "a basic configuration for the server with two replicas",
            "the bearer of bad news asked for a review",
            "src/main/kotlin/net/kigawa/kivra/memory/SecretFilter.kt",
            "KivraSelfDevMemoryValidatorRepositoryTestFixtureNameLong",
            "プロンプトに秘密を書かない。sk-ant- の形は拒否する。",
        )
        for (text in prose) {
            assertFalse(SecretFilter.containsSecret(text), "unexpected finding in: $text")
        }
    }

    @Test
    fun `redact masks the value and findings never print it`() {
        val text = "use ghp_abcdefghijklmnopqrstuvwxyz0123456789 now"
        val red = SecretFilter.redact(text)
        assertEquals("use [REDACTED:VCS_TOKEN] now", red)
        assertFalse(SecretFilter.scan(text).joinToString().contains("abcdefghij"))
        assertFalse(SecretFilter.containsSecret(red))
    }

    @Test
    fun `redact merges overlapping findings`() {
        val red = SecretFilter.redact("API_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        assertFalse(red.contains("abcdefghij"))
    }
}
