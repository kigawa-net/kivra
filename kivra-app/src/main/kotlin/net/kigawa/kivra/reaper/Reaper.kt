package net.kigawa.kivra.reaper

import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import net.kigawa.kivra.exkes.WorkspaceId
import net.kigawa.kivra.exkes.WorkspaceState
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
 * last_error_class of a deletion that exkes accepted (202) or answered with 404. Recorded as UNKNOWN: it is requested,
 * not proven. It is verified gone on a later pass and never resent.
 */
private const val CLASS_DELETE_REQUESTED = "DELETE_REQUESTED"

/**
 * Reaper settings. Safe by default: detection only.
 *
 * - [dryRun] (default true): nothing is deleted and nothing is written to the Journal.
 * - [autoDeleteEnabled] (default false): when set and [dryRun] is false, every orphan that passes all gates is deleted.
 *   Production should keep this off until exkes offers a conditional (generation / empty-only) delete.
 * - [approvals]: a human approval of one specific deletion. When [dryRun] is false, an orphan may be deleted without
 *   [autoDeleteEnabled] only if this port approves its exact [ReapApprovalRequest]. The default approves nothing.
 */
data class ReaperConfig(
    val dryRun: Boolean = true,
    val autoDeleteEnabled: Boolean = false,
    val approvals: ReapApprovalPort = DenyAllReapApprovals,
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

/** The exact deletion a human approves. [digest] binds an approval to workspace, run, labels and action. */
data class ReapApprovalRequest(
    val workspaceId: String,
    val workspaceName: String,
    val runId: String,
    val action: String,
    val digest: String,
)

/**
 * Verifies a human approval of one specific deletion. A real implementation must check an authenticated approver, the
 * approver's independence from the Run, the digest and expiry, and one-shot consumption. That is the #55 approval
 * foundation and is NOT wired here. A throwing port counts as "not approved".
 */
fun interface ReapApprovalPort {
    suspend fun isApproved(request: ReapApprovalRequest): Boolean
}

/** Default: nothing is approved, so an orphan is only ever a candidate unless auto-delete is explicitly on. */
object DenyAllReapApprovals : ReapApprovalPort {
    override suspend fun isApproved(request: ReapApprovalRequest): Boolean = false
}

enum class ReapVerdict {
    /** The Run is not terminal: the workspace belongs to live work. Not an orphan. */
    IN_USE,

    /**
     * The Run is not terminal, its lease owner stopped renewing (lease expired, heartbeat lapsed), and no live lease holds it.
     * This is the LOST candidate of design #3 §4.1 / §6.3. Reported only: nothing is deleted and the Run state is not changed
     * (there is no LOST state in [DevelopmentRunState]). Reconciliation against exkes decides what happens next.
     */
    LEASE_LAPSED,

    /** The Run is terminal but inside the retention grace. */
    RETENTION,

    /** An orphan that is not deletable right now (live lease, hold, live runtimes, runtime listing failed). */
    BLOCKED,

    /** An orphan that passes every gate but is not deleted (dry-run, or not approved). Human decision needed. */
    CANDIDATE,

    /** Cannot be verified (no Run record, no Journal proof of creation, marker mismatch). Reported only, never deleted. */
    QUARANTINE,

    /**
     * exkes accepted the deletion (202) or answered 404 to it. This is a request, not a completed deletion: the workspace
     * is verified gone on a later pass (DELETED) or reported as STILL_PRESENT. It is never resent.
     */
    DELETE_REQUESTED,

    /** A requested deletion was verified: absent from a complete listing AND 404 on GET. */
    DELETED,

    /** A requested deletion whose workspace is still listed, or could not be verified. Reported only, never resent. */
    STILL_PRESENT,

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

/**
 * [listingFailed] is set when exkes could not list workspaces (any page failing counts as a failed listing). Nothing
 * was evaluated, so nothing was touched.
 */
data class ReapReport(val findings: List<ReapFinding>, val listingFailed: String? = null)

/**
 * Kind-only description of a port result. Provider message text is not copied into findings or the Journal, since it
 * may contain sensitive data.
 */
internal fun describe(r: PortResult<*>): String = when (r) {
    is PortResult.Ok -> "ok"
    PortResult.NotFound -> "not found"
    is PortResult.Conflict -> "conflict"
    is PortResult.Rejected -> "rejected (HTTP ${r.httpStatus})"
    is PortResult.Transient -> "transient failure"
    is PortResult.Retryable -> "retryable failure"
    is PortResult.Unknown -> "outcome unknown"
    is PortResult.IdentityMismatch -> "identity mismatch"
    is PortResult.Unsupported -> "unsupported: ${r.capability}"
}

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
 * Deletion protocol (one deletion = one journal record, key `reap:<workspaceId>`):
 *  1. claim (Run row lock): re-check the Run gates, then write a PREPARED record. PREPARED means nothing was sent.
 *  2. re-check the workspace's runtimes (exkes read, before any mutation).
 *  3. arm (Run row lock): re-check the Run gates (terminal, hold, lease, retention) and compare-and-set PREPARED -> IN_FLIGHT
 *     on the record version. Only the winner of this CAS sends the DELETE, so two Reapers delete at most once.
 *  4. DELETE. A 202 or 404 is recorded as UNKNOWN with [CLASS_DELETE_REQUESTED] and is NOT completion. The next pass
 *     verifies it: the workspace must be absent from a complete listing AND answer 404 on GET, then it is CONFIRMED
 *     (DELETED). If it is still listed, the verdict is STILL_PRESENT and nothing is resent.
 *  Any other outcome of the DELETE is UNKNOWN and is never resent. A record that is not PREPARED / RETRYABLE is never
 *  dispatched again.
 *
 * Residual windows (documented, not closed here):
 *  - exkes has no conditional DELETE (no generation / empty-only precondition) and no fencing of new Runtime creation.
 *    A runtime created or a Run changed between step 3 and the DELETE reaching exkes is not prevented. Only the
 *    Kivra-side Run row lock is held. Production auto-delete must stay off until exkes provides such a precondition.
 *  - the Run gates are read from the Kivra DB: a Run lease owned by a different Kivra worker is seen, but exkes-side
 *    work that Kivra does not know about is only seen through the runtime listing.
 *  - retention is measured from the Run's `updated_at`. Every state write refreshes it, so it is never earlier than the
 *    terminal transition (it errs on the safe side). A dedicated terminal_at column is a follow-up.
 *  - a 404 on GET is a "gone" signal from exkes. It is combined with absence from a complete listing, and does not
 *    distinguish visibility or permission problems on their own.
 */
class Reaper(
    private val port: ExecutionPort,
    private val database: Database,
    private val config: ReaperConfig = ReaperConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One detection pass, plus deletions the config allows. Idempotent: a repeated pass finds the same state or less. */
    suspend fun runOnce(): ReapReport {
        // Any failing page counts as a failed listing: a partial listing is never treated as complete. A thrown listing
        // is the same as a failed one: nothing is evaluated, nothing is touched.
        val listed = try {
            when (val r = port.listWorkspaces()) {
                is PortResult.Ok -> r.value
                else -> return ReapReport(emptyList(), listingFailed = describe(r))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReapReport(emptyList(), listingFailed = "listing threw: ${e::class.simpleName}")
        }
        val listedIds = listed.mapTo(HashSet()) { it.id.value }
        val listedNames = listed.mapTo(HashSet()) { it.name }
        val findings = ArrayList<ReapFinding>()
        // Requested deletions no longer in a complete listing: verify them gone before reporting DELETED.
        for (req in requestedDeletions().filter { it.workspaceId !in listedIds }) {
            findings += verifyGone(req)
        }
        for (ws in listed.sortedBy { it.name }) {
            val runId = KivraNaming.runIdOfWorkspaceName(ws.name) ?: continue // not a Kivra name: never touched
            val finding = try {
                review(ws, runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReapFinding(ws.name, ws.id.value, runId, ReapVerdict.QUARANTINE, "review failed: ${e::class.simpleName}")
            }
            findings += finding
        }
        // Runs whose heartbeat lapsed and that have no listed workspace (exkes side missing): LOST candidates, report only.
        // Runs with a listed workspace were already reported by review().
        for (runId in lapsedRunIds()) {
            val wsName = runCatching { KivraNaming.workspaceName(runId) }.getOrDefault("")
            if (wsName in listedNames) continue
            findings += ReapFinding(wsName, "", runId, ReapVerdict.LEASE_LAPSED, "lease expired and no workspace is listed for the run")
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
                onReport(ReapReport(emptyList(), listingFailed = "pass failed: ${e::class.simpleName}"))
            }
            delay(interval.toMillis())
        }
    }

    private suspend fun review(ws: Workspace, runId: String): ReapFinding {
        fun finding(verdict: ReapVerdict, reason: String) = ReapFinding(ws.name, ws.id.value, runId, verdict, reason)

        if (ws.labels[KivraNaming.RUN_ID_LABEL] != runId) {
            return finding(ReapVerdict.QUARANTINE, "marker label ${KivraNaming.RUN_ID_LABEL} is missing or differs from the name")
        }
        transaction(database) { deleteRecord(runId, ws.id.value) }?.let { record ->
            if (record.status == "UNKNOWN" && record.errorClass == CLASS_DELETE_REQUESTED) {
                return finding(ReapVerdict.STILL_PRESENT, "deletion was requested earlier and the workspace is still listed; not resent")
            }
            if (record.status == "CONFIRMED") {
                return finding(ReapVerdict.QUARANTINE, "Journal says this deletion is verified, but the workspace is still listed")
            }
        }
        val run = loadRun(runId) ?: return finding(ReapVerdict.QUARANTINE, "no DevelopmentRun record for $runId")
        if (!run.terminal) {
            return if (run.leaseLapsed) finding(ReapVerdict.LEASE_LAPSED, "run is ${run.state}; lease expired, heartbeat lapsed. Not deleted")
            else finding(ReapVerdict.IN_USE, "run is ${run.state}")
        }

        proofProblem(runId, ws)?.let { return finding(ReapVerdict.QUARANTINE, it) }
        if (!run.retentionElapsed) return finding(ReapVerdict.RETENTION, "run ${run.state} is inside the retention grace")
        run.hold?.let { return finding(ReapVerdict.BLOCKED, "run is on hold ($it)") }
        if (run.liveLease) return finding(ReapVerdict.BLOCKED, "run still holds a live lease")

        when (val rts = port.listRuntimes(ws.id)) {
            is PortResult.Ok -> {
                val live = rts.value.filter { it.state !in NON_BLOCKING_RUNTIME_STATES }
                if (live.isNotEmpty()) return finding(ReapVerdict.BLOCKED, "${live.size} live runtime(s) remain in the workspace")
            }
            else -> return finding(ReapVerdict.BLOCKED, "runtime listing failed: ${describe(rts)}")
        }

        val autoAllowed = !config.dryRun && (config.autoDeleteEnabled || approved(ws, runId))
        if (!autoAllowed) {
            val why = if (config.dryRun) "dry-run" else "not approved and auto-delete is off"
            return finding(ReapVerdict.CANDIDATE, "orphan ($why)")
        }
        return delete(ws, runId)
    }

    /** A human approval of exactly this workspace, run, labels and action. A throwing port is "not approved". */
    private suspend fun approved(ws: Workspace, runId: String): Boolean {
        val digest = sha256Hex("reap-delete-workspace|${ws.id.value}|${ws.name}|$runId|${ws.labels.toSortedMap()}")
        val request = ReapApprovalRequest(ws.id.value, ws.name, runId, "DELETE_WORKSPACE", digest)
        return try {
            config.approvals.isApproved(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private class RunGate(
        val state: String,
        val hold: String?,
        val liveLease: Boolean,
        val retentionElapsed: Boolean,
        val leaseGeneration: Long,
        /** A lease owner is recorded but its expiry has passed: the heartbeat lapsed. */
        val leaseLapsed: Boolean,
    ) {
        val terminal: Boolean get() = state in DevelopmentRunState.terminalNames
    }

    private fun loadRun(runId: String): RunGate? = transaction(database) { readRunGate(runId, locked = false) }

    private fun Transaction.readRunGate(runId: String, locked: Boolean): RunGate? = execQuery(
        """
        SELECT state, hold_reason,
               CASE WHEN lease_owner IS NOT NULL AND lease_expires_at > ${DbTime.NOW} THEN 1 ELSE 0 END,
               CASE WHEN updated_at <= ${DbTime.nowMinusSeconds()} THEN 1 ELSE 0 END,
               lease_generation,
               CASE WHEN lease_owner IS NOT NULL AND lease_expires_at <= ${DbTime.NOW} THEN 1 ELSE 0 END
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
            leaseLapsed = rs.getInt(6) == 1,
        )
    }.singleOrNull()

    /**
     * Ids of non-terminal runs that once had a lease owner and whose lease has expired (heartbeat lapsed, no renewal).
     * A run that never acquired a lease is not listed: it has no heartbeat to lapse.
     */
    private fun lapsedRunIds(): List<String> = transaction(database) {
        execQuery(
            "SELECT run_id, state FROM development_runs WHERE lease_owner IS NOT NULL AND lease_expires_at <= ${DbTime.NOW}",
        ) { it.getString(1) to it.getString(2) }
    }.filter { (_, state) -> state !in DevelopmentRunState.terminalNames }.map { it.first }

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

    private class Existing(
        val opId: String,
        val status: String,
        val attempt: Int,
        val maxAttempts: Int,
        val version: Long,
        val errorClass: String?,
    )

    private fun Transaction.deleteRecord(runId: String, workspaceId: String): Existing? = execQuery(
        "SELECT op_id, status, attempt, max_attempts, version, last_error_class FROM external_operations WHERE run_id = ? AND op_type = ? AND idempotency_key = ?",
        runId, OP_REAP_DELETE_WORKSPACE, "reap:$workspaceId",
    ) { Existing(it.getString(1), it.getString(2), it.getInt(3), it.getInt(4), it.getLong(5), it.getString(6)) }.singleOrNull()

    private sealed interface Claim {
        /** [leaseGeneration] is the Run's lease generation read at the claim; arm refuses if it has changed since. */
        data class Go(val opId: String, val version: Long, val leaseGeneration: Long) : Claim
        data class Refused(val verdict: ReapVerdict, val reason: String) : Claim
    }

    /** Gate refusal for a Run row that is read under lock; null when the Run may still be deleted-for. */
    private fun gateRefusal(gate: RunGate?): Claim.Refused? {
        if (gate == null) return Claim.Refused(ReapVerdict.QUARANTINE, "run record vanished")
        if (!gate.terminal) return Claim.Refused(ReapVerdict.IN_USE, "run became ${gate.state}")
        gate.hold?.let { return Claim.Refused(ReapVerdict.BLOCKED, "run is on hold ($it)") }
        if (gate.liveLease) return Claim.Refused(ReapVerdict.BLOCKED, "run holds a live lease")
        if (!gate.retentionElapsed) return Claim.Refused(ReapVerdict.RETENTION, "run is inside the retention grace")
        return null
    }

    /** Step 1 (Run row lock): gates, then a PREPARED record. Nothing is sent. An existing PREPARED record is reused. */
    private fun claim(runId: String, ws: Workspace): Claim = transaction(database) {
        val gate = readRunGate(runId, locked = true)
        gateRefusal(gate)?.let { return@transaction it }
        val generation = gate!!.leaseGeneration
        val existing = deleteRecord(runId, ws.id.value)
        val now = utcNow(clock)
        when {
            existing == null -> {
                val opId = Ulid.generate(clock)
                execUpdate(
                    """
                    INSERT INTO external_operations
                        (op_id, run_id, op_type, idempotency_key, external_system, stable_ref, external_ref, request_hash,
                         status, attempt, max_attempts, lease_generation, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'EXKES', ?, ?, ?, 'PREPARED', 0, ?, ?, 0, ?, ?)
                    """.trimIndent(),
                    opId, runId, OP_REAP_DELETE_WORKSPACE, "reap:${ws.id.value}", ws.name, ws.id.value,
                    sha256Hex("reap-delete-workspace|${ws.id.value}"), config.maxDeleteAttempts, gate!!.leaseGeneration, now, now,
                )
                Claim.Go(opId, 0, generation)
            }
            existing.status == "PREPARED" || (existing.status == "RETRYABLE" && existing.attempt < existing.maxAttempts) ->
                Claim.Go(existing.opId, existing.version, generation)
            existing.status == "CONFIRMED" -> Claim.Refused(ReapVerdict.JOURNALED, "already deleted: Journal record is CONFIRMED")
            else -> Claim.Refused(
                ReapVerdict.JOURNALED,
                "Journal record is ${existing.status} (attempt ${existing.attempt}/${existing.maxAttempts}); not dispatched again",
            )
        }
    }

    /** Step 3 (Run row lock): gates again, then CAS PREPARED/RETRYABLE -> IN_FLIGHT on the record version. Only the CAS winner sends. */
    private fun arm(runId: String, go: Claim.Go): Claim = transaction(database) {
        val current = readRunGate(runId, locked = true)
        gateRefusal(current)?.let { return@transaction it }
        // Fencing: the lease generation read at the claim must still be the current one. A lease change in between means the
        // judgment is stale, so nothing is sent.
        if (current!!.leaseGeneration != go.leaseGeneration) {
            return@transaction Claim.Refused(
                ReapVerdict.BLOCKED,
                "run lease generation changed since the claim (${go.leaseGeneration} -> ${current.leaseGeneration}); nothing sent",
            )
        }
        val moved = execUpdate(
            """
            UPDATE external_operations
               SET status = 'IN_FLIGHT', attempt = attempt + 1, version = version + 1, updated_at = ?
             WHERE op_id = ? AND version = ? AND status IN ('PREPARED', 'RETRYABLE') AND attempt < max_attempts
            """.trimIndent(),
            utcNow(clock), go.opId, go.version,
        )
        if (moved == 1) Claim.Go(go.opId, go.version + 1, go.leaseGeneration)
        else Claim.Refused(ReapVerdict.JOURNALED, "journal record changed concurrently; another Reaper owns this deletion")
    }

    private suspend fun delete(ws: Workspace, runId: String): ReapFinding {
        fun finding(verdict: ReapVerdict, reason: String) = ReapFinding(ws.name, ws.id.value, runId, verdict, reason)

        val prepared = when (val c = claim(runId, ws)) {
            is Claim.Go -> c
            is Claim.Refused -> return finding(c.verdict, c.reason)
        }
        // Step 2: runtimes again, after the claim. A refusal here leaves the PREPARED record: nothing was sent, and a later
        // pass re-claims it.
        when (val rts = port.listRuntimes(ws.id)) {
            is PortResult.Ok -> {
                val live = rts.value.count { it.state !in NON_BLOCKING_RUNTIME_STATES }
                if (live > 0) return finding(ReapVerdict.BLOCKED, "$live live runtime(s) appeared before the deletion; nothing sent")
            }
            else -> return finding(ReapVerdict.BLOCKED, "runtime re-check failed (${describe(rts)}); nothing sent")
        }
        // Step 3: the Run gates and the PREPARED -> IN_FLIGHT CAS. The journal row is IN_FLIGHT only after this commits.
        val go = when (val c = arm(runId, prepared)) {
            is Claim.Go -> c
            is Claim.Refused -> return finding(c.verdict, c.reason)
        }
        // Step 4: the DELETE. Its outcome is recorded before anything is returned.
        val result: PortResult<Workspace> = try {
            port.deleteWorkspace(ws.id)
        } catch (e: CancellationException) {
            throw e // the row stays IN_FLIGHT: it is never resent, a later pass only records it as JOURNALED
        } catch (e: Exception) {
            PortResult.Unknown("EXKES_DELETE_EXCEPTION")
        }
        return when (result) {
            is PortResult.Ok -> {
                settle(go, "UNKNOWN", CLASS_DELETE_REQUESTED, "exkes accepted the deletion; verified gone on a later pass")
                finding(ReapVerdict.DELETE_REQUESTED, "exkes accepted the deletion (${result.value.state}); not complete until observed gone")
            }
            // 404 on delete is not proof of deletion (it may be a visibility or permission problem): verified on a later pass.
            PortResult.NotFound -> {
                settle(go, "UNKNOWN", CLASS_DELETE_REQUESTED, "404 on delete; verified gone on a later pass")
                finding(ReapVerdict.DELETE_REQUESTED, "404 on delete: not treated as proof of deletion; verified on a later pass")
            }
            // Without a not-sent proof (no lease on a terminal Run), a transient failure may have reached exkes: UNKNOWN, never resent.
            is PortResult.Transient -> {
                settle(go, "UNKNOWN", "TRANSIENT_NOT_PROVEN", describe(result))
                finding(ReapVerdict.DELETE_UNKNOWN, "transient failure without a not-sent proof, recorded as UNKNOWN and never resent")
            }
            // A DELETE never returns Retryable (it is a mutation, so the adapter answers Unknown). Treated like Transient
            // should it ever appear: no proof of no effect, so recorded UNKNOWN and never resent.
            is PortResult.Retryable -> {
                settle(go, "UNKNOWN", "RETRYABLE_NOT_PROVEN", describe(result))
                finding(ReapVerdict.DELETE_UNKNOWN, "retryable failure without a not-sent proof, recorded as UNKNOWN and never resent")
            }
            is PortResult.Unknown -> {
                settle(go, "UNKNOWN", "UNKNOWN", describe(result))
                finding(ReapVerdict.DELETE_UNKNOWN, "outcome unknown, recorded as UNKNOWN and never resent")
            }
            is PortResult.IdentityMismatch -> {
                settle(go, "UNKNOWN", "IDENTITY_MISMATCH", describe(result))
                finding(ReapVerdict.DELETE_UNKNOWN, "response identity mismatch, recorded as UNKNOWN and never resent")
            }
            // Refused without verified REJECTED evidence: recorded UNKNOWN, never resent.
            else -> {
                settle(go, "UNKNOWN", "REJECTED_NOT_PROVEN", describe(result))
                finding(ReapVerdict.DELETE_UNKNOWN, "refused without verified rejected evidence, recorded as UNKNOWN and never resent: ${describe(result)}")
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

    private class Requested(val opId: String, val version: Long, val runId: String, val workspaceName: String, val workspaceId: String)

    /** Deletions that exkes accepted or answered 404 to, and that are not verified yet. */
    private fun requestedDeletions(): List<Requested> = transaction(database) {
        execQuery(
            "SELECT op_id, version, run_id, stable_ref, external_ref FROM external_operations WHERE op_type = ? AND status = 'UNKNOWN' AND last_error_class = ?",
            OP_REAP_DELETE_WORKSPACE, CLASS_DELETE_REQUESTED,
        ) { Requested(it.getString(1), it.getLong(2), it.getString(3), it.getString(4), it.getString(5)) }
    }

    /**
     * Verifies a requested deletion whose workspace is absent from a complete listing. DELETED needs absence from the
     * listing AND a 404 (or DELETED state) on GET. Anything else stays STILL_PRESENT. It is never resent.
     */
    private suspend fun verifyGone(req: Requested): ReapFinding {
        fun finding(verdict: ReapVerdict, reason: String) = ReapFinding(req.workspaceName, req.workspaceId, req.runId, verdict, reason)

        val gone = when (val r = port.getWorkspace(WorkspaceId(req.workspaceId))) {
            PortResult.NotFound -> true
            is PortResult.Ok -> r.value.state == WorkspaceState.DELETED
            else -> false
        }
        if (!gone) return finding(ReapVerdict.STILL_PRESENT, "absent from the listing but GET does not confirm deletion; not resent")
        val settled = transaction(database) {
            execUpdate(
                """
                UPDATE external_operations
                   SET status = 'CONFIRMED', last_error_class = NULL, last_error = ?, version = version + 1, updated_at = ?
                 WHERE op_id = ? AND version = ? AND status = 'UNKNOWN'
                """.trimIndent(),
                "verified gone: absent from a complete listing and not found on GET", utcNow(clock), req.opId, req.version,
            )
        }
        return if (settled == 1) finding(ReapVerdict.DELETED, "verified gone: absent from a complete listing and not found on GET")
        else finding(ReapVerdict.JOURNALED, "journal record changed concurrently during verification")
    }
}
