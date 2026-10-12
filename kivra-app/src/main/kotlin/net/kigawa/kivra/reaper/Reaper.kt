package net.kigawa.kivra.reaper

import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import net.kigawa.kivra.agent.sha256Hex
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.db.DbTime
import net.kigawa.kivra.db.Ulid
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.exkes.ExecutionPort
import net.kigawa.kivra.exkes.KivraNaming
import net.kigawa.kivra.exkes.PortResult
import net.kigawa.kivra.exkes.RuntimeState
import net.kigawa.kivra.exkes.Workspace
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** Runtime states that no longer run anything; a workspace whose only runtimes are in these states is not blocked by them. */
private val NON_BLOCKING_RUNTIME_STATES = setOf(RuntimeState.STOPPED, RuntimeState.FAILED, RuntimeState.EXPIRED, RuntimeState.DELETED)

/** Journal op type written by AgentManager when it creates a Kivra workspace (see AgentManager.workspaceOp). */
private const val OP_CREATE_WORKSPACE = "EXKES_CREATE_WORKSPACE"

/** Journal op type of a Reaper deletion. Its idempotency key is `reap:<workspaceId>`. */
private const val OP_REAP_DELETE_WORKSPACE = "EXKES_REAP_DELETE_WORKSPACE"

/**
 * Reaper settings. Safe by default: detection only.
 *
 * - [dryRun] (default true): nothing is deleted and nothing is written to the Journal.
 * - [autoDeleteEnabled] (default false): when set and [dryRun] is false, every orphan that passes all gates is deleted.
 * - [approvedWorkspaceIds]: a human approval list. When [dryRun] is false, these workspaces may be deleted even while
 *   [autoDeleteEnabled] is off. The gates still apply to them.
 */
data class ReaperConfig(
    val dryRun: Boolean = true,
    val autoDeleteEnabled: Boolean = false,
    val approvedWorkspaceIds: Set<String> = emptySet(),
    /** How long a terminal run's workspace is kept after the run became terminal (its `updated_at`). */
    val retentionGrace: Duration = Duration.ofDays(7),
    /** Dispatches allowed for a deletion that provably had no effect (Transient). UNKNOWN is never resent. */
    val maxDeleteAttempts: Int = 3,
) {
    init {
        require(!retentionGrace.isNegative) { "retentionGrace must not be negative" }
        require(maxDeleteAttempts >= 1) { "maxDeleteAttempts must be >= 1" }
    }
}

enum class ReapVerdict {
    /** The Run is not terminal: the workspace belongs to live work. Not an orphan. */
    IN_USE,

    /** The Run is terminal but inside the retention grace. */
    RETENTION,

    /** An orphan that is not deletable right now (live lease, hold, live runtimes, runtime listing failed). */
    BLOCKED,

    /** An orphan that passes every gate but is not deleted (dry-run, or not approved). Human decision needed. */
    CANDIDATE,

    /** Cannot be verified (no Run record, no Journal proof of creation, marker mismatch). Reported only, never deleted. */
    QUARANTINE,

    /** exkes accepted the deletion (202) or the workspace was already gone (404). */
    DELETED,

    /** The Journal already holds a record for this deletion that is not safely repeatable (UNKNOWN, IN_FLIGHT, FAILED, CONFIRMED). */
    JOURNALED,

    /** The deletion outcome is unknown. Recorded as UNKNOWN and never resent automatically. */
    DELETE_UNKNOWN,

    /** The deletion was refused or failed definitively. */
    DELETE_FAILED,
}

data class ReapFinding(
    val workspaceName: String,
    val workspaceId: String,
    val runId: String?,
    val verdict: ReapVerdict,
    val reason: String,
)

/** [listingFailed] is set when exkes could not list workspaces; nothing was evaluated, so nothing was touched. */
data class ReapReport(val findings: List<ReapFinding>, val listingFailed: String? = null)

/**
 * Reaper (issue #31): finds Kivra-owned exkes workspaces that no live Run owns anymore and, when allowed, deletes them.
 *
 * Ownership rules (the Kivra database and the Operation Journal are the source of truth):
 *  - a workspace is considered at all only if its name is `kivra-run-<runId>` AND its `kivra.run-id` label equals `<runId>`;
 *    anything else is not Kivra's and is never touched;
 *  - it is an orphan only if the Run is terminal, the retention grace has elapsed, the Run holds no live lease and no hold,
 *    no runtime of the workspace is still live, and the Journal proves that this Kivra run created this exact workspace id
 *    (a CONFIRMED EXKES_CREATE_WORKSPACE record with the same external id);
 *  - a Kivra-named workspace that fails the Journal or Run check is QUARANTINE: reported, never deleted.
 *
 * Deletion is journaled in external_operations (op type [OP_REAP_DELETE_WORKSPACE], key `reap:<workspaceId>`) before the
 * call, under a lock on the Run row that re-checks the terminal state, lease and hold. A record that is not
 * RETRYABLE is never dispatched again, so an UNKNOWN deletion is never resent and two Reapers cannot both delete.
 *
 * The Run row lock is taken in the same transaction as the journal insert; no lease is needed because a terminal Run
 * can never be leased again.
 */
class Reaper(
    private val port: ExecutionPort,
    private val database: Database,
    private val config: ReaperConfig = ReaperConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One detection pass, plus deletions the config allows. Idempotent: a repeated pass finds the same state or less. */
    suspend fun runOnce(): ReapReport {
        val listed = when (val r = port.listWorkspaces()) {
            is PortResult.Ok -> r.value
            else -> return ReapReport(emptyList(), listingFailed = r.toString())
        }
        val findings = ArrayList<ReapFinding>()
        for (ws in listed.sortedBy { it.name }) {
            val runId = KivraNaming.runIdOfWorkspaceName(ws.name) ?: continue // not a Kivra name: never touched
            val finding = try {
                review(ws, runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReapFinding(ws.name, ws.id.value, runId, ReapVerdict.QUARANTINE, "review failed: ${e::class.simpleName}: ${e.message}")
            }
            findings += finding
        }
        return ReapReport(findings)
    }

    /** Runs [runOnce] repeatedly until the calling coroutine is cancelled. Each report goes to [onReport]. */
    suspend fun runPeriodically(interval: Duration, onReport: (ReapReport) -> Unit = {}) {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        while (currentCoroutineContext().isActive) {
            try {
                onReport(runOnce())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onReport(ReapReport(emptyList(), listingFailed = "pass failed: ${e::class.simpleName}: ${e.message}"))
            }
            delay(interval.toMillis())
        }
    }

    private suspend fun review(ws: Workspace, runId: String): ReapFinding {
        fun finding(verdict: ReapVerdict, reason: String) = ReapFinding(ws.name, ws.id.value, runId, verdict, reason)

        if (ws.labels[KivraNaming.RUN_ID_LABEL] != runId) {
            return finding(ReapVerdict.QUARANTINE, "marker label ${KivraNaming.RUN_ID_LABEL} is missing or differs from the name")
        }
        val run = loadRun(runId) ?: return finding(ReapVerdict.QUARANTINE, "no DevelopmentRun record for $runId")
        if (!run.terminal) return finding(ReapVerdict.IN_USE, "run is ${run.state}")

        proofProblem(runId, ws)?.let { return finding(ReapVerdict.QUARANTINE, it) }
        if (!run.retentionElapsed) return finding(ReapVerdict.RETENTION, "run ${run.state} is inside the retention grace")
        run.hold?.let { return finding(ReapVerdict.BLOCKED, "run is on hold ($it)") }
        if (run.liveLease) return finding(ReapVerdict.BLOCKED, "run still holds a live lease")

        when (val rts = port.listRuntimes(ws.id)) {
            is PortResult.Ok -> {
                val live = rts.value.filter { it.state !in NON_BLOCKING_RUNTIME_STATES }
                if (live.isNotEmpty()) return finding(ReapVerdict.BLOCKED, "${live.size} live runtime(s) remain in the workspace")
            }
            else -> return finding(ReapVerdict.BLOCKED, "runtime listing failed: $rts")
        }

        val autoAllowed = !config.dryRun && (config.autoDeleteEnabled || ws.id.value in config.approvedWorkspaceIds)
        if (!autoAllowed) {
            val why = if (config.dryRun) "dry-run" else "not approved and auto-delete is off"
            return finding(ReapVerdict.CANDIDATE, "orphan ($why)")
        }
        return delete(ws, runId)
    }

    private class RunGate(
        val state: String,
        val hold: String?,
        val liveLease: Boolean,
        val retentionElapsed: Boolean,
        val leaseGeneration: Long,
    ) {
        val terminal: Boolean get() = state in DevelopmentRunState.terminalNames
    }

    private fun loadRun(runId: String): RunGate? = transaction(database) { readRunGate(runId, locked = false) }

    private fun Transaction.readRunGate(runId: String, locked: Boolean): RunGate? = execQuery(
        """
        SELECT state, hold_reason,
               CASE WHEN lease_owner IS NOT NULL AND lease_expires_at > ${DbTime.NOW} THEN 1 ELSE 0 END,
               CASE WHEN updated_at <= ${DbTime.nowMinusSeconds()} THEN 1 ELSE 0 END,
               lease_generation
          FROM development_runs
         WHERE run_id = ?
        """.trimIndent() + if (locked) " FOR UPDATE" else "",
        config.retentionGrace.seconds, runId,
    ) { rs ->
        RunGate(
            state = rs.getString(1),
            hold = rs.getString(2),
            liveLease = rs.getInt(3) == 1,
            retentionElapsed = rs.getInt(4) == 1,
            leaseGeneration = rs.getLong(5),
        )
    }.singleOrNull()

    /** Null when the Journal proves that this Kivra run created exactly this workspace id; otherwise the reason it does not. */
    private fun proofProblem(runId: String, ws: Workspace): String? {
        val rows = transaction(database) {
            execQuery(
                "SELECT status, external_ref FROM external_operations WHERE run_id = ? AND op_type = ? AND stable_ref = ?",
                runId, OP_CREATE_WORKSPACE, ws.name,
            ) { it.getString(1) to it.getString(2) }
        }
        if (rows.isEmpty()) return "no Journal record of creating this workspace for run $runId"
        if (rows.size > 1) return "ambiguous: ${rows.size} Journal records of creating this workspace"
        val (status, externalRef) = rows.single()
        if (status != "CONFIRMED") return "Journal record of creating this workspace is $status"
        if (externalRef != ws.id.value) return "Journal records a different workspace id than ${ws.id.value}"
        return null
    }

    private sealed interface Claim {
        data class Go(val opId: String, val version: Long) : Claim
        data class Refused(val verdict: ReapVerdict, val reason: String) : Claim
    }

    /**
     * Under a row lock on the Run: re-checks terminal / hold / lease, then writes the deletion's journal record
     * (IN_FLIGHT) before the call. A RETRYABLE record with attempts left is re-dispatched; any other existing record refuses.
     */
    private fun claim(runId: String, ws: Workspace): Claim = transaction(database) {
        val gate = readRunGate(runId, locked = true)
            ?: return@transaction Claim.Refused(ReapVerdict.QUARANTINE, "run record vanished")
        if (!gate.terminal) return@transaction Claim.Refused(ReapVerdict.IN_USE, "run became ${gate.state}")
        gate.hold?.let { return@transaction Claim.Refused(ReapVerdict.BLOCKED, "run is on hold ($it)") }
        if (gate.liveLease) return@transaction Claim.Refused(ReapVerdict.BLOCKED, "run holds a live lease")

        val key = "reap:${ws.id.value}"
        val existing = execQuery(
            "SELECT op_id, status, attempt, max_attempts, version FROM external_operations WHERE run_id = ? AND op_type = ? AND idempotency_key = ?",
            runId, OP_REAP_DELETE_WORKSPACE, key,
        ) { Existing(it.getString(1), it.getString(2), it.getInt(3), it.getInt(4), it.getLong(5)) }.singleOrNull()
        val now = utcNow(clock)
        when {
            existing == null -> {
                val opId = Ulid.generate(clock)
                execUpdate(
                    """
                    INSERT INTO external_operations
                        (op_id, run_id, op_type, idempotency_key, external_system, stable_ref, external_ref, request_hash,
                         status, attempt, max_attempts, lease_generation, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'EXKES', ?, ?, ?, 'IN_FLIGHT', 1, ?, ?, 0, ?, ?)
                    """.trimIndent(),
                    opId, runId, OP_REAP_DELETE_WORKSPACE, key, ws.name, ws.id.value,
                    sha256Hex("reap-delete-workspace|${ws.id.value}"), config.maxDeleteAttempts, gate.leaseGeneration, now, now,
                )
                Claim.Go(opId, 0)
            }
            existing.status == "RETRYABLE" && existing.attempt < existing.maxAttempts -> {
                val moved = execUpdate(
                    "UPDATE external_operations SET status = 'IN_FLIGHT', attempt = attempt + 1, version = version + 1, updated_at = ? WHERE op_id = ? AND version = ?",
                    now, existing.opId, existing.version,
                )
                if (moved == 1) Claim.Go(existing.opId, existing.version + 1)
                else Claim.Refused(ReapVerdict.JOURNALED, "journal record changed concurrently")
            }
            existing.status == "CONFIRMED" -> Claim.Refused(ReapVerdict.JOURNALED, "already deleted: Journal record is CONFIRMED")
            else -> Claim.Refused(
                ReapVerdict.JOURNALED,
                "Journal record is ${existing.status} (attempt ${existing.attempt}/${existing.maxAttempts}); not dispatched again",
            )
        }
    }

    private class Existing(val opId: String, val status: String, val attempt: Int, val maxAttempts: Int, val version: Long)

    private suspend fun delete(ws: Workspace, runId: String): ReapFinding {
        fun finding(verdict: ReapVerdict, reason: String) = ReapFinding(ws.name, ws.id.value, runId, verdict, reason)

        val go = when (val c = claim(runId, ws)) {
            is Claim.Go -> c
            is Claim.Refused -> return finding(c.verdict, c.reason)
        }
        val result: PortResult<Workspace> = try {
            port.deleteWorkspace(ws.id)
        } catch (e: CancellationException) {
            throw e // the row stays IN_FLIGHT: it is never resent, a later pass only records it as JOURNALED
        } catch (e: Exception) {
            PortResult.Unknown("deleteWorkspace threw ${e::class.simpleName}: ${e.message}", e)
        }
        return when (result) {
            is PortResult.Ok -> {
                settle(go, "CONFIRMED", null, null)
                finding(ReapVerdict.DELETED, "exkes accepted the deletion (${result.value.state})")
            }
            PortResult.NotFound -> {
                settle(go, "CONFIRMED", null, "already gone (404)")
                finding(ReapVerdict.DELETED, "already gone (404)")
            }
            is PortResult.Transient -> {
                settle(go, "RETRYABLE", "TRANSIENT", result.message)
                finding(ReapVerdict.DELETE_FAILED, "not sent (transient), retryable: ${result.message}")
            }
            is PortResult.Unknown -> {
                settle(go, "UNKNOWN", "UNKNOWN", result.message)
                finding(ReapVerdict.DELETE_UNKNOWN, "outcome unknown, recorded as UNKNOWN and never resent: ${result.message}")
            }
            is PortResult.IdentityMismatch -> {
                settle(go, "UNKNOWN", "IDENTITY_MISMATCH", "expected ${result.expected}, got ${result.actual}")
                finding(ReapVerdict.DELETE_UNKNOWN, "response identity mismatch, recorded as UNKNOWN and never resent")
            }
            else -> {
                settle(go, "FAILED", "REJECTED", result.toString())
                finding(ReapVerdict.DELETE_FAILED, "refused: $result")
            }
        }
    }

    private fun settle(go: Claim.Go, status: String, errorClass: String?, message: String?) {
        val moved = transaction(database) {
            execUpdate(
                """
                UPDATE external_operations
                   SET status = ?, last_error_class = ?, last_error = ?, version = version + 1, updated_at = ?
                 WHERE op_id = ? AND version = ?
                """.trimIndent(),
                status, errorClass, message?.take(4000), utcNow(clock), go.opId, go.version,
            )
        }
        check(moved == 1) { "journal record ${go.opId} changed while its deletion was in flight" }
    }
}
