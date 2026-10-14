package net.kigawa.kivra.acceptance

/**
 * Fail-closed guard (issue #70): the acceptance JVM must not hold production credentials. Only the presence of
 * variable NAMES is inspected, values are never read or printed. `KIVRA_TEST_*` (the test database settings) is allowed.
 */
object AcceptanceGuard {
    private val forbiddenPrefixes = listOf("KIVRA_", "GITHUB_", "ANTHROPIC_", "EXKES_", "OPENAI_")
    private const val ALLOWED_PREFIX = "KIVRA_TEST_"

    /** Names of production-looking variables present in [env]. Empty = safe to run. */
    fun leakedNames(env: Map<String, String> = System.getenv()): List<String> =
        env.keys.filter { name ->
            !name.startsWith(ALLOWED_PREFIX) && forbiddenPrefixes.any { name.startsWith(it) }
        }.sorted()

    /** Throws before any scenario runs when a production credential name is visible to the test JVM. */
    fun require(env: Map<String, String> = System.getenv()) {
        val leaked = leakedNames(env)
        check(leaked.isEmpty()) {
            "acceptance tests must run without production credentials; unset these variables (names only): $leaked"
        }
    }
}
