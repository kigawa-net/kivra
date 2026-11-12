package net.kigawa.kivra.agent.k8s.pod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.workdir.AgentChange
import net.kigawa.kivra.agent.workdir.AgentChangeKind
import net.kigawa.kivra.agent.workdir.AgentResult
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffLimits
import net.kigawa.kivra.agent.workdir.DiffRejectReason
import net.kigawa.kivra.agent.workdir.PathRules

class PodProtocolTest {
    private val commit = "a".repeat(40)

    private fun input(
        runId: String = "01M545K02Q6YB4M5ZHAM5792CS",
        attemptId: String = "att-1",
        owner: String = "kigawa-net",
        repo: String = "kivra",
        commit: String = this.commit,
        title: String = "Fix docs",
        body: String = "Body",
    ) = PodInput(runId, attemptId, owner, repo, commit, title, body, false)

    private fun write(path: String, content: String? = "text\n") = AgentChange(path, AgentChangeKind.WRITE, content)

    private fun collected(exit: Int = 0, vararg changes: AgentChange) =
        PodReadout.Collected(exit, changes.toList(), emptyList())

    private fun finish(
        readout: PodReadout.Collected,
        summary: String = "Fixed the docs",
        limits: DiffLimits = DiffLimits(),
    ): CollectOutcome = readout.finish(summary, "sess-1", limits, PathRules.BUILT_IN_ONLY)

    private fun reasonsOf(outcome: CollectOutcome): Set<DiffRejectReason> = assertIs<CollectOutcome.Rejected>(outcome).reasons.toSet()

    // ---- input ----

    @Test
    fun inputRoundTrips() {
        val back = PodInput.decode(PodInput.encode(input(title = "t\"q", body = "line1\nline2 あ")))
        assertEquals("t\"q", back.title)
        assertEquals("line1\nline2 あ", back.body)
        assertEquals("kigawa-net", back.owner)
    }

    @Test
    fun inputIsRefusedWhenInvalid() {
        assertFailsWith<PodProtocolException> { input(commit = "main") }
        assertFailsWith<PodProtocolException> { input(commit = "A".repeat(40)) }
        assertFailsWith<PodProtocolException> { input(owner = "a/b") }
        assertFailsWith<PodProtocolException> { input(repo = "x y") }
        assertFailsWith<PodProtocolException> { input(runId = "../x") }
        assertFailsWith<PodProtocolException> { input(title = "x".repeat(PodInput.MAX_TITLE_CHARS + 1)) }
        assertFailsWith<PodProtocolException> { input(body = "x".repeat(PodInput.MAX_BODY_BYTES + 1)) }
        // The body limit is in bytes, not characters.
        assertFailsWith<PodProtocolException> { input(body = "あ".repeat(PodInput.MAX_BODY_BYTES / 3 + 1)) }
    }

    @Test
    fun inputFileIsReadStrictly() {
        val good = String(PodInput.encode(input()), Charsets.UTF_8)
        assertFailsWith<PodProtocolException> { PodInput.decode(good.replace("}", ",\"extra\":1}").toByteArray()) }
        assertFailsWith<PodProtocolException> { PodInput.decode(good.replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray()) }
        assertFailsWith<PodProtocolException> { PodInput.decode(good.replace("\"truncated\":false", "\"truncated\":\"false\"").toByteArray()) }
        assertFailsWith<PodProtocolException> { PodInput.decode("[]".toByteArray()) }
        assertFailsWith<PodProtocolException> { PodInput.decode(ByteArray(PodInput.MAX_FILE_BYTES + 1) { ' '.code.toByte() }) }
    }

    @Test
    fun inputToStringHidesTheTask() {
        val text = input(title = "SECRET-TITLE", body = "SECRET-BODY").toString()
        assertTrue("SECRET" !in text)
    }

    // ---- result file ----

    @Test
    fun acceptedResultRoundTrips() {
        val outcome = CollectOutcome.Accepted(AgentResult("x", listOf(write("docs/a.md", "hi\n"), AgentChange("docs/b.md", AgentChangeKind.DELETE, null)), 0, null))
        val readout = assertIs<PodReadout.Collected>(PodResultCodec.decode(PodResultCodec.encode(0, outcome)))
        assertEquals(0, readout.exitStatus)
        assertEquals(listOf("docs/a.md", "docs/b.md"), readout.changes!!.map { it.path })
        assertEquals("hi\n", readout.changes!![0].content)
        assertNull(readout.changes!![1].content)
    }

    @Test
    fun rejectedResultKeepsTheReasons() {
        val bytes = PodResultCodec.encode(1, CollectOutcome.Rejected(listOf(DiffRejectReason.SYMLINK, DiffRejectReason.CLI_EXIT_NONZERO)))
        val readout = assertIs<PodReadout.Collected>(PodResultCodec.decode(bytes))
        assertNull(readout.changes)
        assertEquals(listOf(DiffRejectReason.SYMLINK, DiffRejectReason.CLI_EXIT_NONZERO), readout.reasons)
        assertEquals(1, readout.exitStatus)
    }

    @Test
    fun errorResultRoundTrips() {
        val readout = assertIs<PodReadout.Error>(PodResultCodec.decode(PodResultCodec.encodeError("WORKDIR_FAILED")))
        assertEquals("WORKDIR_FAILED", readout.reason)
    }

    @Test
    fun resultFileIsReadStrictly() {
        val good = String(PodResultCodec.encode(0, CollectOutcome.Accepted(AgentResult("x", listOf(write("docs/a.md")), 0, null))), Charsets.UTF_8)
        fun bad(text: String) = assertFailsWith<PodProtocolException> { PodResultCodec.decode(text.toByteArray()) }
        bad(good.replace("\"schemaVersion\":1", "\"schemaVersion\":9"))
        bad(good.replace("\"status\":\"COLLECTED\"", "\"status\":\"OTHER\""))
        bad(good.replace("}", ",\"extra\":1}"))
        bad(good.replace("\"exitStatus\":0", "\"exitStatus\":\"0\""))
        bad(good.replace("\"kind\":\"WRITE\"", "\"kind\":\"CHMOD\""))
        bad(good.replace("\"reasons\":[]", "\"reasons\":[\"NO_SUCH_REASON\"]"))
        bad(good.replace("\"reasons\":[]", "\"reasons\":[\"SYMLINK\"]")) // reasons and changes together
        bad(good.replace("\"content\":", "\"extra\":1,\"content\":"))
        bad("not json")
        bad("[]")
        bad("""{"schemaVersion":1,"status":"ERROR","reason":"has space"}""")
        bad("""{"schemaVersion":1,"status":"ERROR","reason":"X","extra":1}""")
    }

    @Test
    fun resultFileOverTheSizeLimitIsRefused() {
        assertFailsWith<PodProtocolException> { PodResultCodec.decode(ByteArray(PodResultCodec.MAX_FILE_BYTES + 1) { ' '.code.toByte() }) }
    }

    @Test
    fun resultWithTooManyChangesIsRefused() {
        val changes = (0..PodResultCodec.MAX_CHANGES).joinToString(",") { """{"path":"docs/$it.md","kind":"DELETE","content":null}""" }
        val text = """{"schemaVersion":1,"status":"COLLECTED","exitStatus":0,"changes":[$changes],"reasons":[]}"""
        assertFailsWith<PodProtocolException> { PodResultCodec.decode(text.toByteArray()) }
    }

    @Test
    fun anOversizedEncodedResultBecomesARefusal() {
        // 6 bytes of JSON per control character: 300 KiB of them passes the diff limit of 200 KiB per file only if the limit was ignored.
        val huge = "\u0001".repeat(PodResultCodec.MAX_FILE_BYTES / 6 + 10)
        val outcome = CollectOutcome.Accepted(AgentResult("x", listOf(write("docs/a.md", huge)), 0, null))
        val readout = assertIs<PodReadout.Collected>(PodResultCodec.decode(PodResultCodec.encode(0, outcome)))
        assertEquals(listOf(DiffRejectReason.TOTAL_TOO_LARGE), readout.reasons)
    }

    // ---- finish: the agent checks the Pod's claim again ----

    @Test
    fun aGoodClaimIsAcceptedWithTheAgentsSummaryAndSession() {
        val outcome = assertIs<CollectOutcome.Accepted>(finish(collected(0, write("docs/a.md"))))
        assertEquals("Fixed the docs", outcome.result.summary)
        assertEquals("sess-1", outcome.result.sessionId)
        assertEquals(0, outcome.result.exitStatus)
    }

    @Test
    fun aClaimWithAPathTheAgentDeniesIsRefused() {
        assertEquals(setOf(DiffRejectReason.PATH_DENIED), reasonsOf(finish(collected(0, write(".github/workflows/ci.yml")))))
        assertEquals(setOf(DiffRejectReason.PATH_NOT_ALLOWED), reasonsOf(finish(collected(0, write("src/Main.kt")))))
        assertTrue(DiffRejectReason.PATH_ABSOLUTE in reasonsOf(finish(collected(0, write("/etc/passwd")))))
        assertTrue(DiffRejectReason.PATH_DOT_SEGMENT in reasonsOf(finish(collected(0, write("docs/../../x")))))
    }

    @Test
    fun theClaimsOwnPathPatternsApplyToThePodsResult() {
        val rules = PathRules.fromServer(allowed = listOf("docs/a/**"), denied = emptyList())
        val outcome = collected(0, write("docs/b.md")).finish("s", null, DiffLimits(), rules)
        assertEquals(setOf(DiffRejectReason.PATH_NOT_ALLOWED), reasonsOf(outcome))
    }

    @Test
    fun theAgentsLimitsApplyNotThePodsDefaults() {
        val strict = DiffLimits(maxChangedFiles = 1)
        val two = collected(0, write("docs/a.md"), write("docs/b.md"))
        assertTrue(DiffRejectReason.TOO_MANY_CHANGES in reasonsOf(finish(two, limits = strict)))
        assertIs<CollectOutcome.Accepted>(finish(two))
        assertTrue(DiffRejectReason.TOO_MANY_DELETES in reasonsOf(finish(collected(0, AgentChange("docs/a.md", AgentChangeKind.DELETE, null)), limits = DiffLimits(maxDeletes = 0))))
        assertTrue(DiffRejectReason.FILE_TOO_LARGE in reasonsOf(finish(collected(0, write("docs/a.md", "x".repeat(100))), limits = DiffLimits(maxWriteFileBytes = 50))))
        assertTrue(
            DiffRejectReason.TOTAL_TOO_LARGE in
                reasonsOf(finish(collected(0, write("docs/a.md", "x".repeat(40)), write("docs/b.md", "x".repeat(40))), limits = DiffLimits(maxTotalWriteBytes = 60))),
        )
        assertTrue(DiffRejectReason.PATH_TOO_LONG in reasonsOf(finish(collected(0, write("docs/" + "a".repeat(30))), limits = DiffLimits(maxPathChars = 20))))
        assertTrue(DiffRejectReason.LINE_TOO_LONG in reasonsOf(finish(collected(0, write("docs/a.md", "x".repeat(50))), limits = DiffLimits(maxLineChars = 10))))
    }

    @Test
    fun secretLikeContentAndSummaryAreRefused() {
        val key = "-----BEGIN RSA PRIVATE KEY-----"
        assertTrue(DiffRejectReason.SECRET_LIKE_CONTENT in reasonsOf(finish(collected(0, write("docs/a.md", key)))))
        assertTrue(DiffRejectReason.SECRET_LIKE_CONTENT in reasonsOf(finish(collected(0, write("docs/a.md")), summary = key)))
    }

    @Test
    fun summaryRulesApply() {
        assertTrue(DiffRejectReason.SUMMARY_EMPTY in reasonsOf(finish(collected(0, write("docs/a.md")), summary = "  ")))
        assertTrue(DiffRejectReason.SUMMARY_TOO_LONG in reasonsOf(finish(collected(0, write("docs/a.md")), summary = "x".repeat(DiffLimits().maxSummaryChars + 1))))
    }

    @Test
    fun aNonZeroExitIsRefusedEvenIfThePodSaidAccepted() {
        assertTrue(DiffRejectReason.CLI_EXIT_NONZERO in reasonsOf(finish(collected(1, write("docs/a.md")))))
    }

    @Test
    fun malformedChangesAreRefused() {
        assertTrue(DiffRejectReason.EMPTY_PATCH in reasonsOf(finish(collected(0))))
        assertTrue(DiffRejectReason.RESULT_FIELD_INVALID in reasonsOf(finish(collected(0, write("docs/a.md", null)))))
        assertTrue(DiffRejectReason.RESULT_FIELD_INVALID in reasonsOf(finish(collected(0, AgentChange("docs/a.md", AgentChangeKind.DELETE, "x")))))
        assertTrue(DiffRejectReason.RESULT_FIELD_INVALID in reasonsOf(finish(collected(0, write("docs/a.md"), write("docs/a.md")))))
        assertTrue(DiffRejectReason.CASE_COLLISION in reasonsOf(finish(collected(0, write("docs/a.md"), write("docs/A.md")))))
        assertTrue(DiffRejectReason.PATH_NOT_NFC in reasonsOf(finish(collected(0, write("docs/é.md")))))
        assertTrue(DiffRejectReason.BINARY_FILE in reasonsOf(finish(collected(0, write("docs/a.md", "a\u0000b")))))
    }

    @Test
    fun aRefusalOfThePodIsKept() {
        val readout = PodReadout.Collected(0, null, listOf(DiffRejectReason.SYMLINK))
        assertEquals(setOf(DiffRejectReason.SYMLINK), reasonsOf(finish(readout)))
        val noReason = PodReadout.Collected(0, null, emptyList())
        assertNotNull(reasonsOf(finish(noReason)))
        assertTrue(DiffRejectReason.RESULT_FIELD_INVALID in reasonsOf(finish(noReason)))
    }

    @Test
    fun reasonsAreSorted() {
        val reasons = (finish(collected(1, write(".github/x"), write("docs/a.md", "a\u0000"))) as CollectOutcome.Rejected).reasons
        assertEquals(reasons.sortedBy { it.ordinal }, reasons)
    }
}
