package net.kigawa.kivra.agent.workdir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PathRulesTest {
    private val max = 200

    private fun rules(allowed: List<String> = emptyList(), denied: List<String> = emptyList()) = PathRules.fromServer(allowed, denied)

    @Test
    fun withoutPatternsOnlyTheBuiltInRulesApply() {
        for (rules in listOf(PathRules.BUILT_IN_ONLY, rules())) {
            assertNull(rules.reason("kivra-agent/src/A.kt", max))
            assertNull(rules.reason("docs/guide.md", max))
            assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules.reason("other/x.txt", max))
            assertEquals(DiffRejectReason.PATH_DENIED, rules.reason(".github/workflows/ci.yml", max))
        }
    }

    @Test
    fun aPathNotInTheServerAllowlistIsRefusedEvenIfTheBuiltInAllowlistAcceptsIt() {
        val rules = rules(allowed = listOf("kivra-*/src/**"))
        assertNull(rules.reason("kivra-agent/src/A.kt", max))
        assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules.reason("docs/guide.md", max))
        // The server narrows the built-in allowlist too: kivra-app is allowed by the built-in rules, not by this claim.
        assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules(allowed = listOf("kivra-agent/**")).reason("kivra-app/src/B.kt", max))
    }

    @Test
    fun aPathMatchingTheServerDenylistIsRefused() {
        val rules = rules(allowed = listOf("kivra-*/src/**"), denied = listOf("**/Generated*.kt"))
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason("kivra-agent/src/Generated1.kt", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason("kivra-agent/src/deep/GeneratedX.kt", max))
        assertNull(rules.reason("kivra-agent/src/Plain.kt", max))
    }

    @Test
    fun theServerDenylistWinsOverTheServerAllowlist() {
        val rules = rules(allowed = listOf("kivra-*/src/**"), denied = listOf("kivra-agent/src/Secret*.kt"))
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason("kivra-agent/src/Secret.kt", max))
    }

    @Test
    fun theBuiltInDenylistAlwaysRefusesEvenWhenTheServerAllowsEverything() {
        val rules = rules(allowed = listOf("**"), denied = emptyList())
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason(".github/workflows/ci.yml", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason("kivra-agent/build.gradle.kts", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason("kivra-agent/src/key.pem", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules.reason("kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt", max))
    }

    @Test
    fun theBuiltInShapeCheckRunsFirst() {
        val rules = rules(allowed = listOf("**"))
        assertEquals(DiffRejectReason.PATH_DOT_SEGMENT, rules.reason("kivra-agent/../A.kt", max))
        assertEquals(DiffRejectReason.PATH_TOO_LONG, rules.reason("kivra-agent/src/" + "a".repeat(200) + ".kt", max))
    }

    @Test
    fun theAllowlistIsCaseSensitiveAndTheDenylistIsNot() {
        // PatchPolicy: allowed patterns match case-sensitively, denied patterns case-insensitively.
        assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules(allowed = listOf("KIVRA-*/src/**")).reason("kivra-agent/src/A.kt", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules(denied = listOf("**/SECRET.TXT")).reason("kivra-agent/src/secret.txt", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules(denied = listOf("docs/README.MD")).reason("docs/readme.md", max))
    }

    @Test
    fun doubleStarAndQuestionMarkFollowThePatchPolicyGlob() {
        // A double star with a slash matches zero or more directories: `kivra-*/src/**/Gen.kt` matches src/Gen.kt too.
        assertEquals(DiffRejectReason.PATH_DENIED, rules(denied = listOf("kivra-*/src/**/Gen.kt")).reason("kivra-agent/src/Gen.kt", max))
        assertEquals(DiffRejectReason.PATH_DENIED, rules(denied = listOf("**/Gen.kt")).reason("kivra-agent/src/a/b/Gen.kt", max))
        // `*` stays inside one segment; `**` crosses segments.
        assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules(allowed = listOf("kivra-*/src/*.kt")).reason("kivra-agent/src/deep/A.kt", max))
        assertNull(rules(allowed = listOf("kivra-*/src/**")).reason("kivra-agent/src/deep/x/A.kt", max))
        // `?` is one character, not a directory separator.
        assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules(allowed = listOf("kivra-?/src/**")).reason("kivra-ab/src/A.kt", max))
        assertNull(rules(allowed = listOf("kivra-?/src/**")).reason("kivra-a/src/A.kt", max))
    }

    @Test
    fun aLeadingSlashIsLiteralAsOnTheServer() {
        // A repository-relative path never starts with `/`, so such a pattern never matches: an allow refuses everything, a deny never fires.
        assertEquals(DiffRejectReason.PATH_NOT_ALLOWED, rules(allowed = listOf("/kivra-agent/src/**")).reason("kivra-agent/src/A.kt", max))
        assertNull(rules(denied = listOf("/kivra-agent/**")).reason("kivra-agent/src/A.kt", max))
    }

    @Test
    fun invalidPatternsAreRefusedAsAWholeWithAFixedMessage() {
        val secret = "PATTERNSECRET"
        val cases = listOf(
            List(PathRules.MAX_PATTERNS + 1) { "docs/$it/**" },
            listOf(""),
            listOf("a".repeat(PathRules.MAX_PATTERN_CHARS + 1)),
            listOf("docs/$secret\u0001/**"),
            listOf("docs/$secret\u007f/**"),
        )
        for (patterns in cases) {
            val e = assertFailsWith<PathPatternException> { PathRules.fromServer(patterns, emptyList()) }
            assertFalse(e.message.orEmpty().contains(secret), "the message names no pattern")
            assertFailsWith<PathPatternException> { PathRules.fromServer(emptyList(), patterns) }
        }
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        PathRules.fromServer(List(PathRules.MAX_PATTERNS) { "docs/$it/**" }, emptyList())
        PathRules.fromServer(listOf("a".repeat(PathRules.MAX_PATTERN_CHARS)), listOf("b"))
    }
}
