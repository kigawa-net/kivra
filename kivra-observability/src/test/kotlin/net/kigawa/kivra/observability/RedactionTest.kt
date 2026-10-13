package net.kigawa.kivra.observability

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test
    fun `ordinary identifiers and enums are safe`() {
        listOf(
            "01HZ9Z3KQV8XW1ABCDEFGHJKMN",
            "4bf92f3577b34da6a3ce929d0e0e4736", // a W3C trace-id is not a secret
            "completed",
            "openai/gpt-x",
            "pull_request",
        ).forEach { assertTrue(Redaction.isSafeValue(it), it) }
    }

    @Test
    fun `common secret shapes are rejected`() {
        listOf(
            "Authorization: Bearer abc123",
            "sk-proj-AbCdEfGhIjKlMnOp",
            "ghp_0123456789abcdefghij",
            "github_pat_11ABCDEFG0123456789",
            "xoxb-1234567890-abcdefghij",
            "AKIAABCDEFGHIJKLMNOP",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijk",
            "-----BEGIN RSA PRIVATE KEY-----",
            "token=abc",
            "api_key: xyz",
        ).forEach { assertFalse(Redaction.isSafeValue(it), it) }
    }
}
