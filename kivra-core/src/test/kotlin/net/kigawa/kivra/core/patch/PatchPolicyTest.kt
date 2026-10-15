package net.kigawa.kivra.core.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PatchPolicyTest {
    private val policy = PatchPolicy()

    private fun patch(vararg files: FileChange, summary: String = "変更の説明", rationale: String? = null) =
        PatchSet(files.toList(), summary, rationale)

    private fun verdict(vararg files: FileChange, current: RepoSnapshot? = null): PatchVerdict =
        policy.validate(patch(*files), current)

    private fun reasonsOf(vararg files: FileChange, current: RepoSnapshot? = null): List<RejectReason> {
        val v = verdict(*files, current = current)
        assertIs<PatchVerdict.Rejected>(v)
        return v.reasons
    }

    private fun assertAccepted(vararg files: FileChange, current: RepoSnapshot? = null) {
        val p = patch(*files)
        val v = policy.validate(p, current)
        assertIs<PatchVerdict.Accepted>(v)
        assertEquals(p, v.patch)
    }

    private fun assertRejectedWith(reason: RejectReason, vararg files: FileChange, current: RepoSnapshot? = null) {
        val reasons = reasonsOf(*files, current = current)
        assertTrue(reason in reasons, "expected $reason in $reasons")
    }

    // ---- paths: shape ----

    @Test
    fun badPathShapesAreRejected() {
        val cases = listOf(
            "" to RejectReason.PATH_EMPTY,
            "." to RejectReason.PATH_DOT_SEGMENT,
            "../x" to RejectReason.PATH_DOT_SEGMENT,
            "/etc/passwd" to RejectReason.PATH_ABSOLUTE,
            "docs/a/../../b" to RejectReason.PATH_DOT_SEGMENT,
            "docs\\a.md" to RejectReason.PATH_BACKSLASH,
            "docs//a.md" to RejectReason.PATH_EMPTY_SEGMENT,
            "~/x" to RejectReason.PATH_TILDE,
            "C:\\x" to RejectReason.PATH_BACKSLASH,
            "C:/x" to RejectReason.PATH_COLON,
            "docs/a\u0000.md" to RejectReason.PATH_CONTROL_CHARACTER,
            "docs/a\u0001.md" to RejectReason.PATH_CONTROL_CHARACTER,
            "docs/a\u200B.md" to RejectReason.PATH_CONTROL_CHARACTER,
            "docs/" to RejectReason.PATH_TRAILING_SLASH,
            "docs/" + "a".repeat(195) + "/x" to RejectReason.PATH_TOO_LONG,
        )
        for ((path, reason) in cases) {
            assertRejectedWith(reason, Write(path, "x\n"))
        }
    }

    @Test
    fun pathLengthBoundaryIsInclusive() {
        // 200 characters is allowed, 201 is not.
        assertAccepted(Write("docs/" + "a".repeat(195), "x\n"))
        assertRejectedWith(RejectReason.PATH_TOO_LONG, Write("docs/" + "a".repeat(196), "x\n"))
    }

    @Test
    fun deleteUsesTheSamePathChecks() {
        assertRejectedWith(RejectReason.PATH_DOT_SEGMENT, Delete("docs/../x.md"))
        assertRejectedWith(RejectReason.PATH_ABSOLUTE, Delete("/docs/x.md"))
    }

    // ---- paths: collisions ----

    @Test
    fun caseOnlyDifferenceIsACollision() {
        assertRejectedWith(RejectReason.CASE_COLLISION, Write("docs/A.md", "x\n"), Write("docs/a.md", "x\n"))
    }

    @Test
    fun unicodeNormalizationDifferenceIsACollision() {
        val nfc = "docs/\u00E9.md"
        val nfd = "docs/e\u0301.md"
        assertRejectedWith(RejectReason.NORMALIZATION_COLLISION, Write(nfc, "x\n"), Write(nfd, "x\n"))
    }

    @Test
    fun exactDuplicatesAreRejected() {
        assertRejectedWith(RejectReason.DUPLICATE_PATH, Write("docs/a.md", "x\n"), Write("docs/a.md", "y\n"))
    }

    @Test
    fun writeAndDeleteOfTheSamePathAreRejected() {
        assertRejectedWith(RejectReason.DUPLICATE_PATH, Write("docs/a.md", "x\n"), Delete("docs/a.md"))
    }

    // ---- allowlist and denylist ----

    @Test
    fun allowedPathsAreAccepted() {
        assertAccepted(
            Write("kivra-github/src/main/kotlin/net/kigawa/kivra/github/X.kt", "class X\n"),
            Write("docs/design/x.md", "# x\n"),
            Write("README.md", "# readme\n"),
            Write("kivra-core/src/test/kotlin/Foo.kt", "class Foo\n"),
        )
    }

    @Test
    fun deniedPathsAreRejected() {
        val denied = listOf(
            ".github/workflows/ci.yml",
            "gradle/libs.versions.toml",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "kivra-core/build.gradle.kts",
            "gradle.properties",
            "Dockerfile",
            "compose.dev.yml",
            "kivra-app/src/main/resources/db/migration/V12__x.sql",
            "docs/adr/0008-x.md",
            "docs/provider-compatibility-matrix.md",
            "CLAUDE.md",
            "CLAUDE.MD",
            ".env",
            "kivra-app/.env.local",
            "certs/server.pem",
            "keys/id.key",
            ".gitignore",
            "kivra-policy/src/main/kotlin/net/kigawa/kivra/policy/P.kt",
            "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt",
            "kivra-model-gateway/src/main/kotlin/net/kigawa/kivra/gateway/Budget.kt",
        )
        for (path in denied) {
            assertRejectedWith(RejectReason.PATH_DENIED, Write(path, "x\n"))
        }
    }

    @Test
    fun pathsOutsideTheAllowlistAreRejected() {
        val notAllowed = listOf("build.sh", "src/main/Foo.kt", "other/x.txt", "docs")
        for (path in notAllowed) {
            assertRejectedWith(RejectReason.PATH_NOT_ALLOWED, Write(path, "x\n"))
        }
    }

    @Test
    fun denyWinsOverAllowAndCoreFilesAreStillCovered() {
        // kivra-model-gateway is under kivra-*/src/**, but Budget.kt is a denied core file.
        assertRejectedWith(
            RejectReason.PATH_DENIED,
            Write("kivra-model-gateway/src/main/kotlin/net/kigawa/kivra/gateway/Budget.kt", "class B\n"),
        )
        assertAccepted(Write("kivra-model-gateway/src/main/kotlin/net/kigawa/kivra/gateway/Other.kt", "class O\n"))
    }

    @Test
    fun defaultPatternsArePinned() {
        assertEquals(
            listOf("kivra-*/src/**", "docs/**", "README.md"),
            PatchPolicyDefaults.ALLOWED_PATH_PATTERNS,
        )
        assertEquals(
            listOf(
                ".github/**",
                "gradle/**",
                "**/gradlew*",
                "**/*.gradle.kts",
                "**/*.gradle",
                "**/gradle.properties",
                "**/Dockerfile*",
                "**/compose*.yml",
                "**/compose*.yaml",
                "kivra-*/src/main/resources/db/migration/**",
                "**/.git*",
                "**/.env*",
                "**/*.pem",
                "**/*.key",
                "**/CLAUDE.md",
                "docs/adr/**",
                "docs/provider-compatibility-matrix.md",
                "kivra-policy/**",
                "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/**",
                "kivra-model-gateway/src/main/kotlin/**/Budget.kt",
            ),
            PatchPolicyDefaults.DENIED_PATH_PATTERNS,
        )
    }

    @Test
    fun defaultLimitsArePinned() {
        val c = PatchPolicyConfig()
        assertEquals(20, c.maxFiles)
        assertEquals(200L * 1024, c.maxFileBytes)
        assertEquals(1024L * 1024, c.maxTotalBytes)
        assertEquals(4000, c.maxSummaryChars)
        assertEquals(10_000, c.maxLineChars)
        assertEquals(5, c.maxDeletes)
        assertEquals(200, c.maxPathChars)
        assertEquals(20, c.shrinkThresholdPercent)
    }

    // ---- size limits ----

    private fun lines(units: Int): String = ("a".repeat(99) + "\n").repeat(units) // 100 bytes per unit

    @Test
    fun fileSizeBoundaryIsInclusive() {
        val exactly = lines(2048) // 204800 bytes
        assertEquals(200L * 1024, utf8Size(exactly))
        assertAccepted(Write("docs/a.md", exactly))
        assertRejectedWith(RejectReason.FILE_TOO_LARGE, Write("docs/a.md", exactly + "a"))
    }

    @Test
    fun totalSizeBoundaryIsInclusive() {
        val file = lines(2048) // 200 KB each
        // 5 files = 1000 KB, under the 1 MB total
        assertAccepted(*Array(5) { Write("docs/f$it.md", file) })
        // 6 files = 1200 KB, over the total
        assertRejectedWith(RejectReason.TOTAL_TOO_LARGE, *Array(6) { Write("docs/f$it.md", file) })
    }

    @Test
    fun fileCountBoundaryIsInclusive() {
        assertAccepted(*Array(20) { Write("docs/f$it.md", "x\n") })
        assertRejectedWith(RejectReason.TOO_MANY_FILES, *Array(21) { Write("docs/f$it.md", "x\n") })
    }

    @Test
    fun summaryLengthBoundaryIsInclusive() {
        val v = policy.validate(patch(Write("docs/a.md", "x\n"), summary = "s".repeat(4000)))
        assertIs<PatchVerdict.Accepted>(v)
    }

    @Test
    fun summaryTooLongIsRejected() {
        val v = policy.validate(patch(Write("docs/a.md", "x\n"), summary = "s".repeat(4001)))
        assertIs<PatchVerdict.Rejected>(v)
        assertTrue(RejectReason.SUMMARY_TOO_LONG in v.reasons)
    }

    @Test
    fun rationaleTooLongIsRejected() {
        val v = policy.validate(patch(Write("docs/a.md", "x\n"), rationale = "r".repeat(4001)))
        assertIs<PatchVerdict.Rejected>(v)
        assertEquals(listOf(RejectReason.RATIONALE_TOO_LONG), v.reasons)
    }

    // ---- content ----

    @Test
    fun binaryContentIsRejected() {
        assertRejectedWith(RejectReason.BINARY_CONTENT, Write("docs/a.bin", "a\u0000b\n"))
    }

    @Test
    fun invalidUtf8IsRejected() {
        // An unpaired surrogate cannot be encoded as valid UTF-8.
        assertRejectedWith(RejectReason.INVALID_UTF8, Write("docs/a.md", "x\uD800y\n"))
        assertRejectedWith(RejectReason.INVALID_UTF8, Write("docs/a.md", "x\uDC00y\n"))
    }

    @Test
    fun validSurrogatePairIsAccepted() {
        assertAccepted(Write("docs/a.md", "絵文字 \uD83D\uDE00\n"))
    }

    @Test
    fun longLineBoundaryIsInclusive() {
        assertAccepted(Write("docs/a.md", "a".repeat(10_000)))
        assertRejectedWith(RejectReason.LINE_TOO_LONG, Write("docs/a.md", "a".repeat(10_001)))
    }

    @Test
    fun secretLikeContentIsRejected() {
        val secrets = listOf(
            "-----BEGIN " + "RSA " + "PRIVATE KEY-----\nMIIE\n",
            "-----BEGIN " + "PRIVATE KEY-----\n",
            "ghp_" + "a".repeat(36),
            "github_pat_" + "a".repeat(40),
            "sk-ant-" + "a".repeat(40),
            "AKIA" + "A".repeat(16),
            "password" + " = \"" + "x".repeat(10) + "\"",
            "PASSWORD: \"" + "y".repeat(8) + "\"",
            "api_key = \"" + "z".repeat(12) + "\"",
        )
        for (secret in secrets) {
            assertRejectedWith(RejectReason.SECRET_LIKE_CONTENT, Write("docs/a.md", "before\n$secret\n"))
        }
    }

    @Test
    fun secretLikeTextInSummaryIsRejected() {
        val v = policy.validate(patch(Write("docs/a.md", "x\n"), summary = "token " + "ghp_" + "a".repeat(36)))
        assertIs<PatchVerdict.Rejected>(v)
        assertTrue(RejectReason.SECRET_LIKE_CONTENT in v.reasons)
    }

    @Test
    fun shortTokenLikeTextIsNotASecret() {
        assertAccepted(Write("docs/a.md", "ghp_" + "a".repeat(5) + "\n"))
    }

    // ---- deletes ----

    @Test
    fun deletesOfAllowedExistingFilesAreAccepted() {
        val repo = InMemoryRepoSnapshot(mapOf("docs/old.md" to "x\n"))
        assertAccepted(Delete("docs/old.md"), current = repo)
    }

    @Test
    fun deleteOfMissingFileIsRejectedWhenRepoIsKnown() {
        val repo = InMemoryRepoSnapshot(emptyMap())
        assertRejectedWith(RejectReason.DELETE_MISSING_FILE, Delete("docs/none.md"), current = repo)
    }

    @Test
    fun deleteOfMissingFileIsNotCheckedWithoutSnapshot() {
        assertAccepted(Delete("docs/none.md"))
    }

    @Test
    fun deleteOfDeniedPathIsRejected() {
        assertRejectedWith(RejectReason.PATH_DENIED, Delete(".github/workflows/ci.yml"))
    }

    @Test
    fun deleteCountBoundaryIsInclusive() {
        val files = (0 until 6).associate { "docs/d$it.md" to "x\n" }
        val repo = InMemoryRepoSnapshot(files)
        assertAccepted(*Array(5) { Delete("docs/d$it.md") }, current = repo)
        assertRejectedWith(RejectReason.TOO_MANY_DELETES, *Array(6) { Delete("docs/d$it.md") }, current = repo)
    }

    // ---- shrink ----

    @Test
    fun writeThatShrinksExistingFileBelowTwentyPercentIsRejected() {
        val old = "x".repeat(999) + "\n" // 1000 bytes
        val repo = InMemoryRepoSnapshot(mapOf("docs/a.md" to old))
        // 199 bytes is under 20% of 1000
        assertRejectedWith(RejectReason.LARGE_SHRINK, Write("docs/a.md", "x".repeat(198) + "\n"), current = repo)
        // exactly 200 bytes is not under 20%
        assertAccepted(Write("docs/a.md", "x".repeat(199) + "\n"), current = repo)
    }

    @Test
    fun newFileIsNotCheckedForShrink() {
        val repo = InMemoryRepoSnapshot(emptyMap())
        assertAccepted(Write("docs/new.md", "x\n"), current = repo)
    }

    // ---- empty and summary ----

    @Test
    fun emptyPatchIsRejected() {
        val v = policy.validate(PatchSet(emptyList(), "説明"))
        assertIs<PatchVerdict.Rejected>(v)
        assertEquals(listOf(RejectReason.EMPTY_PATCH), v.reasons)
    }

    @Test
    fun blankSummaryIsRejected() {
        val v = policy.validate(patch(Write("docs/a.md", "x\n"), summary = "   "))
        assertIs<PatchVerdict.Rejected>(v)
        assertEquals(listOf(RejectReason.SUMMARY_EMPTY), v.reasons)
    }

    // ---- determinism and reporting ----

    @Test
    fun verdictIsDeterministicAndReasonsAreSorted() {
        val files = arrayOf<FileChange>(
            Write("../x", "password = \"" + "q".repeat(10) + "\"\n"),
            Write("docs/A.md", "x\n"),
            Write("docs/a.md", "x\n"),
            Write("CLAUDE.md", "x\n"),
        )
        val first = policy.validate(patch(*files))
        repeat(20) {
            assertEquals(first, policy.validate(patch(*files)))
        }
        assertIs<PatchVerdict.Rejected>(first)
        assertEquals(first.reasons.sorted(), first.reasons)
        assertEquals(first.reasons.distinct(), first.reasons)
    }

    @Test
    fun rejectionReasonsIsEnumOnlyAndDoesNotEchoContent() {
        val secretPath = "docs/" + "ghp_" + "a".repeat(36) + "/x.md"
        val v = verdict(Write(secretPath, "password = \"" + "q".repeat(10) + "\"\n"))
        assertIs<PatchVerdict.Rejected>(v)
        val text = v.toString()
        assertTrue(!text.contains("ghp_"), text)
        assertTrue(!text.contains("qqqq"), text)
        assertTrue(!text.contains("docs/"), text)
    }

    @Test
    fun patchToStringDoesNotPrintContent() {
        val text = patch(Write("docs/a.md", "SECRET_BODY_TEXT\n"), summary = "SUMMARY_TEXT").toString()
        assertTrue(!text.contains("SECRET_BODY_TEXT"), text)
        assertTrue(!text.contains("SUMMARY_TEXT"), text)
    }

    // ---- UNSEEN_OVERWRITE (ADR 0008 item 5) ----

    @Test
    fun writingAnExistingFileThatWasNotInContextIsRejected() {
        val repo = InMemoryRepoSnapshot(mapOf("docs/a.md" to "old\n"), inContext = setOf("README.md"))
        assertRejectedWith(RejectReason.UNSEEN_OVERWRITE, Write("docs/a.md", "new\n"), current = repo)
    }

    @Test
    fun deletingAnExistingFileThatWasNotInContextIsRejected() {
        val repo = InMemoryRepoSnapshot(mapOf("docs/a.md" to "old\n"), inContext = emptySet())
        assertRejectedWith(RejectReason.UNSEEN_OVERWRITE, Delete("docs/a.md"), current = repo)
    }

    @Test
    fun writingAnExistingFileThatWasInContextIsAccepted() {
        val repo = InMemoryRepoSnapshot(mapOf("docs/a.md" to "old\n"), inContext = setOf("docs/a.md"))
        assertAccepted(Write("docs/a.md", "new\n"), current = repo)
    }

    @Test
    fun creatingAFileOutsideContextIsAccepted() {
        val repo = InMemoryRepoSnapshot(mapOf("docs/a.md" to "old\n"), inContext = emptySet())
        assertAccepted(Write("docs/new.md", "x\n"), current = repo)
    }

    @Test
    fun unseenOverwriteIsNotReportedWithoutRepositoryState() {
        // Without a snapshot there is no notion of "existing", so the check cannot apply.
        assertAccepted(Write("docs/a.md", "x\n"))
    }

    @Test
    fun defaultSnapshotTreatsEveryFileAsInContext() {
        val snapshot = object : RepoSnapshot {
            override fun exists(path: String): Boolean = path == "docs/a.md"
            override fun size(path: String): Long? = if (path == "docs/a.md") 4L else null
        }
        assertTrue(snapshot.isInContext("docs/a.md"))
        assertAccepted(Write("docs/a.md", "new\n"), current = snapshot)
    }
}
