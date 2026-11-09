package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.FileChange
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write

/** The re-check of the actual change set (ADR 0011 sections 6.1 to 6.2) under each approval mode. Pure: no database. */
class AutoScopeGuardTest {

    private fun patch(vararg files: FileChange) = PatchSet(files.toList(), "summary")
    private fun write(path: String, lines: Int = 1) = Write(path, "x\n".repeat(lines))
    private val noSize: (String) -> Long? = { null }
    private fun verdict(approval: AutopilotApproval, vararg files: FileChange, sizeOf: (String) -> Long? = noSize) =
        autoScopeOf(patch(*files), sizeOf, approval)

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
            for (path in outOfStage2Scope + denylisted) {
                assertEquals(AutoScopeVerdict.OutOfScope(1, 1), verdict(approval, write(path)), "$approval $path")
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
            assertEquals(AutoScopeVerdict.OutOfScope(1, 1), verdict(AutopilotApproval.FULL, write(path)), path)
        }
        // One denylisted file in an otherwise allowed change set is enough.
        assertEquals(
            AutoScopeVerdict.OutOfScope(2, 2),
            verdict(AutopilotApproval.FULL, write("kivra-app/src/main/kotlin/A.kt"), write("gradle/x.properties")),
        )
    }

    @Test
    fun `every mode keeps the path count cap of three and the 200 line cap`() {
        val four = listOf("kivra-app/src/main/kotlin/A.kt", "kivra-app/src/main/kotlin/B.kt", "docs/c.md", "docs/d.md")
        for (approval in AutopilotApproval.entries) {
            assertEquals(AutoScopeVerdict.OutOfScope(4, 4), verdict(approval, *four.map { write(it) }.toTypedArray()), "$approval")
        }
        val three = listOf("kivra-app/src/main/kotlin/A.kt", "kivra-app/src/main/kotlin/B.kt", "docs/c.md")
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, *three.map { write(it) }.toTypedArray()))

        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, write("kivra-app/src/main/kotlin/A.kt", 200)))
        assertEquals(AutoScopeVerdict.OutOfScope(1, 201), verdict(AutopilotApproval.FULL, write("kivra-app/src/main/kotlin/A.kt", 201)))
        assertEquals(AutoScopeVerdict.OutOfScope(1, 201), verdict(AutopilotApproval.AUTO, write("docs/a.md", 201)))
    }

    @Test
    fun `a delete counts its size in bytes as an upper bound, and an unknown size is out of scope`() {
        assertEquals(AutoScopeVerdict.InScope, verdict(AutopilotApproval.FULL, Delete("kivra-app/src/main/kotlin/A.kt")) { 10L })
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201),
            verdict(AutopilotApproval.FULL, Delete("kivra-app/src/main/kotlin/A.kt")) { 201L },
        )
        assertEquals(
            AutoScopeVerdict.OutOfScope(1, 201),
            verdict(AutopilotApproval.FULL, Delete("kivra-app/src/main/kotlin/A.kt")) { null },
        )
    }

    @Test
    fun `a path outside the PatchPolicy allowlist is out of scope under full too`() {
        assertEquals(AutoScopeVerdict.OutOfScope(1, 1), verdict(AutopilotApproval.FULL, write("scripts/run.sh")))
    }
}
