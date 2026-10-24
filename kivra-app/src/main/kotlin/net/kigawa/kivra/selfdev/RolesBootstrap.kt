package net.kigawa.kivra.selfdev

import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import org.slf4j.LoggerFactory

/*
 * Startup seeding of the assignees (ADR 0020). Called only when the roles mode is not off; with off nothing is read or written.
 * The report and every log line hold fixed codes and counts only: never an owner key, a token or a display name.
 */

/** Fixed display names of the seeded assignees. */
internal const val ROOT_APPROVER_NAME = "root-approver"
internal const val AI_PRINCIPAL_NAME = "ai-principal"

/** Fixed codes for the warnings. */
enum class SeedWarning {
    /** The AI principal of the environment is bound to an assignee whose role is not AI. Nothing was changed. */
    ENV_AI_CONFLICT,
    /** The seeding for the root approver or the AI principal was refused (a malformed owner key). */
    SEED_REFUSED,
}

data class SeedReport(
    val assigneesCreated: Int,
    val membersBound: Int,
    val recovered: Boolean,
    val warnings: List<SeedWarning>,
)

fun seedRoles(repo: SelfDevAssigneeRepository, rootApprover: String?, aiPrincipal: String?, recoverAdmin: Boolean): SeedReport {
    var created = 0
    var bound = 0
    var recovered = false
    val warnings = mutableListOf<SeedWarning>()
    if (rootApprover != null) {
        if (recoverAdmin) {
            when (val r = repo.recoverAdmin(ROOT_APPROVER_NAME, rootApprover)) {
                is AssigneeResult.Moved -> {
                    recovered = true
                    if (r.value.created) created++
                    if (r.value.created || r.value.bound) bound++
                }
                else -> warnings += SeedWarning.SEED_REFUSED
            }
        } else if (repo.accessOf(rootApprover) == null) {
            // Only when the key is bound to nothing: a demoted or disabled assignee is never put back.
            when (repo.seedIfAbsent(ROOT_APPROVER_NAME, AssigneeRole.ADMIN, rootApprover)) {
                is AssigneeResult.Moved -> { created++; bound++ }
                else -> warnings += SeedWarning.SEED_REFUSED
            }
        }
    }
    if (aiPrincipal != null) {
        val existing = repo.accessOf(aiPrincipal)
        if (existing == null) {
            when (repo.seedIfAbsent(AI_PRINCIPAL_NAME, AssigneeRole.AI, aiPrincipal)) {
                is AssigneeResult.Moved -> { created++; bound++ }
                else -> warnings += SeedWarning.SEED_REFUSED
            }
        } else if (existing.role != AssigneeRole.AI) {
            warnings += SeedWarning.ENV_AI_CONFLICT
        }
    }
    return SeedReport(created, bound, recovered, warnings)
}

private val logger = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.RolesBootstrap")

/**
 * The wiring: with the mode off, [repoProvider] is not called and nothing is read. Otherwise seeds, then checks that an ACTIVE ADMIN with
 * a bound owner key exists. Fail-closed: a database error propagates (the startup fails), and with enforce a missing root approver or no
 * ACTIVE ADMIN fails the startup (fixed message). With shadow those are warnings.
 */
fun bootstrapRolesIfEnabled(
    settings: RolesSettings,
    rootApprover: String?,
    aiPrincipal: String?,
    repoProvider: () -> SelfDevAssigneeRepository,
): SeedReport? {
    if (settings.mode == PermissionMode.OFF) return null
    val enforce = settings.mode == PermissionMode.ENFORCE
    if (rootApprover == null) {
        check(!enforce) { "roles bootstrap: ${OrderSettings.ENV_ROOT_APPROVER} is required when ${RolesSettings.ENV_MODE}=enforce" }
        logger.warn("roles bootstrap: ROOT_APPROVER_MISSING")
    }
    val repo = repoProvider()
    if (settings.recoverAdmin) logger.warn("roles bootstrap: RECOVER_ADMIN_ACTIVE remove ${RolesSettings.ENV_RECOVER_ADMIN} after use")
    val report = seedRoles(repo, rootApprover, aiPrincipal, settings.recoverAdmin)
    report.warnings.forEach { logger.warn("roles bootstrap: warning={}", it.name) }
    logger.info("roles bootstrap: assigneesCreated={} membersBound={} recovered={}", report.assigneesCreated, report.membersBound, report.recovered)
    val hasAdmin = repo.list().any {
        it.role == AssigneeRole.ADMIN && it.state == AssigneeState.ACTIVE && repo.memberRefs(it.assigneeId).isNotEmpty()
    }
    if (!hasAdmin) {
        check(!enforce) { "roles bootstrap: no active ADMIN with a bound owner key; see runbook-selfdev section 10.5" }
        logger.warn("roles bootstrap: NO_ACTIVE_ADMIN")
    }
    return report
}
