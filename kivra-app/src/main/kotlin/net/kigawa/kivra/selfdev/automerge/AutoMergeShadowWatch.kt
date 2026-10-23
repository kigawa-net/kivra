package net.kigawa.kivra.selfdev.automerge

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.AutopilotSettings
import org.slf4j.LoggerFactory

/**
 * The shadow judgement of the automatic merge (ADR 0014 S1). Every tick it judges up to [MAX_PRS_PER_TICK] pull request Runs with
 * [decideAutoMerge] and records the verdict in the audit. It never merges, labels, comments or moves a branch: the port has no such method.
 *
 * A verdict is recorded only when it changed for the Run: the same (head SHA, verdict, reason) is not written again. The memory of the
 * last verdict is in process, so a restart records the current verdict once more. That repeat is accepted.
 *
 * Logs carry fixed codes and counts only.
 */
class AutoMergeShadowWatch(
    private val store: AutoMergeShadowStore,
    port: AutoMergeShadowPort,
    private val settings: AutoMergeShadowSettings,
    private val clock: Clock = Clock.systemUTC(),
    /** How often a tick runs. The default is ADR 0014's one minute. */
    private val interval: Duration = INTERVAL,
) {
    private val assembler = AutoMergeFactsAssembler(port, store, settings, clock)

    /** The last recorded (head SHA, verdict and reason) of each Run. Pruned to the Runs of the latest tick. */
    private val lastRecorded = HashMap<RunId, Pair<String, String>>()

    /**
     * One pass. Returns how many judgements were recorded. Blocking on the database; the GitHub reads suspend.
     * A failure in one Run is logged by code and does not stop the others.
     */
    suspend fun tick(): Int {
        val global = store.globalSnapshot()
        val runIds = store.pullRequestRunIds(MAX_PRS_PER_TICK)
        // While the automatic merge is paused (ADR 0014 U-10) the pass judges nothing and reads nothing from GitHub. It records one
        // skip per Run and per change of state, so the audit shows the pause without a record for every tick.
        if (global.autoMergePaused) return recordPaused(runIds)
        val meter = ShadowReadMeter(MAX_READS_PER_TICK)
        val main = assembler.mainCi(meter)
        var recorded = 0
        for (runId in runIds) {
            try {
                val judgement = assembler.judge(runId, meter, main, global) ?: continue
                val key = judgement.headSha to judgement.code
                if (lastRecorded[runId] == key) continue
                store.recordJudgement(runId, judgement.verdict, judgement.payload())
                lastRecorded[runId] = key
                recorded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("automerge shadow: judgement failed, exception={}", e.javaClass.simpleName)
            }
        }
        lastRecorded.keys.retainAll(runIds.toSet())
        if (recorded > 0) logger.info("automerge shadow: recorded count={}", recorded)
        return recorded
    }

    /** Runs [tick] every [interval] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("automerge shadow: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(interval.toMillis())
        }
    }

    private fun recordPaused(runIds: List<RunId>): Int {
        var recorded = 0
        for (runId in runIds) {
            try {
                if (lastRecorded[runId] == PAUSED_KEY) continue
                store.recordPaused(runId)
                lastRecorded[runId] = PAUSED_KEY
                recorded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("automerge shadow: paused record failed, exception={}", e.javaClass.simpleName)
            }
        }
        lastRecorded.keys.retainAll(runIds.toSet())
        if (recorded > 0) logger.info("automerge shadow: paused, recorded count={}", recorded)
        return recorded
    }

    private fun ShadowJudgement.payload(): String = buildJsonObject {
        put("prNumber", prNumber)
        put("headSha", headSha)
        put("verdict", verdict)
        put("reason", reason)
        put("files", files)
        put("lines", lines)
        put("checks", buildJsonArray { checks.forEach { add(JsonPrimitive(it)) } })
        put("readFailures", buildJsonArray { readFailures.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    companion object {
        private val logger = LoggerFactory.getLogger(AutoMergeShadowWatch::class.java)

        /** The memory of a paused record, in the same shape as a judgement's (head SHA, code). No judgement has this code. */
        private val PAUSED_KEY = "" to "PAUSED"

        /** ADR 0014 S1: one judgement pass a minute. */
        val INTERVAL: Duration = Duration.ofSeconds(60)

        /** The most pull request Runs one pass judges (oldest first). */
        const val MAX_PRS_PER_TICK = 5

        /**
         * The most GitHub port calls one pass makes. The worst case of [MAX_PRS_PER_TICK] Runs is about 32 (main 2, and per PR up to 6);
         * the cap is a guard, and a read past it fails as unreadable.
         */
        const val MAX_READS_PER_TICK = 40
    }
}

/**
 * The shadow watch, or null when the shadow switch is off. The switch is read only when the autopilot is on, because the autopilot's config
 * is absent otherwise (see [AutopilotSettings]), so a disabled autopilot starts nothing and reads nothing here.
 */
fun buildAutoMergeShadowWatch(
    autopilot: AutopilotSettings,
    store: AutoMergeShadowStore,
    port: AutoMergeShadowPort,
    repo: RepoRef,
    baseBranch: String,
    botAuthorId: Long,
): AutoMergeShadowWatch? {
    val config = autopilot.config?.takeIf { autopilot.enabled && it.autoMergeShadow } ?: return null
    return AutoMergeShadowWatch(
        store = store,
        port = port,
        settings = AutoMergeShadowSettings(
            repo = repo,
            baseBranch = baseBranch,
            botAuthorId = botAuthorId,
            requiredChecks = config.autoMergeRequiredChecks,
        ),
    )
}
