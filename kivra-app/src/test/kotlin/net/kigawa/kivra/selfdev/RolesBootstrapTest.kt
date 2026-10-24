package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.TestDb
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROOT = "5:oidc:root-secret-sub"
private const val AI = "5:oidc:ai-secret-sub"
private const val OTHER = "5:oidc:other"

class RolesBootstrapTest {
    private val db = TestDb()
    private val repo = SelfDevAssigneeRepository(Database.connect(db.dataSource))

    @AfterTest
    fun close() = db.close()

    private fun seed(root: String? = ROOT, ai: String? = AI, recover: Boolean = false) = seedRoles(repo, root, ai, recover)

    @Test
    fun `an empty database gets an ADMIN root approver and an AI assignee`() {
        val r = seed()
        assertEquals(SeedReport(2, 2, false, emptyList()), r)
        assertEquals(AssigneeRole.ADMIN, repo.accessOf(ROOT)!!.role)
        assertEquals(AssigneeRole.AI, repo.accessOf(AI)!!.role)
        assertEquals(setOf("bootstrap"), repo.list().map { it.createdBy }.toSet())
    }

    @Test
    fun `a second start changes nothing`() {
        seed()
        val before = repo.list()
        assertEquals(SeedReport(0, 0, false, emptyList()), seed())
        assertEquals(before, repo.list())
    }

    @Test
    fun `a demoted root approver is not put back`() {
        seed()
        val admin2 = repo.create("second", AssigneeRole.ADMIN, OTHER)
        val second = (admin2 as net.kigawa.kivra.db.AssigneeResult.Moved).value
        repo.bindMember(second.assigneeId, OTHER, OTHER)
        val root = repo.find(repo.accessOf(ROOT)!!.assigneeId)!!
        repo.changeRole(root.assigneeId, AssigneeRole.VIEWER, root.version, OTHER)
        seed()
        assertEquals(AssigneeRole.VIEWER, repo.accessOf(ROOT)!!.role)
        assertEquals(1, repo.list().count { it.displayName == ROOT_APPROVER_NAME })
    }

    @Test
    fun `an AI key bound to another role is left alone with a warning`() {
        val a = (repo.create("someone", AssigneeRole.OPERATOR, OTHER) as net.kigawa.kivra.db.AssigneeResult.Moved).value
        repo.bindMember(a.assigneeId, AI, OTHER)
        val before = repo.list()
        val r = seed(root = null)
        assertEquals(listOf(SeedWarning.ENV_AI_CONFLICT), r.warnings)
        assertEquals(0, r.assigneesCreated)
        assertEquals(before, repo.list())
        assertEquals(AssigneeRole.OPERATOR, repo.accessOf(AI)!!.role)
    }

    @Test
    fun `recovery re-enables and promotes only the root approver assignee`() {
        seed()
        val keeper = (repo.create("keeper", AssigneeRole.ADMIN, OTHER) as net.kigawa.kivra.db.AssigneeResult.Moved).value
        repo.bindMember(keeper.assigneeId, OTHER, OTHER)
        val bystander = repo.find(repo.accessOf(AI)!!.assigneeId)!!
        repo.setState(bystander.assigneeId, AssigneeState.DISABLED, bystander.version, OTHER)
        var root = repo.find(repo.accessOf(ROOT)!!.assigneeId)!!
        repo.changeRole(root.assigneeId, AssigneeRole.VIEWER, root.version, OTHER)
        root = repo.find(root.assigneeId)!!
        repo.setState(root.assigneeId, AssigneeState.DISABLED, root.version, OTHER)

        val aiBefore = repo.find(bystander.assigneeId)
        val r = seed(recover = true)
        assertTrue(r.recovered)
        val access = repo.accessOf(ROOT)!!
        assertEquals(AssigneeRole.ADMIN, access.role)
        assertEquals(AssigneeState.ACTIVE, access.state)
        assertEquals(aiBefore, repo.find(bystander.assigneeId))
        assertEquals(AssigneeState.DISABLED, repo.find(bystander.assigneeId)!!.state)
        assertEquals(AssigneeRole.ADMIN, repo.find(keeper.assigneeId)!!.role)
    }

    @Test
    fun `recovery rebinds an unbound root approver to the seeded assignee`() {
        seed()
        val root = repo.find(repo.accessOf(ROOT)!!.assigneeId)!!
        val keeper = (repo.create("keeper", AssigneeRole.ADMIN, OTHER) as net.kigawa.kivra.db.AssigneeResult.Moved).value
        repo.bindMember(keeper.assigneeId, OTHER, OTHER)
        repo.unbindMember(root.assigneeId, ROOT, OTHER)
        assertNull(repo.accessOf(ROOT))
        seed(recover = true)
        assertEquals(root.assigneeId, repo.accessOf(ROOT)!!.assigneeId)
        assertEquals(1, repo.list().count { it.displayName == ROOT_APPROVER_NAME })
    }

    @Test
    fun `off never calls the repository provider`() {
        val r = bootstrapRolesIfEnabled(RolesSettings.of(PermissionMode.OFF), ROOT, AI) { throw AssertionError("repository read") }
        assertNull(r)
    }

    @Test
    fun `enforce fails without a root approver, and without an active ADMIN`() {
        assertFailsWith<IllegalStateException> {
            bootstrapRolesIfEnabled(RolesSettings.of(PermissionMode.ENFORCE), null, AI) { repo }
        }
        // an ACTIVE ADMIN lost behind the repository's back (the invariant forbids it through the API): seeding leaves a disabled one alone
        seed()
        db.dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate("UPDATE selfdev_assignees SET state = 'DISABLED'") } }
        val e = assertFailsWith<IllegalStateException> {
            bootstrapRolesIfEnabled(RolesSettings.of(PermissionMode.ENFORCE), ROOT, AI) { repo }
        }
        assertFalse(e.message!!.contains(ROOT))
        // shadow only warns
        assertNotNull(bootstrapRolesIfEnabled(RolesSettings.of(PermissionMode.SHADOW), ROOT, AI) { repo })
    }

    @Test
    fun `enforce on an empty database succeeds and logs counts only`() {
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.RolesBootstrap") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            bootstrapRolesIfEnabled(RolesSettings.of(PermissionMode.ENFORCE, recoverAdmin = true), ROOT, AI) { repo }
        } finally {
            logger.detachAppender(appender)
        }
        val text = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(text.contains("assigneesCreated=2 membersBound=2"), text)
        assertTrue(text.contains("RECOVER_ADMIN_ACTIVE"), text)
        for (secret in listOf(ROOT, AI, "root-secret-sub", "ai-secret-sub", ROOT_APPROVER_NAME, AI_PRINCIPAL_NAME)) {
            assertFalse(text.contains(secret), secret)
        }
    }
}
