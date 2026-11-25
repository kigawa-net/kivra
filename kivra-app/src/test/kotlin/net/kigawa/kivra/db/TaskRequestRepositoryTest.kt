package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jetbrains.exposed.sql.transactions.transaction

/** The approval requests (ADR 0025): the states, the separation of requester and decider, and the audit rows. */
private fun TestDb.newProject(): String = ProjectRepository(database, java.time.Clock.systemUTC()).create("Requests", null).projectId

class TaskRequestRepositoryTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"
    private val approver = "approver@example.com"

    private fun TaskRequestRepository.pending(project: String, requester: String = alice, title: String = "Write the release notes", key: String = "k-1"): TaskRequest =
        (submit(project, requester, key, title) as TaskRequestSubmitResult.Created).request

    @Test
    fun `a new request is PENDING, and the same key with the same title is a replay`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val first = assertIs<TaskRequestSubmitResult.Created>(requests.submit(project, alice, "k-1", "Same"))
        assertEquals(TaskRequestState.PENDING, first.request.state)
        assertNull(first.request.runId)
        assertIs<TaskRequestSubmitResult.Replayed>(requests.submit(project, alice, "k-1", "Same"))
        val conflict = assertIs<TaskRequestSubmitResult.ConflictingTitle>(requests.submit(project, alice, "k-1", "Different"))
        assertEquals(first.request.requestId, conflict.request.requestId)
        // Another requester's same key is another request.
        assertIs<TaskRequestSubmitResult.Created>(requests.submit(project, bob, "k-1", "Same"))
        assertEquals(2, db.count("task_requests"))
    }

    @Test
    fun `another caller decides a PENDING request, and the decision is final`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        val moved = assertIs<TaskRequestTransition.Moved>(requests.decide(project, request.requestId, approver, approve = true, note = null))
        assertEquals(TaskRequestState.APPROVED, moved.request.state)
        assertEquals(approver, moved.request.decidedBy)
        assertNotNull(moved.request.decidedAt)
        // Decided once: a second decision, approve or reject, changes nothing.
        assertIs<TaskRequestTransition.NotPending>(requests.decide(project, request.requestId, approver, approve = false, note = "late"))
        assertIs<TaskRequestTransition.NotPending>(requests.cancel(project, request.requestId, alice))
        assertEquals(TaskRequestState.APPROVED, requests.get(project, request.requestId)!!.state)
        assertNull(requests.get(project, request.requestId)!!.decisionNote)
    }

    @Test
    fun `the requester may not decide its own request, and a missing request is not found`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        assertEquals(TaskRequestTransition.OwnRequest, requests.decide(project, request.requestId, alice, approve = true, note = null))
        assertEquals(TaskRequestTransition.OwnRequest, requests.decide(project, request.requestId, alice, approve = false, note = null))
        assertEquals(TaskRequestState.PENDING, requests.get(project, request.requestId)!!.state)
        assertEquals(TaskRequestTransition.NotFound, requests.decide(project, "01AAAAAAAAAAAAAAAAAAAAAAAA", approver, approve = true, note = null))
    }

    @Test
    fun `the database refuses a self approval even when the repository is bypassed`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        assertFailsWith<Exception> {
            transaction(db.database) {
                exec("UPDATE task_requests SET state = 'REJECTED', decided_by = requested_by WHERE request_id = '${request.requestId}'")
            }
        }
        assertEquals(TaskRequestState.PENDING, requests.get(project, request.requestId)!!.state)
    }

    @Test
    fun `a rejection keeps its reason in the row only`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        val moved = assertIs<TaskRequestTransition.Moved>(requests.decide(project, request.requestId, approver, approve = false, note = "not now"))
        assertEquals(TaskRequestState.REJECTED, moved.request.state)
        assertEquals("not now", moved.request.decisionNote)
        assertNull(moved.request.runId)
        // The audit row has the fixed kind; the reason is not in it (the event table has no free-text column).
        assertEquals(1, db.count("task_request_events", "request_id = '${request.requestId}' AND kind = 'REJECTED'"))
    }

    @Test
    fun `only the requester cancels, and only while PENDING`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        // Another caller's request is NotFound for a cancel, as for a missing one.
        assertEquals(TaskRequestTransition.NotFound, requests.cancel(project, request.requestId, bob))
        assertEquals(TaskRequestState.PENDING, requests.get(project, request.requestId)!!.state)
        val cancelled = assertIs<TaskRequestTransition.Moved>(requests.cancel(project, request.requestId, alice))
        assertEquals(TaskRequestState.CANCELLED, cancelled.request.state)
        assertNull(cancelled.request.decidedBy)
        assertIs<TaskRequestTransition.NotPending>(requests.decide(project, request.requestId, approver, approve = true, note = null))
        assertIs<TaskRequestTransition.NotPending>(requests.cancel(project, request.requestId, alice))
    }

    @Test
    fun `a run is linked once, and only to an approved request`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        // A PENDING request takes no run.
        assertNull(requests.linkRun(request.requestId, "01BBBBBBBBBBBBBBBBBBBBBBBB", approver))
        requests.decide(project, request.requestId, approver, approve = true, note = null)
        val run = db.runs.submitTask(alice, requestRunKey(request.requestId), request.title).run
        val linked = assertNotNull(requests.linkRun(request.requestId, run.runId.value, approver))
        assertEquals(run.runId.value, linked.runId)
        // The second link is refused: the run is set once.
        assertNull(requests.linkRun(request.requestId, run.runId.value, approver))
        assertEquals(1, db.count("task_request_events", "request_id = '${request.requestId}' AND kind = 'RUN_LINKED'"))
        assertEquals(1, db.count("task_request_events", "request_id = '${request.requestId}' AND kind = 'APPROVED'"))
    }

    @Test
    fun `the list is the requester's own, and a decider also sees the others' PENDING requests`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val mine = requests.pending(project, requester = alice, key = "a-1")
        val theirs = requests.pending(project, requester = bob, key = "b-1")
        requests.decide(project, theirs.requestId, approver, approve = false, note = null)
        val pendingOfBob = requests.pending(project, requester = bob, key = "b-2")
        assertEquals(listOf(mine.requestId), requests.list(project, alice, decidable = false, limit = 50).map { it.requestId })
        val decider = requests.list(project, approver, decidable = true, limit = 50).map { it.requestId }.toSet()
        // the approver is neither requester, so both PENDING requests (alice's and bob's second) are decidable; bob's rejected one is not
        assertEquals(setOf(mine.requestId, pendingOfBob.requestId), decider)
    }

    @Test
    fun `every change writes one event, and the actor is a hash of the owner key`() = TestDb().use { db ->
        val project = db.newProject()
        val requests = TaskRequestRepository(db.database)
        val request = requests.pending(project)
        requests.decide(project, request.requestId, approver, approve = true, note = null)
        assertEquals(1, db.count("task_request_events", "request_id = '${request.requestId}' AND actor_ref = '${SelfDevAssigneeRepository.actorRef(alice)}' AND kind = 'REQUESTED'"))
        assertEquals(1, db.count("task_request_events", "request_id = '${request.requestId}' AND actor_ref = '${SelfDevAssigneeRepository.actorRef(approver)}' AND kind = 'APPROVED'"))
        assertEquals(0, db.count("task_request_events", "actor_ref = '$approver'"))
    }
}
