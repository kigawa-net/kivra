package net.kigawa.kivra.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

data class GitHubAdapterConfig(
    val apiBase: String = "https://api.github.com",
    /** Total attempts for a GET (first try + retries). POST is never retried. */
    val maxGetAttempts: Int = 3,
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
    val maxPages: Int = 10,
    /** An operator-approved host allowlist. GHES installations must opt in explicitly. */
    val trustedApiHosts: Set<String> = setOf("api.github.com"),
) {
    init {
        val uri = try {
            java.net.URI(apiBase)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid GitHub API base URL", e)
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "GitHub API base URL must be HTTPS without credentials, query or fragment"
        }
        require(uri.host != null && uri.host.lowercase() in trustedApiHosts.map { it.lowercase() }) {
            "GitHub API endpoint is not in the approved host allowlist"
        }
        require(uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." }) {
            "GitHub API URL must not contain path traversal"
        }
    }
}

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
) : GitHubPort {
    private val json = Json { ignoreUnknownKeys = true }

    private class Raw(val status: Int, val body: String, val headers: Map<String, String>)

    private sealed interface Transport {
        class Ok(val raw: Raw) : Transport
        class Error(val message: String) : Transport
    }

    private suspend fun send(method: HttpMethod, path: String, body: JsonObject?): Transport = try {
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
        Transport.Ok(Raw(resp.status.value, resp.bodyAsText(), resp.headers.entries().associate { it.key.lowercase() to it.value.first() }))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Exception messages from HTTP engines may contain URLs, headers or secrets: retain only the type.
        Transport.Error("GitHub transport failure (${e::class.simpleName ?: "Exception"})")
    }

    /** GET with retry on 5xx / I/O and one token refresh on 401. Returns the raw 2xx response or a classified failure. */
    private suspend fun get(path: String): GitHubResult<Raw> {
        var refreshed = false
        var attempt = 1
        while (true) {
            when (val t = send(HttpMethod.Get, path, null)) {
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
        when (val t = send(HttpMethod.Post, path, body)) {
            is Transport.Error -> GitHubResult.Unknown(operation, t.message)
            is Transport.Ok -> when {
                t.raw.status in 200..299 -> GitHubResult.Success(t.raw)
                t.raw.status >= 500 -> GitHubResult.Unknown(operation, "HTTP ${t.raw.status}")
                else -> {
                    if (t.raw.status == 401) tokens.invalidate()
                    classify(t.raw)
                }
            }
        }

    private fun classify(r: Raw): GitHubResult.Failure {
        val message = runCatching { json.parseToJsonElement(r.body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: r.body.take(200)
        val retryAfter = r.headers["retry-after"]?.toLongOrNull()
            ?: r.headers["x-ratelimit-reset"]?.toLongOrNull()?.let { (it - System.currentTimeMillis() / 1000).coerceAtLeast(0) }
        val rateLimited = r.status == 429 ||
            (r.status == 403 && (r.headers["x-ratelimit-remaining"] == "0" || r.headers.containsKey("retry-after") ||
                message.contains("rate limit", ignoreCase = true)))
        val kind = when {
            rateLimited -> GitHubFailureKind.RATE_LIMITED
            r.status == 401 -> GitHubFailureKind.AUTH
            r.status == 403 -> GitHubFailureKind.PERMISSION
            r.status == 404 -> GitHubFailureKind.NOT_FOUND
            r.status == 409 -> GitHubFailureKind.CONFLICT
            r.status == 422 && message.contains("already exists", ignoreCase = true) -> GitHubFailureKind.CONFLICT
            r.status >= 500 -> GitHubFailureKind.SERVER_ERROR
            else -> GitHubFailureKind.VALIDATION
        }
        return GitHubResult.Failure(kind, message, r.status, if (kind == GitHubFailureKind.RATE_LIMITED) retryAfter else null)
    }

    // ---- parsing / identity verification ----

    private fun parse(raw: Raw): JsonElement? = runCatching { json.parseToJsonElement(raw.body) }.getOrNull()

    private fun protocol(what: String) = GitHubResult.Failure(GitHubFailureKind.PROTOCOL, "unexpected response shape: $what")

    private fun mismatch(what: String) = GitHubResult.Failure(GitHubFailureKind.IDENTITY_MISMATCH, what)

    private fun JsonObject.str(vararg path: String): String? {
        var cur: JsonElement = this
        for (p in path) cur = (cur as? JsonObject)?.get(p) ?: return null
        return (cur as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun sameRepo(fullName: String?, repo: RepoRef) = fullName != null && fullName.equals(repo.toString(), ignoreCase = true)

    private val sha40 = Regex("[0-9a-f]{40}")

    private fun requireSha(sha: String) = require(sha40.matches(sha)) { "sha must be 40 lowercase hex chars: $sha" }

    private fun repoPath(repo: RepoRef) = "/repos/${repo.owner}/${repo.repo}"

    private fun refPath(branch: String) = branch.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    private fun parseBranchRef(repo: RepoRef, branch: String, raw: Raw): GitHubResult<BranchInfo> {
        val o = parse(raw) as? JsonObject ?: return protocol("ref")
        val ref = o.str("ref") ?: return protocol("ref.ref")
        if (ref != "refs/heads/$branch") return mismatch("requested branch $branch but response is $ref")
        val sha = o.str("object", "sha")?.takeIf { sha40.matches(it) } ?: return protocol("ref.object.sha")
        return GitHubResult.Success(BranchInfo(repo, branch, sha))
    }

    private fun parsePr(repo: RepoRef, o: JsonObject): GitHubResult<PullRequest> {
        val number = o.long("number")?.toInt() ?: return protocol("pr.number")
        val headRef = o.str("head", "ref") ?: return protocol("pr.head.ref")
        val headSha = o.str("head", "sha")?.takeIf { sha40.matches(it) } ?: return protocol("pr.head.sha")
        val baseRef = o.str("base", "ref") ?: return protocol("pr.base.ref")
        val baseRepo = o.str("base", "repo", "full_name") ?: return protocol("pr.base.repo.full_name")
        if (!sameRepo(baseRepo, repo)) return mismatch("requested repo $repo but PR belongs to $baseRepo")
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
        requireSha(baseSha)
        val body = buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", baseSha)
        }
        return when (val r = post("createBranch", "${repoPath(repo)}/git/refs", body)) {
            is GitHubResult.Success -> parseBranchRef(repo, branch, r.value).let { res ->
                // The new ref must point at the requested base.
                if (res is GitHubResult.Success && res.value.headSha != baseSha) mismatch("created branch sha ${res.value.headSha} != requested $baseSha") else res
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
                        if (pr.value.headRef != request.head) mismatch("requested head ${request.head} but PR head is ${pr.value.headRef}") else pr
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
                if (o.long("number")?.toInt() != number) mismatch("requested PR #$number but response is #${o.long("number")}")
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
                    return mismatch("PR #${pr.number} head ${o.str("head", "label")} does not match requested $repo:$headBranch")
                }
                out += pr
            }
            if (arr.size < 100) return GitHubResult.Success(out)
        }
        return GitHubResult.Failure(GitHubFailureKind.PROTOCOL, "too many pages of pull requests for head $headBranch")
    }

    override suspend fun ensurePullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<EnsuredPullRequest> {
        val runId = request.runId
        suspend fun lookup(): GitHubResult<EnsuredPullRequest?> =
            when (val f = findPullRequestsByHead(repo, request.head)) {
                is GitHubResult.Failure -> f
                is GitHubResult.Unknown -> f
                is GitHubResult.Success -> {
                    val prs = f.value
                    val foreign = prs.firstOrNull { !it.hasMarker(runId) }
                    when {
                        foreign != null -> GitHubResult.Failure(
                            GitHubFailureKind.FOREIGN_OBJECT,
                            "PR #${foreign.number} on ${request.head} lacks marker ${RunBranch.marker(runId)}; isolating",
                        )
                        // Prefer an open PR; otherwise the most recent closed one is reported (caller decides).
                        else -> GitHubResult.Success(
                            (prs.firstOrNull { it.state == PullRequestState.OPEN } ?: prs.maxByOrNull { it.number })
                                ?.let { EnsuredPullRequest(it, created = false) },
                        )
                    }
                }
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
                    is GitHubResult.Failure -> if (l.kind == GitHubFailureKind.FOREIGN_OBJECT) l else c
                    is GitHubResult.Unknown -> c
                }
        }
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
                if (headSha != sha) return mismatch("requested sha $sha but check run ${c.long("id")} is for $headSha")
                runs += CheckRun(
                    id = c.long("id") ?: return protocol("check id"),
                    name = c.str("name") ?: return protocol("check name"),
                    headSha = headSha,
                    status = c.str("status") ?: return protocol("check status"),
                    conclusion = c.str("conclusion")?.let(::conclusionOf),
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
        if (respSha != sha) return mismatch("requested sha $sha but status is for $respSha")
        if (!sameRepo(o.str("repository", "full_name"), repo)) return mismatch("status belongs to ${o.str("repository", "full_name")}, requested $repo")
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
                    return mismatch("requested $repo#$number but review belongs to $url")
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
}
