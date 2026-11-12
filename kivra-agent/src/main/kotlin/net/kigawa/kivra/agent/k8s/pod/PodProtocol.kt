package net.kigawa.kivra.agent.k8s.pod

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.kigawa.kivra.agent.docker.DockerPatterns
import net.kigawa.kivra.agent.workdir.AgentChange
import net.kigawa.kivra.agent.workdir.AgentChangeKind
import net.kigawa.kivra.agent.workdir.AgentResult
import net.kigawa.kivra.agent.workdir.ChangeRules
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffLimits
import net.kigawa.kivra.agent.workdir.DiffRejectReason
import net.kigawa.kivra.agent.workdir.PathRules
import net.kigawa.kivra.agent.workdir.RepoSource

/**
 * The files that the agent and the in-Pod entrypoint exchange through `pods/exec` (ADR 0010 追補 7 PR 4, "exec readout").
 *
 * - `/work/.kivra/input.json` is written by the agent (stdin of an exec) before the entrypoint does anything. It carries the public
 *   repository, the base commit and the task text. The task text is a file and never a command line argument or an environment value.
 * - `/work/.kivra/result.json` is written by the entrypoint after the CLI has ended. The agent reads it with an exec, so the change
 *   set does not depend on the logs (which the kubelet may rotate). The Pod has no credential and no path to the agent.
 *
 * Both are JSON, bounded, and read strictly: an unknown key, a wrong type or a size over the limit is refused.
 */
internal object PodPaths {
    const val KIVRA_DIR = "/work/.kivra"
    const val INPUT = "$KIVRA_DIR/input.json"
    const val RESULT = "$KIVRA_DIR/result.json"
    const val WORK_ROOT = "/work"
    const val MIRROR_DIR = "/tmp/mirror"
    const val GIT_BINARY = "/usr/bin/git"
}

/** A pod file is malformed or too large. The message is a fixed code; it never holds content. */
class PodProtocolException(val code: String) : RuntimeException(code)

/**
 * The input of one run, as the entrypoint reads it. [toString] shows the ids only (the task text is not for logs).
 */
class PodInput(
    val runId: String,
    val attemptId: String,
    val owner: String,
    val repo: String,
    val commit: String,
    val title: String,
    val body: String,
    val truncated: Boolean,
) {
    init {
        if (!DockerPatterns.ID.matches(runId) || !DockerPatterns.ID.matches(attemptId)) throw PodProtocolException("INPUT_ID_INVALID")
        try {
            RepoSource.PublicGithub(owner, repo)
        } catch (e: IllegalArgumentException) {
            throw PodProtocolException("INPUT_REPO_INVALID")
        }
        if (!COMMIT.matches(commit)) throw PodProtocolException("INPUT_COMMIT_INVALID")
        if (title.length > MAX_TITLE_CHARS || body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw PodProtocolException("INPUT_TASK_TOO_LARGE")
    }

    fun source(): RepoSource.PublicGithub = RepoSource.PublicGithub(owner, repo)

    override fun toString(): String = "PodInput(runId=$runId, attemptId=$attemptId)"

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_TITLE_CHARS = 2_000
        const val MAX_BODY_BYTES = 64 * 1024

        /** The most bytes of the input file. The task text is at most 64 KiB, and JSON escaping can make it six times longer. */
        const val MAX_FILE_BYTES = 512 * 1024
        private val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
        private val KEYS = setOf("schemaVersion", "runId", "attemptId", "owner", "repo", "commit", "title", "body", "truncated")

        fun encode(input: PodInput): ByteArray = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("runId", input.runId)
            put("attemptId", input.attemptId)
            put("owner", input.owner)
            put("repo", input.repo)
            put("commit", input.commit)
            put("title", input.title)
            put("body", input.body)
            put("truncated", input.truncated)
        }.toString().toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): PodInput {
            if (bytes.size > MAX_FILE_BYTES) throw PodProtocolException("INPUT_TOO_LARGE")
            val obj = PodJson.parseObject(bytes, "INPUT_MALFORMED")
            if (obj.keys != KEYS) throw PodProtocolException("INPUT_MALFORMED")
            if (PodJson.int(obj, "schemaVersion") != SCHEMA_VERSION) throw PodProtocolException("INPUT_SCHEMA")
            return PodInput(
                runId = PodJson.text(obj, "runId"),
                attemptId = PodJson.text(obj, "attemptId"),
                owner = PodJson.text(obj, "owner"),
                repo = PodJson.text(obj, "repo"),
                commit = PodJson.text(obj, "commit"),
                title = PodJson.text(obj, "title"),
                body = PodJson.text(obj, "body"),
                truncated = PodJson.bool(obj, "truncated"),
            )
        }
    }
}

/**
 * What the agent reads back from the Pod. [Error] means the entrypoint could not prepare the work directory or collect (a fixed
 * [Error.reason] code). [Collected] holds the entrypoint's own collection: the CLI's exit status and either changes or reasons.
 * Both are claims of a Pod that ran model-driven code, so the agent checks them again ([Collected.finish]).
 */
sealed interface PodReadout {
    class Error(val reason: String) : PodReadout {
        override fun toString(): String = "PodReadout.Error(reason=$reason)"
    }

    class Collected(
        val exitStatus: Int,
        /** Null when the Pod's collection was refused. */
        val changes: List<AgentChange>?,
        /** The Pod's reasons for a refusal (empty when [changes] is not null). */
        val reasons: List<DiffRejectReason>,
    ) : PodReadout {
        override fun toString(): String = "PodReadout.Collected(exitStatus=$exitStatus, changes=${changes?.size}, reasons=${reasons.size})"

        /**
         * Checks the claim with the real limits and path rules of the attempt, and adds the summary and the session id (which only the
         * agent knows, from the CLI's output). Nothing the Pod said is trusted: every path, size, line, count and the summary is checked
         * as the host-side [net.kigawa.kivra.agent.workdir.DiffCollector] does. A refused result lists every reason, sorted.
         */
        fun finish(summary: String, sessionId: String?, limits: DiffLimits, pathRules: PathRules): CollectOutcome {
            val found = sortedSetOf<DiffRejectReason>()
            found.addAll(reasons)
            if (summary.isBlank()) found.add(DiffRejectReason.SUMMARY_EMPTY)
            if (summary.length > limits.maxSummaryChars) found.add(DiffRejectReason.SUMMARY_TOO_LONG)
            if (ChangeRules.containsSecretLike(summary)) found.add(DiffRejectReason.SECRET_LIKE_CONTENT)
            if (exitStatus != 0) found.add(DiffRejectReason.CLI_EXIT_NONZERO)
            val claimed = changes
            if (claimed == null) {
                if (found.isEmpty()) found.add(DiffRejectReason.RESULT_FIELD_INVALID)
                return CollectOutcome.Rejected(found.toList())
            }
            var writes = 0L
            var deletes = 0
            val keys = HashMap<String, String>()
            for (change in claimed) {
                pathRules.reason(change.path, limits.maxPathChars)?.let { found.add(it) }
                if (Normalizer.normalize(change.path, Normalizer.Form.NFC) != change.path) found.add(DiffRejectReason.PATH_NOT_NFC)
                val previous = keys.put(ChangeRules.collisionKey(change.path), change.path)
                if (previous != null) found.add(if (previous == change.path) DiffRejectReason.RESULT_FIELD_INVALID else DiffRejectReason.CASE_COLLISION)
                when (change.kind) {
                    AgentChangeKind.DELETE -> {
                        deletes++
                        if (change.content != null) found.add(DiffRejectReason.RESULT_FIELD_INVALID)
                    }
                    AgentChangeKind.WRITE -> {
                        val text = change.content
                        if (text == null) {
                            found.add(DiffRejectReason.RESULT_FIELD_INVALID)
                        } else {
                            val bytes = text.toByteArray(Charsets.UTF_8)
                            if (bytes.size > limits.maxWriteFileBytes) {
                                found.add(DiffRejectReason.FILE_TOO_LARGE)
                            } else {
                                writes += bytes.size
                                found.addAll(ChangeRules.contentOf(bytes, limits.maxLineChars).reasons)
                            }
                        }
                    }
                }
            }
            if (claimed.isEmpty()) found.add(DiffRejectReason.EMPTY_PATCH)
            if (claimed.size > limits.maxChangedFiles) found.add(DiffRejectReason.TOO_MANY_CHANGES)
            if (deletes > limits.maxDeletes) found.add(DiffRejectReason.TOO_MANY_DELETES)
            if (writes > limits.maxTotalWriteBytes) found.add(DiffRejectReason.TOTAL_TOO_LARGE)
            if (found.isNotEmpty()) return CollectOutcome.Rejected(found.toList())
            return CollectOutcome.Accepted(AgentResult(summary, claimed, exitStatus, sessionId))
        }
    }
}

/** The result file: its format, its size limit, and the strict reader. */
object PodResultCodec {
    const val SCHEMA_VERSION = 1

    /** The most bytes of the result file the agent reads. Changes are at most 1 MiB of text; JSON escaping can make that six times longer. */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /** The most changes a result may list. The agent's own limit is far lower; this bounds the parse. */
    const val MAX_CHANGES = 1_000
    private const val MAX_REASONS = 100
    private val COLLECTED_KEYS = setOf("schemaVersion", "status", "exitStatus", "changes", "reasons")
    private val ERROR_KEYS = setOf("schemaVersion", "status", "reason")
    private val CHANGE_KEYS = setOf("path", "kind", "content")
    private val ERROR_REASON = Regex("[A-Z_]{1,40}")

    fun encodeError(reason: String): ByteArray {
        require(ERROR_REASON.matches(reason)) { "reason must be a code" }
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("status", "ERROR")
            put("reason", reason)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun encode(exitStatus: Int, outcome: CollectOutcome): ByteArray {
        val obj = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("status", "COLLECTED")
            put("exitStatus", exitStatus)
            val changes = (outcome as? CollectOutcome.Accepted)?.result?.changes.orEmpty()
            put(
                "changes",
                buildJsonArray {
                    for (c in changes) {
                        add(
                            buildJsonObject {
                                put("path", c.path)
                                put("kind", c.kind.name)
                                put("content", c.content?.let { JsonPrimitive(it) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
            val reasons = (outcome as? CollectOutcome.Rejected)?.reasons.orEmpty()
            put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it.name)) } })
        }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        // A result over the read limit would never be read: say so as a refusal instead.
        if (bytes.size > MAX_FILE_BYTES) return encode(exitStatus, CollectOutcome.Rejected(listOf(DiffRejectReason.TOTAL_TOO_LARGE)))
        return bytes
    }

    /** Reads the result file. Throws [PodProtocolException] for anything that is not exactly the format above. */
    fun decode(bytes: ByteArray): PodReadout {
        if (bytes.size > MAX_FILE_BYTES) throw PodProtocolException("RESULT_TOO_LARGE")
        val obj = PodJson.parseObject(bytes, "RESULT_MALFORMED")
        if (PodJson.int(obj, "schemaVersion") != SCHEMA_VERSION) throw PodProtocolException("RESULT_SCHEMA")
        return when (PodJson.text(obj, "status")) {
            "ERROR" -> {
                if (obj.keys != ERROR_KEYS) throw PodProtocolException("RESULT_MALFORMED")
                val reason = PodJson.text(obj, "reason")
                if (!ERROR_REASON.matches(reason)) throw PodProtocolException("RESULT_MALFORMED")
                PodReadout.Error(reason)
            }
            "COLLECTED" -> {
                if (obj.keys != COLLECTED_KEYS) throw PodProtocolException("RESULT_MALFORMED")
                val reasons = PodJson.array(obj, "reasons").also { if (it.size > MAX_REASONS) throw PodProtocolException("RESULT_MALFORMED") }
                    .map { e ->
                        val name = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw PodProtocolException("RESULT_MALFORMED")
                        DiffRejectReason.entries.firstOrNull { it.name == name } ?: throw PodProtocolException("RESULT_MALFORMED")
                    }
                val changes = PodJson.array(obj, "changes").also { if (it.size > MAX_CHANGES) throw PodProtocolException("RESULT_MALFORMED") }
                    .map { change(it) }
                // A refusal has reasons and no changes; an acceptance has no reasons. Anything else is not what the entrypoint writes.
                if (reasons.isNotEmpty() && changes.isNotEmpty()) throw PodProtocolException("RESULT_MALFORMED")
                PodReadout.Collected(
                    exitStatus = PodJson.int(obj, "exitStatus"),
                    changes = if (reasons.isEmpty()) changes else null,
                    reasons = reasons,
                )
            }
            else -> throw PodProtocolException("RESULT_MALFORMED")
        }
    }

    private fun change(element: JsonElement): AgentChange {
        val o = element as? JsonObject ?: throw PodProtocolException("RESULT_MALFORMED")
        if (o.keys != CHANGE_KEYS) throw PodProtocolException("RESULT_MALFORMED")
        val kind = AgentChangeKind.entries.firstOrNull { it.name == PodJson.text(o, "kind") } ?: throw PodProtocolException("RESULT_MALFORMED")
        val content = when (val c = o["content"]) {
            is JsonNull -> null
            is JsonPrimitive -> if (c.isString) c.content else throw PodProtocolException("RESULT_MALFORMED")
            else -> throw PodProtocolException("RESULT_MALFORMED")
        }
        return AgentChange(PodJson.text(o, "path"), kind, content)
    }
}

internal object PodJson {
    private val json = Json { isLenient = false }

    fun parseObject(bytes: ByteArray, code: String): JsonObject = try {
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: throw PodProtocolException(code)
    } catch (e: PodProtocolException) {
        throw e
    } catch (e: Exception) {
        throw PodProtocolException(code)
    }

    fun text(o: JsonObject, key: String): String {
        val p = o[key] as? JsonPrimitive ?: throw PodProtocolException("FIELD_INVALID")
        if (!p.isString) throw PodProtocolException("FIELD_INVALID")
        return p.contentOrNull ?: throw PodProtocolException("FIELD_INVALID")
    }

    fun int(o: JsonObject, key: String): Int {
        val p = o[key] as? JsonPrimitive ?: throw PodProtocolException("FIELD_INVALID")
        if (p.isString) throw PodProtocolException("FIELD_INVALID")
        return p.intOrNull ?: throw PodProtocolException("FIELD_INVALID")
    }

    fun bool(o: JsonObject, key: String): Boolean {
        val p = o[key] as? JsonPrimitive ?: throw PodProtocolException("FIELD_INVALID")
        if (p.isString) throw PodProtocolException("FIELD_INVALID")
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> throw PodProtocolException("FIELD_INVALID")
        }
    }

    fun array(o: JsonObject, key: String): JsonArray = o[key] as? JsonArray ?: throw PodProtocolException("FIELD_INVALID")
}
