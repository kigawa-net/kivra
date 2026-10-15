package net.kigawa.kivra.acceptance

/**
 * Fail-closed guard (issue #70). The acceptance tests must not be able to read the production credentials or production
 * connection settings that Kivra itself (and its adapters) read. Only variable NAMES are inspected, never values.
 *
 * Scope: what the code under test can read, not everything in the developer's shell. A variable is reported when its name
 * matches one of the following:
 * - prefix `KIVRA_` (except `KIVRA_TEST_`, the test database settings);
 * - prefixes of adapters that are not wired in yet, so they cannot slip in later: `ANTHROPIC_`, `OPENAI_`, `EXKES_`,
 *   `RECL_`, `AI_SCHEDULER_`. (`FUSHA_` is not listed: Kivra reads `KIVRA_FUSHA_*`, and `FUSHA_*` belongs to developer tools.)
 *
 * GitHub credentials are not reported yet. The current `src/main` does not read any GitHub credential from the environment
 * (checked with `git grep` on 2026-10-15). When a GitHub adapter is wired from environment variables, add its names
 * (for example `GITHUB_TOKEN`, `GH_TOKEN`, `GITHUB_PAT_TOKEN`, `GITHUB_APP_*`) to the detection list in the same change.
 *
 * Basis (`git grep` over the src/main trees on 2026-10-15). The environment variables that production code reads are:
 * - `kivra-app/src/main/resources/application.conf` (`${?...}`): KIVRA_DB_JDBC_URL, KIVRA_DB_USER, KIVRA_DB_PASSWORD,
 *   KIVRA_DB_MIGRATION_USER, KIVRA_DB_MIGRATION_PASSWORD, KIVRA_LEASE_TTL_SECONDS, KIVRA_LEASE_GRACE_SECONDS,
 *   KIVRA_BUDGET_TENANT_ID, KIVRA_BUDGET_MONTHLY_LIMIT_JPY, KIVRA_BUDGET_SOFT_RATIO, KIVRA_METRICS_ENABLED,
 *   KIVRA_METRICS_BEARER_TOKEN, KIVRA_FUSHA_BASE_URL, KIVRA_FUSHA_ENV, KIVRA_FUSHA_TOKEN, KIVRA_FUSHA_REQUEST_TIMEOUT_MS,
 *   KIVRA_FUSHA_MAX_RETRIES, KIVRA_FUSHA_VIEW_ID, KIVRA_FUSHA_ASSIGNEE_ID, KIVRA_FUSHA_POLL_INTERVAL_MS,
 *   KIVRA_FUSHA_STATUS_WRITE_ENABLED (and PORT, which is not a credential).
 * - Kotlin literals: KIVRA_AUTH_MODE, KIVRA_RUNTIME_ENV, KIVRA_OIDC_ISSUER, KIVRA_OIDC_JWKS_URL, KIVRA_OIDC_AUDIENCE,
 *   KIVRA_OIDC_REQUIRED_ROLE, KIVRA_ENVIRONMENT, KIVRA_DEV_DEMO_ENABLED, KIVRA_FX_USD_JPY, KIVRA_FX_MAX_AGE_DAYS,
 *   KIVRA_FX_MARGIN_RATIO, KIVRA_FX_AS_OF, KIVRA_TAX_MARGIN_RATIO.
 * - `kivra-model-gateway` reads the secret from a variable named by the deployment-owned `CredentialBinding.secretEnvVar`.
 *   No such name is in source, so it is not enumerated here. Name it with one of the prefixes above when wiring it.
 *
 * Not reported, on purpose: standard GitHub Actions variables (`GITHUB_ACTIONS`, `GITHUB_SHA`, `GITHUB_RUN_ID`, ...) and
 * unrelated developer secrets (`BWS_ACCESS_TOKEN`, `CLOUDFLARE_API_TOKEN`, `AWS_SECRET_ACCESS_KEY`, ...). Kivra does not read
 * them, so reporting them would only break local runs without protecting anything.
 */
object AcceptanceGuard {
    private const val ALLOWED_PREFIX = "KIVRA_TEST_"
    private val forbiddenPrefixes = listOf(
        "KIVRA_", "ANTHROPIC_", "OPENAI_", "EXKES_", "RECL_", "AI_SCHEDULER_",
    )

    /** Names of variables in [env] that the acceptance tests must not see, sorted. Values are never included. */
    fun leakedNames(env: Map<String, String> = System.getenv()): List<String> =
        env.keys.filter { name ->
            !name.startsWith(ALLOWED_PREFIX) &&
                forbiddenPrefixes.any { name.startsWith(it) }
        }.sorted()

    /** Throws before any scenario runs when a production-scoped variable name is visible to the test JVM. */
    fun require(env: Map<String, String> = System.getenv()) {
        val leaked = leakedNames(env)
        check(leaked.isEmpty()) {
            "acceptance tests must run without production credentials or settings; unset these variables (names only): $leaked"
        }
    }
}
