package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiOperation
import net.kigawa.kivra.ui.api.ApiProject

/** Project と Operation の ID 空間を区別する。 */
enum class NodeKind(val label: String) { PROJECT("Project"), OPERATION("Operation") }

data class NodeRef(val id: String, val kind: NodeKind) {
    init { require(id.isNotBlank()) }
}

/** API の DTO を変更せず、どちらの種類も親にも子にもできる UI のノード。 */
data class Node(val ref: NodeRef, val name: String, val parent: NodeRef? = null) {
    val id: String get() = ref.id
    val parentId: String? get() = parent?.id

    fun operationsRoute(): Route = when (ref.kind) {
        NodeKind.PROJECT -> Route.ProjectPage(id, NavDestination.Operations)
        NodeKind.OPERATION -> Route.OperationChildren(id)
    }
}

fun ApiOperation.asNode(parent: NodeRef? = null): Node = Node(NodeRef(id, NodeKind.OPERATION), name, parent)
fun ApiProject.asNode(parent: NodeRef? = null): Node = Node(NodeRef(projectId, NodeKind.PROJECT), name, parent)

fun operationRoute(operationId: String, parent: NodeRef?): Route.OperationDetail =
    Route.OperationDetail(operationId, parent?.id, parent?.kind ?: NodeKind.OPERATION)

/** parentId なしは既存のルート Operation 一覧。絞り込みはサーバーが行う。 */
fun operationsPath(parentId: String? = null): String =
    if (parentId == null) "/api/operations" else "/api/operations?parentId=${percentEncode(parentId)}"

/** サイドナビの選択は親の種類と ID も含めて判定する。 */
val Route.operationParent: NodeRef?
    get() = when (this) {
        is Route.OperationDetail -> parentId?.let { NodeRef(it, parentKind) }
        is Route.OperationChildren -> NodeRef(operationId, NodeKind.OPERATION)
        is Route.ProjectPage -> if (dest == NavDestination.Operations) NodeRef(projectId, NodeKind.PROJECT) else null
        else -> null
    }
