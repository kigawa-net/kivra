package net.kigawa.kivra.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

data class GitHubAdapterConfig(
    val apiBase: String = "https://api.github.com",
    /** Total attempts for a GET (first try + retries). POST is never retried. */
    val maxGetAttempts: Int = 3,
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
    val maxPages: Int = 10,
    /** An operator-approved host allowlist. GHES installations must opt in explicitly. */
    val trustedApiHosts: Set<String> = setOf("api.github.com"),
    /** Ceilings for [GitHubPort.commitFiles], checked before any request. */
    val commitFiles: CommitFilesLimits = CommitFilesLimits(),
) {
    init {
        require(commitFiles.maxFiles > 0 && commitFiles.maxFileBytes > 0 && commitFiles.maxTotalBytes > 0 &&
            commitFiles.maxPathChars > 0 && commitFiles.maxMessageChars > 0) { "commitFiles limits must be positive" }
        // Messages are fixed strings and the URI parse failure is not chained: both could echo the configured URL.
        val uri = try {
            java.net.URI(apiBase)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid GitHub API base URL")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "GitHub API base URL must be HTTPS without credentials, query or fragment"
        }
        require(uri.port == -1 || uri.port == 443) { "GitHub API base URL must use the default HTTPS port" }
        val host = uri.host?.lowercase()
        require(host != null && isPublicDnsName(host)) {
            "GitHub API host must be a DNS name (no IP literal, localhost or internal name)"
        }
        require(host in trustedApiHosts.map { it.lowercase() }) {
            "GitHub API endpoint is not in the approved host allowlist"
        }
        require(trustedApiHosts.all { isPublicDnsName(it.lowercase()) }) {
            "trusted API host allowlist entries must be DNS names"
        }
        require(uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." || it.contains('\\') }) {
            "GitHub API URL must not contain path traversal"
        }
    }

    private companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** Accepts a multi-label DNS name only; rejects IP literals (v4/v6), single-label and local/internal names. */
        fun isPublicDnsName(h: String): Boolean {
            if (h.isEmpty() || h.contains(':') || h.startsWith('[') || IPV4.matches(h)) return false
            if (!h.contains('.') || h.endsWith('.')) return false
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
                h.endsWith(".internal") || h.endsWith(".localdomain") || h.endsWith(".lan")
            ) return false
            return h.split('.').all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        }
    }
}

/** Upper bound of one issue response. A GitHub issue body is at most 65,536 characters; the JSON around it is small. */
internal const val ISSUE_MAX_RESPONSE_BYTES = 512 * 1024

/** Upper bound of a compare answer read for the branch update check (at most 300 files, each with a patch). */
internal const val COMPARE_FILES_MAX_RESPONSE_BYTES = 4 * 1024 * 1024

/** Builds the HTTP client with timeouts. Tests pass a MockEngine here. */
fun githubHttpClient(engine: HttpClientEngine, config: GitHubAdapterConfig = GitHubAdapterConfig()): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        // GitHub API responses must never redirect a bearer-authenticated request to another origin.
        // Redirects are returned as non-2xx and classified by the adapter, not followed.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
        }
    }

/**
 * REST adapter. Every response is verified to be about the requested object (owner/repo/number/sha/branch):
 * a mismatch is `IDENTITY_MISMATCH`, never silently accepted (lesson from the PR #47 review).
 */
class HttpGitHubAdapter(
    private val client: HttpClient,
    private val tokens: TokenProvider,
    private val config: GitHubAdapterConfig = GitHubAdapterConfig(),
    private val backoff: suspend (attempt: Int) -> Unit = { delay(200L * (1L shl (it - 1))) },
) : GitHubPort, GitHubIssueReader, PrBranchPort {
    private val json = Json { ignoreUnknownKeys = true }

    private class Raw(val status: Int, val body: String, val headers: Map<String, String>)

    private sealed interface Transport {
        class Ok(val raw: Raw) : Transport
        class Error(val message: String) : Transport

        /** The body is longer than the caller's limit. It is not decoded, and it is not retried. */
        data object TooLarge : Transport
    }

    /**
     * One request. With [maxBodyBytes] the body is read only up to that limit; a longer body is [Transport.TooLarge].
     */
    private suspend fun send(method: HttpMethod, path: String, body: JsonObject?, maxBodyBytes: Int? = null): Transport = try {
        val resp: HttpResponse = client.request(config.apiBase.trimEnd('/') + path) {
            this.method = method
            header("Authorization", "Bearer ${tokens.token()}")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val text = if (maxBodyBytes == null) resp.bodyAsText() else readBounded(resp, maxBodyBytes)
        if (text == null) {
            Transport.TooLarge
        } else {
            Transport.Ok(Raw(resp.status.value, text, resp.headers.entries().associate { it.key.lowercase() to it.value.first() }))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Exception messages from HTTP engines may contain URLs, headers or secrets: retain only the type.
        Transport.Error("GitHub transport failure (${e::class.simpleName ?: "Exception"})")
    }

    /** The body as UTF-8 text, or null when it is longer than [maxBytes]. Reads at most [maxBytes] + 1 bytes. */
    private suspend fun readBounded(resp: HttpResponse, maxBytes: Int): String? {
        val bytes = resp.bodyAsChannel().readRemaining(maxBytes + 1L).readBytes()
        return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
    }

    /** GET with retry on 5xx / I/O and one token refresh on 401. Returns the raw 2xx response or a classified failure. */
    private suspend fun get(path: String, maxBodyBytes: Int? = null): GitHubResult<Raw> {
        var refreshed = false
        var attempt = 1
        while (true) {
            when (val t = send(HttpMethod.Get, path, null, maxBodyBytes)) {
                is Transport.TooLarge -> return GitHubResult.Failure(GitHubFailureKind.TOO_LARGE, GitHubFailureKind.TOO_LARGE.code)
                is Transport.Error ->
                    if (attempt >= config.maxGetAttempts) return GitHubResult.Failure(GitHubFailureKind.NETWORK, t.message)
                is Transport.Ok -> {
                    val r = t.raw
                    when {
                        r.status in 200..299 -> return GitHubResult.Success(r)
                        r.status == 401 && !refreshed -> {
                            refreshed = true
                            tokens.invalidate()
                            continue
                        }
                        r.status >= 500 && attempt < config.maxGetAttempts -> {}
                        else -> return classify(r)
                    }
                }
            }
            backoff(attempt)
            attempt++
        }
    }

    /** POST: exactly one send. Transport error or 5xx means the outcome is unknown. */
    private suspend fun post(operation: String, path: String, body: JsonObject): GitHubResult<Raw> =
        mutate(HttpMethod.Post, operation, path, body)

    /**
     * PATCH (ref update): exactly one send, same outcome rules as POST. A 422 is a rejected non-fast-forward update
     * (another writer moved the branch first), so it is `CONFLICT`.
     */
    private suspend fun patch(operation: String, path: String, body: JsonObject): GitHubResult<Raw> =
        mutate(HttpMethod.Patch, operation, path, body, unprocessableIsConflict = true)

    private suspend fun mutate(
        method: HttpMethod,
        operation: String,
        path: String,
        body: JsonObject,
        unprocessableIsConflict: Boolean = false,
    ): GitHubResult<Raw> =
        when (val t = send(method, path, body)) {
            is Transport.Error -> GitHubResult.Unknown(operation, t.message)
            // Not reachable: a mutation reads the whole body (no limit is passed).
            is Transport.TooLarge -> GitHubResult.Unknown(operation, "response too large")
            is Transport.Ok -> when {
                t.raw.status in 200..299 -> GitHubResult.Success(t.raw)
                // The server received the request but we did not follow the redirect: the outcome is unknown.
                t.raw.status >= 500 || t.raw.status in 300..399 -> GitHubResult.Unknown(operation, "HTTP ${t.raw.status}")
                else -> {
                    if (t.raw.status == 401) tokens.invalidate()
                    if (unprocessableIsConflict && t.raw.status == 422) {
                        GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code, t.raw.status)
                    } else {
                        classify(t.raw)
                    }
                }
            }
        }

    /**
     * Maps a non-2xx response to a fixed machine-readable code. The provider's body (`message`, raw text) is read only
     * for classification and is never copied into the result, logs or audit.
     */
    private fun classify(r: Raw): GitHubResult.Failure {
        val providerText = runCatching { json.parseToJsonElement(r.body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            .orEmpty()
        val retryAfter = r.headers["retry-after"]?.toLongOrNull()
            ?: r.headers["x-ratelimit-reset"]?.toLongOrNull()?.let { (it - System.currentTimeMillis() / 1000).coerceAtLeast(0) }
        val rateLimited = r.status == 429 ||
            (r.status == 403 && (r.headers["x-ratelimit-remaining"] == "0" || r.headers.containsKey("retry-after") ||
                providerText.contains("rate limit", ignoreCase = true)))
        val kind = when {
            rateLimited -> GitHubFailureKind.RATE_LIMITED
            r.status in 300..399 -> GitHubFailureKind.REDIRECT
            r.status == 401 -> GitHubFailureKind.AUTH
            r.status == 403 -> GitHubFailureKind.PERMISSION
            r.status == 404 -> GitHubFailureKind.NOT_FOUND
            r.status == 409 -> GitHubFailureKind.CONFLICT
            r.status == 422 && providerText.contains("already exists", ignoreCase = true) -> GitHubFailureKind.CONFLICT
            r.status >= 500 -> GitHubFailureKind.SERVER_ERROR
            else -> GitHubFailureKind.VALIDATION
        }
        return GitHubResult.Failure(kind, kind.code, r.status, if (kind == GitHubFailureKind.RATE_LIMITED) retryAfter else null)
    }

    // ---- parsing / identity verification ----

    private fun parse(raw: Raw): JsonElement? = runCatching { json.parseToJsonElement(raw.body) }.getOrNull()

    private fun protocol(what: String) = GitHubResult.Failure(GitHubFailureKind.PROTOCOL, "unexpected response shape: $what")

    /** Response disagrees with the request. The response-derived detail is deliberately not kept. */
    private fun mismatch() = GitHubResult.Failure(GitHubFailureKind.IDENTITY_MISMATCH, GitHubFailureKind.IDENTITY_MISMATCH.code)

    private fun JsonObject.str(vararg path: String): String? {
        var cur: JsonElement = this
        for (p in path) cur = (cur as? JsonObject)?.get(p) ?: return null
        return (cur as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.nestedLong(vararg path: String): Long? {
        var cur: JsonElement = this
        for (p in path) cur = (cur as? JsonObject)?.get(p) ?: return null
        return (cur as? JsonPrimitive)?.longOrNull
    }

    private fun sameRepo(fullName: String?, repo: RepoRef) = fullName != null && fullName.equals(repo.toString(), ignoreCase = true)

    private val sha40 = Regex("[0-9a-f]{40}")
    private val compareCountLiteral = Regex("0|[1-9][0-9]{0,9}")

    /** Lookup failures that decide which PR is ours; reported even when the create outcome was unknown. */
    private val IDENTITY_DECISIONS = setOf(
        GitHubFailureKind.FOREIGN_OBJECT,
        GitHubFailureKind.IDENTITY_MISMATCH,
        GitHubFailureKind.CONFLICT,
    )

    private fun requireSha(sha: String) = require(sha40.matches(sha)) { "sha must be 40 lowercase hex chars: $sha" }

    private fun repoPath(repo: RepoRef) = "/repos/${repo.owner}/${repo.repo}"

    /**
     * `URLEncoder` leaves `.` and `..` unchanged, so a dot segment would traverse the API path. Such a branch is
     * rejected before any request is built (issue #138); the message never echoes the branch name.
     */
    private fun requireSafeBranch(branch: String) =
        require(branch.split('/').none { it == "." || it == ".." }) { "branch name contains a dot segment" }

    private fun refPath(branch: String): String {
        requireSafeBranch(branch)
        return branch.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
    }

    private fun parseBranchRef(repo: RepoRef, branch: String, raw: Raw): GitHubResult<BranchInfo> {
        val o = parse(raw) as? JsonObject ?: return protocol("ref")
        val ref = o.str("ref") ?: return protocol("ref.ref")
        if (ref != "refs/heads/$branch") return mismatch()
        val sha = o.str("object", "sha")?.takeIf { sha40.matches(it) } ?: return protocol("ref.object.sha")
        return GitHubResult.Success(BranchInfo(repo, branch, sha))
    }

    private fun parsePr(repo: RepoRef, o: JsonObject): GitHubResult<PullRequest> {
        val number = o.long("number")?.toInt() ?: return protocol("pr.number")
        val headRef = o.str("head", "ref") ?: return protocol("pr.head.ref")
        val headSha = o.str("head", "sha")?.takeIf { sha40.matches(it) } ?: return protocol("pr.head.sha")
        val baseRef = o.str("base", "ref") ?: return protocol("pr.base.ref")
        val baseRepo = o.str("base", "repo", "full_name") ?: return protocol("pr.base.repo.full_name")
        if (!sameRepo(baseRepo, repo)) return mismatch()
        val state = when (o.str("state")) {
            "open" -> PullRequestState.OPEN
            "closed" -> PullRequestState.CLOSED
            else -> return protocol("pr.state")
        }
        return GitHubResult.Success(
            PullRequest(
                repo = repo,
                number = number,
                state = state,
                merged = o.bool("merged") ?: (o.str("merged_at") != null),
                draft = o.bool("draft") ?: false,
                headRef = headRef,
                headSha = headSha,
                baseRef = baseRef,
                body = o.str("body") ?: "",
                htmlUrl = o.str("html_url") ?: "",
                nodeId = o.str("node_id"),
                baseRepositoryId = o.nestedLong("base", "repo", "id"),
                mergeCommitSha = o.str("merge_commit_sha")?.takeIf { sha40.matches(it) },
                authorId = o.nestedLong("user", "id"),
                labels = (o["labels"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") },
            ),
        )
    }

    // ---- branches ----

    override suspend fun getBranch(repo: RepoRef, branch: String): GitHubResult<BranchInfo?> =
        when (val r = get("${repoPath(repo)}/git/ref/heads/${refPath(branch)}")) {
            is GitHubResult.Success -> parseBranchRef(repo, branch, r.value).map { it }
            // A missing ref and a missing/inaccessible repo are both 404: confirm the repo before concluding "no branch".
            is GitHubResult.Failure ->
                if (r.kind != GitHubFailureKind.NOT_FOUND) r
                else when (val repoCheck = get(repoPath(repo))) {
                    is GitHubResult.Success -> GitHubResult.Success(null)
                    is GitHubResult.Failure -> repoCheck
                    is GitHubResult.Unknown -> repoCheck
                }
            is GitHubResult.Unknown -> r
        }

    override suspend fun createBranch(repo: RepoRef, branch: String, baseSha: String): GitHubResult<BranchInfo> {
        requireSafeBranch(branch)
        requireSha(baseSha)
        val body = buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", baseSha)
        }
        return when (val r = post("createBranch", "${repoPath(repo)}/git/refs", body)) {
            is GitHubResult.Success -> parseBranchRef(repo, branch, r.value).let { res ->
                // The new ref must point at the requested base.
                if (res is GitHubResult.Success && res.value.headSha != baseSha) mismatch() else res
            }
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }
    }

    override suspend fun ensureRunBranch(repo: RepoRef, runId: String, baseSha: String): GitHubResult<EnsuredBranch> {
        val name = RunBranch.name(runId)
        when (val existing = getBranch(repo, name)) {
            is GitHubResult.Success -> existing.value?.let { return GitHubResult.Success(EnsuredBranch(it, created = false)) }
            is GitHubResult.Failure -> return existing
            is GitHubResult.Unknown -> return existing
        }
        return when (val c = createBranch(repo, name, baseSha)) {
            is GitHubResult.Success -> GitHubResult.Success(EnsuredBranch(c.value, created = true))
            is GitHubResult.Failure ->
                if (c.kind == GitHubFailureKind.CONFLICT) reuse(repo, name, c) else c
            is GitHubResult.Unknown -> reuseAfterUnknown(repo, name, c)
        }
    }

    private suspend fun reuse(repo: RepoRef, name: String, original: GitHubResult.Failure): GitHubResult<EnsuredBranch> =
        when (val g = getBranch(repo, name)) {
            is GitHubResult.Success -> g.value?.let { GitHubResult.Success(EnsuredBranch(it, created = false)) } ?: original
            is GitHubResult.Failure -> g
            is GitHubResult.Unknown -> g
        }

    /** Create outcome unknown: reconcile by name. Found -> adopt (created=false, caller compares sha); absent -> still Unknown. */
    private suspend fun reuseAfterUnknown(repo: RepoRef, name: String, unknown: GitHubResult.Unknown): GitHubResult<EnsuredBranch> =
        when (val g = getBranch(repo, name)) {
            is GitHubResult.Success -> g.value?.let { GitHubResult.Success(EnsuredBranch(it, created = false)) } ?: unknown
            is GitHubResult.Failure -> unknown
            is GitHubResult.Unknown -> unknown
        }

    // ---- pull requests ----

    override suspend fun createPullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<PullRequest> {
        requireSha(request.headSha)
        val body = buildJsonObject {
            put("title", request.title)
            put("head", request.head)
            put("base", request.base)
            put("body", request.renderedBody())
            put("draft", request.draft)
        }
        return when (val r = post("createPullRequest", "${repoPath(repo)}/pulls", body)) {
            is GitHubResult.Success -> {
                val o = parse(r.value) as? JsonObject ?: return protocol("pr")
                when (val pr = parsePr(repo, o)) {
                    is GitHubResult.Success ->
                        if (pr.value.headRef != request.head) mismatch() else pr
                    else -> pr
                }
            }
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }
    }

    override suspend fun getPullRequest(repo: RepoRef, number: Int): GitHubResult<PullRequest> =
        when (val r = get("${repoPath(repo)}/pulls/$number")) {
            is GitHubResult.Success -> {
                val o = parse(r.value) as? JsonObject ?: return protocol("pr")
                if (o.long("number")?.toInt() != number) mismatch()
                else parsePr(repo, o)
            }
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }

    override suspend fun findPullRequestsByHead(repo: RepoRef, headBranch: String): GitHubResult<List<PullRequest>> {
        val out = mutableListOf<PullRequest>()
        for (page in 1..config.maxPages) {
            val head = java.net.URLEncoder.encode("${repo.owner}:$headBranch", "UTF-8")
            val r = get("${repoPath(repo)}/pulls?head=$head&state=all&per_page=100&page=$page")
            val raw = when (r) {
                is GitHubResult.Success -> r.value
                is GitHubResult.Failure -> return r
                is GitHubResult.Unknown -> return r
            }
            val arr = parse(raw) as? JsonArray ?: return protocol("pulls list")
            for (el in arr) {
                val o = el as? JsonObject ?: return protocol("pull item")
                val pr = when (val p = parsePr(repo, o)) {
                    is GitHubResult.Success -> p.value
                    is GitHubResult.Failure -> return p
                    is GitHubResult.Unknown -> return p
                }
                // GitHub ignores a malformed head filter and returns everything: verify, never trust the filter.
                if (pr.headRef != headBranch || !sameRepo(o.str("head", "repo", "full_name"), repo)) {
                    return mismatch()
                }
                out += pr
            }
            if (arr.size < 100) return GitHubResult.Success(out)
        }
        return GitHubResult.Failure(GitHubFailureKind.PROTOCOL, "too many pages of pull requests for head $headBranch")
    }

    override suspend fun ensurePullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<EnsuredPullRequest> {
        suspend fun lookup(): GitHubResult<EnsuredPullRequest?> =
            when (val f = findPullRequestsByHead(repo, request.head)) {
                is GitHubResult.Failure -> f
                is GitHubResult.Unknown -> f
                is GitHubResult.Success -> adoptOwnPullRequest(request, f.value)
            }
        when (val l = lookup()) {
            is GitHubResult.Failure -> return l
            is GitHubResult.Unknown -> return l
            is GitHubResult.Success -> l.value?.let { return GitHubResult.Success(it) }
        }
        return when (val c = createPullRequest(repo, request)) {
            is GitHubResult.Success -> GitHubResult.Success(EnsuredPullRequest(c.value, created = true))
            is GitHubResult.Failure ->
                if (c.kind == GitHubFailureKind.CONFLICT) {
                    when (val l = lookup()) {
                        is GitHubResult.Success -> l.value?.let { GitHubResult.Success(it) } ?: c
                        is GitHubResult.Failure -> l
                        is GitHubResult.Unknown -> l
                    }
                } else c
            is GitHubResult.Unknown ->
                when (val l = lookup()) {
                    is GitHubResult.Success -> l.value?.let { GitHubResult.Success(it) } ?: c
                    // Identity decisions are definitive: keep them instead of reporting a bare Unknown.
                    is GitHubResult.Failure ->
                        if (l.kind in IDENTITY_DECISIONS) l else c
                    is GitHubResult.Unknown -> c
                }
        }
    }

    /**
     * Chooses this run's PR among the PRs on its head branch (issue #34, P1). Fail closed: an open PR is adopted only
     * when the marker, head repo and head ref (checked by [findPullRequestsByHead]) and also the head sha and base ref
     * all match the request. A stale or foreign-base PR is never adopted, and the caller must not create a new PR
     * while one of them is open. With no open PR, the most recent closed one is reported (`created=false`), as before.
     */
    private fun adoptOwnPullRequest(request: CreatePullRequest, prs: List<PullRequest>): GitHubResult<EnsuredPullRequest?> {
        val foreign = prs.firstOrNull { !it.hasMarker(request.runId) }
        if (foreign != null) {
            return GitHubResult.Failure(
                GitHubFailureKind.FOREIGN_OBJECT,
                "PR #${foreign.number} on ${request.head} lacks marker ${RunBranch.marker(request.runId)}; isolating",
            )
        }
        val open = prs.filter { it.state == PullRequestState.OPEN }
        if (open.isEmpty()) {
            return GitHubResult.Success(prs.maxByOrNull { it.number }?.let { EnsuredPullRequest(it, created = false) })
        }
        val matching = open.filter { it.headSha == request.headSha && it.baseRef == request.base }
        return when (matching.size) {
            0 -> mismatch()
            1 -> GitHubResult.Success(EnsuredPullRequest(matching.single(), created = false))
            else -> GitHubResult.Failure(GitHubFailureKind.CONFLICT, "multiple open PRs match this run; refusing to choose")
        }
    }

    // ---- commits ----

    override suspend fun commitFiles(repo: RepoRef, request: CommitFilesRequest): GitHubResult<CommittedFiles> {
        CommitFilesPolicy.violation(request, config.commitFiles)?.let { return validation(it) }
        val branch = request.branch
        val expected = request.expectedHeadSha
        val readUrl = "${repoPath(repo)}/git/ref/heads/${refPath(branch)}"
        val writeUrl = "${repoPath(repo)}/git/refs/heads/${refPath(branch)}"

        // (a) Nothing is written unless the live head is the base the caller worked from.
        val headSha = when (val r = get(readUrl)) {
            is GitHubResult.Success -> when (val b = parseBranchRef(repo, branch, r.value)) {
                is GitHubResult.Success -> b.value.headSha
                is GitHubResult.Failure -> return b
                is GitHubResult.Unknown -> return b
            }
            is GitHubResult.Failure -> return r
            is GitHubResult.Unknown -> return r
        }
        if (headSha != expected) return GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)

        // (b) Base tree of the current head.
        val baseTree = when (val r = get("${repoPath(repo)}/git/commits/$headSha")) {
            is GitHubResult.Success -> {
                val o = parse(r.value) as? JsonObject ?: return protocol("commit")
                if (o.str("sha") != headSha) return mismatch()
                o.str("tree", "sha")?.takeIf { sha40.matches(it) } ?: return protocol("commit.tree.sha")
            }
            is GitHubResult.Failure -> return r
            is GitHubResult.Unknown -> return r
        }

        // (c) One blob per written file. Each response is checked against the git blob id computed locally.
        val blobShas = HashMap<Int, String>()
        request.changes.forEachIndexed { index, change ->
            if (change is FileWrite.Write) {
                val body = buildJsonObject {
                    put("content", Base64.getEncoder().encodeToString(change.content))
                    put("encoding", "base64")
                }
                when (val r = post("createBlob", "${repoPath(repo)}/git/blobs", body)) {
                    is GitHubResult.Success -> {
                        val o = parse(r.value) as? JsonObject ?: return protocol("blob")
                        val sha = o.str("sha")?.takeIf { sha40.matches(it) } ?: return protocol("blob.sha")
                        if (sha != gitBlobSha(change.content)) return mismatch()
                        blobShas[index] = sha
                    }
                    is GitHubResult.Failure -> return r
                    is GitHubResult.Unknown -> return r
                }
            }
        }

        // (d) New tree on top of the base tree. Mode is always 100644; a Delete is an entry with sha null.
        val entries = request.changes.mapIndexed { index, change ->
            buildJsonObject {
                put("path", change.path)
                put("mode", "100644")
                put("type", "blob")
                put("sha", blobShas[index]?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        val treeSha = when (val r = post(
            "createTree",
            "${repoPath(repo)}/git/trees",
            buildJsonObject {
                put("base_tree", baseTree)
                put("tree", JsonArray(entries))
            },
        )) {
            is GitHubResult.Success -> {
                val o = parse(r.value) as? JsonObject ?: return protocol("tree")
                o.str("sha")?.takeIf { sha40.matches(it) } ?: return protocol("tree.sha")
            }
            is GitHubResult.Failure -> return r
            is GitHubResult.Unknown -> return r
        }

        // (e) The commit, parented on the head we verified in (a).
        val commitSha = when (val r = post(
            "createCommit",
            "${repoPath(repo)}/git/commits",
            buildJsonObject {
                put("message", request.message)
                put("tree", treeSha)
                put("parents", JsonArray(listOf(JsonPrimitive(headSha))))
            },
        )) {
            is GitHubResult.Success -> {
                val o = parse(r.value) as? JsonObject ?: return protocol("commit")
                if (o.str("tree", "sha") != treeSha) return mismatch()
                o.str("sha")?.takeIf { sha40.matches(it) } ?: return protocol("commit.sha")
            }
            is GitHubResult.Failure -> return r
            is GitHubResult.Unknown -> return r
        }

        // (f) The only ref write. force=false: a non-fast-forward is rejected (422 -> CONFLICT), never forced.
        // An unknown outcome is reconciled by reading the ref back; the PATCH is not re-sent.
        return when (val r = patch("updateRef", writeUrl, buildJsonObject {
            put("sha", commitSha)
            put("force", false)
        })) {
            is GitHubResult.Success -> when (val b = parseBranchRef(repo, branch, r.value)) {
                is GitHubResult.Success ->
                    if (b.value.headSha == commitSha) GitHubResult.Success(CommittedFiles(commitSha, treeSha, created = true))
                    else mismatch()
                is GitHubResult.Failure -> b
                is GitHubResult.Unknown -> b
            }
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> reconcileCommit(repo, branch, expected, commitSha, treeSha)
        }
    }

    /**
     * Decides the outcome of a ref update whose response was lost (issue #56). Reads the ref only; never writes.
     * - head is [commitSha]: our update landed -> `Success(created = true)`.
     * - head is [expectedHeadSha]: confirmed not applied -> `Failure(NOT_APPLIED)`. Nothing was written, so the caller may run
     *   `commitFiles` again from the start, because the blobs, trees and commit it left behind are unreferenced.
     * - any other head: another writer moved the branch -> `CONFLICT`.
     * A failed or unusable read stays `Unknown`.
     */
    internal suspend fun reconcileCommit(
        repo: RepoRef,
        branch: String,
        expectedHeadSha: String,
        commitSha: String,
        treeSha: String,
    ): GitHubResult<CommittedFiles> {
        val op = "updateRef"
        val head = when (val r = get("${repoPath(repo)}/git/ref/heads/${refPath(branch)}")) {
            is GitHubResult.Success -> when (val b = parseBranchRef(repo, branch, r.value)) {
                is GitHubResult.Success -> b.value.headSha
                else -> return GitHubResult.Unknown(op, "reconcile read unusable")
            }
            else -> return GitHubResult.Unknown(op, "reconcile read failed")
        }
        return when (head) {
            commitSha -> GitHubResult.Success(CommittedFiles(commitSha, treeSha, created = true))
            // Confirmed not applied: the ref is still at expectedHeadSha. Nothing was written, so the caller may re-run once.
            expectedHeadSha -> GitHubResult.Failure(GitHubFailureKind.NOT_APPLIED, GitHubFailureKind.NOT_APPLIED.code)
            else -> GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)
        }
    }

    private fun validation(reason: String) =
        GitHubResult.Failure(GitHubFailureKind.VALIDATION, "${GitHubFailureKind.VALIDATION.code}:$reason")

    /** The object id git computes for a blob: SHA-1 over `blob <size>\0<content>`. */
    private fun gitBlobSha(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(content)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    // ---- checks / status / reviews ----

    override suspend fun getCheckRuns(repo: RepoRef, sha: String): GitHubResult<CheckRunsSummary> {
        requireSha(sha)
        val runs = mutableListOf<CheckRun>()
        var total = 0
        for (page in 1..config.maxPages) {
            val raw = when (val r = get("${repoPath(repo)}/commits/$sha/check-runs?per_page=100&page=$page")) {
                is GitHubResult.Success -> r.value
                is GitHubResult.Failure -> return r
                is GitHubResult.Unknown -> return r
            }
            val o = parse(raw) as? JsonObject ?: return protocol("check-runs")
            total = o.long("total_count")?.toInt() ?: return protocol("total_count")
            val arr = o["check_runs"] as? JsonArray ?: return protocol("check_runs")
            for (el in arr) {
                val c = el as? JsonObject ?: return protocol("check run")
                val headSha = c.str("head_sha") ?: return protocol("head_sha")
                if (headSha != sha) return mismatch()
                runs += CheckRun(
                    id = c.long("id") ?: return protocol("check id"),
                    name = c.str("name") ?: return protocol("check name"),
                    headSha = headSha,
                    status = c.str("status") ?: return protocol("check status"),
                    conclusion = c.str("conclusion")?.let(::conclusionOf),
                    startedAt = c.str("started_at")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
                )
            }
            if (runs.size >= total || arr.isEmpty()) break
        }
        return GitHubResult.Success(CheckRunsSummary(sha, runs, total, complete = runs.size >= total))
    }

    private fun conclusionOf(s: String) = when (s) {
        "success" -> CheckConclusion.SUCCESS
        "failure" -> CheckConclusion.FAILURE
        "neutral" -> CheckConclusion.NEUTRAL
        "cancelled" -> CheckConclusion.CANCELLED
        "skipped" -> CheckConclusion.SKIPPED
        "timed_out" -> CheckConclusion.TIMED_OUT
        "action_required" -> CheckConclusion.ACTION_REQUIRED
        "stale" -> CheckConclusion.STALE
        else -> CheckConclusion.OTHER
    }

    override suspend fun getCombinedStatus(repo: RepoRef, sha: String): GitHubResult<CombinedStatus> {
        requireSha(sha)
        val raw = when (val r = get("${repoPath(repo)}/commits/$sha/status?per_page=100")) {
            is GitHubResult.Success -> r.value
            is GitHubResult.Failure -> return r
            is GitHubResult.Unknown -> return r
        }
        val o = parse(raw) as? JsonObject ?: return protocol("status")
        val respSha = o.str("sha") ?: return protocol("status.sha")
        if (respSha != sha) return mismatch()
        if (!sameRepo(o.str("repository", "full_name"), repo)) return mismatch()
        val contexts = (o["statuses"] as? JsonArray)?.associate {
            val s = it.jsonObject
            (s.str("context") ?: return protocol("status context")) to (s.str("state") ?: return protocol("status state"))
        } ?: emptyMap()
        return GitHubResult.Success(CombinedStatus(sha, o.str("state") ?: return protocol("status.state"), contexts))
    }

    override suspend fun getReviews(repo: RepoRef, number: Int): GitHubResult<List<Review>> {
        val out = mutableListOf<Review>()
        for (page in 1..config.maxPages) {
            val raw = when (val r = get("${repoPath(repo)}/pulls/$number/reviews?per_page=100&page=$page")) {
                is GitHubResult.Success -> r.value
                is GitHubResult.Failure -> return r
                is GitHubResult.Unknown -> return r
            }
            val arr = parse(raw) as? JsonArray ?: return protocol("reviews")
            for (el in arr) {
                val o = el as? JsonObject ?: return protocol("review")
                // pull_request_url ends with /repos/{owner}/{repo}/pulls/{number}: verify the target.
                val url = o.str("pull_request_url")
                if (url != null && !url.endsWith("/repos/${repo.owner}/${repo.repo}/pulls/$number", ignoreCase = true)) {
                    return mismatch()
                }
                out += Review(
                    id = o.long("id") ?: return protocol("review id"),
                    reviewer = o.str("user", "login") ?: "",
                    state = o.str("state") ?: return protocol("review state"),
                    commitId = o.str("commit_id"),
                )
            }
            if (arr.size < 100) return GitHubResult.Success(out)
        }
        return GitHubResult.Failure(GitHubFailureKind.PROTOCOL, "too many pages of reviews")
    }

    // ---- read-only repository access (GET only) ----

    /** A branch name or a 40-hex commit SHA, with no control characters, empty or dot segments. Checked before any request. */
    private fun isValidRef(ref: String): Boolean =
        sha40.matches(ref) || (
            ref.isNotEmpty() && ref.length <= 255 &&
                ref.none { it.code < 0x20 || it.code == 0x7f || it == ' ' } &&
                ref.split('/').none { it.isEmpty() || it == "." || it == ".." }
            )

    /** Path segments are encoded one by one; `URLEncoder` writes a space as `+`, which is a literal plus in a path. */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun encodeRef(ref: String): String = if (sha40.matches(ref)) ref else refPath(ref)

    override suspend fun listTree(repo: RepoRef, ref: String): GitHubResult<RepoTree> {
        if (!isValidRef(ref)) return validation("ref")
        val raw = when (val g = get("${repoPath(repo)}/git/trees/${encodeRef(ref)}?recursive=1")) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return g
            is GitHubResult.Unknown -> return g
        }
        val o = parse(raw) as? JsonObject ?: return protocol("tree")
        val sha = o.str("sha")?.takeIf { sha40.matches(it) } ?: return protocol("tree.sha")
        if (sha40.matches(ref) && sha != ref) return mismatch()
        val truncated = o.bool("truncated") ?: return protocol("tree.truncated")
        if (truncated) return GitHubResult.Failure(GitHubFailureKind.TREE_TRUNCATED, GitHubFailureKind.TREE_TRUNCATED.code)
        val arr = o["tree"] as? JsonArray ?: return protocol("tree.tree")
        val entries = ArrayList<TreeEntry>(arr.size)
        for (el in arr) {
            val e = el as? JsonObject ?: return protocol("tree entry")
            val path = e.str("path") ?: return protocol("tree entry path")
            val type = when (e.str("type")) {
                "blob" -> TreeEntryType.BLOB
                "tree" -> TreeEntryType.TREE
                "commit" -> TreeEntryType.COMMIT
                else -> return protocol("tree entry type")
            }
            val mode = e.str("mode") ?: return protocol("tree entry mode")
            val size = if (type == TreeEntryType.BLOB) {
                e.long("size") ?: return protocol("tree entry size")
            } else {
                null
            }
            entries += TreeEntry(path, type, mode, size, e.str("sha")?.takeIf { sha40.matches(it) })
        }
        return GitHubResult.Success(RepoTree(sha, entries, truncated = false))
    }

    // ---- pull request branch update (issue #276, PrBranchPort) ----

    override suspend fun getPrRebaseView(repo: RepoRef, number: Int): GitHubResult<PrRebaseView> {
        val raw = when (val r = get("${repoPath(repo)}/pulls/$number")) {
            is GitHubResult.Success -> r.value
            is GitHubResult.Failure -> return r
            is GitHubResult.Unknown -> return r
        }
        val o = parse(raw) as? JsonObject ?: return protocol("pr")
        if (o.long("number")?.toInt() != number) return mismatch()
        val mergeable = when (val m = o["mergeable"]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (m.isString) null else m.booleanOrNull
            else -> null
        }
        return parsePr(repo, o).map { PrRebaseView(it, o.str("mergeable_state"), mergeable) }
    }

    override suspend fun listPullRequestFiles(repo: RepoRef, number: Int): GitHubResult<PullRequestFiles> {
        val out = mutableListOf<PullRequestFile>()
        // Pages of 100 until the page is short or the limit is reached. The limit is a refusal, never a partial answer.
        for (page in 1..PULL_REQUEST_FILES_LIMIT / 100) {
            val raw = when (val r = get("${repoPath(repo)}/pulls/$number/files?per_page=100&page=$page")) {
                is GitHubResult.Success -> r.value
                is GitHubResult.Failure -> return r
                is GitHubResult.Unknown -> return r
            }
            val arr = parse(raw) as? JsonArray ?: return protocol("pull files")
            for (el in arr) {
                val f = el as? JsonObject ?: return protocol("pull files[]")
                out += PullRequestFile(
                    path = f.str("filename") ?: return protocol("pull files[].filename"),
                    status = f.str("status") ?: return protocol("pull files[].status"),
                    additions = f.long("additions")?.takeIf { it >= 0 } ?: return protocol("pull files[].additions"),
                    deletions = f.long("deletions")?.takeIf { it >= 0 } ?: return protocol("pull files[].deletions"),
                )
            }
            if (out.size >= PULL_REQUEST_FILES_LIMIT) return GitHubResult.Success(PullRequestFiles(out.take(PULL_REQUEST_FILES_LIMIT), truncated = true))
            if (arr.size < 100) break
        }
        return GitHubResult.Success(PullRequestFiles(out, truncated = false))
    }

    override suspend fun getBehindBy(repo: RepoRef, base: String, headSha: String): GitHubResult<Int> {
        if (!isValidRef(base) || !sha40.matches(headSha)) return validation("compare")
        return compareCount(repo, base, headSha, "behind_by")
    }

    override suspend fun getAheadBy(repo: RepoRef, baseSha: String, headSha: String): GitHubResult<Int> {
        if (!sha40.matches(baseSha) || !sha40.matches(headSha)) return validation("compare")
        return compareCount(repo, baseSha, headSha, "ahead_by")
    }

    /** One count of `GET /compare/{base}...{head}`, named by [field]. */
    private suspend fun compareCount(repo: RepoRef, base: String, headSha: String, field: String): GitHubResult<Int> {
        val raw = when (val g = get("${repoPath(repo)}/compare/$base...$headSha")) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return g
            is GitHubResult.Unknown -> return g
        }
        val o = parse(raw) as? JsonObject ?: return protocol("compare")
        // A JSON number only: a string "9" or a fraction is not the count, and a negative count is not one either.
        val prim = o[field] as? JsonPrimitive ?: return protocol("compare.$field")
        // The literal's text must be a plain non-negative integer: no quotes, sign, fraction or exponent, and no leading zero.
        if (prim.isString || !compareCountLiteral.matches(prim.content)) return protocol("compare.$field")
        val count = prim.content.toIntOrNull() ?: return protocol("compare.$field")
        return GitHubResult.Success(count)
    }

    override suspend fun getCompareFiles(repo: RepoRef, base: String, head: String): GitHubResult<List<ComparedFile>> {
        if (!sha40.matches(base) || !sha40.matches(head)) return validation("compare")
        val raw = when (val g = get("${repoPath(repo)}/compare/$base...$head", maxBodyBytes = COMPARE_FILES_MAX_RESPONSE_BYTES)) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return g
            is GitHubResult.Unknown -> return g
        }
        val o = parse(raw) as? JsonObject ?: return protocol("compare")
        val arr = o["files"] as? JsonArray ?: return protocol("compare.files")
        val out = ArrayList<ComparedFile>(arr.size)
        for (el in arr) {
            val f = el as? JsonObject ?: return protocol("compare.files[]")
            val filename = f.str("filename") ?: return protocol("compare.files[].filename")
            val status = f.str("status") ?: return protocol("compare.files[].status")
            val patch = when (val p = f["patch"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (p.isString) p.content else return protocol("compare.files[].patch")
                else -> return protocol("compare.files[].patch")
            }
            out += ComparedFile(filename, status, patch)
        }
        return GitHubResult.Success(out)
    }

    override suspend fun updatePrBranch(repo: RepoRef, number: Int, expectedHeadSha: String): GitHubResult<Unit> {
        if (!sha40.matches(expectedHeadSha)) return validation("expected_head_sha")
        val body = buildJsonObject { put("expected_head_sha", expectedHeadSha) }
        // Exactly one send. A 422 (head moved or merge conflict) is a CONFLICT, the same as a rejected ref update.
        return when (val m = mutate(HttpMethod.Put, "updatePrBranch", "${repoPath(repo)}/pulls/$number/update-branch", body, unprocessableIsConflict = true)) {
            is GitHubResult.Success -> GitHubResult.Success(Unit)
            is GitHubResult.Failure -> m
            is GitHubResult.Unknown -> m
        }
    }

    override suspend fun getCommitParents(repo: RepoRef, sha: String): GitHubResult<List<String>> {
        if (!sha40.matches(sha)) return validation("sha")
        val raw = when (val g = get("${repoPath(repo)}/commits/$sha")) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return g
            is GitHubResult.Unknown -> return g
        }
        val o = parse(raw) as? JsonObject ?: return protocol("commit")
        if (o.str("sha") != sha) return mismatch()
        val arr = o["parents"] as? JsonArray ?: return protocol("commit.parents")
        val parents = ArrayList<String>(arr.size)
        for (el in arr) {
            parents += (el as? JsonObject)?.str("sha")?.takeIf { sha40.matches(it) } ?: return protocol("commit.parents[].sha")
        }
        return GitHubResult.Success(parents)
    }

    override suspend fun mergeBaseOf(repo: RepoRef, a: String, b: String): GitHubResult<String> {
        if (!sha40.matches(a) || !sha40.matches(b)) return validation("sha")
        val raw = when (val g = get("${repoPath(repo)}/compare/$a...$b")) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return g
            is GitHubResult.Unknown -> return g
        }
        val o = parse(raw) as? JsonObject ?: return protocol("compare")
        val base = o.str("merge_base_commit", "sha")?.takeIf { sha40.matches(it) } ?: return protocol("compare.merge_base_commit.sha")
        return GitHubResult.Success(base)
    }

    override suspend fun listTreeAt(repo: RepoRef, commitSha: String): GitHubResult<RepoTree> =
        if (!sha40.matches(commitSha)) validation("sha") else listTree(repo, commitSha)

    override suspend fun getIssue(repo: RepoRef, number: Long): GitHubResult<GitHubIssue> {
        if (number <= 0) return validation("issue number")
        val raw = when (val g = get("${repoPath(repo)}/issues/$number", maxBodyBytes = ISSUE_MAX_RESPONSE_BYTES)) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return g
            is GitHubResult.Unknown -> return g
        }
        val o = parse(raw) as? JsonObject ?: return protocol("issue")
        if (o.long("number") != number) return mismatch()
        val title = o.str("title") ?: return protocol("issue.title")
        val state = when (o.str("state")) {
            "open" -> true
            "closed" -> false
            else -> return protocol("issue.state")
        }
        // The issues endpoint answers for a pull request too, and marks it with a `pull_request` key.
        val isPullRequest = o.containsKey("pull_request")
        return GitHubResult.Success(
            GitHubIssue(
                repo = repo,
                number = number,
                title = title,
                body = o.str("body") ?: "",
                open = state,
                isPullRequest = isPullRequest,
            ),
        )
    }

    override suspend fun readFile(repo: RepoRef, ref: String, path: String, maxBytes: Int): GitHubResult<RepoFileContent?> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (!isValidRef(ref)) return validation("ref")
        // Same rules as commitFiles: no empty, dot, `..`, backslash, control, absolute or `.git` path segment.
        if (CommitFilesPolicy.pathViolation(path, CommitFilesLimits()) != null) return validation("path")
        val query = "?ref=${java.net.URLEncoder.encode(ref, "UTF-8")}"
        val raw = when (val g = get("${repoPath(repo)}/contents/${encodePath(path)}$query")) {
            is GitHubResult.Success -> g.value
            is GitHubResult.Failure -> return if (g.kind == GitHubFailureKind.NOT_FOUND) GitHubResult.Success(null) else g
            is GitHubResult.Unknown -> return g
        }
        val parsed = parse(raw) ?: return protocol("contents")
        // A directory answers with an array.
        if (parsed is JsonArray) return validation("not a file")
        val o = parsed as? JsonObject ?: return protocol("contents")
        val type = o.str("type") ?: return protocol("contents.type")
        if (type != "file") return validation("not a file")
        if (o.str("path") != path) return mismatch()
        val size = o.long("size") ?: return protocol("contents.size")
        if (size < 0) return protocol("contents.size")
        // The contents response carries the body, so the size is checked here and nothing is decoded for an oversize file.
        if (size > maxBytes) return GitHubResult.Failure(GitHubFailureKind.TOO_LARGE, GitHubFailureKind.TOO_LARGE.code)
        if (o.str("encoding") != "base64") return protocol("contents.encoding")
        val encoded = o.str("content") ?: return protocol("contents.content")
        // GitHub wraps base64 with LF only. The strict decoder rejects any other character.
        val bytes = try {
            Base64.getDecoder().decode(encoded.replace("\n", ""))
        } catch (_: IllegalArgumentException) {
            return protocol("contents.base64")
        }
        if (bytes.size.toLong() != size || bytes.size > maxBytes) return protocol("contents.length")
        return GitHubResult.Success(RepoFileContent(path, bytes, isText = isUtf8Text(bytes)))
    }

    private fun isUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
