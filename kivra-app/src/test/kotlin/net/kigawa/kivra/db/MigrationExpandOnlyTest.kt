package net.kigawa.kivra.db

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static Expand-only check (Issue #88, acceptance: old Writers keep working while the new schema is applied).
 *
 * The migrations are read from the classpath and scanned for statements that break a Writer built against the previous
 * schema: dropping, renaming, or retyping an existing column or table, deleting rows, and adding a NOT NULL column without
 * a DEFAULT (an old INSERT that does not name the column then fails). Such a change needs a Contract step after every
 * Writer is gone, so it is refused here unless it is listed in [KNOWN_CONTRACT_REQUIRED] with a reason.
 *
 * The scan is a conservative text check, not a SQL parser: comments are stripped, statements are split on `;`.
 */
class MigrationExpandOnlyTest {

    @Test
    fun `every migration is expand-only, except the listed changes that already need a Contract step`() {
        val files = migrationFiles()
        val expected = (1..files.keys.max()).filter { it !in RESERVED_MIGRATION_VERSIONS }
        assertEquals(expected, files.keys.toList(), "migration versions must be contiguous from V1, except the reserved ones")

        val found = files.flatMap { (version, sql) -> expandViolations(version, sql) }.toSet()

        val unexpected = found - KNOWN_CONTRACT_REQUIRED.keys
        assertTrue(unexpected.isEmpty(), "non-expand changes (refused unless listed with a reason): $unexpected")
        // A listed exception that no longer matches is stale: remove it from the list so it cannot hide a later change.
        val stale = KNOWN_CONTRACT_REQUIRED.keys - found
        assertTrue(stale.isEmpty(), "allowlist entries that no migration contains any more: $stale")
    }

    @Test
    fun `the detector flags every forbidden form and accepts additive ones`() {
        val forbidden = listOf(
            "ALTER TABLE external_operations DROP COLUMN request_hash;",
            "DROP TABLE audit_events;",
            "ALTER TABLE outbox_events RENAME COLUMN claimed_by TO claimer;",
            "RENAME TABLE agents TO agents_old;",
            "ALTER TABLE development_runs MODIFY COLUMN task_id VARCHAR(255) NOT NULL;",
            "ALTER TABLE development_runs CHANGE COLUMN task_id task_ref VARCHAR(128) NULL;",
            "ALTER TABLE development_runs ALTER COLUMN task_id SET DATA TYPE VARCHAR(255);",
            "TRUNCATE TABLE outbox_events;",
            "DELETE FROM outbox_events WHERE state = 'DONE';",
            "ALTER TABLE development_runs ADD COLUMN flag BOOLEAN NOT NULL;",
        )
        for (sql in forbidden) {
            assertTrue(expandViolations(99, sql).isNotEmpty(), "should be flagged: $sql")
        }

        val additive = listOf(
            "ALTER TABLE development_runs ADD COLUMN flag BOOLEAN NULL;",
            "ALTER TABLE development_runs ADD COLUMN flag BOOLEAN NOT NULL DEFAULT FALSE;",
            "CREATE TABLE t (id INT NOT NULL, PRIMARY KEY (id));",
            "CREATE UNIQUE INDEX uk_x ON development_runs (task_id, principal);",
            "-- DROP COLUMN in a comment is not a statement\nALTER TABLE development_runs ADD COLUMN flag INT NULL;",
        )
        for (sql in additive) {
            assertEquals(emptyList(), expandViolations(99, sql), "should be accepted: $sql")
        }
    }

    @Test
    fun multipleAddColumnsIndependentlyChecked() {
        assertEquals(
            listOf("V99:ADD NOT NULL t.unsafe"),
            expandViolations(99, "ALTER TABLE t ADD COLUMN unsafe INT NOT NULL, ADD COLUMN safe INT DEFAULT 0;"),
        )
        assertEquals(
            listOf("V99:ADD NOT NULL t.a", "V99:ADD NOT NULL t.b"),
            expandViolations(99, "ALTER TABLE t ADD COLUMN a INT NOT NULL, ADD COLUMN b VARCHAR(20) NOT NULL;"),
        )
        assertEquals(
            emptyList(),
            expandViolations(
                99,
                "ALTER TABLE t ADD COLUMN amount DECIMAL(12,2) NOT NULL DEFAULT 0, " +
                    "ADD COLUMN note VARCHAR(80) NOT NULL DEFAULT 'a,b';",
            ),
        )
    }

    private fun migrationFiles(): Map<Int, String> {
        val dir = Paths.get(requireNotNull(TestDb::class.java.classLoader.getResource("db/migration")) { "db/migration is missing" }.toURI())
        return Files.list(dir).use { stream ->
            stream.toList()
                .mapNotNull { path ->
                    val m = Regex("""V(\d+)__.*\.sql""").matchEntire(path.fileName.toString()) ?: return@mapNotNull null
                    m.groupValues[1].toInt() to Files.readString(path)
                }
                .sortedBy { it.first }
                .toMap()
        }
    }

    companion object {
        /**
         * Changes that are not Expand-only, with the reason. Each key is `V<n>:<what>` as produced by [expandViolations].
         * Adding an entry here is a decision: the old Writer must be drained first, or the change reworked to be additive.
         */
        val KNOWN_CONTRACT_REQUIRED: Map<String, String> = mapOf(
            "V2:ADD NOT NULL development_runs.completion_mode" to
                "NOT NULL without DEFAULT. A pre-V2 Writer INSERT into development_runs does not name this column, so on MariaDB " +
                "(strict mode) it fails with ERROR 1364 once V2 is applied. The old Writer must be drained before V2, or the column " +
                "needs a DEFAULT (V2 is the one unmerged migration that is edited in place, so this is still open).",
            "V27:DROP (ALTER TABLE SELFDEV_ORDER_STOP_REQUESTS DROP CONSTRAINT CK_SELFDEV_ORDER_STOP_SEPARATION)" to
                "V27 replaces the separation CHECK with one that is equal for every row an old Writer can write: an old Writer does not " +
                "name target_is_root, so the column is 1 (the root), and the new CHECK then reads the same as the old one. No Contract " +
                "step is needed for this change. It is listed so the scan accepts exactly this statement; a later DROP is still refused.",
            "V28:DROP (ALTER TABLE SELFDEV_ORDER_STOP_REQUESTS DROP CONSTRAINT CK_SELFDEV_ORDER_STOP_DECIDER)" to
                "V28 replaces the decider CHECK with one that accepts every value the old one did (HUMAN and NULL) and adds ORDER. An old " +
                "Writer never writes ORDER, so its rows pass the new CHECK as they passed the old one. No Contract step is needed.",
            "V28:DROP (ALTER TABLE SELFDEV_ORDER_STOP_REQUESTS DROP CONSTRAINT CK_SELFDEV_ORDER_STOP_REQUESTER)" to
                "V28 replaces the requester CHECK with one that accepts HUMAN and AI (as before) and adds SYSTEM. An old Writer never writes " +
                "SYSTEM, so its rows pass the new CHECK as they passed the old one. No Contract step is needed.",
            "V32:DROP (ALTER TABLE SELFDEV_MEMORY_EVENTS DROP CONSTRAINT CK_SELFDEV_MEMORY_EVENTS_KIND)" to
                "V32 replaces the event kind CHECK with one that accepts every value the old one did and adds SHADOW_SUPPRESS. An old " +
                "Writer never writes SHADOW_SUPPRESS, so its rows pass the new CHECK as they passed the old one. No Contract step is needed.",
            "V34:DROP (ALTER TABLE SELFDEV_ASSIGNEE_EVENTS DROP CONSTRAINT CK_SELFDEV_ASSIGNEE_EVENTS_KIND)" to
                "V34 replaces the event kind CHECK with one that accepts every value the old one did and adds TOKEN_ISSUED and " +
                "TOKEN_REVOKED. An old Writer never writes those kinds, so its rows pass the new CHECK as they passed the old one. No " +
                "Contract step is needed.",
            "V35:DROP (ALTER TABLE SELFDEV_ASSIGNEES DROP CONSTRAINT CK_SELFDEV_ASSIGNEES_ROLE)" to
                "V35 replaces the role CHECK with one that accepts every value the old one did and adds AI_APPROVER. An old Writer never " +
                "writes AI_APPROVER, so its rows pass the new CHECK as they passed the old one. No Contract step is needed.",
            "V37:DROP (ALTER TABLE SELFDEV_LLM_STOP_DECISIONS DROP CONSTRAINT CK_SELFDEV_LLM_STOP_DECISIONS_OUTCOME)" to
                "V37 replaces the outcome CHECK with one that accepts every value the old one did and adds WOULD_APPROVE (a shadow APPROVE). " +
                "Nothing wrote the table before L4, and an old Writer never writes WOULD_APPROVE, so its rows pass the new CHECK as they " +
                "passed the old one. No Contract step is needed.",
            "V39:DROP (ALTER TABLE SELFDEV_ORDER_STOP_REQUESTS DROP CONSTRAINT CK_SELFDEV_ORDER_STOP_DECIDER)" to
                "V39 replaces the decider CHECK. An old Writer never writes the new values, so its rows pass the new CHECK.",
            "V39:DROP (ALTER TABLE SELFDEV_ORDER_STOP_REQUESTS DROP CONSTRAINT CK_SELFDEV_ORDER_STOP_DECIDER_REF)" to
                "V39 replaces the decider_ref CHECK. An old Writer never writes the new values, so its rows pass the new CHECK.",
            "V39:DROP (ALTER TABLE SELFDEV_ORDER_STOP_REQUESTS DROP CONSTRAINT CK_SELFDEV_ORDER_STOP_AUTO_ROOT)" to
                "V39 replaces the auto_root CHECK. An old Writer never writes the new values, so its rows pass the new CHECK.",
            "V39:DROP (ALTER TABLE SELFDEV_ORDER_EVENTS DROP CONSTRAINT CK_SELFDEV_ORDER_EVENTS_ACTOR)" to
                "V39 replaces the actor CHECK. An old Writer never writes the new values, so its rows pass the new CHECK.",
        )
    }
}

/**
 * Returns the forbidden or non-additive statements of [sql] as `V<version>:<what>` keys. Comments (`--`) are removed first.
 */
internal fun expandViolations(version: Int, sql: String): List<String> {
    val withoutComments = sql.lines().joinToString("\n") { it.substringBefore("--") }
    return withoutComments.split(';')
        .map { it.trim().replace(Regex("\\s+"), " ").uppercase() }
        .filter { it.isNotEmpty() }
        .flatMap { statement -> violationsOf(statement).map { "V$version:$it" } }
}

private val ALTER_TABLE = Regex("""^ALTER TABLE (\w+) """)
private val ADD_START = Regex("""(?:^ALTER TABLE \w+ |,\s*)ADD (?:COLUMN )?(\w+) """)

private fun violationsOf(s: String): List<String> {
    if (Regex("""\bDROP\b""").containsMatchIn(s)) return listOf("DROP ($s)")
    if (Regex("""\bRENAME\b""").containsMatchIn(s)) return listOf("RENAME ($s)")
    if (Regex("""\bMODIFY\b""").containsMatchIn(s)) return listOf("MODIFY ($s)")
    if (Regex("""\bCHANGE (COLUMN )?\w+ \w+""").containsMatchIn(s)) return listOf("CHANGE ($s)")
    if (Regex("""\bALTER COLUMN\b""").containsMatchIn(s) || s.contains("SET DATA TYPE")) return listOf("ALTER COLUMN type ($s)")
    if (Regex("""\bTRUNCATE\b""").containsMatchIn(s) || s.startsWith("DELETE FROM")) return listOf("DELETE rows ($s)")

    val table = ALTER_TABLE.find(s)?.groupValues?.get(1) ?: return emptyList()
    val additions = ADD_START.findAll(s).toList()
    return additions.mapIndexedNotNull { index, match ->
        val column = match.groupValues[1]
        if (column in setOf("INDEX", "KEY", "PRIMARY", "CONSTRAINT", "FOREIGN", "UNIQUE")) {
            return@mapIndexedNotNull null
        }
        val until = additions.getOrNull(index + 1)?.range?.first ?: s.length
        val definition = s.substring(match.range.last + 1, until)
        val notNull = Regex("""\bNOT NULL\b""").containsMatchIn(definition)
        val hasDefault = Regex("""\bDEFAULT\b""").containsMatchIn(definition)
        if (notNull && !hasDefault) "ADD NOT NULL " + table.lowercase() + "." + column.lowercase() else null
    }
}
