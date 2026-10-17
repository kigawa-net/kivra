package net.kigawa.kivra.selfdev

import net.kigawa.kivra.coding.TaskBodyText
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubIssue
import net.kigawa.kivra.github.GitHubIssueReader
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef

/**
 * Resolution of a task title that is a reference (Issue #229, addendum: task references). The title of a self-development run may name
 * an issue (`https://github.com/<owner>/<repo>/issues/<n>`, `#<n>`, or `<owner>/<repo>#<n>`). The resolver reads it and gives the
 * text the coding agent is asked to work from. Nothing here is logged with its content: callers log the run id, a fixed code, a count and
 * the `owner/repo#n` reference only.
 */
sealed interface TaskReferenceResult {
    /** [text] is `Issue #<n>: <title>` and the body, at most [TaskBodyText.MAX_CHARS] code points. [source] is `owner/repo#n`. */
    data class Resolved(val text: String, val source: String) : TaskReferenceResult

    /** The title is not a reference. Nothing is resolved and the run goes on as before. */
    data object NotApplicable : TaskReferenceResult

    /** The title is a reference, and it cannot be used. [code] is a fixed code. The run fails before any attempt is queued. */
    data class Failed(val code: TaskReferenceFailure) : TaskReferenceResult
}

/** Why a reference could not be resolved. The codes are fixed and carry no GitHub text. */
enum class TaskReferenceFailure {
    /** The repository is not the self-development target repository. */
    REPO_NOT_ALLOWED,

    /** The number is a pull request, not an issue. */
    PULL_REQUEST,

    /** The issue is closed. */
    CLOSED,

    /** The issue does not exist (or is not visible to the App). */
    NOT_FOUND,

    /** The App is not allowed to read the issue. */
    FORBIDDEN,

    /** A server error, rate limit, network failure or redirect after the retries. */
    UNAVAILABLE,

    /** The response is larger than the limit. */
    RESPONSE_TOO_LARGE,

    /** The manual body leaves no room for the referenced text. */
    NO_ROOM_FOR_REFERENCE,
    ;

    /** The sub reason stored in the audit trail, for example `TASK_REFERENCE_CLOSED`. */
    val code: String get() = "TASK_REFERENCE_$name"
}

/**
 * One way to resolve a title. Implementations are plugged into [TaskReferenceResolvers]; a new kind of reference (for example a pull
 * request URL) is one more implementation. Must not throw for a provider condition: such conditions are [TaskReferenceResult.Failed].
 */
fun interface TaskReferenceResolver {
    suspend fun resolve(title: String): TaskReferenceResult
}

/** Tries [resolvers] in order. The first result that is not [TaskReferenceResult.NotApplicable] is the answer. */
class TaskReferenceResolvers(private val resolvers: List<TaskReferenceResolver>) {
    suspend fun resolve(title: String): TaskReferenceResult {
        for (resolver in resolvers) {
            val result = resolver.resolve(title)
            if (result !is TaskReferenceResult.NotApplicable) return result
        }
        return TaskReferenceResult.NotApplicable
    }
}

/**
 * Resolves a GitHub issue reference, and only one in [targetRepo]. The whole (trimmed) title must be one of the three forms; anything
 * else is [TaskReferenceResult.NotApplicable]. The issue is read through [reader] (GitHub App read access, bounded, redirects not followed).
 * Only the title, the body and the state are used. Comments, labels and the author are not read.
 */
class GitHubIssueResolver(
    private val reader: GitHubIssueReader,
    private val targetRepo: RepoRef,
) : TaskReferenceResolver {

    override suspend fun resolve(title: String): TaskReferenceResult {
        val ref = parseIssueReference(title.trim()) ?: return TaskReferenceResult.NotApplicable
        // A bare `#<n>` names the target repository. An explicit owner/repo must be the target, compared without case.
        val owner = ref.owner
        val repo = ref.repo
        if (owner != null && repo != null && (!owner.equals(targetRepo.owner, ignoreCase = true) || !repo.equals(targetRepo.repo, ignoreCase = true))) {
            return TaskReferenceResult.Failed(TaskReferenceFailure.REPO_NOT_ALLOWED)
        }
        return when (val r = reader.getIssue(targetRepo, ref.number)) {
            is GitHubResult.Success -> issueResult(r.value)
            is GitHubResult.Failure -> TaskReferenceResult.Failed(
                when (r.kind) {
                    GitHubFailureKind.NOT_FOUND -> TaskReferenceFailure.NOT_FOUND
                    GitHubFailureKind.PERMISSION, GitHubFailureKind.AUTH -> TaskReferenceFailure.FORBIDDEN
                    GitHubFailureKind.TOO_LARGE -> TaskReferenceFailure.RESPONSE_TOO_LARGE
                    else -> TaskReferenceFailure.UNAVAILABLE
                },
            )
            is GitHubResult.Unknown -> TaskReferenceResult.Failed(TaskReferenceFailure.UNAVAILABLE)
        }
    }

    private fun issueResult(issue: GitHubIssue): TaskReferenceResult {
        if (issue.isPullRequest) return TaskReferenceResult.Failed(TaskReferenceFailure.PULL_REQUEST)
        if (!issue.open) return TaskReferenceResult.Failed(TaskReferenceFailure.CLOSED)
        val source = "${targetRepo.owner}/${targetRepo.repo}#${issue.number}"
        val text = "Issue #${issue.number}: ${TaskBodyText.plainLine(issue.title)}\n\n${TaskBodyText.plainText(issue.body)}"
        return TaskReferenceResult.Resolved(TaskBodyText.clip(text, TaskBodyText.MAX_CHARS), source)
    }

    /** A parsed reference. [owner] and [repo] are null for `#<n>`, which means the target repository. */
    internal data class IssueRef(val owner: String?, val repo: String?, val number: Long)

    internal companion object {
        private val URL_FORM = Regex("https://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9_.-]{1,100})/(?:issues|pull)/([0-9]{1,18})")
        private val HASH_FORM = Regex("#([0-9]{1,18})")
        private val SLUG_FORM = Regex("([A-Za-z0-9-]{1,39})/([A-Za-z0-9_.-]{1,100})#([0-9]{1,18})")

        /** The issue reference that is the whole [title], or null. The number must be positive. */
        fun parseIssueReference(title: String): IssueRef? {
            URL_FORM.matchEntire(title)?.let { m -> return positive(m.groupValues[3])?.let { IssueRef(m.groupValues[1], m.groupValues[2], it) } }
            SLUG_FORM.matchEntire(title)?.let { m -> return positive(m.groupValues[3])?.let { IssueRef(m.groupValues[1], m.groupValues[2], it) } }
            HASH_FORM.matchEntire(title)?.let { m -> return positive(m.groupValues[1])?.let { IssueRef(null, null, it) } }
            return null
        }

        private fun positive(digits: String): Long? = digits.toLongOrNull()?.takeIf { it > 0 }
    }
}
