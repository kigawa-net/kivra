package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.core.chat.ENDED_TASK_STATES
import net.kigawa.kivra.core.chat.ChatActionCode
import net.kigawa.kivra.core.chat.ChatActionOrigin
import net.kigawa.kivra.core.chat.ChatActionProposal
import net.kigawa.kivra.core.chat.ChatActionState
import net.kigawa.kivra.core.chat.ChatApprovalFact
import net.kigawa.kivra.core.chat.ChatCycleTaskFact
import net.kigawa.kivra.core.chat.ChatOrderFacts
import net.kigawa.kivra.core.chat.ChatResultCode
import net.kigawa.kivra.core.chat.ChatTargetKind
import net.kigawa.kivra.core.chat.ChatTaskFact
import net.kigawa.kivra.core.chat.MEMBER_ABSENT
import net.kigawa.kivra.core.chat.MEMBER_PRESENT
import net.kigawa.kivra.core.chat.isCycleStuck
import net.kigawa.kivra.core.chat.proposeChatActions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** One proposal row of the project chat (V56). [code] is a [ChatActionCode] name; [state] a [ChatActionState] name. */
data class ProjectChatActionRow(
    val actionId: String,
    val projectId: String,
    val code: String,
    val targetId: String?,
    val expectedVersion: Long?,
    val state: String,
    val resultCode: String?,
    val proposedAt: LocalDateTime,
    val resolvedAt: LocalDateTime?,
    val resolvedBy: String?,
    /** What [targetId] names (V57; a [ChatTargetKind] name). ORDER for a row written before V57 by an order code. */
    val targetKind: String = ChatTargetKind.ORDER.name,
    /** Who proposed the row (V57; a [ChatActionOrigin] name). RULE for every row before V57. */
    val origin: String = ChatActionOrigin.RULE.name,
    /** A fixed parameter of the action (V57), never free text: the member role of a MEMBER_ADD. Null otherwise. */
    val paramCode: String? = null,
)

/** The result of [ProjectChatActionRepository.proposeByAi]. Either way the operator sees one open proposal for the target. */
sealed interface AiProposalResult {
    val actionId: String

    /** A new PROPOSED row was written. */
    data class Created(override val actionId: String) : AiProposalResult

    /** A proposal of the same project, code and target is already open (from the rules or an earlier AI call); nothing was written. */
    data class AlreadyOpen(override val actionId: String) : AiProposalResult
}

/** The Task of an approval proposal as the chat card reads it at GET time: its raw title (planner-written, so the route sanitizes it) and state reason. */
data class ChatApprovalTaskRow(val title: String, val stateReason: String?)

/**
 * The proposals of the project chat (ADR 0023, stages 1 and 2). [refresh] is the rule-based proposer: it reads the root orders of a project and
 * their cycles in progress, expires the open proposals the rules no longer make, and writes the new ones. It runs when an operator reads the
 * chat, so a proposal always reflects the state at that read. Nothing here calls a model.
 *
 * The rules are [proposeChatActions] and [isCycleStuck] (kivra-core). The writes are conditional: an open proposal is expired with
 * `state = 'PROPOSED'` in the WHERE, and a new one is refused by the UNIQUE on `active_key` when another is open. A proposal for an order and a
 * version that already has a row in any state is not written again, so a failed or dismissed action is not proposed again at the same version.
 */
class ProjectChatActionRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json

    /** A root order as the proposer reads it. */
    private data class RootRow(
        val orderId: String,
        val state: String,
        val version: Long,
        val currentGoalId: String?,
        val cyclesStarted: Int,
        val maxCycles: Int,
        val expiresAt: LocalDateTime,
    )

    /**
     * Brings the open proposals of [projectId] in line with the rules, and returns the number of new ones. Expired rows are kept (a history).
     * The root orders are read under no lock: a stale read only expires or proposes a row that the confirmation then checks again.
     */
    fun refresh(projectId: String): Int = transaction(database) {
        val now = utcNow(clock)
        val roots = execQuery(
            """
            SELECT order_id, state, version, current_goal_id, cycles_started, max_cycles, expires_at
              FROM selfdev_orders WHERE project_id = ? AND parent_order_id IS NULL
             ORDER BY created_at, order_id LIMIT ?
            """.trimIndent(),
            projectId, MAX_ROOTS,
        ) { rs ->
            RootRow(
                orderId = rs.getString(1),
                state = rs.getString(2),
                version = rs.getLong(3),
                currentGoalId = rs.getString(4),
                cyclesStarted = rs.getInt(5),
                maxCycles = rs.getInt(6),
                expiresAt = rs.getObject(7, LocalDateTime::class.java),
            )
        }
        val facts = roots.map { root ->
            ChatOrderFacts(
                orderId = root.orderId,
                state = root.state,
                version = root.version,
                currentGoalId = root.currentGoalId,
                cyclesStarted = root.cyclesStarted,
                maxCycles = root.maxCycles,
                expired = !root.expiresAt.isAfter(now),
                cycleStuck = root.currentGoalId?.let { cycleStuck(it) } ?: false,
                cycleTask = root.currentGoalId?.let { cycleTaskOf(it) },
                approvals = root.currentGoalId?.let { approvalsOf(it) } ?: emptyList(),
            )
        }
        val proposals = proposeChatActions(facts)
        val wanted = proposals.map { Triple(it.code.name, it.targetId, it.expectedVersion) }.toSet()
        val orderVersionOf = facts.associate { it.orderId to it.version }
        val taskVersionOf = facts.flatMap { f ->
            listOfNotNull(f.cycleTask?.let { it.taskId to it.version }) + f.approvals.map { it.taskId to it.version }
        }.toMap()

        // Expire the open proposals the rules no longer make (the version moved, or the order or Task is no longer eligible).
        val open = openRows(projectId)
        val openKeys = mutableSetOf<Triple<String, String?, Long?>>()
        for (row in open) {
            val key = Triple(row.code, row.targetId, row.expectedVersion)
            if (key in wanted) {
                openKeys += key
                continue
            }
            val current = row.targetId?.let { id ->
                when (kindOf(row.code)) {
                    ChatTargetKind.ORDER -> orderVersionOf[id] ?: if (row.origin == ChatActionOrigin.AI.name) orderVersionRow(id) else null
                    ChatTargetKind.TASK -> taskVersionOf[id] ?: taskVersionRow(id)
                    ChatTargetKind.ASSIGNEE -> membershipIn(projectId, id)
                }
            }
            // An AI proposal is not the rules' to withdraw: it stays open while its target is as it was when proposed (ADR 0023 追補 5).
            if (row.origin == ChatActionOrigin.AI.name && current != null && current == row.expectedVersion) continue
            val moved = current != null && current != row.expectedVersion
            execUpdate(
                "UPDATE project_chat_actions SET state = ?, active_key = NULL, result_code = ?, resolved_at = ? WHERE action_id = ? AND state = ?",
                ChatActionState.EXPIRED.name,
                if (moved) ChatResultCode.VERSION_MOVED else ChatResultCode.NO_LONGER_ELIGIBLE,
                now, row.actionId, ChatActionState.PROPOSED.name,
            )
        }

        var created = 0
        for (proposal in proposals) {
            val key = Triple(proposal.code.name, proposal.targetId, proposal.expectedVersion)
            if (key in openKeys) continue
            if (hasAnyRow(projectId, proposal)) continue
            try {
                insert(projectId, proposal, now)
                created++
            } catch (e: Exception) {
                // Another proposal of the same key was written meanwhile (the UNIQUE on active_key): the open one stands.
                if (!e.isUniqueViolation()) throw e
            }
        }
        created
    }

    /**
     * Writes a proposal of the chat's AI (ADR 0023 追補 5): PROPOSED, origin AI, for [targetId] of [code]'s target kind, valid while the target's
     * version (or, for a member, its membership) is [expectedVersion]. [paramCode] is a fixed parameter (the member role of MEMBER_ADD). Nothing
     * runs here: the operator confirms on the screen. When a proposal of the same project, code and target is open already, nothing is written
     * and that one is returned. Unlike the rules, an earlier resolved row of the same version does not block it: the operator asked again.
     */
    fun proposeByAi(projectId: String, code: ChatActionCode, targetId: String, expectedVersion: Long, paramCode: String? = null): AiProposalResult =
        transaction(database) {
            openRowOf(projectId, code, targetId)?.let { return@transaction AiProposalResult.AlreadyOpen(it) }
            val id = Ulid.generate(clock)
            try {
                execUpdate(
                    """
                    INSERT INTO project_chat_actions (action_id, project_id, action_code, target_id, target_kind, origin, param_code, expected_version,
                                                      state, active_key, proposed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """.trimIndent(),
                    id, projectId, code.name, targetId, code.targetKind.name, ChatActionOrigin.AI.name, paramCode, expectedVersion,
                    ChatActionState.PROPOSED.name, utcNow(clock),
                )
                AiProposalResult.Created(id)
            } catch (e: Exception) {
                if (!e.isUniqueViolation()) throw e
                null
            }
        } ?: transaction(database) {
            // Another open proposal of the same key was written meanwhile (the UNIQUE on active_key): that one stands.
            AiProposalResult.AlreadyOpen(checkNotNull(openRowOf(projectId, code, targetId)) { "the open proposal is gone" })
        }

    /** The membership of [assigneeId] in [projectId] as a member proposal's expected value: [MEMBER_PRESENT] or [MEMBER_ABSENT]. */
    fun membership(projectId: String, assigneeId: String): Long = transaction(database) { membershipIn(projectId, assigneeId) }

    /** The display name of an assignee, for a member proposal's card (an operator's text, shown to the operator), or null when it is missing. */
    fun assigneeNameOf(assigneeId: String): String? = transaction(database) {
        execQuery("SELECT display_name FROM selfdev_assignees WHERE assignee_id = ?", assigneeId) { it.getString(1) }.firstOrNull()
    }

    /** True when the assignee exists (whatever its state). */
    fun assigneeExists(assigneeId: String): Boolean = transaction(database) {
        execQuery("SELECT COUNT(*) FROM selfdev_assignees WHERE assignee_id = ?", assigneeId) { it.getLong(1) }.single() > 0
    }

    private fun Transaction.membershipIn(projectId: String, assigneeId: String): Long =
        if (execQuery("SELECT COUNT(*) FROM project_members WHERE project_id = ? AND assignee_id = ?", projectId, assigneeId) { it.getLong(1) }
                .single() > 0
        ) MEMBER_PRESENT else MEMBER_ABSENT

    private fun Transaction.openRowOf(projectId: String, code: ChatActionCode, targetId: String): String? =
        execQuery(
            "SELECT action_id FROM project_chat_actions WHERE project_id = ? AND action_code = ? AND target_id = ? AND state = ?",
            projectId, code.name, targetId, ChatActionState.PROPOSED.name,
        ) { it.getString(1) }.firstOrNull()

    /** The current version of an order (one an AI proposal names, which may be a child), or null when it is gone. */
    private fun Transaction.orderVersionRow(orderId: String): Long? =
        execQuery("SELECT version FROM selfdev_orders WHERE order_id = ?", orderId) { it.getLong(1) }.firstOrNull()

    /** The open (PROPOSED) proposals of [projectId], oldest first. */
    fun proposed(projectId: String): List<ProjectChatActionRow> = transaction(database) {
        execQuery(
            "$SELECT_COLUMNS WHERE project_id = ? AND state = ? ORDER BY proposed_at, action_id",
            projectId, ChatActionState.PROPOSED.name,
        ) { it.toRow() }
    }

    /** The proposal [actionId] of [projectId], or null. A row of another project is not found. */
    fun find(projectId: String, actionId: String): ProjectChatActionRow? = transaction(database) {
        execQuery("$SELECT_COLUMNS WHERE action_id = ? AND project_id = ?", actionId, projectId) { it.toRow() }.firstOrNull()
    }

    /**
     * Moves a PROPOSED row to [state] with a fixed [resultCode]. True when the row was PROPOSED and is now resolved; false when it had been
     * resolved already (a second confirm or dismiss is refused, nothing runs twice).
     */
    fun resolve(actionId: String, state: ChatActionState, resultCode: String, resolvedBy: String?): Boolean = transaction(database) {
        val now = utcNow(clock)
        val changed = execUpdate(
            "UPDATE project_chat_actions SET state = ?, active_key = NULL, result_code = ?, resolved_at = ?, resolved_by = ? WHERE action_id = ? AND state = ?",
            state.name, resultCode, now, resolvedBy, actionId, ChatActionState.PROPOSED.name,
        )
        // A resolved approval (executed, failed or expired) takes its alternative with it: approve and reject of one Task are exclusive. A
        // dismissal only hides the proposal, so it leaves the other one open. The version move would expire the other one too, but a failed
        // transition does not move the version, so the sibling is expired here.
        if (changed == 1 && state != ChatActionState.DISMISSED) expireSiblingsOf(actionId, now)
        changed == 1
    }

    /** The other open approval proposals of the same Task as [actionId] (APPROVE and REJECT of one Task) are expired as resolved. */
    private fun Transaction.expireSiblingsOf(actionId: String, now: LocalDateTime) {
        val row = execQuery("SELECT project_id, action_code, target_id FROM project_chat_actions WHERE action_id = ?", actionId) { rs ->
            Triple(rs.getString(1), rs.getString(2), rs.getString(3))
        }.firstOrNull() ?: return
        val (projectId, code, targetId) = row
        if (code !in APPROVAL_CODES || targetId == null) return
        execUpdate(
            "UPDATE project_chat_actions SET state = ?, active_key = NULL, result_code = ?, resolved_at = ? " +
                "WHERE project_id = ? AND action_code IN (?, ?) AND target_id = ? AND action_id <> ? AND state = ?",
            ChatActionState.EXPIRED.name, ChatResultCode.SIBLING_RESOLVED, now,
            projectId, ChatActionCode.TASK_APPROVE.name, ChatActionCode.TASK_REJECT.name, targetId, actionId, ChatActionState.PROPOSED.name,
        )
    }

    /** The Task of an approval proposal, read for the card at GET time (its title and state reason only; never the body), or null when missing. */
    fun approvalTaskOf(taskId: String): ChatApprovalTaskRow? = transaction(database) {
        execQuery("SELECT title, state_reason FROM selfdev_tasks WHERE task_id = ?", taskId) { rs ->
            ChatApprovalTaskRow(rs.getString(1), rs.getString(2))
        }.firstOrNull()
    }

    private fun Transaction.openRows(projectId: String): List<ProjectChatActionRow> =
        execQuery("$SELECT_COLUMNS WHERE project_id = ? AND state = ?", projectId, ChatActionState.PROPOSED.name) { it.toRow() }

    /** True when a row of the same project, code, target and version exists in any state. */
    private fun Transaction.hasAnyRow(projectId: String, proposal: ChatActionProposal): Boolean =
        execQuery(
            "SELECT COUNT(*) FROM project_chat_actions WHERE project_id = ? AND action_code = ? AND target_id = ? AND expected_version = ?",
            projectId, proposal.code.name, proposal.targetId, proposal.expectedVersion,
        ) { it.getLong(1) }.single() > 0

    private fun Transaction.insert(projectId: String, proposal: ChatActionProposal, now: LocalDateTime) {
        execUpdate(
            """
            INSERT INTO project_chat_actions (action_id, project_id, action_code, target_id, target_kind, origin, expected_version, state, active_key,
                                              proposed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
            """.trimIndent(),
            Ulid.generate(clock), projectId, proposal.code.name, proposal.targetId, proposal.code.targetKind.name, ChatActionOrigin.RULE.name,
            proposal.expectedVersion, ChatActionState.PROPOSED.name, now,
        )
    }

    /**
     * True when the cycle's root Task of a cycle in progress is stuck (see [isCycleStuck]). The root's state, its child Tasks (the rows whose
     * `parent_task_id` is the root) and the states of the IDs they depend on are read. A missing row is not stuck.
     */
    private fun Transaction.cycleStuck(rootTaskId: String): Boolean {
        val rootState = execQuery("SELECT state FROM selfdev_tasks WHERE task_id = ?", rootTaskId) { it.getString(1) }.firstOrNull()
            ?: return false
        val children = execQuery(
            "SELECT task_id, state, dependencies FROM selfdev_tasks WHERE parent_task_id = ? ORDER BY task_id",
            rootTaskId,
        ) { rs -> ChatTaskFact(rs.getString(1), rs.getString(2), dependenciesOf(rs.getString(3))) }
        if (children.none { it.state !in ENDED_TASK_STATES }) return false
        val depIds = children.flatMap { it.dependencies }.toSet()
        val states = depIds.associateWith { dep ->
            execQuery("SELECT state FROM selfdev_tasks WHERE task_id = ?", dep) { it.getString(1) }.firstOrNull()
        }
        return isCycleStuck(rootEnded = rootState in ENDED_TASK_STATES, children = children) { states[it] }
    }

    /** The child Tasks of the cycle's root that wait for approval (PENDING_APPROVAL), oldest ID first. The rule decides which are approvable. */
    private fun Transaction.approvalsOf(rootTaskId: String): List<ChatApprovalFact> =
        execQuery(
            "SELECT task_id, version, state, revision FROM selfdev_tasks WHERE parent_task_id = ? AND state = ? ORDER BY task_id",
            rootTaskId, "PENDING_APPROVAL",
        ) { rs -> ChatApprovalFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4) > 0) }

    /** The cycle's root Task (the order's current goal) as the replan rule reads it, or null when the row is missing. */
    private fun Transaction.cycleTaskOf(rootTaskId: String): ChatCycleTaskFact? =
        execQuery("SELECT task_id, version, state, state_reason FROM selfdev_tasks WHERE task_id = ?", rootTaskId) { rs ->
            ChatCycleTaskFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4))
        }.firstOrNull()

    /** The current version of a Task that is not a cycle root of this read (an open TASK_REPLAN whose Task moved on), or null when gone. */
    private fun Transaction.taskVersionRow(taskId: String): Long? =
        execQuery("SELECT version FROM selfdev_tasks WHERE task_id = ?", taskId) { it.getLong(1) }.firstOrNull()

    /** The target kind of a stored code. A code this build does not know is an order (it is never proposed here). */
    private fun kindOf(code: String): ChatTargetKind =
        ChatActionCode.entries.firstOrNull { it.name == code }?.targetKind ?: ChatTargetKind.ORDER

    private fun dependenciesOf(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun java.sql.ResultSet.toRow() = ProjectChatActionRow(
        actionId = getString(1),
        projectId = getString(2),
        code = getString(3),
        targetId = getString(4),
        expectedVersion = getObject(5)?.let { (it as Number).toLong() },
        state = getString(6),
        resultCode = getString(7),
        proposedAt = getObject(8, LocalDateTime::class.java),
        resolvedAt = getObject(9, LocalDateTime::class.java),
        resolvedBy = getString(10),
        targetKind = getString(11),
        origin = getString(12),
        paramCode = getString(13),
    )

    private companion object {
        const val MAX_ROOTS = 200
        val APPROVAL_CODES = setOf(ChatActionCode.TASK_APPROVE.name, ChatActionCode.TASK_REJECT.name)
        const val SELECT_COLUMNS = "SELECT action_id, project_id, action_code, target_id, expected_version, state, result_code, " +
            "proposed_at, resolved_at, resolved_by, target_kind, origin, param_code FROM project_chat_actions"
    }
}

/** True for a unique or primary key violation (MariaDB 1062, H2 23505) anywhere in the cause chain. */
private fun Throwable.isUniqueViolation(): Boolean = generateSequence(this) { it.cause }.take(5).any {
    it is java.sql.SQLException && (it.sqlState == "23505" || it.errorCode == 1062)
}
