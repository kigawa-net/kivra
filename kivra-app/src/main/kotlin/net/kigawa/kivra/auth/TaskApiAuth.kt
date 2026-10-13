package net.kigawa.kivra.auth

/**
 * How the Task API (`/api/tasks`, `/api/runs`) identifies its caller. Set by `KIVRA_AUTH_MODE`.
 *
 * - [OAUTH2_PROXY]: the caller is identified by the `X-Auth-Request-Email` / `X-Auth-Request-User` headers that
 *   oauth2-proxy sets (`--set-xauthrequest=true`). Requests without a valid identity get 401.
 *   **Precondition:** kivra is reachable only through oauth2-proxy (NetworkPolicy, applied outside this code). Anyone who
 *   can reach kivra directly can forge these headers.
 * - [NONE]: no authentication, every caller is one shared principal. Only for `KIVRA_RUNTIME_ENV=development`.
 */
enum class TaskApiAuthMode { OAUTH2_PROXY, NONE }

/** Principal used in [TaskApiAuthMode.NONE]. It is never a real identity. */
const val DEVELOPMENT_ANONYMOUS_PRINCIPAL = "development-anonymous"

const val MAX_PRINCIPAL_LENGTH = 256

/**
 * Resolves `KIVRA_AUTH_MODE`. Returns null when unset: the Task API routes are then not registered at all (fail-closed).
 * Throws [IllegalArgumentException] (startup failure) for an unknown value, or for `none` outside `KIVRA_RUNTIME_ENV=development`.
 */
fun resolveTaskApiAuthMode(env: Map<String, String>): TaskApiAuthMode? {
    val raw = env["KIVRA_AUTH_MODE"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (raw) {
        "oauth2-proxy" -> TaskApiAuthMode.OAUTH2_PROXY
        "none" -> {
            require(env["KIVRA_RUNTIME_ENV"] == "development") {
                "KIVRA_AUTH_MODE=none is allowed only with KIVRA_RUNTIME_ENV=development"
            }
            TaskApiAuthMode.NONE
        }
        else -> throw IllegalArgumentException("KIVRA_AUTH_MODE must be oauth2-proxy or none")
    }
}

/**
 * Principal for a request. In OAUTH2_PROXY mode it is the email when present, otherwise the user. Null (-> 401) when the
 * value is missing, empty, longer than [MAX_PRINCIPAL_LENGTH] or contains control characters.
 */
fun principalFromHeaders(mode: TaskApiAuthMode, email: String?, user: String?): String? {
    if (mode == TaskApiAuthMode.NONE) return DEVELOPMENT_ANONYMOUS_PRINCIPAL
    val candidate = (email?.takeIf { it.isNotBlank() } ?: user)?.trim() ?: return null
    if (candidate.isEmpty() || candidate.length > MAX_PRINCIPAL_LENGTH) return null
    if (candidate.any { it.isISOControl() }) return null
    return candidate
}
