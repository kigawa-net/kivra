package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiOperation
import net.kigawa.kivra.ui.api.ApiProject

/** 種類も含めて識別する。同じ ID の Project と Operation を混同しない。 */
enum class NodeKind { PROJECT, OPERATION }

data class NodeId(val id: String, val kind: NodeKind) {
    init { require(id.isNotBlank()) }
}

/** API の DTO とは独立した UI のノード。親子はどちらの種類でも、何階層でも表せる。 */
data class Node(val id: NodeId, val name: String, val parentId: NodeId? = null)

fun ApiOperation.asNode(parentId: NodeId? = null): Node = Node(NodeId(id, NodeKind.OPERATION), name, parentId)
fun ApiProject.asNode(parentId: NodeId? = null): Node = Node(NodeId(projectId, NodeKind.PROJECT), name, parentId)

fun Node.openRoute(): Route = when (id.kind) {
    NodeKind.PROJECT -> Route.ProjectPage(id.id, NavDestination.Dashboard)
    NodeKind.OPERATION -> Route.OperationDetail(id.id, parentId?.id, parentId?.kind ?: NodeKind.OPERATION)
}

fun operationsRouteOf(parent: NodeId?): Route = when (parent?.kind) {
    null -> Route.Page(NavDestination.Operations)
    NodeKind.PROJECT -> Route.ProjectPage(parent.id, NavDestination.Operations)
    NodeKind.OPERATION -> Route.OperationChildren(parent.id)
}

/** ノードの切替。Project の既存の画面種別は引き継ぐ。 */
fun Route.inNodeContext(node: Node): Route = when (node.id.kind) {
    NodeKind.PROJECT -> inProjectContext(node.id.id)
    NodeKind.OPERATION -> node.openRoute()
}
