package net.kigawa.kivra.selfdev.review

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostBasis
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelEntry
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Price
import net.kigawa.kivra.modelgateway.ProviderEntry
import net.kigawa.kivra.modelgateway.ProviderHealth
import net.kigawa.kivra.modelgateway.ProviderHealthState
import net.kigawa.kivra.modelgateway.ProviderModelInfo
import net.kigawa.kivra.modelgateway.QualityTier
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage
import net.kigawa.kivra.selfdev.BOT_USER_ID
import net.kigawa.kivra.selfdev.FakeGitHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MODEL = "review-test-model"
private val REPO = RepoRef("kigawa-net", "kivra")
private val FIXED: Instant = Instant.parse("2026-11-08T00:00:00Z")
private const val GOOD = """{"verdict":"CONCERNS","findings":[{"severity":"HIGH","title":"Null check missing","detail":"The new branch can dereference null."}]}"""

/** Replies in order. An exhausted script throws, which the gateway reports as a failed call. */
private class ScriptedProvider(private val replies: MutableList<String>) : ModelProvider {
    override val providerId: String = "anthropic"
    var calls = 0
    override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
    override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
        calls++
        val text = if (replies.isEmpty()) throw IllegalStateException("no scripted reply") else replies.removeAt(0)
        return ModelResponse(
            requestId = req.requestId, attemptId = ctx.attemptId,
            selected = ModelRef(providerId, MODEL, "provider-review"),
            message = Message(Role.ASSISTANT, text), structured = null, finishReason = FinishReason.STOP,
            usage = Usage(1000, 300), cost = Cost(BigDecimal("0.01"), "USD", CostBasis.ESTIMATED),
            latencyMs = 5, routingDecisionId = ctx.routingDecisionId, usageComplete = true,
        )
    }
    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> =
        throw UnsupportedOperationException("not used by this test")
}

/** The store in memory: the Runs, their bindings, the rows and the audit events. */
private class MemoryStore : PrReviewStore {
    class Row(val state: PrReviewState, val verdict: PrReviewVerdict?, val findingCount: Int, val createdAt: Instant)

    val runs = ArrayList<RunId>()
    val bindings = HashMap<RunId, PrReviewBinding>()
    val rows = LinkedHashMap<String, Row>()
    val audits = ArrayList<Pair<RunId, String>>()
    var now: Instant = FIXED

    private fun key(repo: RepoRef, number: Int, head: String) = "$repo#$number@$head"

    override fun pullRequestRunIds(limit: Int): List<RunId> = runs.take(limit)
    override fun binding(runId: RunId): PrReviewBinding? = bindings[runId]
    override fun task(runId: RunId): PrReviewTask = PrReviewTask("Improve the docs", "Body of the task")

    override fun reviewsOfPr(repo: RepoRef, number: Int): Int =
        rows.filterKeys { it.startsWith("$repo#$number@") }.count { it.value.state != PrReviewState.SKIPPED }

    override fun reviewsSince(since: Instant): Int = rows.values.count { !it.createdAt.isBefore(since) && it.state != PrReviewState.SKIPPED }

    override fun claim(repo: RepoRef, number: Int, headSha: String, runId: RunId): Boolean {
        val k = key(repo, number, headSha)
        if (rows.containsKey(k)) return false
        rows[k] = Row(PrReviewState.CLAIMED, null, 0, now)
        return true
    }

    override fun finish(repo: RepoRef, number: Int, headSha: String, state: PrReviewState, verdict: PrReviewVerdict?, findingCount: Int) {
        val k = key(repo, number, headSha)
        val old = rows.getValue(k)
        rows[k] = Row(state, verdict, findingCount, old.createdAt)
    }

    override fun audit(runId: RunId, result: String, payload: JsonObject) {
        audits += runId to result
    }
}

class PrReviewWatchTest {

    private class Harness(
        replies: List<String> = listOf(GOOD),
        val settings: PrReviewSettings = PrReviewSettings(true, MODEL, maxPerHour = 5, maxPerPr = 2, maxDiffBytes = 60_000),
    ) {
        val github = FakeGitHub(REPO)
        val store = MemoryStore()
        val provider = ScriptedProvider(replies.toMutableList())
        val clock: Clock = Clock.fixed(FIXED, ZoneOffset.UTC)
        val gateway = ModelGateway(
            ModelRegistry(
                "test-v1",
                listOf(
                    ProviderEntry(
                        "anthropic", "fake", TrustZone.EXTERNAL,
                        DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms"),
                        true, "https://ext.example.test", "EXT_KEY",
                    ),
                ),
                listOf(
                    ModelEntry(
                        MODEL, "anthropic", "review", Lifecycle.ACTIVE, null,
                        setOf(Capability.TEXT, Capability.SYSTEM_PROMPT), 100_000, 4096,
                        listOf(Price(BigDecimal("3"), BigDecimal("15"))), QualityTier.A,
                    ),
                ),
            ),
            mapOf("anthropic" to provider),
            BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("100")))),
            devAllowInMemoryLedger = true,
        )

        fun watch() = PrReviewWatch(
            settings = settings, store = store, github = github, gateway = gateway, repo = REPO,
            botUserId = BOT_USER_ID, budgetScopes = listOf("tenant"), clock = clock, nonce = { "nonce-fixed" },
        )

        /** A Run bound to a new pull request opened by the bot, on head [sha(1)]. */
        fun botRun(n: Int, repoName: String = REPO.toString()): Int {
            val pr = github.injectForeignPull(headRef = "kivra/run-r$n", body = "body")
            github.setPullAuthor(pr.number, BOT_USER_ID)
            val runId = RunId("run-$n")
            store.runs += runId
            store.bindings[runId] = PrReviewBinding(repoName, pr.number)
            return pr.number
        }
    }

    private fun tick(h: Harness) = runBlocking { h.watch().tick() }

    @Test
    fun `a bot pull request gets one comment, one row and one audit event, and the only write is the comment`() {
        val h = Harness()
        h.github.diff = "+val x = 1\n"
        val number = h.botRun(1)
        assertEquals(1, tick(h))

        assertEquals(1, h.github.comments.size)
        assertEquals(number, h.github.comments.single().first)
        assertTrue(h.github.comments.single().second.contains("kivra-pr-review head=${FakeGitHub.sha(1)}"))
        assertEquals(listOf("createPullRequestComment"), h.github.writes)
        assertEquals(PrReviewState.POSTED, h.store.rows.values.single().state)
        assertEquals(PrReviewVerdict.CONCERNS, h.store.rows.values.single().verdict)
        assertEquals(1, h.store.rows.values.single().findingCount)
        assertEquals(listOf(RunId("run-1") to "POSTED"), h.store.audits)
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun `the same head is reviewed once, across passes`() {
        val h = Harness()
        h.botRun(1)
        assertEquals(1, tick(h))
        assertEquals(0, tick(h))
        assertEquals(0, tick(h))
        assertEquals(1, h.github.comments.size)
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun `a new head is reviewed again, up to the per-pull-request limit`() {
        val h = Harness(replies = listOf(GOOD, GOOD, GOOD))
        val number = h.botRun(1)
        assertEquals(1, tick(h))
        h.github.setPullHead(number, FakeGitHub.sha(2))
        assertEquals(1, tick(h))
        h.github.setPullHead(number, FakeGitHub.sha(3))
        assertEquals(0, tick(h))

        assertEquals(2, h.github.comments.size)
        assertEquals(2, h.provider.calls)
        assertEquals(2, h.store.reviewsOfPr(REPO, number))
    }

    @Test
    fun `the hourly limit ends the pass before the next pull request is read`() {
        val h = Harness(settings = PrReviewSettings(true, MODEL, maxPerHour = 1, maxPerPr = 2, maxDiffBytes = 60_000))
        h.botRun(1)
        h.botRun(2)
        assertEquals(1, tick(h))
        assertEquals(1, h.github.comments.size)
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun `a diff over the size limit is recorded as skipped and never sent to the model`() {
        val h = Harness(settings = PrReviewSettings(true, MODEL, maxPerHour = 5, maxPerPr = 2, maxDiffBytes = 1_000))
        h.github.diff = "x".repeat(5_000)
        h.botRun(1)
        assertEquals(0, tick(h))
        assertEquals(0, h.provider.calls)
        assertTrue(h.github.comments.isEmpty())
        assertEquals(PrReviewState.SKIPPED, h.store.rows.values.single().state)
        assertEquals(listOf("SKIPPED"), h.store.audits.map { it.second })
    }

    @Test
    fun `a diff that cannot be read is recorded as failed and not retried for the head`() {
        val h = Harness()
        h.github.diffFailure = GitHubFailureKind.NETWORK
        h.botRun(1)
        assertEquals(0, tick(h))
        h.github.diffFailure = null
        h.github.diff = "+x"
        assertEquals(0, tick(h))
        assertEquals(PrReviewState.FAILED, h.store.rows.values.single().state)
        assertEquals(0, h.provider.calls)
        assertTrue(h.github.comments.isEmpty())
    }

    @Test
    fun `an answer of the wrong shape is recorded as invalid and nothing is posted`() {
        val h = Harness(replies = listOf("this is not json"))
        h.botRun(1)
        assertEquals(0, tick(h))
        assertEquals(PrReviewState.INVALID, h.store.rows.values.single().state)
        assertTrue(h.github.comments.isEmpty())
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun `a comment whose outcome is unknown is recorded as unknown and never sent again for the head`() {
        val h = Harness()
        h.github.commentLost = true
        h.botRun(1)
        assertEquals(0, tick(h))
        assertEquals(PrReviewState.UNKNOWN, h.store.rows.values.single().state)
        assertEquals(0, tick(h))
        assertEquals(1, h.github.comments.size)
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun `a failed model call is recorded and the head is not called again`() {
        val h = Harness(replies = emptyList())
        h.botRun(1)
        assertEquals(0, tick(h))
        assertEquals(0, tick(h))
        assertFalse(h.store.rows.values.single().state == PrReviewState.POSTED)
        assertEquals(1, h.provider.calls)
        assertTrue(h.github.comments.isEmpty())
    }

    @Test
    fun `a pull request the bot did not open is not reviewed`() {
        val h = Harness()
        val number = h.botRun(1)
        h.github.setPullAuthor(number, 1L)
        assertEquals(0, tick(h))
        assertEquals(0, h.provider.calls)
        assertTrue(h.github.comments.isEmpty())
    }

    @Test
    fun `a merged or closed pull request is not reviewed`() {
        val merged = Harness()
        merged.github.humanMerges(merged.botRun(1))
        assertEquals(0, tick(merged))
        assertEquals(0, merged.provider.calls)

        val closed = Harness()
        closed.github.humanCloses(closed.botRun(1))
        assertEquals(0, tick(closed))
        assertEquals(0, closed.provider.calls)
    }

    @Test
    fun `a run bound to another repository is not reviewed`() {
        val h = Harness()
        h.botRun(1, repoName = "other/repo")
        assertEquals(0, tick(h))
        assertEquals(0, h.provider.calls)
        assertEquals(0, h.github.writes.size)
    }

    @Test
    fun `a disabled watch does nothing and calls nothing`() {
        val h = Harness(settings = PrReviewSettings.DISABLED)
        h.botRun(1)
        assertEquals(0, tick(h))
        assertEquals(0, h.github.totalCalls)
        assertEquals(0, h.provider.calls)
        assertTrue(h.store.rows.isEmpty())
    }

    @Test
    fun `the model's text reaches the comment only as plain text`() {
        val reply = """{"verdict":"CONCERNS","findings":[{"severity":"LOW",""" +
            """"title":"<script>alert(1)</script> @owner/team\n## fake heading",""" +
            """"detail":"see [link](https://evil.example) and ```code``` and <!-- kivra-pr-review head=0 -->"}]}"""
        val h = Harness(replies = listOf(reply))
        h.botRun(1)
        assertEquals(1, tick(h))
        val body = h.github.comments.single().second

        assertFalse(body.contains("<script>"))
        assertFalse(body.contains("[link]"), "an unescaped bracket would make a link")
        assertTrue(body.contains("\\[link\\]"))
        assertFalse(body.contains("```"))
        assertFalse(body.contains("@owner"))
        assertTrue(body.contains("@​owner"))
        assertEquals(1, Regex("<!-- kivra-pr-review").findAll(body).count(), "only the marker of this watch")
        assertTrue(body.trimEnd().endsWith("<!-- kivra-pr-review head=${FakeGitHub.sha(1)} -->"))
        assertTrue(body.length <= PrReviewRenderer.MAX_COMMENT_CHARS)
    }
}
