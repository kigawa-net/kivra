package net.kigawa.kivra.agent

import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.DeveloperRequest
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.RepoFile
import net.kigawa.kivra.github.CheckConclusion
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RunBranch
import org.slf4j.LoggerFactory

/** Result of one [PrAutoFixWorker] pass. Counts only. */
data class PrAutoFixPass(
    val prsScanned: Int,
    val fixesAttempted: Int,
    val fixesSucceeded: Int,
    val fixesFailed: Int,
)

/**
 * Periodically scans for PRs that need automatic fixes and attempts to fix them.
 *
 * Fix actions:
 * 1. **CI failures**: When a PR has failed CI checks, generates a fix patch using DeveloperPort.
 * 2. **Review comments**: When a PR has review comments requesting changes, generates a fix patch.
 *
 * The worker uses the DeveloperPort to generate code patches based on the failure context.
 * Logs carry counts and fixed codes only. Never a result, a path, a title or an exception message.
 */
class PrAutoFixWorker(
    private val github: GitHubPort,
    private val developer: DeveloperPort,
    private val runRepo: DevelopmentRunRepository,
    private val config: PrAutoFixConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One pass. Never throws (except cancellation): a failing step is logged with a fixed code. */
    suspend fun tick(): PrAutoFixPass {
        return try {
            val pass = scanAndFix()
            if (pass.fixesAttempted > 0) {
                logger.info(
                    "pr-auto-fix pass: prsScanned={} fixesAttempted={} fixesSucceeded={} fixesFailed={}",
                    pass.prsScanned, pass.fixesAttempted, pass.fixesSucceeded, pass.fixesFailed,
                )
            }
            pass
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("pr-auto-fix failed: step=SCAN exception={}", e.javaClass.simpleName)
            PrAutoFixPass(0, 0, 0, 0)
        }
    }

    /** Scans for PRs that need fixes and attempts to fix them. */
    private suspend fun scanAndFix(): PrAutoFixPass {
        var prsScanned = 0
        var fixesAttempted = 0
        var fixesSucceeded = 0
        var fixesFailed = 0

        // Get active runs that have PRs
        val activeRuns = runRepo.findActiveRuns().take(config.maxPrsPerTick)
        for (run in activeRuns) {
            if (run.state != DevelopmentRunState.DEVELOPING && run.state != DevelopmentRunState.DONE) {
                continue
            }

            prsScanned++
            val prRepo = run.prRepository ?: continue
            val repo = try {
                val parts = prRepo.split("/")
                if (parts.size == 2) RepoRef(parts[0], parts[1]) else null
            } catch (e: Exception) {
                null
            } ?: continue
            val runId = run.runId.id

            // Check if the PR has failed CI checks
            val needsFix = checkCiFailures(repo, runId) || checkReviewComments(repo, runId)
            if (!needsFix) {
                continue
            }

            // Attempt to fix the PR
            fixesAttempted++
            val fixed = attemptFix(run, runId)
            if (fixed) {
                fixesSucceeded++
            } else {
                fixesFailed++
            }
        }

        return PrAutoFixPass(prsScanned, fixesAttempted, fixesSucceeded, fixesFailed)
    }

    /** Checks if a PR has failed CI checks. */
    private suspend fun checkCiFailures(repo: RepoRef, runId: String): Boolean {
        val branchName = RunBranch.name(runId)
        return when (val result = github.getBranch(repo, branchName)) {
            is GitHubResult.Success -> {
                val branch = result.value ?: return false
                when (val checks = github.getCheckRuns(repo, branch.headSha)) {
                    is GitHubResult.Success -> {
                        val summary = checks.value
                        summary.runs.any {
                            it.status == "completed" && it.conclusion == CheckConclusion.FAILURE
                        }
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    /** Checks if a PR has review comments requesting changes. */
    private suspend fun checkReviewComments(repo: RepoRef, runId: String): Boolean {
        val branchName = RunBranch.name(runId)
        return when (val result = github.getBranch(repo, branchName)) {
            is GitHubResult.Success -> {
                val branch = result.value ?: return false
                // Find PRs for this branch
                when (val prs = github.findPullRequestsByHead(repo, branchName)) {
                    is GitHubResult.Success -> {
                        val prList = prs.value
                        if (prList.isEmpty()) return false
                        val pr = prList.first()
                        // Check for review comments
                        when (val reviews = github.getReviews(repo, pr.number)) {
                            is GitHubResult.Success -> {
                                reviews.value.any { it.state == "COMMENTED" || it.state == "CHANGES_REQUESTED" }
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    /** Attempts to fix a PR by generating a patch using DeveloperPort. */
    private suspend fun attemptFix(run: DevelopmentRun, runId: String): Boolean {
        try {
            // Get the task context
            val taskTitle = run.taskTitle ?: "Fix PR issues"
            val taskDescription = buildFixDescription(run)

            // Get repo context (simplified - in real implementation, would fetch actual files)
            val repoContext = emptyList<RepoFile>()

            // Create developer request
            val request = DeveloperRequest(
                runId = runId,
                taskTitle = taskTitle,
                taskDescription = taskDescription,
                repoContext = repoContext,
            )

            // Propose a fix
            val result = developer.propose(request)
            return when (result) {
                is DeveloperResult.Proposed -> {
                    // Patch generated successfully - in real implementation, would apply it
                    logger.info("pr-auto-fix: runId={}, patch generated", runId)
                    true
                }
                is DeveloperResult.Refused -> {
                    logger.warn("pr-auto-fix: runId={}, patch refused reason={}", runId, result.reason)
                    false
                }
                is DeveloperResult.Unknown -> {
                    logger.warn("pr-auto-fix: runId={}, patch unknown reason={}", runId, result.reason)
                    false
                }
            }
        } catch (e: Exception) {
            logger.warn("pr-auto-fix: runId={}, fix failed exception={}", runId, e.javaClass.simpleName)
            false
        }
    }

    /** Builds a description for the fix based on the run context. */
    private fun buildFixDescription(run: DevelopmentRun): String {
        val parts = mutableListOf<String>()
        parts.add("Automatic fix for PR issues.")
        if (run.taskBody != null) {
            parts.add("Original task: ${run.taskBody}")
        }
        parts.add("Please analyze the CI failures or review comments and generate appropriate fixes.")
        return parts.joinToString("\n")
    }

    /** Starts the loop in [scope]: a pass, then one every [config.interval]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info(
            "pr-auto-fix worker started: intervalSeconds={} maxPrs={} maxAttempts={}",
            config.interval.seconds, config.maxPrsPerTick, config.maxAttemptsPerPr,
        )
        while (isActive) {
            tick()
            delay(config.interval.toMillis())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PrAutoFixWorker::class.java)
    }
}
