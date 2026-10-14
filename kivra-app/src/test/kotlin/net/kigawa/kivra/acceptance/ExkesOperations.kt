package net.kigawa.kivra.acceptance

import kotlin.coroutines.cancellation.CancellationException
import net.kigawa.kivra.agent.Observed
import net.kigawa.kivra.agent.Op
import net.kigawa.kivra.agent.sha256Hex
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.PrepareOperation
import net.kigawa.kivra.exkes.ExecutionPort
import net.kigawa.kivra.exkes.PortResult
import net.kigawa.kivra.exkes.Runtime
import net.kigawa.kivra.exkes.RuntimeSpec
import net.kigawa.kivra.exkes.WorkspaceId

/** Where a simulated worker crash happens, relative to the provider call. Crashes are exceptions, never sleeps. */
enum class Crash { BEFORE_SEND, AFTER_SEND }

/**
 * The runtime-create operation of a run, as the Operation Journal sees it: idempotency key and stable ref are both derived from
 * the run id, so every worker builds the same row. [crash] simulates the worker dying (CancellationException leaves the row
 * IN_FLIGHT, exactly as a real crash does).
 */
fun AcceptanceRun.runtimeCreateOp(runId: RunId, workspace: String, crash: Crash? = null): Op<Runtime> {
    val labels = mapOf("kivra.run-id" to runId.value)
    val wsId = WorkspaceId(workspace)
    val spec = RuntimeSpec(templateId = "tpl-harness", labels = labels)
    val exkes: ExecutionPort = port
    val send: suspend () -> PortResult<Runtime> = {
        if (crash == Crash.BEFORE_SEND) throw CancellationException("simulated worker crash before the POST")
        val r = exkes.createRuntime(wsId, spec)
        if (crash == Crash.AFTER_SEND) throw CancellationException("simulated worker crash after the provider answered")
        r
    }
    return Op(
        opType = "EXKES_CREATE_RUNTIME",
        key = "run:${runId.value}:runtime",
        stableRef = runId.value,
        requestHash = sha256Hex("create-runtime:${runId.value}:$workspace"),
        call = send,
        refOf = { it.id.value },
        observe = {
            when (val r = exkes.findRuntimesByLabel(wsId, labels)) {
                is PortResult.Ok -> when (r.value.size) {
                    0 -> Observed.Absent(replaySafe = false)
                    1 -> Observed.Exists(r.value.single().id.value)
                    else -> Observed.Undetermined("AMBIGUOUS_LABELS")
                }
                else -> Observed.Undetermined("LOOKUP_FAILED")
            }
        },
    )
}

/** The journal row of [op] as a PREPARED write by [runId]'s lease, without dispatching (a worker that died right after). */
fun AcceptanceRun.prepareOnly(lease: net.kigawa.kivra.core.Lease, op: Op<*>) = db.journal.prepare(
    lease,
    PrepareOperation(op.opType, op.key, "EXKES", op.stableRef, op.requestHash, AcceptanceRun.TENANT, AcceptanceRun.CONTRACT, AcceptanceRun.MAX_ATTEMPTS),
)
