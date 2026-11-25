package net.kigawa.kivra.ui

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.kigawa.kivra.ui.api.ApiOperation
import net.kigawa.kivra.ui.api.ApiProject

class NodeNavigationTest {
    private val operation = ApiOperation("op/子", "子", "running", "", "", "")

    @Test
    fun eitherNodeKindCanHaveEitherKindOfParentWithoutChangingDtos() {
        val project = ApiProject("project", "Project", null)
        for (parent in listOf(project.asNode(), operation.asNode())) {
            assertEquals(parent.id, project.asNode(parent.ref).parentId)
            assertEquals(parent.id, operation.asNode(parent.ref).parentId)
        }
        val child = operation.asNode(project.asNode().ref)
        val grandchild = project.asNode(child.ref)
        assertEquals(child.id, grandchild.parentId)
        assertNotEquals(NodeRef("same", NodeKind.PROJECT), NodeRef("same", NodeKind.OPERATION))
        assertNull(operation.asNode().parentId)
        assertEquals(operation, Json.decodeFromString<ApiOperation>(Json.encodeToString(operation)))
    }

    @Test
    fun scopedListsAndDetailsRoundTripWithEncodedIds() {
        val routes = listOf(
            Route.Page(NavDestination.Operations),
            Route.ProjectPage("親 /?#日本語", NavDestination.Operations),
            Route.OperationChildren("親 /?#日本語"),
            operationRoute(operation.id, null),
            operationRoute(operation.id, NodeRef("親 /?#日本語", NodeKind.PROJECT)),
            operationRoute(operation.id, NodeRef("親 /?#日本語", NodeKind.OPERATION)),
        )
        for (route in routes) assertEquals(route, parseRoute(route.toHash()), route.toHash())
        assertEquals("#/operations/parent/operations/child", Route.OperationDetail("child", "parent").toHash())
        assertEquals("#/projects/p/operations", ApiProject("p", "Project", null).asNode().operationsRoute().toHash())
    }

    @Test
    fun projectScopeIsOnlyUsedForAProjectParent() {
        val projectChild = Route.OperationDetail("child", "parent", NodeKind.PROJECT)
        assertEquals("parent", projectChild.projectIdOrNull)
        assertNull(Route.OperationDetail("child", "parent").projectIdOrNull)
        assertNull(Route.OperationChildren("parent").projectIdOrNull)
        val crumbs = projectChild.breadcrumbs { "Project name" }
        assertEquals(listOf("プロジェクト", "Project name", "Operation", "child"), crumbs.map { it.label })
        assertEquals(Route.ProjectPage("parent", NavDestination.Operations), crumbs[2].route)
        val operationCrumbs = Route.OperationDetail("child", "parent").breadcrumbs()
        assertEquals(Route.OperationDetail("parent"), operationCrumbs[1].route)
        assertEquals(Route.OperationChildren("parent"), operationCrumbs[2].route)
    }

    @Test
    fun scopedNavigationPreservesGlobalEntryAndExposesChildren() {
        assertTrue(projectLinkTargets("p").any { it.target == Route.ProjectPage("p", NavDestination.Operations) })
        val root = Route.Page(NavDestination.Operations)
        assertEquals(root, parseRoute("#/operations"))
        val project = ApiProject("parent", "Project", null).asNode()
        val child = operation.asNode(project.ref)
        val crumbs = operationRoute(child.id, project.ref).breadcrumbs()
        assertEquals(Route.ProjectPage("parent", NavDestination.Dashboard), crumbs[1].route)
        assertEquals(Route.OperationChildren(child.id), child.operationsRoute())
        assertEquals(Route.OperationChildren("parent"), operation.copy(id = "parent").asNode().operationsRoute())
        assertEquals(listOf(NavDestination.Home, NavDestination.Projects), sidebarSections(root).single().items.map { it.dest })
    }

    @Test
    fun sidebarSelectionDistinguishesRootAndEachParentScope() {
        val root = NavItem(NavDestination.Operations, Route.Page(NavDestination.Operations))
        assertTrue(root.isSelectedBy(Route.Page(NavDestination.Operations)))
        assertTrue(root.isSelectedBy(Route.OperationDetail("root")))
        assertFalse(root.isSelectedBy(Route.OperationChildren("parent")))
        assertFalse(root.isSelectedBy(Route.OperationDetail("child", "parent")))
        assertFalse(root.isSelectedBy(Route.OperationDetail("child", "parent", NodeKind.PROJECT)))
        val children = NavItem(NavDestination.Operations, Route.OperationChildren("parent"))
        assertTrue(children.isSelectedBy(Route.OperationChildren("parent")))
        assertTrue(children.isSelectedBy(Route.OperationDetail("child", "parent")))
        assertFalse(children.isSelectedBy(Route.OperationDetail("child", "other")))
        assertFalse(children.isSelectedBy(Route.OperationDetail("child", "parent", NodeKind.PROJECT)))
        assertTrue(sidebarSections().single().items.single { it.dest == NavDestination.Projects }
            .isSelectedBy(Route.ProjectChooser(NavDestination.Goals)))
    }

    @Test
    fun switchingNodesPreservesProjectPageKindAndOperationParent() {
        val current = Route.ProjectPage("old", NavDestination.Tasks)
        val project = ApiProject("new", "Project", null).asNode()
        assertEquals(Route.ProjectPage("new", NavDestination.Tasks), current.inNodeContext(project))
        val child = operation.asNode(project.ref)
        assertEquals(Route.OperationDetail(operation.id, "new", NodeKind.PROJECT), current.inNodeContext(child))
    }

    @Test
    fun malformedOrOverlongNestedLinksAreRejected() {
        for (hash in listOf(
            "#/operations/%ZZ/operations", "#/operations/p/operations/%ZZ",
            "#/operations/%20", "#/operations/%20/operations", "#/operations/%20/operations/c",
            "#/projects/%20/operations/c", "#/projects/p/operations/%20",
            "#/operations/p/operations/%20", "#/operations/p/operations/c/extra",
            "#/projects/unassigned/operations", "#/projects/unassigned/operations/c",
        )) assertTrue(parseRoute(hash) is Route.Invalid, hash)
    }

    @Test
    fun apiFilterUsesAnEncodedParentIdAndKeepsTheRootEndpoint() {
        assertEquals("/api/operations", operationsPath())
        assertEquals("/api/operations?parentId=p%2F%3F%26%3D%20%E8%A6%AA", operationsPath("p/?&= 親"))
    }
}
