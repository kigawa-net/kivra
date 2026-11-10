package net.kigawa.kivra.db

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Clock
import java.time.LocalDateTime
import javax.sql.DataSource
import org.slf4j.LoggerFactory

/**
 * The operator backfill of the Goal/Plan rows that V40 removed: it maps the `*_pre_v40` archives (see [archiveLegacyGoalPlanTables])
 * into the current `selfdev_tasks` model. The mapping and the reasons are in docs/design/17-legacy-goal-plan-backfill.md.
 *
 * It is NOT run at startup and has no HTTP route. An operator calls [runLegacyGoalPlanBackfill] from an explicit path.
 *
 * Safety rules (see the design doc):
 * - Every imported task lands in PENDING_APPROVAL, or is a SKIPPED plan root. The Planner takes only TASK_SUBMITTED, the dispatcher
 *   runs only QUEUED, and PENDING_APPROVAL leaves only through an operator approval. So nothing imported is dispatched.
 * - Only the live plan of a goal (the newest PROPOSED revision) is imported. Its COMPLETED, terminal and in-flight tasks are not.
 * - A task whose project cannot be found in `project_repos` is not imported: selfdev_tasks.project_id is NOT NULL (V52).
 * - Keys are `v40:goal:<goal_id>` and `v40:task:<plan_id>:<ordinal>` under (created_by, idempotency_key), so a re-run inserts nothing twice.
 * - [LegacyBackfillMode.DRY_RUN] runs the same classification and always rolls back. [LegacyBackfillMode.APPLY] commits once, at the end.
 *
 * Only counts are logged. Titles, bodies and ids never reach the log.
 */
enum class LegacyBackfillMode { DRY_RUN, APPLY }

/** Why a legacy plan task is not imported. Fixed codes; the report carries counts only. */
enum class LegacyNotImported {
    /** Its plan is not the live plan of its goal: SUPERSEDED, REJECTED, an older PROPOSED revision, or no goal at all. */
    PLAN_NOT_LIVE,
    /** Already COMPLETED. The work is done; importing it again would repeat it. */
    TASK_COMPLETED,
    /** FAILED, CANCELLED or SKIPPED. The outcome is final; running it again is an operator decision made outside the backfill. */
    TASK_TERMINAL,
    /** SUBMITTING or SUBMITTED. A Run or a pull request may exist, and the run linkage is not carried over. */
    TASK_IN_FLIGHT,
    /** A state the V18 table does not allow. */
    INVALID_ROW,
    /** The goal is DONE or STOPPED. Its pending tasks are not revived. */
    GOAL_ENDED,
    /** `project_repos` has no project for the goal's target_repo. Nothing is guessed. */
    NO_PROJECT_MAPPING,
    /** depends_on is not a JSON list of ordinals, or names an ordinal that is not earlier in the plan. */
    DEPENDENCY_INVALID,
    /** A dependency is neither imported nor COMPLETED, so it could never be satisfied. */
    DEPENDENCY_NOT_IMPORTED,
    /** The title, body or target_repo breaks a selfdev_tasks CHECK. */
    INVALID_TEXT,
}

/**
 * The result of one run. `tasks` are plan tasks; `goalsNotImported` counts goals with no imported task (their roots are not written).
 * Every archived plan task is counted once: as imported (inserted or already present) or under one [LegacyNotImported] reason.
 */
data class LegacyBackfillReport(
    val mode: LegacyBackfillMode,
    val archivedGoals: Int,
    val archivedPlans: Int,
    val archivedPlanTasks: Int,
    /** Archived plan tasks per legacy state. */
    val planTasksByLegacyState: Map<String, Int>,
    /** Plan roots (SKIPPED) written by APPLY; for DRY_RUN, the roots that would be written. */
    val insertedRoots: Int,
    val alreadyPresentRoots: Int,
    /** Planned tasks written by APPLY; for DRY_RUN, the tasks that would be written. */
    val insertedTasks: Int,
    val alreadyPresentTasks: Int,
    val notImportedTasks: Map<LegacyNotImported, Int>,
    val goalsNotImported: Int,
    /** COMPLETED dependencies that were dropped from an imported task's dependency list, because they are satisfied. */
    val satisfiedDependenciesDropped: Int,
) {
    val importedTasks: Int get() = insertedTasks + alreadyPresentTasks
}

private val backfillLogger = LoggerFactory.getLogger("net.kigawa.kivra.db.LegacyGoalPlanBackfill")

private val ARCHIVE_TABLES = LEGACY_GOAL_PLAN_TABLES.map { "${it}_pre_v40" }
private val TARGET_TABLES = listOf("selfdev_tasks", "project_repos")

private const val GOALS_SQL =
    "SELECT goal_id, created_by, title, body, target_repo, state, revision, created_at, updated_at FROM selfdev_goals_pre_v40 " +
        "ORDER BY created_at, goal_id"
private const val PLANS_SQL = "SELECT plan_id, goal_id, revision, state FROM selfdev_plans_pre_v40 ORDER BY goal_id, revision, plan_id"
private const val PLAN_TASKS_SQL =
    "SELECT plan_id, ordinal, title, body, state, depends_on, created_at, updated_at FROM selfdev_plan_tasks_pre_v40 " +
        "ORDER BY plan_id, ordinal"

private const val INSERT_ROOT_SQL =
    "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, body, target_repo, state, dependencies, requires_approval, " +
        "revision, attempts, version, created_at, updated_at, parent_task_id, project_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, 'SKIPPED', '[]', TRUE, ?, 0, 0, ?, ?, NULL, ?)"

private const val INSERT_TASK_SQL =
    "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, body, target_repo, state, dependencies, requires_approval, " +
        "revision, attempts, version, created_at, updated_at, parent_task_id, project_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, 0, 0, 0, ?, ?, ?, ?)"

private val ORDINAL_LIST = Regex("""\[\s*(\d+(\s*,\s*\d+)*)?\s*]""")
private val ORDINAL = Regex("""\d+""")

/**
 * Runs the backfill over [dataSource]. Throws when a table it needs is missing (for example a database where the archives were
 * never made). APPLY commits once, after every row is written; any failure rolls the whole run back.
 */
fun runLegacyGoalPlanBackfill(
    dataSource: DataSource,
    mode: LegacyBackfillMode,
    clock: Clock = Clock.systemUTC(),
): LegacyBackfillReport {
    dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            val report = writeBackfill(conn, mode, clock)
            if (mode == LegacyBackfillMode.APPLY) conn.commit() else conn.rollback()
            backfillLogger.info(
                "legacy goal/plan backfill: mode={} archivedPlanTasks={} insertedRoots={} alreadyPresentRoots={} insertedTasks={} " +
                    "alreadyPresentTasks={} goalsNotImported={} satisfiedDependenciesDropped={} notImportedTasks={}",
                mode, report.archivedPlanTasks, report.insertedRoots, report.alreadyPresentRoots, report.insertedTasks,
                report.alreadyPresentTasks, report.goalsNotImported, report.satisfiedDependenciesDropped, report.notImportedTasks,
            )
            return report
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }
}

private data class ArchivedGoal(
    val goalId: String,
    val createdBy: String,
    val title: String,
    val body: String?,
    val targetRepo: String,
    val state: String,
    val revision: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

private data class ArchivedPlan(val planId: String, val goalId: String, val revision: Int, val state: String)

private data class ArchivedPlanTask(
    val planId: String,
    val ordinal: Int,
    val title: String,
    val body: String?,
    val state: String,
    val dependsOn: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

private sealed interface Verdict {
    data class Skip(val reason: LegacyNotImported) : Verdict

    data class Import(val dependencies: List<String>, val satisfiedDropped: Int, val projectId: String) : Verdict
}

private class PlannedTask(
    val key: String,
    val taskId: String,
    val exists: Boolean,
    val row: ArchivedPlanTask,
    val dependencies: List<String>,
    val projectId: String,
)

private fun writeBackfill(conn: Connection, mode: LegacyBackfillMode, clock: Clock): LegacyBackfillReport {
    val tables = tableNames(conn)
    val missing = (ARCHIVE_TABLES + TARGET_TABLES).filter { it !in tables }
    check(missing.isEmpty()) { "the legacy backfill needs these tables: ${missing.joinToString()}" }

    val goals = conn.select(GOALS_SQL) { rs ->
        ArchivedGoal(
            goalId = rs.getString("goal_id"),
            createdBy = rs.getString("created_by"),
            title = rs.getString("title"),
            body = rs.getString("body"),
            targetRepo = rs.getString("target_repo"),
            state = rs.getString("state"),
            revision = rs.getInt("revision"),
            createdAt = rs.getObject("created_at", LocalDateTime::class.java),
            updatedAt = rs.getObject("updated_at", LocalDateTime::class.java),
        )
    }
    val plans = conn.select(PLANS_SQL) { rs ->
        ArchivedPlan(rs.getString("plan_id"), rs.getString("goal_id"), rs.getInt("revision"), rs.getString("state"))
    }
    val tasks = conn.select(PLAN_TASKS_SQL) { rs ->
        ArchivedPlanTask(
            planId = rs.getString("plan_id"),
            ordinal = rs.getInt("ordinal"),
            title = rs.getString("title"),
            body = rs.getString("body"),
            state = rs.getString("state"),
            dependsOn = rs.getString("depends_on"),
            createdAt = rs.getObject("created_at", LocalDateTime::class.java),
            updatedAt = rs.getObject("updated_at", LocalDateTime::class.java),
        )
    }
    val projects: Map<String, String> = conn.select("SELECT repo_key, project_id FROM project_repos") { rs ->
        rs.getString(1) to rs.getString(2)
    }.toMap()

    val tasksByPlan = tasks.groupBy { it.planId }
    val plansByGoal = plans.groupBy { it.goalId }
    // The live plan: the newest PROPOSED revision. SUPERSEDED, REJECTED and older PROPOSED revisions are not live.
    val livePlanByGoal: Map<String, ArchivedPlan?> = goals.associate { goal ->
        goal.goalId to plansByGoal[goal.goalId].orEmpty().filter { it.state == "PROPOSED" }.maxByOrNull { it.revision }
    }
    val livePlanIds = livePlanByGoal.values.filterNotNull().map { it.planId }.toSet()

    val notImported = mutableMapOf<LegacyNotImported, Int>()
    fun skip(reason: LegacyNotImported, count: Int = 1) {
        notImported[reason] = (notImported[reason] ?: 0) + count
    }
    skip(LegacyNotImported.PLAN_NOT_LIVE, tasks.count { it.planId !in livePlanIds })

    var insertedRoots = 0
    var alreadyPresentRoots = 0
    var insertedTasks = 0
    var alreadyPresentTasks = 0
    var satisfiedDropped = 0
    var goalsNotImported = 0

    for (goal in goals) {
        val livePlan = livePlanByGoal[goal.goalId]
        if (livePlan == null) {
            goalsNotImported++
            continue
        }
        val planTasks = tasksByPlan[livePlan.planId].orEmpty().sortedBy { it.ordinal }
        val byOrdinal = planTasks.associateBy { it.ordinal }
        val idOf = HashMap<Int, String>()
        val planned = mutableListOf<PlannedTask>()
        for (task in planTasks) {
            when (val verdict = classify(task, goal, byOrdinal, idOf, projects)) {
                is Verdict.Skip -> skip(verdict.reason)
                is Verdict.Import -> {
                    val key = taskKey(task)
                    val existing = conn.findTaskId(goal.createdBy, key)
                    val taskId = existing ?: Ulid.generate(clock)
                    idOf[task.ordinal] = taskId
                    satisfiedDropped += verdict.satisfiedDropped
                    planned += PlannedTask(key, taskId, existing != null, task, verdict.dependencies, verdict.projectId)
                }
            }
        }
        if (planned.isEmpty()) {
            goalsNotImported++
            continue
        }
        val rootKey = goalKey(goal)
        val rootExisting = conn.findTaskId(goal.createdBy, rootKey)
        val rootId = rootExisting ?: Ulid.generate(clock)
        val projectId = planned.first().projectId
        if (rootExisting == null) {
            insertedRoots++
            if (mode == LegacyBackfillMode.APPLY) conn.insertRoot(rootId, goal, rootKey, projectId)
        } else {
            alreadyPresentRoots++
        }
        for (p in planned) {
            if (p.exists) {
                alreadyPresentTasks++
            } else {
                insertedTasks++
                if (mode == LegacyBackfillMode.APPLY) conn.insertTask(p, goal, rootId)
            }
        }
    }

    return LegacyBackfillReport(
        mode = mode,
        archivedGoals = goals.size,
        archivedPlans = plans.size,
        archivedPlanTasks = tasks.size,
        planTasksByLegacyState = tasks.groupingBy { it.state }.eachCount().toSortedMap(),
        insertedRoots = insertedRoots,
        alreadyPresentRoots = alreadyPresentRoots,
        insertedTasks = insertedTasks,
        alreadyPresentTasks = alreadyPresentTasks,
        notImportedTasks = notImported.toSortedMap(),
        goalsNotImported = goalsNotImported,
        satisfiedDependenciesDropped = satisfiedDropped,
    )
}

/** The verdict for one live plan task. [idOf] holds the ids of the earlier tasks of the plan that are imported. */
private fun classify(
    task: ArchivedPlanTask,
    goal: ArchivedGoal,
    byOrdinal: Map<Int, ArchivedPlanTask>,
    idOf: Map<Int, String>,
    projects: Map<String, String>,
): Verdict {
    when (task.state) {
        "PENDING_APPROVAL", "QUEUED" -> Unit
        "COMPLETED" -> return Verdict.Skip(LegacyNotImported.TASK_COMPLETED)
        "FAILED", "CANCELLED", "SKIPPED" -> return Verdict.Skip(LegacyNotImported.TASK_TERMINAL)
        "SUBMITTING", "SUBMITTED" -> return Verdict.Skip(LegacyNotImported.TASK_IN_FLIGHT)
        else -> return Verdict.Skip(LegacyNotImported.INVALID_ROW)
    }
    if (goal.state == "DONE" || goal.state == "STOPPED") return Verdict.Skip(LegacyNotImported.GOAL_ENDED)
    val projectId = projects[goal.targetRepo] ?: return Verdict.Skip(LegacyNotImported.NO_PROJECT_MAPPING)
    if (!textFits(task.title, 200, required = true) || !textFits(task.body, 8000, required = false) ||
        !textFits(goal.targetRepo, 200, required = true) || !textFits(goal.title, 200, required = true) ||
        !textFits(goal.body, 8000, required = false)
    ) {
        return Verdict.Skip(LegacyNotImported.INVALID_TEXT)
    }
    val ordinals = parseOrdinals(task.dependsOn) ?: return Verdict.Skip(LegacyNotImported.DEPENDENCY_INVALID)
    val dependencies = mutableListOf<String>()
    var dropped = 0
    for (ordinal in ordinals) {
        if (ordinal >= task.ordinal) return Verdict.Skip(LegacyNotImported.DEPENDENCY_INVALID)
        val dep = byOrdinal[ordinal] ?: return Verdict.Skip(LegacyNotImported.DEPENDENCY_INVALID)
        val depId = idOf[ordinal]
        when {
            depId != null -> dependencies += depId
            // A COMPLETED dependency is satisfied, as the dispatcher would have judged it. Its record is not imported.
            dep.state == "COMPLETED" -> dropped++
            else -> return Verdict.Skip(LegacyNotImported.DEPENDENCY_NOT_IMPORTED)
        }
    }
    return Verdict.Import(dependencies, dropped, projectId)
}

/** The depends_on of a legacy plan task: a JSON list of ordinals, or null when it is not one. */
private fun parseOrdinals(json: String): List<Int>? {
    if (!ORDINAL_LIST.matches(json.trim())) return null
    val ordinals = mutableListOf<Int>()
    for (match in ORDINAL.findAll(json)) ordinals += match.value.toIntOrNull() ?: return null
    return ordinals
}

private fun textFits(text: String?, max: Int, required: Boolean): Boolean {
    if (text == null) return !required
    val length = text.codePointCount(0, text.length)
    return length <= max && (!required || length >= 1)
}

private fun taskKey(task: ArchivedPlanTask): String = "v40:task:${task.planId}:${task.ordinal}"

private fun goalKey(goal: ArchivedGoal): String = "v40:goal:${goal.goalId}"

private fun Connection.insertRoot(rootId: String, goal: ArchivedGoal, key: String, projectId: String) {
    execWrite(
        INSERT_ROOT_SQL,
        rootId, goal.createdBy, key, goal.title, goal.body, goal.targetRepo, goal.revision, goal.createdAt, goal.updatedAt, projectId,
    )
}

private fun Connection.insertTask(p: PlannedTask, goal: ArchivedGoal, parentId: String) {
    val dependencies = p.dependencies.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
    execWrite(
        INSERT_TASK_SQL,
        p.taskId, goal.createdBy, p.key, p.row.title, p.row.body, goal.targetRepo, dependencies,
        // requires_approval is carried over; the row needs an operator approval either way.
        true, p.row.createdAt, p.row.updatedAt, parentId, p.projectId,
    )
}

private fun Connection.findTaskId(createdBy: String, key: String): String? =
    select("SELECT task_id FROM selfdev_tasks WHERE created_by = ? AND idempotency_key = ?", createdBy, key) { it.getString(1) }
        .firstOrNull()

private fun tableNames(conn: Connection): Set<String> =
    conn.metaData.getTables(conn.catalog, conn.schema, "%", arrayOf("TABLE")).use { rs ->
        buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
    }

private fun <T> Connection.select(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        bind(ps, params)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

private fun Connection.execWrite(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { ps ->
        bind(ps, params)
        ps.executeUpdate()
    }

private fun bind(ps: java.sql.PreparedStatement, params: Array<out Any?>) {
    params.forEachIndexed { i, value ->
        if (value == null) ps.setNull(i + 1, Types.VARCHAR) else ps.setObject(i + 1, value)
    }
}
