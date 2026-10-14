package net.kigawa.kivra.acceptance

/**
 * Fail-closed guard (issue #70): the acceptance JVM must not hold production secrets or production connection settings.
 * Only variable NAMES are inspected. Values are never read or printed.
 *
 * A name is reported when:
 * 1. it looks like a secret (suffix `_TOKEN`, `_KEY`, `_SECRET`, `_PASSWORD`, `_PASSWD`, `_PAT`, `_CREDENTIAL(S)`, `_AUTH`, or an
 *    infix `_TOKEN_`, `_SECRET_`, `_PASSWORD_`, `_PAT_`), whatever its prefix, including names on the standard allowlist; or
 * 2. it has a service-specific prefix (`KIVRA_`, `ANTHROPIC_`, `EXKES_`, `OPENAI_`) even if it does not look like a secret.
 *
 * Standard GitHub Actions variables (`GITHUB_RUN_ID`, `GITHUB_SHA`, ...) are allowed. Unknown `GITHUB_*` names are not reported,
 * so new runner variables do not break CI. `KIVRA_TEST_*` (test database settings) is always allowed.
 */
object AcceptanceGuard {
    private const val ALLOWED_PREFIX = "KIVRA_TEST_"
    private val secretSuffixes = listOf("_TOKEN", "_KEY", "_SECRET", "_PASSWORD", "_PASSWD", "_PAT", "_CREDENTIAL", "_CREDENTIALS", "_AUTH")
    private val secretInfixes = listOf("_TOKEN_", "_SECRET_", "_PASSWORD_", "_PAT_")
    private val servicePrefixes = listOf("KIVRA_", "ANTHROPIC_", "EXKES_", "OPENAI_")

    /** Standard, non-secret variables set by GitHub Actions. A name here is still reported when it looks like a secret. */
    private val standardGithubNames = setOf(
        "GITHUB_ACTIONS", "GITHUB_ACTOR", "GITHUB_ACTOR_ID", "GITHUB_API_URL", "GITHUB_BASE_REF", "GITHUB_ENV",
        "GITHUB_EVENT_NAME", "GITHUB_EVENT_PATH", "GITHUB_GRAPHQL_URL", "GITHUB_HEAD_REF", "GITHUB_JOB", "GITHUB_OUTPUT",
        "GITHUB_PATH", "GITHUB_REF", "GITHUB_REF_NAME", "GITHUB_REF_PROTECTED", "GITHUB_REF_TYPE", "GITHUB_REPOSITORY",
        "GITHUB_REPOSITORY_ID", "GITHUB_REPOSITORY_OWNER", "GITHUB_REPOSITORY_OWNER_ID", "GITHUB_RETENTION_DAYS",
        "GITHUB_RUN_ATTEMPT", "GITHUB_RUN_ID", "GITHUB_RUN_NUMBER", "GITHUB_SERVER_URL", "GITHUB_SHA", "GITHUB_STATE",
        "GITHUB_STEP_SUMMARY", "GITHUB_TRIGGERING_ACTOR", "GITHUB_WORKFLOW", "GITHUB_WORKFLOW_REF", "GITHUB_WORKFLOW_SHA",
        "GITHUB_WORKSPACE",
    )

    fun looksLikeSecret(name: String): Boolean =
        secretSuffixes.any { name.endsWith(it) } || secretInfixes.any { name.contains(it) }

    /** Names of variables in [env] that must not be visible to the acceptance tests, sorted. Values are never included. */
    fun leakedNames(env: Map<String, String> = System.getenv()): List<String> =
        env.keys.filter { name ->
            when {
                name.startsWith(ALLOWED_PREFIX) -> false
                looksLikeSecret(name) -> true
                name in standardGithubNames -> false
                else -> servicePrefixes.any { name.startsWith(it) }
            }
        }.sorted()

    /** Throws before any scenario runs when a secret-like or production-prefixed variable name is visible to the test JVM. */
    fun require(env: Map<String, String> = System.getenv()) {
        val leaked = leakedNames(env)
        check(leaked.isEmpty()) {
            "acceptance tests must run without production secrets; unset these variables (names only): $leaked"
        }
    }
}
