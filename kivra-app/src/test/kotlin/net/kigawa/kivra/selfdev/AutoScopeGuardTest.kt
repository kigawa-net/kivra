package net.kigawa.kivra.selfdev

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.FileChange
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write

/** The re-check of the actual change set (ADR 0011 sections 6.1 to 6.2) under each approval mode. Pure: no database. */
class AutoScopeGuardTest {

    private fun patch(vararg files: FileChange) = PatchSet(files.toList(), "summary")
    private fun write(path: String, lines: Int = 1) = Write(path, "x\n".repeat(lines))
    private val noSize: (String) -> Long? = { null }
    private val noBase: (String) -> String? = { null }
    private fun verdict(
        approval: AutopilotApproval,
        vararg files: FileChange,
        sizeOf: (String) -> Long? = noSize,
        baseOf: (String) -> String? = noBase,
    ) = autoScopeOf(patch(*files), sizeOf, baseOf, approval)

    private val inStage2Scope = listOf("docs/a.md", "README.md", "kivra-app/src/test/kotlin/A.kt")
    private val outOfStage2Scope = listOf("kivra-app/src/main/kotlin/A.kt", "kivra-core/src/main/kotlin/B.kt")
    private val denylisted = listOf(
        ".github/workflows/ci.yml",
        "gradle/wrapper/gradle-wrapper.properties",
        "kivra-app/build.gradle.kts",
        "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt",
        "kivra-app/src/main/resources/db/migration/V99__x.sql",
        ".kivra/config.yml",
    )

    @Test
    fun `required and auto keep the stage 2 scope - docs, test sources and README only`() {
        for (approval in listOf(AutopilotApproval.REQUIRED, AutopilotApproval.AUTO)) {
            for (path in inStage2Scope) {
                assertEquals(AutoScopeVerdict.InScope, verdict(approval, write(path)), "$approval $path")
            }
            for (path in outOfStage2Scope) {
                val expected = AutoScopeVerdict.OutOfScope(1, 1, AutoScopeReason.PATH_SCOPE)
                assertEquals(expected, verdict(approval, write(path)), "$approval $path")
            }
            for (path in denylisted) {
                val expected = AutoScopeVerdict.OutOfScope(1, 1, AutoScopeReason.DENYLIST)
                assertEquals(expected, verdict(approval, write(path)), "$approval $path")
            }
        }
    }

    @Test
    fun `full admits any allowed path, but still enforces the denylist`() {
        for (path in outOfStage2Scope + inStage2Scope) {
            assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, write(path)), path)
        }
        for (path in denylisted) {
            // Denylisted paths are out of scope under every mode, including full: the task then waits for the operator.
            assertEquals(AutoScopeVerdict.OutOfScope(1, 1, AutoScopeReason.DENYLIST), verdict(AutopilotApproval.FULL, write(path)), path)
        }
        // One denylisted file in an otherwise allowed change set is enough.
        assertEquals(
            AutoScopeVerdict.OutOfScope(2, 2, AutoScopeReason.DENYLIST),
            verdict(AutopilotApproval.FULL, write("kivra-app/src/main/kotlin/A.kt"), write("gradle/x.properties")),
        )
    }

    @Test
    fun `every mode keeps the path count cap of three`() {
        val four = listOf("kivra-app/src/main/kotlin/A.kt", "kivra-app/src/main/kotlin/B.kt", "docs/c.md", "docs/d.md")
        for (approval in AutopilotApproval.entries) {
            assertEquals(
                AutoScopeVerdict.OutOfScope(4, 4, AutoScopeReason.PATH_COUNT),
                verdict(approval, *four.map { write(it) }.toTypedArray()),
                "$approval",
            )
        }
        val three = listOf("kivra-app/src/main/kotlin/A.kt", "kivra-app/src/main/kotlin/B.kt", "docs/c.md")
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, *three.map { write(it) }.toTypedArray()))
    }

    @Test
    fun `a new file (no base) counts its whole new content, so the 200 line cap holds`() {
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, write("kivra-app/src/main/kotlin/A.kt", 200)))
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.FULL, write("kivra-app/src/main/kotlin/A.kt", 201)),
        )
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.AUTO, write("docs/a.md", 201)),
        )
    }

    @Test
    fun `a small edit to a large file is in scope, counted as added plus removed lines`() {
        val base = (1..1000).joinToString("") { "line $it\n" }
        val edited = base.replace("line 500\n", "changed\n")
        val v = verdict(AutopilotApproval.AUTO, Write("docs/big.md", edited), baseOf = { base })
        assertEquals(AutoScopeVerdict.InScope, v)
        // Full rewrite of the same 1000 lines is far over the cap, although the new content alone is 1000 lines.
        val rewritten = (1..1000).joinToString("") { "other $it\n" }
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.AUTO, Write("docs/big.md", rewritten), baseOf = { base }),
        )
    }

    @Test
    fun `the diff cap is exactly 200 changed lines`() {
        val base = (1..100).joinToString("") { "a$it\n" }
        val hundredChanged = (1..100).joinToString("") { "b$it\n" }
        // 100 removed plus 100 added = 200.
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.AUTO, Write("docs/a.md", hundredChanged), baseOf = { base }))
        val hundredOneChanged = hundredChanged + "extra\n"
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.AUTO, Write("docs/a.md", hundredOneChanged), baseOf = { base }),
        )
    }

    @Test
    fun `an unchanged rewrite counts zero lines`() {
        val base = (1..500).joinToString("") { "same $it\n" }
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.AUTO, Write("docs/a.md", base), baseOf = { base }))
    }

    @Test
    fun `a new file without a base counts its whole new content`() {
        // A new file has no base-tree entry; its 201 newly added lines exceed the cap.
        val content = "x\n".repeat(201)
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.AUTO, Write("docs/a.md", content), baseOf = { null }),
        )
    }

    @Test
    fun `an existing file with an unreadable base fails closed even when the replacement is short`() {
        val v = verdict(
            AutopilotApproval.AUTO,
            Write("docs/a.md", "one line\n"),
            sizeOf = { 10_000L },
            baseOf = { null },
        )
        assertEquals(AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT), v)
        assertEquals(201L, changedLines(null, "one line\n", existsInBase = true))
    }

    @Test
    fun `an input over the diff size bound fails closed instead of overlooking a mass deletion`() {
        val base = "a\n".repeat(AutoScopeRule.MAX_DIFF_CHARS / 2)
        assertEquals(201L, changedLines(base, "a\n"))
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.AUTO, Write("docs/a.md", "a\n"), sizeOf = { 2_000_000L }, baseOf = { base }),
        )
    }

    @Test
    fun `a delete counts its size in bytes as an upper bound, and an unknown size is out of scope`() {
        val delete = Delete("kivra-app/src/main/kotlin/A.kt")
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, delete, sizeOf = { 10L }))
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.FULL, delete, sizeOf = { 201L }),
        )
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201, AutoScopeReason.LINE_COUNT),
            verdict(AutopilotApproval.FULL, delete, sizeOf = { null }),
        )
    }

    @Test
    fun `a path outside the PatchPolicy allowlist is out of scope under full too`() {
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 1, AutoScopeReason.PATH_SCOPE),
            verdict(AutopilotApproval.FULL, write("scripts/run.sh")),
        )
    }

    @Test
    fun `the changed line count matches a longest common subsequence reference`() {
        // Random small line lists over a 3-letter alphabet: indels = n + m - 2 * LCS.
        val random = Random(42)
        repeat(500) {
            val a = List(random.nextInt(0, 9)) { "abc"[random.nextInt(3)].toString() }
            val b = List(random.nextInt(0, 9)) { "abc"[random.nextInt(3)].toString() }
            assertEquals(a.size + b.size - 2 * lcs(a, b), LineDiff.indelDistance(a, b, maxD = 100), "$a vs $b")
        }
    }

    @Test
    fun `the line diff stops at the cap and reports null beyond it`() {
        val a = List(10) { "a$it" }
        val b = List(10) { "b$it" }
        assertEquals(20, LineDiff.indelDistance(a, b, maxD = 20))
        assertNull(LineDiff.indelDistance(a, b, maxD = 19))
        assertEquals(0, LineDiff.indelDistance(emptyList(), emptyList(), maxD = 0))
        assertEquals(3, LineDiff.indelDistance(emptyList(), listOf("x", "y", "z"), maxD = 3))
        assertNull(LineDiff.indelDistance(emptyList(), listOf("x", "y", "z"), maxD = 2))
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp[a.size][b.size]
    }
}
