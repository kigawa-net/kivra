package net.kigawa.kivra.ui

import kotlinx.serialization.json.Json
import net.kigawa.kivra.ui.api.ApiOperation
import net.kigawa.kivra.ui.api.ApiProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeTest {
    private val operation = ApiOperation("op", "Operation", "running", "description", "created", "updated")
    private val project = ApiProject("p", "Project", null)

    @Test
    fun bothKindsCanHaveEitherKindAsParentWithoutChangingDtos() {
        val parents = listOf(project.asNode().ref, operation.asNode().ref)
        for (parent in parents) {
            for (child in listOf(project.asNode(parent), operation.asNode(parent))) {
                assertEquals(parent, child.parent)
                assertEquals(parent.id, child.parentId)
            }
        }
        assertNull(operation.asNode().parentId)
        val json = Json.encodeToString(ApiOperation.serializer(), operation)
        assertTrue("parentId" !in json)
        assertEquals(operation, Json.decodeFromString(ApiOperation.serializer(), json))
    }

    @Test
    fun childrenNavigateWithTheirTypedParentAndRoundTripEncodedIds() {
        for (kind in NodeKind.entries) {
            val parent = NodeRef("親 /?#%", kind)
            val route = operationRoute("子 /?#%", parent)
            assertEquals(parent, route.operationParent)
            assertEquals(route, parseRoute(route.toHash()))
            assertEquals(if (kind == NodeKind.PROJECT) parent.id else null, route.projectIdOrNull)
        }
        val children = Route.OperationChildren("親 /?#%")
        assertEquals(children, parseRoute(children.toHash()))
        assertNull(children.projectIdOrNull)
    }

    @Test
    fun parentKindsWithTheSameIdHaveDifferentRoutesAndSidebarTargets() {
        val projectParent = NodeRef("same", NodeKind.PROJECT)
        val operationParent = NodeRef("same", NodeKind.OPERATION)
        val projectChild = operationRoute("child", projectParent)
        val operationChild = operationRoute("child", operationParent)
        assertNotEquals(projectChild.toHash(), operationChild.toHash())
        assertEquals(Route.ProjectPage("same", NavDestination.Operations), sidebarSections(projectChild)[1].items.single().target)
        assertEquals(Route.OperationChildren("same"), sidebarSections(operationChild)[1].items.single().target)
        assertEquals(Route.Page(NavDestination.Operations), sidebarSections(operationChild).last().items[1].target)
    }

    @Test
    fun projectLinksAndNodeLinksOpenTheCorrectChildLists() {
        assertEquals(Route.ProjectPage("p", NavDestination.Operations), project.asNode().operationsRoute())
        assertEquals(Route.OperationChildren("op"), operation.asNode().operationsRoute())
        assertTrue(projectLinkTargets("p").any { it.target == project.asNode().operationsRoute() })
        assertTrue(projectLinkTargets("unassigned").none { it.dest == NavDestination.Operations })
    }

    @Test
    fun breadcrumbsReturnToTheParentList() {
        val child = Route.OperationDetail("child", "parent")
        assertEquals(Route.OperationDetail("parent"), child.breadcrumbs()[1].route)
        assertEquals(Route.OperationChildren("parent"), child.breadcrumbs()[2].route)
        val projectChild = Route.OperationDetail("child", "p", NodeKind.PROJECT)
        assertEquals(Route.ProjectPage("p", NavDestination.Operations), projectChild.breadcrumbs()[2].route)
    }

    @Test
    fun apiRequestsPreserveTheGlobalEndpointAndEncodeParentQuery() {
        assertEquals("/api/operations", operationsPath())
        assertEquals("/api/operations?parentId=p", operationsPath("p"))
        assertEquals("/api/operations?parentId=%E8%A6%AA%20%2F%3F%23%25%26%3D", operationsPath("親 /?#%&="))
    }

    @Test
    fun malformedChildPathsAreRejected() {
        for (hash in listOf("#/operations/p/operations/%XX", "#/operations/p/operations/c/extra", "#/projects/unassigned/operations/c", "#/operations/%20/operations/c", "#/projects/%20/operations/c")) {
            assertTrue(parseRoute(hash) is Route.Invalid, hash)
        }
    }
}
