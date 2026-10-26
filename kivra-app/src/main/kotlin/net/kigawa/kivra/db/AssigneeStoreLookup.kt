package net.kigawa.kivra.db

import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.Role

/**
 * The permission service's view of the assignee store (ADR 0020). It maps the store's role onto the service's [Role]; a DISABLED assignee
 * is `active = false`. A database failure is not caught here: the service denies on any exception (fail closed).
 */
class AssigneeStoreLookup(private val repo: SelfDevAssigneeRepository) : AssigneeLookup {
    override fun accessOf(ownerKey: String): net.kigawa.kivra.auth.AssigneeAccess? =
        repo.accessOf(ownerKey)?.let {
            net.kigawa.kivra.auth.AssigneeAccess(
                role = when (it.role) {
                    AssigneeRole.ADMIN -> Role.ADMIN
                    AssigneeRole.OPERATOR -> Role.OPERATOR
                    AssigneeRole.APPROVER -> Role.APPROVER
                    AssigneeRole.AI -> Role.AI
                    AssigneeRole.VIEWER -> Role.VIEWER
                    AssigneeRole.AI_APPROVER -> Role.AI_APPROVER
                },
                active = it.state == AssigneeState.ACTIVE,
            )
        }
}
