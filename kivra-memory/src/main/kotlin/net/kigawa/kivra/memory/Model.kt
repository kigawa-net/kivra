package net.kigawa.kivra.memory

import java.time.Instant

enum class MemoryKind { EPISODIC, SEMANTIC, PREFERENCE, PROCEDURAL }

/** Fact classification (design #6 section 3.2). */
enum class MemoryProvenance { USER_STATED, PRIMARY_SOURCE, AGENT_OBSERVED, AGENT_INFERRED }

/** Ordered: a higher ordinal is more sensitive. RESTRICTED bodies are never stored in recl (design #6 section 7.1). */
enum class Sensitivity { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

enum class MemoryStatus { ACTIVE, SUPERSEDED, DELETED }

enum class SourceType { GITHUB_COMMIT, GITHUB_PR, GITHUB_ISSUE, FILE, EXKES_ARTIFACT, EXKES_LOG, FUSHA_TASK, USER_MESSAGE, URL }

@JvmInline
value class MemoryId(val value: String) {
    init {
        require(ID.matches(value)) { "invalid memory id" }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9._-]{1,128}")
        fun isValid(raw: String): Boolean = ID.matches(raw)
    }
}

/** Citation tag `M:<id>@v<n>` (design #6 section 5.3). */
@JvmInline
value class CitationId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid citation id" }
    }

    val memoryId: MemoryId get() = MemoryId(value.substring(2, value.lastIndexOf("@v")))
    val version: Int get() = value.substring(value.lastIndexOf("@v") + 2).toInt()

    companion object {
        private val PATTERN = Regex("M:[A-Za-z0-9._-]{1,128}@v[0-9]{1,9}")
        fun of(id: MemoryId, version: Int): CitationId = CitationId("M:${id.value}@v$version")
    }
}

/**
 * Scope convention (design #6 section 3.3):
 * `tenant:<id>` [ `/user:<id>` | `/project:<id>` [ `/exec:<id>` ] ].
 * Whether recl treats scopes hierarchically (prefix match) is unconfirmed (要確認); [isWithin] is Kivra's own,
 * client-side notion used to compute and verify allowed scopes.
 */
@JvmInline
value class ScopeRef private constructor(val value: String) {
    /** True when this scope equals [ancestor] or is nested under it. */
    fun isWithin(ancestor: ScopeRef): Boolean = value == ancestor.value || value.startsWith(ancestor.value + "/")

    override fun toString(): String = "ScopeRef($value)"

    companion object {
        private val SEG = Regex("[A-Za-z0-9._-]{1,128}")

        fun parse(raw: String): ScopeRef {
            val parts = raw.split('/')
            require(parts.size in 1..3) { "invalid scope" }
            val kinds = parts.map { p ->
                val i = p.indexOf(':')
                require(i > 0 && SEG.matches(p.substring(i + 1))) { "invalid scope segment" }
                p.substring(0, i)
            }
            require(kinds[0] == "tenant") { "scope must start with tenant" }
            if (kinds.size >= 2) require(kinds[1] == "user" || kinds[1] == "project") { "invalid scope level" }
            if (kinds.size == 3) require(kinds[1] == "project" && kinds[2] == "exec") { "exec scope requires a project" }
            return ScopeRef(raw)
        }

        fun tenant(id: String) = parse("tenant:$id")
        fun user(tenant: String, user: String) = parse("tenant:$tenant/user:$user")
        fun project(tenant: String, project: String) = parse("tenant:$tenant/project:$project")
        fun exec(tenant: String, project: String, executionId: String) = parse("tenant:$tenant/project:$project/exec:$executionId")
    }
}

/** Where a memory came from (design #6 section 3.4). Large artifacts are referenced, never embedded. */
data class SourceRef(
    val type: SourceType,
    val uri: String,
    val locator: String? = null,
    val retrievedAt: Instant,
    val contentHash: String? = null,
) {
    init {
        require(uri.isNotBlank() && uri.length <= 2048 && uri.none { it.isISOControl() }) { "invalid source uri" }
        require(!USERINFO.containsMatchIn(uri)) { "source uri must not carry credentials" }
        require(!SecretFilter.containsSecret(uri)) { "source uri looks like it contains a secret" }
        if (type == SourceType.URL) require(uri.startsWith("https://")) { "url sources must be https" }
        require(locator == null || (locator.length <= 512 && locator.none { it.isISOControl() })) { "invalid locator" }
        require(contentHash == null || HASH.matches(contentHash)) { "invalid content hash" }
    }

    private companion object {
        val USERINFO = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*@")
        val HASH = Regex("[A-Za-z0-9:_-]{8,128}")
    }
}

/**
 * Who is asking and why (design #6 section 4.1). [allowedScopes] is computed by Kivra (Policy, #32), never by the
 * caller of an adapter; recl's own scope check is the second line of defence. Memory is advisory and is never an
 * input to authorization decisions.
 */
data class AccessContext(
    val allowedScopes: Set<ScopeRef>,
    val executionId: String,
    val taskId: String,
    val agentProfile: String,
    val traceId: String,
    val purpose: String,
) {
    init {
        require(allowedScopes.isNotEmpty()) { "allowedScopes must not be empty" }
        require(executionId.isNotBlank() && taskId.isNotBlank() && traceId.isNotBlank()) { "ids must not be blank" }
        require(SAFE.matches(traceId) && SAFE.matches(executionId) && SAFE.matches(taskId)) { "ids contain unsafe characters" }
    }

    /** True when [scope] is within one of the allowed scopes. */
    fun permits(scope: ScopeRef): Boolean = allowedScopes.any { scope.isWithin(it) }

    private companion object {
        val SAFE = Regex("[A-Za-z0-9._:/-]{1,200}")
    }
}
