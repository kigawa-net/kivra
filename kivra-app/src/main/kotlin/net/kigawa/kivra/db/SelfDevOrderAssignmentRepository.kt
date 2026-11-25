package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** ADR 0024: assignment is information, never an authorization rule. assignedBy is an actor reference, never an owner key. */
data class OrderAssignment(
    val assignmentId: String,
    val orderId: String,
    val assigneeId: String,
    val assignedBy: String,
    val assignedAt: LocalDateTime,
    val unassignedAt: LocalDateTime?,
)

/** Fixed codes only; no user input is included in the exception. */
class InvalidOrderAssignment(val code: String) : IllegalArgumentException(code)

class SelfDevOrderAssignmentRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {
    fun <T> atomic(block: () -> T): T = transaction(database) { block() }

    /** Shared by REST and chat, including creation. Also rechecked while writing. */
    fun validate(projectId: String?, assigneeId: String) = transaction(database) {
        if (projectId == null) throw InvalidOrderAssignment("NO_PROJECT")
        val a = execQuery("SELECT state, role FROM selfdev_assignees WHERE assignee_id = ? FOR UPDATE", assigneeId) {
            it.getString(1) to it.getString(2)
        }.firstOrNull() ?: throw InvalidOrderAssignment("ASSIGNEE_NOT_FOUND")
        if (a.first != "ACTIVE") throw InvalidOrderAssignment("ASSIGNEE_DISABLED")
        if (a.second in setOf("AI", "AI_APPROVER")) throw InvalidOrderAssignment("ASSIGNEE_AI")
        val role = execQuery("SELECT role FROM project_members WHERE project_id = ? AND assignee_id = ? FOR UPDATE", projectId, assigneeId) {
            it.getString(1)
        }.firstOrNull() ?: throw InvalidOrderAssignment("ASSIGNEE_NOT_MEMBER")
        if (role == "AI") throw InvalidOrderAssignment("ASSIGNEE_AI")
    }

    fun assign(orderId: String, assigneeId: String, assignedBy: String): OrderAssignment = reassign(orderId, assigneeId, assignedBy)

    fun reassign(orderId: String, newAssigneeId: String, assignedBy: String): OrderAssignment = transaction(database) {
        require(assignedBy.matches(Regex("[0-9a-f]{12}"))) { "assignedBy must be an actor reference" }
        // Lock the order even when no assignment exists, so first assignments also serialize.
        val project = lockOrder(orderId)
        validate(project, newAssigneeId)
        val active = active(orderId, lock = true)
        if (active?.assigneeId == newAssigneeId) return@transaction active
        val now = utcNow(clock)
        close(orderId, now)
        val id = Ulid.generate(clock)
        execUpdate("INSERT INTO selfdev_order_assignments (assignment_id, order_id, assignee_id, assigned_by, assigned_at, active_slot) VALUES (?, ?, ?, ?, ?, 1)",
            id, orderId, newAssigneeId, assignedBy, now)
        OrderAssignment(id, orderId, newAssigneeId, assignedBy, now, null)
    }

    fun unassign(orderId: String, assignedBy: String) = transaction(database) {
        require(assignedBy.matches(Regex("[0-9a-f]{12}"))) { "assignedBy must be an actor reference" }
        lockOrder(orderId)
        active(orderId, lock = true)
        close(orderId, utcNow(clock))
        Unit
    }

    fun getActive(orderId: String): OrderAssignment? = transaction(database) { active(orderId) }
    fun listForOrder(orderId: String): List<OrderAssignment> = transaction(database) {
        execQuery("SELECT $COLUMNS FROM selfdev_order_assignments WHERE order_id = ? ORDER BY assigned_at, assignment_id", orderId) { it.assignment() }
    }
    fun listForAssignee(assigneeId: String): List<OrderAssignment> = transaction(database) {
        execQuery("SELECT $COLUMNS FROM selfdev_order_assignments WHERE assignee_id = ? AND unassigned_at IS NULL ORDER BY assigned_at, assignment_id", assigneeId) { it.assignment() }
    }
    private fun Transaction.lockOrder(orderId: String): String? = execQuery(
        "SELECT project_id FROM selfdev_orders WHERE order_id = ? FOR UPDATE", orderId,
    ) { it.getString(1) }.let { rows ->
        if (rows.isEmpty()) throw InvalidOrderAssignment("ORDER_NOT_FOUND")
        rows.single()
    }
    private fun Transaction.active(orderId: String, lock: Boolean = false): OrderAssignment? = execQuery(
        "SELECT $COLUMNS FROM selfdev_order_assignments WHERE order_id = ? AND unassigned_at IS NULL" + if (lock) " FOR UPDATE" else "", orderId,
    ) { it.assignment() }.firstOrNull()
    private fun Transaction.close(orderId: String, now: LocalDateTime) {
        execUpdate("UPDATE selfdev_order_assignments SET unassigned_at = ?, active_slot = NULL WHERE order_id = ? AND unassigned_at IS NULL", now, orderId)
    }
    private companion object {
        const val COLUMNS = "assignment_id, order_id, assignee_id, assigned_by, assigned_at, unassigned_at"
    }
}

private fun java.sql.ResultSet.assignment() = OrderAssignment(getString(1), getString(2), getString(3), getString(4),
    getObject(5, LocalDateTime::class.java), getObject(6, LocalDateTime::class.java))
