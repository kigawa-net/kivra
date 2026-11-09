package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun theProjectIdIsEncodedInThePath() {
        assertEquals("/api/projects/a%20b%2Fc/tasks", ProjectApi.goals("a b/c"))
    }
}
