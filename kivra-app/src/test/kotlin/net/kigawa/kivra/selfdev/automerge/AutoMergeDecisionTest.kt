package net.kigawa.kivra.selfdev.automerge

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.selfdev.automerge.AutoMergeCheckConclusion as Conclusion
import net.kigawa.kivra.selfdev.automerge.AutoMergeCheckStatus as Status
import net.kigawa.kivra.selfdev.automerge.AutoMergeDecision.Eligible
import net.kigawa.kivra.selfdev.automerge.AutoMergeDecision.Hold
import net.kigawa.kivra.selfdev.automerge.AutoMergeDecision.Refuse

private val NOW: Instant = Instant.parse("2026-10-22T10:00:00Z")
private const val REPO = "kigawa-net/kivra"
private const val RUN_ID = "01M5GJ23DV0A4VCB9VTV9V08PZ"
private const val SHA = "0123456789abcdef0123456789abcdef01234567"
private const val OTHER_SHA = "fedcba9876543210fedcba9876543210fedcba98"
private const val BOT_ID = 4242L
private const val IN_SCOPE = "kivra-app/src/test/kotlin/net/kigawa/kivra/XTest.kt"
private val REQUIRED = setOf("test", "build", "boot")

private val SETTINGS = AutoMergeSettings(now = NOW, botAuthorId = BOT_ID, requiredChecks = REQUIRED)

private val OK_PR = AutoMergePrFacts(
    repo = REPO,
    number = 7,
    nodeId = "PR_node_7",
    authorId = BOT_ID,
    headRef = "kivra/run-$RUN_ID",
    headSha = SHA,
    draft = false,
    labels = emptyList(),
    mergeable = true,
    mergeableState = "clean",
)

private val OK_RUN = AutoMergeRunFacts(
    state = DevelopmentRunState.PULL_REQUEST,
    runId = RUN_ID,
    binding = AutoMergeBinding(repo = REPO, prNumber = 7, nodeId = "PR_node_7", headSha = SHA),
    markerHeadSha = SHA,
    approval = AutoMergeTaskApproval.AUTO_APPROVED,
)

private val OK_FILES = listOf(AutoMergeFileFact(IN_SCOPE, "modified", additions = 10, deletions = 2, mode = "100644"))

private val OK_GLOBAL = AutoMergeGlobalFacts(
    selfDevEnabled = true,
    autopilotEnabled = true,
    autoMergeEnabled = true,
    autoMergePaused = false,
    selfDevPaused = false,
    breakerOpen = false,
    mainCi = MainCiResult.SUCCESS,
    inFlight = 0,
    mergedToday = 0,
)

private fun okChecks(): List<AutoMergeCheckFact> = REQUIRED.map {
    AutoMergeCheckFact(it, Status.COMPLETED, Conclusion.SUCCESS, obtainedForHeadSha = true, pendingSince = null)
}

private fun input(
    pr: AutoMergePrFacts = OK_PR,
    run: AutoMergeRunFacts = OK_RUN,
    files: List<AutoMergeFileFact> = OK_FILES,
    checks: List<AutoMergeCheckFact> = okChecks(),
    global: AutoMergeGlobalFacts = OK_GLOBAL,
) = AutoMergeInput(pr, run, files, checks, global)

private fun decide(input: AutoMergeInput, settings: AutoMergeSettings = SETTINGS) = decideAutoMerge(input, settings)

private fun fileOf(path: String) = listOf(AutoMergeFileFact(path, "modified", 1, 0, "100644"))

class AutoMergeDecisionTest {

    @Test
    fun `all conditions hold gives Eligible`() {
        assertEquals(Eligible, decide(input()))
    }

    @Test
    fun `breaking one condition gives its reason`() {
        val cases: List<Pair<String, Pair<AutoMergeInput, AutoMergeDecision>>> = listOf(
            // C1: origin
            "NOT_BOT_AUTHOR" to (input(pr = OK_PR.copy(authorId = 1L)) to Refuse(AutoMergeReason.NOT_BOT_AUTHOR)),
            "NOT_BOT_AUTHOR (no author id)" to (input(pr = OK_PR.copy(authorId = null)) to Refuse(AutoMergeReason.NOT_BOT_AUTHOR)),
            "BRANCH_NOT_RUN" to (input(pr = OK_PR.copy(headRef = "feature/x")) to Refuse(AutoMergeReason.BRANCH_NOT_RUN)),
            "BRANCH_NOT_RUN (bad run id)" to (
                input(run = OK_RUN.copy(runId = "bad id!")) to Refuse(AutoMergeReason.BRANCH_NOT_RUN)
                ),
            "RUN_NOT_PULL_REQUEST" to (
                input(run = OK_RUN.copy(state = DevelopmentRunState.DEVELOPING)) to
                    Refuse(AutoMergeReason.RUN_NOT_PULL_REQUEST)
                ),
            "BINDING_MISMATCH (PR number)" to (
                input(run = OK_RUN.copy(binding = OK_RUN.binding!!.copy(prNumber = 8))) to
                    Refuse(AutoMergeReason.BINDING_MISMATCH)
                ),
            "BINDING_MISMATCH (no binding)" to (input(run = OK_RUN.copy(binding = null)) to Refuse(AutoMergeReason.BINDING_MISMATCH)),
            "BINDING_MISMATCH (node id)" to (
                input(pr = OK_PR.copy(nodeId = "PR_other")) to Refuse(AutoMergeReason.BINDING_MISMATCH)
                ),
            // C2: approval
            "OPERATOR_APPROVED_TASK" to (
                input(run = OK_RUN.copy(approval = AutoMergeTaskApproval.OPERATOR_APPROVED)) to
                    Refuse(AutoMergeReason.OPERATOR_APPROVED_TASK)
                ),
            "NOT_AUTO_APPROVED" to (input(run = OK_RUN.copy(approval = null)) to Refuse(AutoMergeReason.NOT_AUTO_APPROVED)),
            // C3: diff
            "FILES_OUT_OF_RANGE" to (input(files = emptyList()) to Refuse(AutoMergeReason.FILES_OUT_OF_RANGE)),
            "TOO_MANY_FILES" to (
                input(files = (1..4).map { AutoMergeFileFact("kivra-app/src/test/kotlin/T$it.kt", "added", 1, 0, "100644") }) to
                    Refuse(AutoMergeReason.TOO_MANY_FILES)
                ),
            "TOO_MANY_LINES" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", 199, 2, "100644"))) to
                    Refuse(AutoMergeReason.TOO_MANY_LINES)
                ),
            "STATUS_NOT_ALLOWED (removed)" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "removed", 0, 5, "100644"))) to
                    Refuse(AutoMergeReason.STATUS_NOT_ALLOWED)
                ),
            "STATUS_NOT_ALLOWED (renamed)" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "renamed", 0, 0, "100644"))) to
                    Refuse(AutoMergeReason.STATUS_NOT_ALLOWED)
                ),
            "PATH_UNSAFE" to (
                input(files = fileOf("kivra-app/src/test/kotlin/../XTest.kt")) to Refuse(AutoMergeReason.PATH_UNSAFE)
                ),
            "PATH_OUT_OF_SCOPE" to (
                input(files = fileOf("docs/a.md")) to Refuse(AutoMergeReason.PATH_OUT_OF_SCOPE)
                ),
            "MODE_NOT_REGULAR (executable)" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", 1, 0, "100755"))) to
                    Refuse(AutoMergeReason.MODE_NOT_REGULAR)
                ),
            "MODE_NOT_REGULAR (symlink)" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", 1, 0, "120000"))) to
                    Refuse(AutoMergeReason.MODE_NOT_REGULAR)
                ),
            "MODE_NOT_REGULAR (unknown)" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", 1, 0, null))) to
                    Refuse(AutoMergeReason.MODE_NOT_REGULAR)
                ),
            // C4: head
            "HEAD_MISMATCH (marker)" to (input(run = OK_RUN.copy(markerHeadSha = OTHER_SHA)) to Refuse(AutoMergeReason.HEAD_MISMATCH)),
            "HEAD_MISMATCH (binding)" to (
                input(run = OK_RUN.copy(binding = OK_RUN.binding!!.copy(headSha = OTHER_SHA))) to
                    Refuse(AutoMergeReason.HEAD_MISMATCH)
                ),
            "HEAD_MISMATCH (no marker)" to (input(run = OK_RUN.copy(markerHeadSha = null)) to Refuse(AutoMergeReason.HEAD_MISMATCH)),
            // C5: required checks
            "CHECK_MISSING" to (
                input(checks = okChecks().filter { it.name != "boot" }) to Refuse(AutoMergeReason.CHECK_MISSING)
                ),
            "CHECK_MISSING (only at another head)" to (
                input(checks = okChecks().map { it.copy(obtainedForHeadSha = false) }) to Refuse(AutoMergeReason.CHECK_MISSING)
                ),
            "CHECK_NOT_SUCCESS (failure)" to (
                input(checks = okChecks().map { if (it.name == "build") it.copy(conclusion = Conclusion.FAILURE) else it }) to
                    Refuse(AutoMergeReason.CHECK_NOT_SUCCESS)
                ),
            "CHECK_NOT_SUCCESS (neutral)" to (
                input(checks = okChecks().map { if (it.name == "build") it.copy(conclusion = Conclusion.NEUTRAL) else it }) to
                    Refuse(AutoMergeReason.CHECK_NOT_SUCCESS)
                ),
            "CHECK_NOT_SUCCESS (skipped)" to (
                input(checks = okChecks().map { if (it.name == "build") it.copy(conclusion = Conclusion.SKIPPED) else it }) to
                    Refuse(AutoMergeReason.CHECK_NOT_SUCCESS)
                ),
            "CHECK_NOT_SUCCESS (cancelled)" to (
                input(checks = okChecks().map { if (it.name == "build") it.copy(conclusion = Conclusion.CANCELLED) else it }) to
                    Refuse(AutoMergeReason.CHECK_NOT_SUCCESS)
                ),
            "CHECK_NOT_SUCCESS (completed without conclusion)" to (
                input(checks = okChecks().map { if (it.name == "build") it.copy(conclusion = null) else it }) to
                    Refuse(AutoMergeReason.CHECK_NOT_SUCCESS)
                ),
            "CHECK_PENDING" to (
                input(checks = okChecks().map {
                    if (it.name == "test") it.copy(status = Status.IN_PROGRESS, conclusion = null, pendingSince = NOW) else it
                }) to Hold(AutoMergeReason.CHECK_PENDING)
                ),
            "CHECK_PENDING_TIMEOUT" to (
                input(checks = okChecks().map {
                    if (it.name == "test") {
                        it.copy(status = Status.QUEUED, conclusion = null, pendingSince = NOW.minus(Duration.ofMinutes(61)))
                    } else {
                        it
                    }
                }) to Refuse(AutoMergeReason.CHECK_PENDING_TIMEOUT)
                ),
            // C6: draft and mergeable state
            "DRAFT" to (input(pr = OK_PR.copy(draft = true)) to Refuse(AutoMergeReason.DRAFT)),
            "NOT_MERGEABLE (false)" to (input(pr = OK_PR.copy(mergeable = false)) to Refuse(AutoMergeReason.NOT_MERGEABLE)),
            "NOT_MERGEABLE (dirty)" to (input(pr = OK_PR.copy(mergeableState = "dirty")) to Refuse(AutoMergeReason.NOT_MERGEABLE)),
            "NOT_MERGEABLE (blocked)" to (input(pr = OK_PR.copy(mergeableState = "blocked")) to Refuse(AutoMergeReason.NOT_MERGEABLE)),
            "MERGEABLE_UNKNOWN (null)" to (
                input(pr = OK_PR.copy(mergeable = null, mergeableState = null)) to Hold(AutoMergeReason.MERGEABLE_UNKNOWN)
                ),
            "MERGEABLE_UNKNOWN (state unknown)" to (
                input(pr = OK_PR.copy(mergeableState = "unknown")) to Hold(AutoMergeReason.MERGEABLE_UNKNOWN)
                ),
            // C7: hold label
            "HOLD_LABEL" to (input(pr = OK_PR.copy(labels = listOf("kivra:hold"))) to Refuse(AutoMergeReason.HOLD_LABEL)),
            "HOLD_LABEL (case)" to (input(pr = OK_PR.copy(labels = listOf("Kivra:Hold"))) to Refuse(AutoMergeReason.HOLD_LABEL)),
            // C8: whole system
            "FLAG_OFF (auto merge)" to (
                input(global = OK_GLOBAL.copy(autoMergeEnabled = false)) to Refuse(AutoMergeReason.FLAG_OFF)
                ),
            "FLAG_OFF (autopilot)" to (
                input(global = OK_GLOBAL.copy(autopilotEnabled = false)) to Refuse(AutoMergeReason.FLAG_OFF)
                ),
            "FLAG_OFF (self-dev)" to (
                input(global = OK_GLOBAL.copy(selfDevEnabled = false)) to Refuse(AutoMergeReason.FLAG_OFF)
                ),
            "PAUSED (feature)" to (input(global = OK_GLOBAL.copy(autoMergePaused = true)) to Refuse(AutoMergeReason.PAUSED)),
            "PAUSED (whole autopilot)" to (input(global = OK_GLOBAL.copy(selfDevPaused = true)) to Refuse(AutoMergeReason.PAUSED)),
            "BREAKER_OPEN" to (input(global = OK_GLOBAL.copy(breakerOpen = true)) to Refuse(AutoMergeReason.BREAKER_OPEN)),
            "MAIN_CI_NOT_GREEN (failure)" to (
                input(global = OK_GLOBAL.copy(mainCi = MainCiResult.FAILURE)) to Refuse(AutoMergeReason.MAIN_CI_NOT_GREEN)
                ),
            "MAIN_CI_NOT_GREEN (pending)" to (
                input(global = OK_GLOBAL.copy(mainCi = MainCiResult.PENDING)) to Hold(AutoMergeReason.MAIN_CI_NOT_GREEN)
                ),
            "MAIN_CI_NOT_GREEN (unknown)" to (
                input(global = OK_GLOBAL.copy(mainCi = MainCiResult.UNKNOWN)) to Hold(AutoMergeReason.MAIN_CI_NOT_GREEN)
                ),
            // C9: rates
            "CONCURRENCY_LIMIT" to (input(global = OK_GLOBAL.copy(inFlight = 1)) to Hold(AutoMergeReason.CONCURRENCY_LIMIT)),
            "DAILY_LIMIT" to (input(global = OK_GLOBAL.copy(mergedToday = 3)) to Hold(AutoMergeReason.DAILY_LIMIT)),
            // malformed input
            "INVALID_FACTS (duplicate path)" to (
                input(files = OK_FILES + OK_FILES) to Refuse(AutoMergeReason.INVALID_FACTS)
                ),
            "INVALID_FACTS (negative count)" to (
                input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", -1, 0, "100644"))) to
                    Refuse(AutoMergeReason.INVALID_FACTS)
                ),
        )
        for ((name, case) in cases) {
            assertEquals(case.second, decide(case.first), name)
        }
    }

    @Test
    fun `operator approved task is refused even when everything else holds`() {
        val operator = input(run = OK_RUN.copy(approval = AutoMergeTaskApproval.OPERATOR_APPROVED))
        assertEquals(Refuse(AutoMergeReason.OPERATOR_APPROVED_TASK), decide(operator))
    }

    @Test
    fun `whole-system stops never give Eligible`() {
        val stops = listOf(
            OK_GLOBAL.copy(autoMergeEnabled = false),
            OK_GLOBAL.copy(autopilotEnabled = false),
            OK_GLOBAL.copy(selfDevEnabled = false),
            OK_GLOBAL.copy(autoMergePaused = true),
            OK_GLOBAL.copy(selfDevPaused = true),
            OK_GLOBAL.copy(breakerOpen = true),
            OK_GLOBAL.copy(mainCi = MainCiResult.FAILURE),
            OK_GLOBAL.copy(mainCi = MainCiResult.UNKNOWN),
            OK_GLOBAL.copy(mainCi = MainCiResult.PENDING),
        )
        for (global in stops) {
            assertNotEquals(Eligible, decide(input(global = global)), global.toString())
        }
    }

    @Test
    fun `file count boundary is three and four`() {
        fun files(n: Int) = (1..n).map { AutoMergeFileFact("kivra-app/src/test/kotlin/T$it.kt", "added", 1, 0, "100644") }
        assertEquals(Eligible, decide(input(files = files(3))))
        assertEquals(Refuse(AutoMergeReason.TOO_MANY_FILES), decide(input(files = files(4))))
    }

    @Test
    fun `line boundary is 200 and 201, counting deletions`() {
        assertEquals(Eligible, decide(input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", 150, 50, "100644")))))
        assertEquals(Refuse(AutoMergeReason.TOO_MANY_LINES), decide(input(files = listOf(AutoMergeFileFact(IN_SCOPE, "modified", 150, 51, "100644")))))
    }

    @Test
    fun `pending limit boundary is 60 minutes and 60 minutes plus one second`() {
        fun pendingAt(since: Instant) = okChecks().map {
            if (it.name == "test") it.copy(status = Status.IN_PROGRESS, conclusion = null, pendingSince = since) else it
        }
        assertEquals(Hold(AutoMergeReason.CHECK_PENDING), decide(input(checks = pendingAt(NOW.minus(Duration.ofMinutes(60))))))
        assertEquals(
            Refuse(AutoMergeReason.CHECK_PENDING_TIMEOUT),
            decide(input(checks = pendingAt(NOW.minus(Duration.ofMinutes(60)).minusSeconds(1)))),
        )
    }

    @Test
    fun `daily cap boundary is two and three`() {
        assertEquals(Eligible, decide(input(global = OK_GLOBAL.copy(mergedToday = 2))))
        assertEquals(Hold(AutoMergeReason.DAILY_LIMIT), decide(input(global = OK_GLOBAL.copy(mergedToday = 3))))
    }

    @Test
    fun `concurrency boundary is zero and one`() {
        assertEquals(Eligible, decide(input(global = OK_GLOBAL.copy(inFlight = 0))))
        assertEquals(Hold(AutoMergeReason.CONCURRENCY_LIMIT), decide(input(global = OK_GLOBAL.copy(inFlight = 1))))
    }

    @Test
    fun `scope accepts test sources and rejects everything else`() {
        val accepted = listOf(IN_SCOPE, "kivra-app/src/test/kotlin/net/kigawa/kivra/util/XTest.kt", "kivra-selfdev/src/test/kotlin/A.kt")
        for (path in accepted) {
            assertEquals(Eligible, decide(input(files = fileOf(path))), path)
        }

        val rejected = mapOf(
            "kivra-app/src/main/kotlin/net/kigawa/kivra/Main.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "docs/runbook-selfdev.md" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "README.md" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            ".github/workflows/ci.yml" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src/test/resources/fixture.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-policy/src/test/kotlin/net/kigawa/kivra/policy/XTest.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-github/src/test/kotlin/net/kigawa/kivra/github/XTest.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src/main/kotlin/net/kigawa/kivra/selfdev/XTest.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src/main/resources/db/migration/V99__x.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "Kivra-app/src/test/kotlin/net/kigawa/kivra/XTest.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src/test/kotlin/net/kigawa/kivra/Dockerfile.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src/test/kotlin/net/kigawa/kivra/.env.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src/test/kotlin/net/kigawa/kivra/dockerfile.kt" to AutoMergeReason.PATH_OUT_OF_SCOPE,
            "kivra-app/src\\test/kotlin/XTest.kt" to AutoMergeReason.PATH_UNSAFE,
            "kivra-app/src/test/kotlin/../XTest.kt" to AutoMergeReason.PATH_UNSAFE,
            "/kivra-app/src/test/kotlin/XTest.kt" to AutoMergeReason.PATH_UNSAFE,
            "kivra-app/src/test/kotlin/XTest\u0000.kt" to AutoMergeReason.PATH_UNSAFE,
            "kivra-app/src/test/kotlin/XTest\u202E.kt" to AutoMergeReason.PATH_UNSAFE,
            "kivra-app/src/test/kotlin/\u2066XTest.kt" to AutoMergeReason.PATH_UNSAFE,
            "kivra-app/src/test/kotlin/XTest\nX.kt" to AutoMergeReason.PATH_UNSAFE,
        )
        for ((path, code) in rejected) {
            assertEquals(Refuse(code), decide(input(files = fileOf(path))), path)
        }
    }

    @Test
    fun `test sources of the gate and its guards are refused by the denylist`() {
        // Each path matches the scope (kivra-*/src/test/kotlin/**/*.kt) but also a denylist pattern (ADR 0014 section 2.1, applied to tests).
        val denied = listOf(
            "kivra-app/src/test/kotlin/net/kigawa/kivra/selfdev/automerge/AutoMergeDecisionTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/selfdev/AutopilotDispatcherTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/coding/AutoScopeGuardTest.kt",
            "kivra-core/src/test/kotlin/net/kigawa/kivra/core/patch/PatchPolicyTest.kt",
            "kivra-core/src/test/kotlin/net/kigawa/kivra/core/States.kt",
            "kivra-agent/src/test/kotlin/net/kigawa/kivra/agent/workdir/PathRulesTest.kt",
            "kivra-agent/src/test/kotlin/net/kigawa/kivra/agent/workdir/DiffCollectorTest.kt",
            "kivra-agent/src/test/kotlin/net/kigawa/kivra/agent/workdir/DiffLimitsTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/ArchitectureTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/x/InvariantCheckTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/x/SelfDevInvariantsTest.kt",
            "kivra-policy/src/test/kotlin/net/kigawa/kivra/policy/XTest.kt",
            "kivra-github/src/test/kotlin/net/kigawa/kivra/github/XTest.kt",
            "kivra-model-gateway/src/test/kotlin/net/kigawa/kivra/auth/AuthTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/mcp/McpToolsTest.kt",
            "kivra-app/src/test/kotlin/net/kigawa/kivra/routes/RoutesTest.kt",
        )
        for (path in denied) {
            assertEquals(Refuse(AutoMergeReason.PATH_OUT_OF_SCOPE), decide(input(files = fileOf(path))), path)
        }
    }

    @Test
    fun `a path in both scope and denylist is refused`() {
        assertEquals(
            Refuse(AutoMergeReason.PATH_OUT_OF_SCOPE),
            decide(input(files = fileOf("kivra-policy/src/test/kotlin/net/kigawa/kivra/policy/XTest.kt"))),
        )
    }

    @Test
    fun `reason strings are the enum names and never carry a path`() {
        val secretPath = "kivra-app/src/main/kotlin/net/kigawa/kivra/SECRETPATH.kt"
        val decision = decide(input(files = fileOf(secretPath)))
        assertEquals(Refuse(AutoMergeReason.PATH_OUT_OF_SCOPE), decision)
        assertFalse(decision.toString().contains("SECRETPATH"))
        assertEquals("PATH_OUT_OF_SCOPE", AutoMergeReason.PATH_OUT_OF_SCOPE.name)
    }

    @Test
    fun `same input gives the same output`() {
        val inputs = listOf(input(), input(global = OK_GLOBAL.copy(mergedToday = 3)), input(files = fileOf("docs/a.md")))
        for (i in inputs) {
            assertEquals(decide(i), decide(i))
        }
    }

    @Test
    fun `empty required set is never eligible and malformed settings refuse`() {
        assertEquals(Refuse(AutoMergeReason.CHECK_MISSING), decide(input(), SETTINGS.copy(requiredChecks = emptySet())))
        assertEquals(Refuse(AutoMergeReason.INVALID_FACTS), decide(input(), SETTINGS.copy(maxFiles = 0)))
    }
}
