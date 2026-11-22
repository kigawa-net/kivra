package net.kigawa.kivra.ui

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.kigawa.kivra.ui.api.ApiOperation
import net.kigawa.kivra.ui.api.ApiProject
import net.kigawa.kivra.ui.api.operationsPath

class NodeNavigationTest {
    private val operation = ApiOperation("op/子", "子", "running", "", "", "")

    @Test
    fun eitherNodeKindCanHaveEitherKindOfParentWithoutChangingDtos() {
        val project = ApiProject("project", "Project", null)
        for (parent in listOf(project.asNode(), operation.asNode())) {
            assertEquals(parent.id, project.asNode(parent.id).parentId)
            assertEquals(parent.id, operation.asNode(parent.id).parentId)
        }
        val child = operation.asNode(project.asNode().id)
        val grandchild = project.asNode(child.id)
        assertEquals(child.id, grandchild.parentId)
        assertNotEquals(NodeId("same", NodeKind.PROJECT), NodeId("same", NodeKind.OPERATION))
        assertNull(operation.asNode().parentId)
        assertEquals(operation, Json.decodeFromString<ApiOperation>(Json.encodeToString(operation)))
    }

    @Test
    fun scopedListsAndDetailsRoundTripWithEncodedIds() {
        val routes = listOf(
            operationsRouteOf(null),
            operationsRouteOf(NodeId("親 /?#日本語", NodeKind.PROJECT)),
            operationsRouteOf(NodeId("親 /?#日本語", NodeKind.OPERATION)),
            operation.asNode().openRoute(),
            operation.asNode(NodeId("親 /?#日本語", NodeKind.PROJECT)).openRoute(),
            operation.asNode(NodeId("親 /?#日本語", NodeKind.OPERATION)).openRoute(),
        )
        for (route in routes) assertEquals(route, parseRoute(route.toHash()), route.toHash())
        assertEquals("#/operations/parent/operations/child", Route.OperationDetail("child", "parent").toHash())
        assertEquals("#/projects/p/operations", operationsRouteOf(NodeId("p", NodeKind.PROJECT)).toHash())
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
        assertTrue(sidebarSections().flatMap { it.items }.any { it.target == Route.Page(NavDestination.Operations) })
        val section = operationSidebarSection(Route.OperationDetail("child", "parent", NodeKind.PROJECT))!!
        assertEquals(Route.ProjectPage("parent", NavDestination.Dashboard), section.items.first().target)
        assertEquals(Route.OperationChildren("child"), section.items.last().target)
        assertNull(operationSidebarSection(Route.Page(NavDestination.Operations)))
        assertEquals(Route.OperationChildren("parent"), operationSidebarSection(Route.OperationChildren("parent"))!!.items.single().target)
    }

    @Test
    fun switchingNodesPreservesProjectPageKindAndOperationParent() {
        val current = Route.ProjectPage("old", NavDestination.Tasks)
        val project = ApiProject("new", "Project", null).asNode()
        assertEquals(Route.ProjectPage("new", NavDestination.Tasks), current.inNodeContext(project))
        val child = operation.asNode(project.id)
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
        // 既存の全体の画面も、その詳細で選択が外れない。
        assertTrue(NavItem(NavDestination.Projects, Route.Page(NavDestination.Projects))
            .isSelectedBy(Route.ProjectChooser(NavDestination.Goals)))
    }

    @Test
    fun apiFilterUsesAnEncodedParentIdAndKeepsTheRootEndpoint() {
        assertEquals("/api/operations", operationsPath())
        assertEquals("/api/operations?parentId=p%2F%3F%26%3D%20%E8%A6%AA", operationsPath("p/?&= 親"))
    }
}
