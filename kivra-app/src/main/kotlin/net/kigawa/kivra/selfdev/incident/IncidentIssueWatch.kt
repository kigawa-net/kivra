package net.kigawa.kivra.selfdev.incident

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import org.slf4j.LoggerFactory

/**
 * Files one GitHub issue per cause of a self-development failure (issue #225, the first stage of self-healing). Every tick it reads the
 * failed and unknown self-development Runs of the window and the breaker, and files an incident for each cause that passes its rule:
 * RUN_FAILED at the configured number of failures with the same reason, RUN_UNKNOWN at one, BREAKER_OPEN while the breaker is open.
 *
 * It only reads the database and only writes one thing to GitHub: [GitHubPort.createIssue]. It never comments, edits, closes, labels,
 * merges or approves, and it does not start a Run (that is #226). The issue is built from fixed codes and counts (see
 * [renderIncidentIssue]).
 *
 * Idempotency: the claim of the fingerprint is written before the issue is created. A cause has one claim per window, so a repeated
 * tick, a second Pod or a lost response never files a second issue in the window. Limit: [IncidentIssueSettings.maxPerHour] claims in
 * the clock hour, counted in the claim table (a failed or unknown create counts too, so a broken GitHub cannot loop). A failure never
 * stops the other causes; logs carry the codes and counts only.
 *
 * This runs in the Kivra process. A Kivra that is down cannot file its own failure: an external watchdog is a separate stage.
 */
class IncidentIssueWatch(
    private val settings: IncidentIssueSettings,
    private val store: IncidentStore,
    private val source: IncidentSource,
    private val github: GitHubPort,
    private val repo: RepoRef,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = INTERVAL,
) {
    /** What one cause came to: an issue opened, a cause not opened (inside its window, or the create did not succeed), or the hourly limit. */
    private enum class Step { OPENED, NOT_OPENED, HOURLY_LIMIT }

    /** One pass. Returns how many issues were opened. A disabled watch reads nothing and calls nothing. */
    suspend fun tick(): Int {
        if (!settings.enabled) return 0
        val now = clock.instant()
        val window = Duration.ofHours(settings.windowHours.toLong())
        val incidents = try {
            detect(now.minus(window))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("incident: detection failed, exception={}", e.javaClass.simpleName)
            return 0
        }
        var opened = 0
        for (incident in incidents) {
            try {
                when (fileOne(incident, now, window)) {
                    Step.OPENED -> opened++
                    Step.NOT_OPENED -> Unit
                    Step.HOURLY_LIMIT -> {
                        logger.info("incident: hourly limit reached, pass ends")
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("incident: filing failed, code={}, exception={}", incident.code.name, e.javaClass.simpleName)
            }
        }
        return opened
    }

    /** Runs [tick] every [interval] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("incident: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(interval.toMillis())
        }
    }

    /** The incidents that pass their rule now, in a stable order. Runs of other kinds (state not FAILED or UNKNOWN) are not counted. */
    internal fun detect(since: Instant): List<Incident> {
        val counts = LinkedHashMap<Pair<IncidentCode, String>, Long>()
        for (failure in source.failedRunCounts(since)) {
            val code = when (failure.state) {
                "FAILED" -> IncidentCode.RUN_FAILED
                "UNKNOWN" -> IncidentCode.RUN_UNKNOWN
                else -> continue
            }
            val key = code to fixedReasonCode(failure.reason)
            counts[key] = (counts[key] ?: 0L) + failure.count
        }
        val incidents = counts.mapNotNull { (key, count) ->
            val (code, reason) = key
            val threshold = if (code == IncidentCode.RUN_FAILED) settings.minFailedRuns.toLong() else 1L
            if (count >= threshold) Incident(code, reason, count) else null
        }.toMutableList()
        if (source.breakerOpen()) incidents += Incident(IncidentCode.BREAKER_OPEN, REASON_NONE, 1)
        return incidents.sortedWith(compareBy({ it.code.ordinal }, { it.reason }))
    }

    private suspend fun fileOne(incident: Incident, now: Instant, window: Duration): Step {
        val fingerprint = incident.fingerprint(repo)
        if (store.issuesSince(now.minus(Duration.ofHours(1))) >= settings.maxPerHour) return Step.HOURLY_LIMIT
        if (!store.claim(fingerprint, incident, now.minus(window))) return Step.NOT_OPENED

        val request = renderIncidentIssue(incident, repo, settings.windowHours, fingerprint)
        // The issue is created once. A failure or an unknown outcome is recorded and never re-sent inside the window.
        return when (val r = github.createIssue(repo, request)) {
            is GitHubResult.Success -> {
                store.finish(fingerprint, IncidentState.OPENED, r.value)
                logger.info("incident: issue opened, code={}, reason={}, count={}", incident.code.name, incident.reason, incident.count)
                Step.OPENED
            }
            is GitHubResult.Failure -> {
                store.finish(fingerprint, IncidentState.FAILED, null)
                logger.warn("incident: issue not created, code={}, failure={}", incident.code.name, r.kind.name)
                Step.NOT_OPENED
            }
            is GitHubResult.Unknown -> {
                store.finish(fingerprint, IncidentState.UNKNOWN, null)
                logger.warn("incident: issue outcome unknown, code={}", incident.code.name)
                Step.NOT_OPENED
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IncidentIssueWatch::class.java)

        /** One detection pass every five minutes. */
        val INTERVAL: Duration = Duration.ofSeconds(300)
    }
}
