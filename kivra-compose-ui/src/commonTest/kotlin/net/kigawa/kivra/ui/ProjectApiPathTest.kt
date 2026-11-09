package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 一覧と作成の path(ADR 0022 PR-E)。選択なし(null)は旧ルート、選択ありは別名ルート。 */
class ProjectApiPathTest {

    @Test
    fun withoutAProjectTheOldRoutesAreUsed() {
        assertEquals("/api/selfdev/goals", ProjectApi.goals(null))
        assertEquals("/api/selfdev/orders", ProjectApi.orders(null))
        assertEquals("/api/selfdev/stop-requests?state=PENDING", ProjectApi.approvals(null))
        assertEquals("/api/selfdev/assignees", ProjectApi.assignees(null))
        assertEquals("/api/selfdev/memory", ProjectApi.memory(null))
    }

    @Test
    fun withAProjectTheAliasesAreUsed() {
        assertEquals("/api/projects/p1/tasks", ProjectApi.goals("p1"))
        assertEquals("/api/projects/p1/orders", ProjectApi.orders("p1"))
        assertEquals("/api/projects/p1/approvals?state=PENDING", ProjectApi.approvals("p1"))
        assertEquals("/api/projects/p1/assignees", ProjectApi.assignees("p1"))
        assertEquals("/api/projects/p1/memory", ProjectApi.memory("p1"))
    }

    @Test
    fun creationsGoToTheProjectInTheirPath() {
        assertEquals("/api/projects/p1/tasks", ProjectApi.goalSubmit("p1"))
        assertEquals("/api/projects/p1/orders", ProjectApi.orderCreate("p1"))
    }

    @Test
    fun unassignedListsUseTheOldRoutesWithTheProjectIdQuery() {
        assertEquals("/api/selfdev/goals?projectId=unassigned", ProjectApi.goals("unassigned"))
        assertEquals("/api/selfdev/orders?projectId=unassigned", ProjectApi.orders("unassigned"))
        assertEquals("/api/selfdev/stop-requests?state=PENDING&projectId=unassigned", ProjectApi.approvals("unassigned"))
        assertEquals("/api/selfdev/memory?projectId=unassigned", ProjectApi.memory("unassigned"))
    }

    @Test
    fun withQueryJoinsWithAmpersandWhenThePathAlreadyHasAQuery() {
        assertEquals("/api/selfdev/memory?projectId=unassigned&limit=20", ProjectApi.withQuery(ProjectApi.memory("unassigned"), "limit=20"))
        assertEquals("/api/projects/p1/memory?limit=20", ProjectApi.withQuery(ProjectApi.memory("p1"), "limit=20"))
        assertEquals("/api/selfdev/memory?limit=20", ProjectApi.withQuery(ProjectApi.memory(null), "limit=20"))
    }

    @Test
    fun creationsNeverGoToTheUnassignedPseudoProject() {
        assertFailsWith<IllegalArgumentException> { ProjectApi.goalSubmit("unassigned") }
        assertFailsWith<IllegalArgumentException> { ProjectApi.orderCreate("unassigned") }
    }

    @Test
    fun theProjectIdIsEncodedInThePath() {
        assertEquals("/api/projects/a%20b%2Fc/tasks", ProjectApi.goals("a b/c"))
    }
}
