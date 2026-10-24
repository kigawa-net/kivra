package net.kigawa.kivra.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Settings for `KIVRA_AUTH_MODE=oidc-jwt`. All of them are required except [jwksUrl], which defaults to
 * `<issuer>/protocol/openid-connect/certs`.
 *
 * - `KIVRA_OIDC_ISSUER`: the `iss` a token must carry, e.g. `https://user.kigawa.net/realms/kigawa-net`.
 * - `KIVRA_OIDC_JWKS_URL`: optional JWKS URL.
 * - `KIVRA_OIDC_AUDIENCE`: the `aud` a token must contain, e.g. `kivra-dev`.
 * - `KIVRA_OIDC_REQUIRED_ROLE`: `<client>:<role>`, e.g. `admin-panel:admin`. The token must list the role in
 *   `resource_access.<client>.roles`.
 * - `KIVRA_OIDC_MEMBER_ROLE`: optional, `<client>:<role>`, e.g. `kivra:member`. A token with this role (and none of the operator role) is
 *   authenticated as a member ([MemberKind.MEMBER_ONLY]); it is not an operator. Authorization is decided elsewhere. It must differ from
 *   `KIVRA_OIDC_REQUIRED_ROLE`, and (checked where the agent API reads its own variables) from `KIVRA_AGENT_REQUIRED_ROLE`. Unset (the default): nothing changes.
 */
data class OidcJwtConfig(
    val issuer: String,
    val jwksUrl: String,
    val audience: String,
    val requiredClient: String,
    val requiredRole: String,
    val memberRole: RequiredRole? = null,
) {
    init {
        require(memberRole == null || memberRole != RequiredRole(requiredClient, requiredRole)) {
            "KIVRA_OIDC_MEMBER_ROLE must differ from KIVRA_OIDC_REQUIRED_ROLE"
        }
    }

    companion object {
        fun fromEnv(env: Map<String, String>): OidcJwtConfig {
            val issuer = required(env, "KIVRA_OIDC_ISSUER")
            val jwksUrl = env["KIVRA_OIDC_JWKS_URL"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${issuer.trimEnd('/')}/protocol/openid-connect/certs"
            val audience = required(env, "KIVRA_OIDC_AUDIENCE")
            val role = required(env, "KIVRA_OIDC_REQUIRED_ROLE")
            val parts = role.split(":")
            require(parts.size == 2 && parts.all { it.isNotBlank() }) {
                "KIVRA_OIDC_REQUIRED_ROLE must be <client>:<role>"
            }
            val operatorRole = RequiredRole(parts[0].trim(), parts[1].trim())
            val memberRole = env["KIVRA_OIDC_MEMBER_ROLE"]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { RequiredRole.parse(it, "KIVRA_OIDC_MEMBER_ROLE") }
            require(memberRole == null || memberRole != operatorRole) {
                "KIVRA_OIDC_MEMBER_ROLE must differ from KIVRA_OIDC_REQUIRED_ROLE"
            }
            return OidcJwtConfig(issuer, jwksUrl, audience, operatorRole.client, operatorRole.role, memberRole)
        }

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("$name is required for KIVRA_AUTH_MODE=oidc-jwt")
    }
}

/**
 * The owner key of a verified JWT caller: `<n>:<iss>:<sub>`, where `n` is the UTF-8 byte length of `iss`. The explicit
 * length makes the encoding unambiguous: `iss` is read back by its length, so a `:` inside `iss` or `sub` can never make
 * two different (iss, sub) pairs produce the same key. (`iss="a:b", sub="c"` gives `3:a:b:c`; `iss="a", sub="b:c"` gives
 * `1:a:b:c`.) Only the verified issuer is used, and `sub` is kept as is (not trimmed). Null (fail closed) when [subject]
 * is null, blank or has control characters, or when the key would exceed [MAX_PRINCIPAL_LENGTH] (the DB column length).
 */
internal fun oidcOwnerKey(issuer: String, subject: String?): String? {
    if (subject == null || subject.isBlank() || subject.any { it.isISOControl() }) return null
    if (issuer.isBlank() || issuer.any { it.isISOControl() }) return null
    val issuerBytes = issuer.toByteArray(Charsets.UTF_8).size
    return "$issuerBytes:$issuer:$subject".takeIf { it.length <= MAX_PRINCIPAL_LENGTH }
}

/**
 * Which authenticating role a verified token carries. [LEGACY_ADMIN]: the operator role (`KIVRA_OIDC_REQUIRED_ROLE`, `admin-panel:admin`).
 * [MEMBER_ONLY]: only the optional member role (`KIVRA_OIDC_MEMBER_ROLE`), no operator role. [NONE]: neither (a valid token without a role).
 * Having the operator role wins when both are present. An agent token is never any of these (it is refused first).
 */
enum class MemberKind { LEGACY_ADMIN, MEMBER_ONLY, NONE }

/**
 * What a verified request carries (see [OidcJwtVerifier.caller]). [Member] is any valid token with its owner key; [kind] tells which role it
 * has ([operator] is true only for [MemberKind.LEGACY_ADMIN], so a member-only token is not an operator). [AgentRefused] is a token with the
 * agent role. [Unauthenticated] is everything else.
 */
sealed interface TaskCaller {
    data class Member(val principal: String, val kind: MemberKind) : TaskCaller {
        val operator: Boolean get() = kind == MemberKind.LEGACY_ADMIN
    }
    data object AgentRefused : TaskCaller
    data object Unauthenticated : TaskCaller
}

/**
 * Verifies a Keycloak access token and returns its principal, or null (-> 401) for any failure. The failure reason is
 * logged by exception class only, and is never sent to the client.
 *
 * Checks: `alg` must be RS256 (`none` and HS256 are rejected before any key lookup); signature against the key with the
 * token's `kid` from [jwks]; `iss` equals [OidcJwtConfig.issuer]; `aud` contains [OidcJwtConfig.audience]; `exp` is
 * present and not passed; `nbf` is not in the future. Clock skew is tolerated for [LEEWAY_SECONDS] seconds. The token
 * must contain the required client role.
 *
 * Owner (principal): the canonical key from the verified `iss` (the configured [OidcJwtConfig.issuer]) and `sub`, see
 * [oidcOwnerKey]. `email` and `preferred_username` are never used for ownership: they can change or be reassigned.
 * A token without a non-blank `sub` is rejected.
 *
 * Migration: runs created before this change are owned by an email (or username) and are not mapped to any `sub`. Their
 * owner key does not match, so they are unreachable (404) for every caller. An audited explicit mapping is a separate
 * issue; there is no automatic backfill.
 */
class OidcJwtVerifier(
    private val config: OidcJwtConfig,
    private val jwks: JwkProvider,
    /**
     * A role that marks the token as an agent. A token that verifies as an operator but also carries this role is
     * [OperatorAuth.AgentRefused] (ADR 0011 section 1). Null (the default) keeps the Task API's behaviour unchanged.
     */
    private val agentRole: RequiredRole? = null,
) : OperatorAuthenticator {
    init {
        require(agentRole == null || agentRole != config.memberRole) { "KIVRA_OIDC_MEMBER_ROLE must differ from KIVRA_AGENT_REQUIRED_ROLE" }
    }

    private val logger = LoggerFactory.getLogger(OidcJwtVerifier::class.java)

    fun asIdentity(): TaskApiIdentity = TaskApiIdentity { headers -> principal(headers) }

    suspend fun principal(headers: Headers): String? = (authenticate(headers) as? OperatorAuth.Authenticated)?.principal

    /**
     * Authenticates an operator request. Same checks as [principal]; the result also tells an agent token apart
     * ([OperatorAuth.AgentRefused], only when this verifier was built with an [agentRole]).
     */
    override suspend fun authenticate(headers: Headers): OperatorAuth = when (val caller = caller(headers)) {
        is TaskCaller.Member ->
            if (caller.operator) {
                OperatorAuth.Authenticated(caller.principal)
            } else {
                logRejection("RequiredRoleMissing")
                OperatorAuth.Unauthenticated
            }
        TaskCaller.AgentRefused -> OperatorAuth.AgentRefused
        TaskCaller.Unauthenticated -> OperatorAuth.Unauthenticated
    }

    /**
     * Authenticates a member request: an operator token ([MemberKind.LEGACY_ADMIN]) or a token with only the member role
     * ([MemberKind.MEMBER_ONLY]). A token with neither role is [MemberAuth.Unauthenticated]; an agent token is [MemberAuth.AgentRefused], even
     * when it also carries the member role. Authorization is not decided here. No route calls this yet (wired by the permission PR).
     */
    suspend fun authenticateMember(headers: Headers): MemberAuth = when (val caller = caller(headers)) {
        is TaskCaller.Member ->
            if (caller.kind == MemberKind.NONE) {
                logRejection("RequiredRoleMissing")
                MemberAuth.Unauthenticated
            } else {
                MemberAuth.Authenticated(caller.principal, caller.kind)
            }
        TaskCaller.AgentRefused -> MemberAuth.AgentRefused
        TaskCaller.Unauthenticated -> MemberAuth.Unauthenticated
    }

    /**
     * Identifies any valid caller, with or without the operator role (ADR 0012 section 4: a run's creator is not always an operator).
     * The checks are the same as [authenticate]; only the role decision differs. A token that fails any check is [TaskCaller.Unauthenticated].
     */
    suspend fun caller(headers: Headers): TaskCaller {
        val token = bearerToken(headers)
        if (token == null) {
            // Why there is no usable token. Fixed names only, rate limited like every other rejection. This is the case
            // where a logged-in browser's request reaches kivra without the access token (a proxy that does not forward it).
            logRejection(missingTokenReason(headers))
            return TaskCaller.Unauthenticated
        }
        // JWKS may be fetched over the network, so keep the blocking work off the Netty event loop.
        return withContext(Dispatchers.IO) {
            try {
                verify(token)
            } catch (e: Exception) {
                logRejection((e as? JwksLookupException)?.reasonCode ?: e.javaClass.simpleName)
                TaskCaller.Unauthenticated
            }
        }
    }

    /**
     * Logs why a token was rejected: a fixed reason or the exception class name only, never the token, a claim value or
     * the exception message. At WARN so that it shows at the default production level, rate limited per reason so that
     * unauthenticated traffic cannot flood the log. Without this a 401 cannot be told apart (expired token, wrong
     * audience, JWKS unreachable, missing role).
     */
    private fun logRejection(reason: String) {
        val now = System.nanoTime()
        var shouldLog = false
        lastRejectionLog.compute(reason) { _, previous ->
            if (previous == null || now - previous >= REJECTION_LOG_INTERVAL_MILLIS * 1_000_000L) {
                shouldLog = true
                now
            } else {
                previous
            }
        }
        if (shouldLog) logger.warn("OIDC access token rejected: reason={}", reason)
    }

    private val lastRejectionLog = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun reject(reason: String): TaskCaller {
        logRejection(reason)
        return TaskCaller.Unauthenticated
    }

    private fun verify(token: String): TaskCaller {
        val decoded = JWT.decode(token)
        if (decoded.algorithm != "RS256") return reject("AlgorithmNotRs256")
        val key = jwks.get(decoded.keyId).publicKey as? RSAPublicKey ?: return reject("SigningKeyNotRsa")
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .acceptLeeway(LEEWAY_SECONDS)
            .build()
            .verify(token)
        if (verified.expiresAt == null) return reject("MissingExp")
        val resourceAccess = verified.getClaim("resource_access").asMap()
        // An agent token is refused (403) before the operator role is looked at, so a token with only the agent role is refused too.
        // An agent is never an operator, even when the token carries the operator role as well (ADR 0011 section 1).
        if (agentRole != null && hasRole(resourceAccess, agentRole)) {
            logRejection("AgentRoleRefused")
            return TaskCaller.AgentRefused
        }
        val kind = memberKindOf(resourceAccess, config)

        val subject = verified.subject
        if (subject == null) return reject("MissingSubject")
        val principal = oidcOwnerKey(config.issuer, subject) ?: return reject("InvalidSubject")
        return TaskCaller.Member(principal, kind)
    }

    companion object {
        /** Clock-skew tolerance for exp / nbf. Must stay within 30 seconds. */
        const val LEEWAY_SECONDS = 30L

        /** The same rejection reason is logged at most once per minute. */
        const val REJECTION_LOG_INTERVAL_MILLIS = 60_000L

        /** Upper bound on the token length, checked before parsing. */
        const val MAX_TOKEN_LENGTH = 16 * 1024

        /**
         * The access token from `Authorization: Bearer <token>`. When there is no Authorization header, from
         * `X-Forwarded-Access-Token`. Null when the value is missing, is not a Bearer credential, or is not shaped like a
         * JWT (three dot-separated parts).
         */
        /** A fixed name for why [bearerToken] found nothing. Never the header value. */
        internal fun missingTokenReason(headers: Headers): String {
            val authorization = headers[HttpHeaders.Authorization]
            val token = if (authorization != null) {
                val trimmed = authorization.trim()
                if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return "AuthorizationNotBearer"
                trimmed.substring("Bearer ".length).trim()
            } else {
                headers["X-Forwarded-Access-Token"]?.trim()
                    ?: return "NoAuthorizationAndNoForwardedToken"
            }
            return when {
                token.isEmpty() -> "EmptyToken"
                token.length > MAX_TOKEN_LENGTH -> "TokenTooLong"
                else -> "TokenNotJwtShaped"
            }
        }

        internal fun bearerToken(headers: Headers): String? {
            val authorization = headers[HttpHeaders.Authorization]
            val token = if (authorization != null) {
                val trimmed = authorization.trim()
                if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
                trimmed.substring("Bearer ".length).trim()
            } else {
                headers["X-Forwarded-Access-Token"]?.trim()
            }
            if (token.isNullOrEmpty() || token.length > MAX_TOKEN_LENGTH) return null
            return token.takeIf { it.count { c -> c == '.' } == 2 }
        }
    }
}
