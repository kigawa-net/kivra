package net.kigawa.kivra.agent

import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.DeveloperRequest
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.RepoFile
import org.slf4j.LoggerFactory

/** Result of one [CodeAutoFixWorker] pass. Counts only. */
data class CodeAutoFixPass(
    val issuesScanned: Int,
    val fixesAttempted: Int,
    val fixesSucceeded: Int,
    val fixesFailed: Int,
)

/**
 * Periodically scans for code issues and attempts to fix them automatically.
 *
 * Fix actions:
 * 1. **Compilation errors**: When the project has compilation errors, generates a fix patch using DeveloperPort.
 * 2. **Test failures**: When tests are failing, generates a fix patch using DeveloperPort.
 *
 * The worker uses the DeveloperPort to generate code patches based on the issue context.
 * Logs carry counts and fixed codes only. Never a result, a path, a title or an exception message.
 */
class CodeAutoFixWorker(
    private val developer: DeveloperPort,
    private val codeScanner: CodeIssueScanner,
    private val config: CodeAutoFixConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One pass. Never throws (except cancellation): a failing step is logged with a fixed code. */
    suspend fun tick(): CodeAutoFixPass {
        return try {
            val pass = scanAndFix()
            if (pass.fixesAttempted > 0) {
                logger.info(
                    "code-auto-fix pass: issuesScanned={} fixesAttempted={} fixesSucceeded={} fixesFailed={}",
                    pass.issuesScanned, pass.fixesAttempted, pass.fixesSucceeded, pass.fixesFailed,
                )
            }
            pass
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("code-auto-fix failed: step=SCAN exception={}", e.javaClass.simpleName)
            CodeAutoFixPass(0, 0, 0, 0)
        }
    }

    /** Scans for code issues and attempts to fix them. */
    private suspend fun scanAndFix(): CodeAutoFixPass {
        var issuesScanned = 0
        var fixesAttempted = 0
        var fixesSucceeded = 0
        var fixesFailed = 0

        // Collect issues from scanner
        val issues = mutableListOf<CodeIssue>()
        if (config.scanCompilationErrors) {
            issues.addAll(codeScanner.getCompilationErrors())
        }
        if (config.scanTestFailures) {
            issues.addAll(codeScanner.getTestFailures())
        }

        // Process up to maxIssuesPerTick
        val issuesToProcess = issues.take(config.maxIssuesPerTick)
        for (issue in issuesToProcess) {
            issuesScanned++

            // Attempt to fix the issue
            fixesAttempted++
            val fixed = attemptFix(issue)
            if (fixed) {
                fixesSucceeded++
            } else {
                fixesFailed++
            }
        }

        return CodeAutoFixPass(issuesScanned, fixesAttempted, fixesSucceeded, fixesFailed)
    }

    /** Attempts to fix a code issue by generating a patch using DeveloperPort. */
    private suspend fun attemptFix(issue: CodeIssue): Boolean {
        try {
            // Build task description from the issue
            val taskTitle = "Auto-fix: ${issue.type.name}"
            val taskDescription = buildFixDescription(issue)

            // Get repo context (files related to the issue)
            val repoContext = issue.relatedFiles.map { path ->
                RepoFile(path, codeScanner.readFileContent(path))
            }

            // Create developer request
            val request = DeveloperRequest(
                runId = "code-auto-fix-${issue.id}",
                taskTitle = taskTitle,
                taskDescription = taskDescription,
                repoContext = repoContext,
            )

            // Propose a fix
            val result = developer.propose(request)
            return when (result) {
                is DeveloperResult.Proposed -> {
                    // Patch generated successfully - in real implementation, would apply it
                    logger.info("code-auto-fix: issue={}, patch generated", issue.id)
                    true
                }
                is DeveloperResult.Refused -> {
                    logger.warn("code-auto-fix: issue={}, patch refused reason={}", issue.id, result.reason)
                    false
                }
                is DeveloperResult.Unknown -> {
                    logger.warn("code-auto-fix: issue={}, patch unknown reason={}", issue.id, result.reason)
                    false
                }
            }
        } catch (e: Exception) {
            logger.warn("code-auto-fix: issue={}, fix failed exception={}", issue.id, e.javaClass.simpleName)
            false
        }
    }

    /** Builds a description for the fix based on the issue context. */
    private fun buildFixDescription(issue: CodeIssue): String {
        val parts = mutableListOf<String>()
        parts.add("Automatic fix for code issue.")
        parts.add("Issue type: ${issue.type.name}")
        parts.add("Message: ${issue.message}")
        if (issue.filePath != null) {
            parts.add("File: ${issue.filePath}")
        }
        if (issue.lineNumber != null) {
            parts.add("Line: ${issue.lineNumber}")
        }
        parts.add("Please analyze the issue and generate appropriate fixes.")
        return parts.joinToString("\n")
    }

    /** Starts the loop in [scope]: a pass, then one every [config.interval]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info(
            "code-auto-fix worker started: intervalSeconds={} maxIssues={} maxAttempts={}",
            config.interval.seconds, config.maxIssuesPerTick, config.maxAttemptsPerIssue,
        )
        while (isActive) {
            tick()
            delay(config.interval.toMillis())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CodeAutoFixWorker::class.java)
    }
}

/** Represents a code issue that needs to be fixed. */
data class CodeIssue(
    val id: String,
    val type: IssueType,
    val message: String,
    val filePath: String? = null,
    val lineNumber: Int? = null,
    val relatedFiles: List<String> = emptyList(),
)

/** Types of code issues. */
enum class IssueType {
    COMPILATION_ERROR,
    TEST_FAILURE,
}

/** Interface for scanning code issues. */
interface CodeIssueScanner {
    /** Get compilation errors. */
    fun getCompilationErrors(): List<CodeIssue>

    /** Get test failures. */
    fun getTestFailures(): List<CodeIssue>

    /** Read file content for repo context. */
    fun readFileContent(path: String): String
}

/**
 * A no-op implementation of [CodeIssueScanner] that returns empty lists.
 * Used when code issue scanning is not available or disabled.
 */
object NoOpCodeIssueScanner : CodeIssueScanner {
    override fun getCompilationErrors(): List<CodeIssue> = emptyList()
    override fun getTestFailures(): List<CodeIssue> = emptyList()
    override fun readFileContent(path: String): String = ""
}
