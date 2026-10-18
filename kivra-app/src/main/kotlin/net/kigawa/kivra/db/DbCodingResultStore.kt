package net.kigawa.kivra.db

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.coding.CodingResultPurge
import net.kigawa.kivra.coding.CodingResultStore
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.FileChange
import net.kigawa.kivra.core.patch.ParseResult
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.PatchSetParser
import net.kigawa.kivra.core.patch.Write
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * The MariaDB / H2 store of accepted change sets (V15 `coding_results`, design docs/design/15-coding-attempts.md section 6.5).
 *
 * Only [net.kigawa.kivra.coding.CodingRuntimeService] calls [save], and only with a PatchSet that passed the shape checks and
 * PatchPolicy. This class does not run PatchPolicy again (it has no policy config). Nothing here logs, and error messages never
 * include the stored text or the digest.
 *
 * Generation (fencing) is not checked here: the service checks it before [save] and the attempt's CAS (complete) checks it
 * after. While an attempt is CLAIMED or RUNNING its lease generation cannot change (only claim bumps it, and claim needs QUEUED),
 * so a stale holder can only write a row for an attempt that is already terminal, and that row is never referenced.
 */
class DbCodingResultStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : CodingResultStore, CodingResultPurge {

    /**
     * Stores [patch] for [attemptId]. Idempotent: a second save with the same digest succeeds and changes nothing. A save with a
     * different digest is refused, so a stored result cannot be replaced. Throws when the change set is too large for the parser
     * or does not read back as the same PatchSet, when the attempt does not exist (foreign key), or when the write fails.
     */
    override fun save(attemptId: String, digest: String, patch: PatchSet) {
        require(DIGEST.matches(digest)) { "digest must be 64 lowercase hex characters" }
        val json = encode(patch)
        try {
            transaction(database) {
                execUpdate(
                    "INSERT INTO coding_results (attempt_id, result_digest, patch_json, created_at) VALUES (?, ?, ?, ?)",
                    attemptId, digest, json, utcNow(clock),
                )
            }
        } catch (insertFailure: Exception) {
            // A concurrent save may have won the row (primary key). Only an existing row with the same digest is success.
            val stored = storedDigest(attemptId) ?: throw insertFailure
            if (stored != digest) throw IllegalStateException("a different result is already stored for this attempt")
        }
    }

    /** The change set stored for ([attemptId], [digest]), or null. Throws when the stored text cannot be read back. */
    override fun load(attemptId: String, digest: String): PatchSet? {
        val json = transaction(database) {
            execQuery(
                "SELECT patch_json FROM coding_results WHERE attempt_id = ? AND result_digest = ?",
                attemptId, digest,
            ) { it.getString(1) }.singleOrNull()
        } ?: return null
        return when (val parsed = PatchSetParser.parse(json)) {
            is ParseResult.Parsed -> parsed.patch
            is ParseResult.Invalid -> throw IllegalStateException("stored change set cannot be read: ${parsed.reason.name}")
        }
    }

    /**
     * Deletes up to [limit] rows created before [before] whose attempt is not active and whose Run is terminal, or whose row is not the
     * attempt's accepted result (a save after a refused `complete`). The select and the delete run in one transaction. The rule holds
     * because an attempt that is not active never becomes active again, and a terminal Run stays terminal. MariaDB has no DELETE ... LIMIT
     * with a join, so the ids are selected first.
     */
    override fun purgeBefore(before: LocalDateTime, limit: Int): Int {
        require(limit > 0) { "limit must be positive" }
        val terminal = DevelopmentRunState.terminalNames.toList()
        val terminalMarks = terminal.joinToString(",") { "?" }
        return transaction(database) {
            val ids = execQuery(
                """SELECT r.attempt_id FROM coding_results r
                   JOIN coding_attempts a ON a.attempt_id = r.attempt_id
                   JOIN development_runs d ON d.run_id = a.run_id
                   WHERE r.created_at < ?
                     AND a.state NOT IN ('QUEUED', 'CLAIMED', 'RUNNING')
                     AND (d.state IN ($terminalMarks) OR a.result_digest IS NULL OR a.result_digest <> r.result_digest)
                   ORDER BY r.created_at
                   LIMIT ?""",
                *(listOf<Any?>(before) + terminal + listOf<Any?>(limit)).toTypedArray(),
            ) { it.getString(1) }
            if (ids.isEmpty()) {
                0
            } else {
                val idMarks = ids.joinToString(",") { "?" }
                execUpdate("DELETE FROM coding_results WHERE attempt_id IN ($idMarks)", *ids.toTypedArray())
            }
        }
    }

    private fun storedDigest(attemptId: String): String? = transaction(database) {
        execQuery("SELECT result_digest FROM coding_results WHERE attempt_id = ?", attemptId) { it.getString(1) }.singleOrNull()
    }

    companion object {
        private val DIGEST = Regex("^[0-9a-f]{64}$")

        /**
         * The JSON text in the shape [PatchSetParser] reads. It must be at most [PatchSetParser.MAX_INPUT_BYTES] bytes (the parser's
         * own limit, so the text can always be read back), and it must read back as [patch] (checked here, before anything is written).
         */
        internal fun encode(patch: PatchSet): String {
            val text = toJson(patch).toString()
            require(text.toByteArray(StandardCharsets.UTF_8).size <= PatchSetParser.MAX_INPUT_BYTES) { "change set too large to store" }
            val back = (PatchSetParser.parse(text) as? ParseResult.Parsed)?.patch
            require(back == patch) { "change set does not read back unchanged" }
            return text
        }

        private fun toJson(patch: PatchSet): JsonObject = buildJsonObject {
            put("files", buildJsonArray { patch.files.forEach { add(fileJson(it)) } })
            put("summary", patch.summary)
            patch.rationale?.let { put("rationale", it) }
        }

        private fun fileJson(change: FileChange): JsonObject = when (change) {
            is Write -> buildJsonObject {
                put("op", "write")
                put("path", change.path)
                put("content", change.content)
            }
            is Delete -> buildJsonObject {
                put("op", "delete")
                put("path", change.path)
            }
        }
    }
}
