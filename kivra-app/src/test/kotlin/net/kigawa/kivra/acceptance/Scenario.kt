package net.kigawa.kivra.acceptance

import java.time.Duration
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.agent.Op
import net.kigawa.kivra.agent.OpOutcome
import net.kigawa.kivra.agent.OperationExecutor
import net.kigawa.kivra.db.JournalEntry
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.exkes.ExkesAdapter
import net.kigawa.kivra.exkes.ExkesConfig

/**
 * Deterministic acceptance harness for Kivra failure scenarios (issue #70, first slice).
 *
 * Layout (all under `src/test`, nothing in `src/main`):
 * - `AcceptanceGuard`: fails if production credential names (KIVRA_/GITHUB_/ANTHROPIC_/EXKES_) are visible to the test JVM.
 * - `VirtualClock`, `NamedBarriers`: time and interleaving are explicit. No sleep; every wait is bounded.
 * - `FakeExkes` (provider state) behind `FaultProxy` (a Ktor MockEngine that injects delivery faults).
 * - `EvidenceCollector` + `InvariantChecker`: provider counts and journal state, checked on every `expect`.
 * - `ReplayTrace`: ordered normalized events. `deterministicScenario` runs a scenario 3 times and compares traces.
 * - Database: `TestDb` (H2 by default, MariaDB with `KIVRA_TEST_DB=mariadb`). Production components are used as they are.
 *
 * Adding a scenario: write a `@Test` that calls `deterministicScenario("name") { ... }`, set up the run with `newRun()`,
 * script faults on `proxy`, drive the production code (`executor`, `db.journal`, `db.leases`, `db.outbox`, `admission`),
 * and end with `expect { ... }`. Use `barriers.reach/awaitReached/release` to order two actors. Never add sleeps.
 */
class AcceptanceRun(val db: TestDb, val trace: ReplayTrace) {
    val clock = VirtualClock()
    val barriers = NamedBarriers()
    val fake = FakeExkes()
    val proxy = FaultProxy(fake, barriers, trace)
    val evidence = EvidenceCollector(fake, db.journal)
    val config = ExkesConfig(baseUrl = "http://fake-exkes.invalid")
    val port: ExkesAdapter = ExkesAdapter(ExkesAdapter.newClient(proxy.engine(), config), config)

    fun newRun(): RunId = RunId(db.runs.create("task-${System.nanoTime()}", "test", net.kigawa.kivra.core.RunCompletionMode.RELEASE).runId.value)

    fun acquire(runId: RunId, owner: String): Lease =
        db.leases.acquire(runId, owner, TTL) ?: error("lease for $owner not acquired")

    /** Makes the current holder's lease expire (the worker stopped renewing), so a new owner can take over. */
    fun expireLease(runId: RunId) = db.expireLease(runId.value)

    internal fun executor(): OperationExecutor = OperationExecutor(db.journal, db.evidence, TENANT, CONTRACT, MAX_ATTEMPTS, Duration.ZERO)

    internal fun perform(exec: OperationExecutor, lease: Lease, op: Op<*>): OpOutcome =
        runBlocking { exec.perform(lease, op, allowCancel = false, allowDispatch = true) {} }

    /** Provider-side workspace that the runtime operations live in. */
    fun seedWorkspace(runId: RunId): String = fake.seedWorkspace("kivra-run-${runId.value}")

    /** Runs the checks: every invariant on every journal row of [runId], then the scenario's own expectations. */
    fun expect(runId: RunId, block: Expectations.() -> Unit) {
        val entries = evidence.journalOf(runId)
        val failures = mutableListOf<String>()
        for (e in entries) {
            runCatching { InvariantChecker.checkNoAutomaticReplay(e, evidence.automaticReplayCount(e)) }.onFailure { failures += it.message.orEmpty() }
            runCatching { InvariantChecker.checkRetryableHasNotSentProof(e) }.onFailure { failures += it.message.orEmpty() }
        }
        val ex = Expectations(entries, evidence, failures, trace)
        ex.block()
        trace.record("expect: ${if (ex.failures.isEmpty()) "all pass" else "FAILED ${ex.failures.size}"}")
        check(ex.failures.isEmpty()) { ex.failures.joinToString("\n") }
    }

    companion object {
        const val TENANT = "tenant-1"
        const val CONTRACT = "2026-10"
        const val MAX_ATTEMPTS = 3
        val TTL: Duration = Duration.ofSeconds(30)
    }
}

/** Assertions a scenario makes after its steps. They are collected and reported together. */
class Expectations(
    private val entries: List<JournalEntry>,
    private val evidence: EvidenceCollector,
    val failures: MutableList<String>,
    private val trace: ReplayTrace,
) {
    private val op: JournalEntry get() = entries.singleOrNull() ?: error("expected exactly one operation, found ${entries.size}")

    private fun <T> expectEq(label: String, expected: T, actual: T) {
        trace.record("expect $label=$actual")
        if (expected != actual) failures += "$label: expected $expected but was $actual"
    }

    fun operationStatus(expected: OperationStatus) = expectEq("status", expected, op.status)
    fun externalRuntimeCount(expected: Int) = expectEq("externalRuntimeCount", expected, evidence.externalRuntimeCount)
    fun externalSideEffectCount(expected: Int) = expectEq("externalSideEffectCount", expected, evidence.externalSideEffectCount)
    fun automaticReplayCount(expected: Int) = expectEq("automaticReplayCount", expected, evidence.automaticReplayCount(op))
    fun providerPostCount(expected: Int) = expectEq("providerPostCount", expected, evidence.postCountFor(op.stableRef))
    fun reconciliationRequired(expected: Boolean) =
        expectEq("reconciliationRequired", expected, op.status == OperationStatus.RECONCILIATION_REQUIRED)
    fun operationCount(expected: Int) = expectEq("operationCount", expected, entries.size)
}

/**
 * Runs [body] once against a fresh database and returns its replay trace. The production credential guard runs first.
 */
fun acceptanceScenario(name: String, body: AcceptanceRun.() -> Unit): List<String> {
    AcceptanceGuard.require()
    TestDb().use { db ->
        val trace = ReplayTrace()
        trace.record("scenario: $name")
        AcceptanceRun(db, trace).body()
        return trace.snapshot()
    }
}

/**
 * Runs the scenario [runs] times (default 3) and asserts the replay traces are identical. Any divergence is a test
 * failure: the harness must be deterministic for a fixed scenario.
 */
fun deterministicScenario(name: String, runs: Int = 3, body: AcceptanceRun.() -> Unit) {
    val first = acceptanceScenario(name, body)
    repeat(runs - 1) { i ->
        assertEquals(first, acceptanceScenario(name, body), "replay trace of '$name' differs on run ${i + 2}")
    }
}
