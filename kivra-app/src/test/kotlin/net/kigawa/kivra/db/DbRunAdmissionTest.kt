package net.kigawa.kivra.db

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.config.DatabaseConfig
import net.kigawa.kivra.core.AdmissionBlockReason
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.AdmissionResult
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RerunRejectReason
import net.kigawa.kivra.core.RerunResult
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Task -> Run admission (Issue #26): TaskBinding + DevelopmentRun in one transaction. Runs on H2 by default and on MariaDB
 * with `KIVRA_TEST_DB=mariadb`.
 */
class DbRunAdmissionTest {

    private val policy = AdmissionPolicy(
        statusField = "status",
        admittableStatuses = setOf("ready"),
        cancelledStatuses = setOf("cancelled"),
        dependenciesField = "dependencies",
        targetField = "target",
        allowedTargets = setOf("A", "B"),
        repositoryField = "repository",
    )

    /** Task body is a fixed marker, so tests can prove it never reaches a run, an audit row, an outbox row or an error. */
    private fun task(
        n: Long = 1,
        updatedAt: Instant = T0,
        description: String = "body $n",
        status: String = "\"ready\"",
        dependencies: String? = "[]",
        target: String? = "\"A\"",
        repository: String? = "\"kigawa/app\"",
    ) = TaskSnapshot(
        id = TaskId("fusha:test:$n"),
        title = "title $n",
        description = description,
        assigneeId = null,
        dueDate = null,
        fields = buildMap {
            put("status", status)
            dependencies?.let { put("dependencies", it) }
            target?.let { put("target", it) }
            repository?.let { put("repository", it) }
        },
        nextTaskIds = emptyList(),
        relatedTaskIds = emptyList(),
        updatedAt = updatedAt,
        fetchedAt = T0,
    )

    private fun TestDb.admission(policy: AdmissionPolicy? = this@DbRunAdmissionTest.policy) = DbRunAdmission(database, runs, audit, policy)

    private fun TestDb.bindingRunId(n: Long): String? = transaction(database) {
        execQuery("SELECT active_run_id FROM task_bindings WHERE provider = 'fusha' AND environment = 'test' AND task_id = ?", "$n") {
            it.getString(1)
        }.single()
    }

    private fun TestDb.bindingCount(): Int = count("task_bindings")

    private fun TestDb.bindingColumn(n: Long, column: String): String? = transaction(database) {
        execQuery("SELECT $column FROM task_bindings WHERE task_id = ?", "$n") { it.getString(1) }.single()
    }

    private fun TestDb.runCount(n: Long): Int = count("development_runs", "task_id = 'fusha:test:$n'")

    @Test
    fun `a first admission creates the run and the binding, as PR_ONLY with the system owner`() = TestDb().use { db ->
        val result = runBlocking { db.admission().admit(task(1), "req-1") }
        val runId = assertIs<AdmissionResult.Accepted>(result).runId

        assertEquals(runId.value, db.bindingRunId(1))
        assertEquals(1, db.runCount(1))
        assertEquals("1", db.bindingColumn(1, "generation"))
        assertEquals(1, db.count("audit_events", "run_id = '${runId.value}' AND action = 'RUN_CREATED' AND actor = 'system:fusha-poller'"))
        assertEquals(1, db.count("audit_events", "run_id = '${runId.value}' AND action = 'TASK_ADMITTED'"))
        val run = assertNotNull(db.runs.get(runId))
        assertEquals(DevelopmentRunState.CREATED, run.state)
        assertNull(run.principal)
        assertEquals("fusha:test:1", run.taskId)
    }

    @Test
    fun `concurrent admissions of one task from two replicas converge on one run`() = TestDb().use { db ->
        assertConcurrentAdmissionsConverge(db, replicas = 2, perReplica = 1)
    }

    @Test
    fun `eight concurrent admissions of one task converge on one run`() = TestDb().use { db ->
        assertConcurrentAdmissionsConverge(db, replicas = 8, perReplica = 1)
    }

    private fun assertConcurrentAdmissionsConverge(db: TestDb, replicas: Int, perReplica: Int) {
        val total = replicas * perReplica
        // Each replica has its own Database connection, like a separate process.
        val admissions = (1..replicas).map { DbRunAdmission(connectDatabase(db.dataSource), db.runs, db.audit, policy) }
        val barrier = CyclicBarrier(total)
        val pool = Executors.newFixedThreadPool(total)
        try {
            val futures = (0 until total).map { i ->
                pool.submit(Callable {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    runBlocking { admissions[i % replicas].admit(task(7), "req-$i") }
                })
            }
            val results = futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            val accepted = results.filterIsInstance<AdmissionResult.Accepted>()
            assertEquals(1, accepted.size, "exactly one admission creates the run: $results")
            val runIds = results.map {
                when (it) {
                    is AdmissionResult.Accepted -> it.runId
                    is AdmissionResult.AlreadyActive -> it.runId
                    else -> error("unexpected $it")
                }
            }.toSet()
            assertEquals(1, runIds.size)
            assertEquals(1, db.runCount(7))
            assertEquals(runIds.single().value, db.bindingRunId(7))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `resending the same request returns the same run`() = TestDb().use { db ->
        val admission = db.admission()
        val first = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(2), "same-request") }).runId
        val again = runBlocking { admission.admit(task(2), "same-request") }
        assertEquals(AdmissionResult.AlreadyActive(first), again)
        assertEquals(1, db.runCount(2))
    }

    @Test
    fun `a failure after the run insert rolls back both the run and the binding, and a retry converges`() = TestDb().use { db ->
        val admission = db.admission()
        admission.afterRunInsert = { throw IllegalStateException("injected failure") }
        assertFailsWith<IllegalStateException> { runBlocking { admission.admit(task(3), "req-3") } }
        assertEquals(0, db.runCount(3), "no run survives the failed admission")
        assertEquals(0, db.bindingCount(), "no binding survives the failed admission")
        assertEquals(0, db.count("audit_events", "resource = 'task:fusha:test:3'"))

        admission.afterRunInsert = null
        val runId = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(3), "req-3") }).runId
        assertEquals(runId.value, db.bindingRunId(3))
        assertEquals(1, db.runCount(3))
    }

    @Test
    fun `a finished run is answered AlreadyTerminal and no new run is created`() = TestDb().use { db ->
        val admission = db.admission()
        val runId = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(4), "req-4") }).runId
        db.forceState(runId.value, DevelopmentRunState.COMPLETED)

        assertEquals(AdmissionResult.AlreadyTerminal(runId), runBlocking { admission.admit(task(4), "req-4-again") })
        assertEquals(1, db.runCount(4))
    }

    @Test
    fun `an explicit rerun is refused while the run is active and allowed after it finished`() = TestDb().use { db ->
        val admission = db.admission()
        val task = TaskId("fusha:test:5")
        assertEquals(RerunResult.Rejected(RerunRejectReason.NOT_BOUND), runBlocking { admission.requestRerun(task, "op", "why") })

        val first = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(5), "req-5") }).runId
        assertEquals(
            RerunResult.Rejected(RerunRejectReason.RUN_NOT_TERMINAL),
            runBlocking { admission.requestRerun(task, "op", "why") },
        )
        assertEquals(first.value, db.bindingRunId(5))

        db.forceState(first.value, DevelopmentRunState.FAILED)
        assertEquals(RerunResult.Requested(2), runBlocking { admission.requestRerun(task, "operator-1", "retry after fix") })
        assertNull(db.bindingRunId(5))
        assertEquals("2", db.bindingColumn(5, "generation"))
        assertEquals(
            1,
            db.count("audit_events", "run_id = '${first.value}' AND action = 'TASK_RERUN_REQUESTED' AND actor = 'operator-1' AND payload LIKE '%retry after fix%'"),
        )
        assertEquals(
            RerunResult.Rejected(RerunRejectReason.NOTHING_TO_RERUN),
            runBlocking { admission.requestRerun(task, "operator-1", "again") },
        )

        val second = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(5), "req-5-b") }).runId
        assertNotEquals(first, second)
        assertEquals(second.value, db.bindingRunId(5))
        assertEquals(2, db.runCount(5))
        assertEquals(
            1,
            db.count("audit_events", "run_id = '${second.value}' AND action = 'TASK_ADMITTED' AND payload LIKE '%admission:fusha:test:5:2%'"),
        )
    }

    @Test
    fun `blocked snapshots write neither a run nor a binding`() = TestDb().use { db ->
        val admission = db.admission()
        val cases = listOf(
            AdmissionBlockReason.CANCELLED to task(11, status = "\"cancelled\""),
            AdmissionBlockReason.STATUS_NOT_ADMITTABLE to task(12, status = "\"draft\""),
            AdmissionBlockReason.STATUS_UNKNOWN to task(13, status = "7"),
            AdmissionBlockReason.DEPENDENCIES_UNRESOLVED to task(14, dependencies = "[\"fusha:test:99\"]"),
            AdmissionBlockReason.DEPENDENCIES_UNKNOWN to task(15, dependencies = null),
            AdmissionBlockReason.TARGET_UNKNOWN to task(16, target = "\"C\""),
            AdmissionBlockReason.TARGET_UNKNOWN to task(17, target = null),
            AdmissionBlockReason.REPOSITORY_UNKNOWN to task(18, repository = null),
        )
        for ((reason, snapshot) in cases) {
            assertEquals(AdmissionResult.Blocked(reason), runBlocking { admission.admit(snapshot, "req") }, "for ${snapshot.id.value}")
        }
        assertEquals(AdmissionResult.Blocked(AdmissionBlockReason.POLICY_UNCONFIRMED), runBlocking { db.admission(null).admit(task(19), "req") })
        assertEquals(0, db.count("development_runs"))
        assertEquals(0, db.bindingCount())
    }

    @Test
    fun `a changed body with the same updatedAt records the hash and never creates a second run`() = TestDb().use { db ->
        val admission = db.admission()
        val runId = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(20), "req-a") }).runId
        val hashBefore = db.bindingColumn(20, "observed_hash")

        assertEquals(AdmissionResult.AlreadyActive(runId), runBlocking { admission.admit(task(20, description = "edited body"), "req-b") })
        assertNotEquals(hashBefore, db.bindingColumn(20, "observed_hash"))
        assertEquals(1, db.runCount(20))

        db.forceState(runId.value, DevelopmentRunState.COMPLETED)
        assertEquals(AdmissionResult.AlreadyTerminal(runId), runBlocking { admission.admit(task(20, description = "edited again"), "req-c") })
        assertEquals(1, db.runCount(20))
    }

    @Test
    fun `an older snapshot never moves the binding backwards and creates no run`() = TestDb().use { db ->
        val admission = db.admission()
        val newer = T0.plusSeconds(60)
        val runId = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(21, updatedAt = newer), "req-new") }).runId
        val observedBefore = db.bindingColumn(21, "observed_updated_at")

        assertEquals(AdmissionResult.AlreadyActive(runId), runBlocking { admission.admit(task(21, updatedAt = T0), "req-old") })
        assertEquals(observedBefore, db.bindingColumn(21, "observed_updated_at"))
        assertEquals(1, db.runCount(21))

        // After a finished run and a rerun there is no active run: an older snapshot must not create one.
        db.forceState(runId.value, DevelopmentRunState.FAILED)
        runBlocking { admission.requestRerun(TaskId("fusha:test:21"), "op", "rerun") }
        assertEquals(
            AdmissionResult.Blocked(AdmissionBlockReason.STALE_SNAPSHOT),
            runBlocking { admission.admit(task(21, updatedAt = T0), "req-old-2") },
        )
        assertEquals(1, db.runCount(21))
    }

    @Test
    fun `the task body never reaches the run, the audit, the outbox, a block result or an error`() = TestDb().use { db ->
        val marker = "SECRET-BODY-MARKER-${UUID.randomUUID()}"
        val admission = db.admission()
        assertIs<AdmissionResult.Blocked>(runBlocking { admission.admit(task(22, description = marker, status = "\"cancelled\""), "req") })
        admission.afterRunInsert = { throw IllegalStateException("injected") }
        val error = assertFailsWith<IllegalStateException> { runBlocking { admission.admit(task(23, description = marker), "req") } }
        assertTrue(error.stackTraceToString().contains(marker).not())
        admission.afterRunInsert = null
        assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(24, description = marker), "req") })

        assertTrue(!db.allPersistedText().contains(marker))
        val bindingText = transaction(db.database) {
            execQuery("SELECT * FROM task_bindings") { rs -> (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() } }
        }.joinToString("\n")
        assertTrue(!bindingText.contains(marker))
    }

    @Test
    fun `an application user with DML only can admit (MariaDB only)`() {
        if (!TestDb.isMariaDb) return
        TestDb().use { admin ->
            val schema = requireNotNull(admin.schema)
            val server = (System.getenv("KIVRA_TEST_MARIADB_URL") ?: "jdbc:mariadb://localhost:3306/").trimEnd('/')
            val rootUser = System.getenv("KIVRA_TEST_MARIADB_USER") ?: "root"
            val rootPassword = System.getenv("KIVRA_TEST_MARIADB_PASSWORD") ?: ""
            val appUser = "kivra_adm_${System.nanoTime()}"
            val appPassword = UUID.randomUUID().toString()
            DriverManager.getConnection("$server/?sessionVariables=time_zone='+00:00'", rootUser, rootPassword).use { c ->
                c.createStatement().use {
                    it.execute("CREATE USER '$appUser'@'%' IDENTIFIED BY '$appPassword'")
                    it.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `$schema`.* TO '$appUser'@'%'")
                }
            }
            try {
                val app = createDataSource(DatabaseConfig("$server/$schema?sessionVariables=time_zone='+00:00'", appUser, appPassword))
                try {
                    val database = connectDatabase(app)
                    val runs = DevelopmentRunRepository(database, OutboxRepository(database), AuditRepository(), admin.prAuthority)
                    val admission = DbRunAdmission(database, runs, AuditRepository(), policy)
                    assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(30), "dml-req") })
                    assertIs<AdmissionResult.AlreadyActive>(runBlocking { admission.admit(task(30), "dml-req") })
                } finally {
                    closeDataSource(app)
                }
            } finally {
                DriverManager.getConnection("$server/", rootUser, rootPassword).use { c ->
                    c.createStatement().use { it.execute("DROP USER IF EXISTS '$appUser'@'%'") }
                }
            }
        }
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-15T00:00:00Z")
        const val TIMEOUT_SECONDS = 30L
    }
}
