package net.kigawa.kivra.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The project check of the permission service (ADR 0022 PR-D): membership, the project role's permissions, the global ADMIN, and fail closed. */
class ProjectDenialTest {
    private val project = "01PROJ" + "A".repeat(20)
    private val member = "assignee-member"

    private class Members(private val map: Map<Pair<String, String>, Role>, private val fail: Boolean = false) : ProjectMemberLookup {
        override fun roleIn(projectId: String, assigneeId: String): Role? {
            if (fail) throw IllegalStateException("store down")
            return map[projectId to assigneeId]
        }
    }

    private fun service(members: ProjectMemberLookup?) =
        PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root", null, PermissionMode.ENFORCE, members)

    /** An assignee's access as the store gives it: the role's permissions, with the assignee id the membership is keyed by. */
    private fun access(role: Role, assigneeId: String? = member, source: AccessSource = AccessSource.ASSIGNEE) =
        PrincipalAccess(role, ROLE_PERMISSIONS.getValue(role), source, assigneeId)

    @Test
    fun `a member whose project role holds the permission is allowed`() {
        val s = service(Members(mapOf((project to member) to Role.OPERATOR)))
        assertNull(s.projectDenial(access(Role.OPERATOR), project, Permission.ORDER_MANAGE))
    }

    @Test
    fun `a member whose project role lacks the permission is refused with the permission code`() {
        val s = service(Members(mapOf((project to member) to Role.VIEWER)))
        assertEquals(DENY_PROJECT_PERMISSION, s.projectDenial(access(Role.OPERATOR), project, Permission.ORDER_MANAGE))
        assertNull(s.projectDenial(access(Role.OPERATOR), project, Permission.READ))
    }

    @Test
    fun `a non-member is refused, and so is a caller with no assignee`() {
        val s = service(Members(emptyMap()))
        assertEquals(DENY_NOT_MEMBER, s.projectDenial(access(Role.OPERATOR), project, Permission.READ))
        assertEquals(DENY_NOT_MEMBER, s.projectDenial(access(Role.OPERATOR, assigneeId = null), project, Permission.READ))
        // a member of another project is not a member of this one
        val other = service(Members(mapOf((("01OTHER" + "B".repeat(19)) to member) to Role.ADMIN)))
        assertEquals(DENY_NOT_MEMBER, other.projectDenial(access(Role.OPERATOR), project, Permission.READ))
    }

    @Test
    fun `the global ADMIN passes without any membership`() {
        val s = service(Members(emptyMap()))
        assertNull(s.projectDenial(access(Role.ADMIN), project, Permission.ORDER_MANAGE))
        assertNull(s.projectDenial(access(Role.ADMIN), project, Permission.ASSIGNEE_MANAGE))
        // an ADMIN that is only the operator of today's rule (no assignee) is not the global ADMIN
        assertEquals(DENY_NOT_MEMBER, s.projectDenial(access(Role.ADMIN, source = AccessSource.LEGACY_DEFAULT), project, Permission.READ))
    }

    @Test
    fun `the AI is judged by its membership too`() {
        val s = service(Members(mapOf((project to member) to Role.AI)))
        assertNull(s.projectDenial(access(Role.AI, source = AccessSource.ENV_AI), project, Permission.SPEND_START))
        assertEquals(
            DENY_PROJECT_PERMISSION,
            s.projectDenial(access(Role.AI, source = AccessSource.ENV_AI), project, Permission.ORDER_MANAGE),
        )
        assertEquals(DENY_NOT_MEMBER, service(Members(emptyMap())).projectDenial(access(Role.AI), project, Permission.SPEND_START))
    }

    @Test
    fun `a failed membership store is refused with the lookup code, and the global ADMIN is not asked`() {
        val s = service(Members(emptyMap(), fail = true))
        assertEquals(DENY_PROJECT_LOOKUP, s.projectDenial(access(Role.OPERATOR), project, Permission.READ))
        assertNull(s.projectDenial(access(Role.ADMIN), project, Permission.READ))
    }

    @Test
    fun `no membership store at all refuses every non-admin`() {
        val s = service(null)
        assertEquals(DENY_NOT_MEMBER, s.projectDenial(access(Role.OPERATOR), project, Permission.READ))
    }
}
