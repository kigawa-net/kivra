package net.kigawa.kivra.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** The project that owns [repoKey] in project_repos, or null when none does (or the key is null). Runs in the caller's transaction. */
internal fun Transaction.projectIdOfRepo(repoKey: String?): String? {
    if (repoKey == null) return null
    return execQuery("SELECT project_id FROM project_repos WHERE repo_key = ?", repoKey) { it.getString(1) }.firstOrNull()
}

/** The project a row gets from project_repos through its `target_repo`, as SQL over the row's table alias [alias]. */
internal fun repoProjectSql(alias: String): String =
    "SELECT pr.project_id FROM project_repos pr WHERE pr.repo_key = $alias.target_repo"

/**
 * One table of a backfill (ADR 0022, PR-B). An implementation writes only rows whose `project_id` is NULL, and only a project it
 * derives from the rows' own context. It never writes a row that has a project already.
 */
internal interface ProjectBackfillTarget {
    val table: String

    /** The rows that have NULL and a project to be given. */
    fun matched(): Long

    /** The rows that have NULL and no project to be given: they stay NULL. */
    fun unmatched(): Long

    /** Writes one batch of at most the given number of rows. Returns the rows written; 0 when nothing is left to write. */
    fun writeBatch(batchSize: Int): Int
}

/**
 * A table whose rows take the project of the SQL expression [project] (NULL when none). The expression refers to the table by its
 * name, so it can be used in a SELECT and in an UPDATE on that table. [pk] is the table's primary key.
 */
internal class SqlProjectBackfillTarget(
    private val database: Database,
    override val table: String,
    private val pk: String,
    private val project: String,
) : ProjectBackfillTarget {
    override fun matched(): Long = count("$table WHERE project_id IS NULL AND ($project) IS NOT NULL")

    override fun unmatched(): Long = count("$table WHERE project_id IS NULL AND ($project) IS NULL")

    override fun writeBatch(batchSize: Int): Int {
        val ids = transaction(database) {
            execQuery(
                "SELECT $pk FROM $table WHERE project_id IS NULL AND ($project) IS NOT NULL ORDER BY $pk LIMIT ?",
                batchSize,
            ) { it.getString(1) }
        }
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        // The guard `project_id IS NULL` keeps a value written by someone else; the project is read again in the same statement.
        return transaction(database) {
            execUpdate(
                "UPDATE $table SET project_id = ($project) WHERE $pk IN ($placeholders) AND project_id IS NULL",
                *ids.toTypedArray(),
            )
        }
    }

    private fun count(fromAndWhere: String): Long = transaction(database) {
        execQuery("SELECT COUNT(*) FROM $fromAndWhere") { it.getLong(1) }.first()
    }
}

/** The result of a backfill for one table: the rows with a project to give, the rows written, and the rows that stay NULL. */
data class BackfillTableReport(
    /** The rows with a mapping: the count a real run would write (dry run), or the count it wrote (real run). */
    val matched: Long,
    /** The rows written. Always 0 for a dry run. */
    val updated: Long,
    /** The rows that stay NULL because no project maps to them. */
    val unmatched: Long,
)

/** The result of a backfill, per table (keys are the table names, in the order they were processed). */
data class BackfillReport(val dryRun: Boolean, val tables: Map<String, BackfillTableReport>)

/**
 * The backfill of ADR 0022 (PR-B). It assigns the rows whose `project_id` is NULL to the project that owns their repository in
 * `project_repos`. It never changes a row that already has a project, and a row whose repository maps to no project stays NULL
 * (nothing is guessed). Running it again changes nothing that is already assigned, so it is idempotent.
 *
 * A row's project is derived from the current state, the same way in a dry run and in a real run:
 * - a Task: its own `project_id`, else the project of its `target_repo`;
 * - an order: the project of its `target_repo` (a child order has its root's repository, as created);
 * - a Run: its own `project_id`, else the project of the Task that holds it (`selfdev_tasks.run_id`), as above;
 * - a memory record: the project of its goal's Task, else of its plan's Task, else of its Run (the order PR-C uses to write it).
 *   The memory table is written only in its repository, so that part is [SelfDevMemoryRepository.projectBackfillTarget].
 *
 * Rows are read and written in batches of [batchSize] (LIMIT), each batch in its own transaction, so a long backfill never holds one
 * large lock. A dry run writes nothing: it only counts. A real run stops early if a batch changes nothing (a concurrent writer took
 * the rows), and the next run picks up what is left.
 */
class ProjectBackfillRepository(
    private val database: Database,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    /** Runs the backfill over every target table. [dryRun] true counts and writes nothing. */
    fun run(dryRun: Boolean): BackfillReport {
        val tables = LinkedHashMap<String, BackfillTableReport>()
        for (target in targets()) {
            tables[target.table] = if (dryRun) {
                BackfillTableReport(matched = target.matched(), updated = 0, unmatched = target.unmatched())
            } else {
                execute(target)
            }
        }
        return BackfillReport(dryRun, tables)
    }

    private fun execute(target: ProjectBackfillTarget): BackfillTableReport {
        var updated = 0L
        while (true) {
            val written = target.writeBatch(batchSize)
            if (written == 0) break
            updated += written
        }
        return BackfillTableReport(matched = updated, updated = updated, unmatched = target.unmatched())
    }

    private fun targets(): List<ProjectBackfillTarget> = listOf(
        SqlProjectBackfillTarget(database, "selfdev_tasks", "task_id", TASK_PROJECT),
        SqlProjectBackfillTarget(database, "selfdev_orders", "order_id", ORDER_PROJECT),
        SqlProjectBackfillTarget(database, "development_runs", "run_id", RUN_PROJECT),
        SelfDevMemoryRepository.projectBackfillTarget(database),
    )

    companion object {
        const val DEFAULT_BATCH_SIZE = 500

        private val TASK_PROJECT = "COALESCE(selfdev_tasks.project_id, (${repoProjectSql("selfdev_tasks")}))"

        private val ORDER_PROJECT = "COALESCE(selfdev_orders.project_id, (${repoProjectSql("selfdev_orders")}))"

        /** A Run's project: its own, else the project of its Task (selfdev_tasks.run_id). */
        private val RUN_PROJECT =
            "COALESCE(development_runs.project_id, (SELECT COALESCE(t.project_id, (${repoProjectSql("t")})) " +
                "FROM selfdev_tasks t WHERE t.run_id = development_runs.run_id LIMIT 1))"
    }
}
