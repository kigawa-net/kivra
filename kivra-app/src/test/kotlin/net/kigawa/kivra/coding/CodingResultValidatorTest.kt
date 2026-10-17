package net.kigawa.kivra.coding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import net.kigawa.kivra.core.patch.PatchPolicy

class CodingResultValidatorTest {
    private val validator = CodingResultValidator(PatchPolicy(), CodingLimits())

    private fun result(
        changes: List<CodingChange> = listOf(CodingChange("docs/a.md", ChangeKind.WRITE, "text\n")),
        summary: String = "summary",
        exitStatus: Int = 0,
        sessionId: String? = null,
    ) = CodingResult(summary, changes, exitStatus, sessionId)

    @Test
    fun `the digest is a stable SHA-256 hex and changes with every field`() {
        val base = result()
        assertEquals(CodingResultValidator.digestOf(base), CodingResultValidator.digestOf(result()))
        assertEquals(64, CodingResultValidator.digestOf(base).length)
        val variants = listOf(
            result(summary = "summary 2"),
            result(changes = listOf(CodingChange("docs/b.md", ChangeKind.WRITE, "text\n"))),
            result(changes = listOf(CodingChange("docs/a.md", ChangeKind.WRITE, "text!\n"))),
            result(changes = listOf(CodingChange("docs/a.md", ChangeKind.DELETE))),
            result(exitStatus = 1),
            result(sessionId = "s-1"),
        )
        for (variant in variants) assertNotEquals(CodingResultValidator.digestOf(base), CodingResultValidator.digestOf(variant))
    }

    @Test
    fun `the digest does not depend on how field boundaries fall (length prefixes)`() {
        val a = result(summary = "ab", changes = listOf(CodingChange("c", ChangeKind.WRITE, "d")))
        val b = result(summary = "a", changes = listOf(CodingChange("bc", ChangeKind.WRITE, "d")))
        assertNotEquals(CodingResultValidator.digestOf(a), CodingResultValidator.digestOf(b))
    }

    @Test
    fun `an acceptable result gives a PatchSet with the same changes and the digest of the result`() {
        val verdict = assertIs<CodingResultValidator.Verdict.Accepted>(validator.check(result()))
        assertEquals(listOf("docs/a.md"), verdict.patch.files.map { it.path })
        assertEquals(CodingResultValidator.digestOf(result()), verdict.digest)
    }

    @Test
    fun `the shape checks run before the PatchPolicy, so a symlink with a denied path reports the shape`() {
        val verdict = assertIs<CodingResultValidator.Verdict.Rejected>(
            validator.check(result(changes = listOf(CodingChange(".github/x.yml", ChangeKind.WRITE, "x", FileKind.SYMLINK)))),
        )
        assertEquals(listOf(CodingRejectReason.SYMLINK), verdict.reasons)
        assertFalse(CodingRejectReason.POLICY_REJECTED in verdict.reasons)
    }

    @Test
    fun `the toString of a change and of a result shows no path, content or summary`() {
        val change = CodingChange("docs/secret-path.md", ChangeKind.WRITE, "secret-body")
        val text = change.toString() + result(summary = "secret-summary").toString()
        assertFalse(text.contains("secret-path"))
        assertFalse(text.contains("secret-body"))
        assertFalse(text.contains("secret-summary"))
    }
}
