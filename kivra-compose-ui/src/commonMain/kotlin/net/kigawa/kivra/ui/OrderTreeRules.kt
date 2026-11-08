package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiOrder

/*
 * order 一覧の木構造。旧 UI(kivra-app/src/main/resources/ui/app.js の ordTree)と同じ規則:
 * 一覧の順のまま、子は親の直後に一段深く並ぶ(深さ優先)。親が一覧に無い order(孤児)は最上位。
 * 自分自身を親にするものは最上位として扱い、どの根からも届かないもの(データの循環)は最上位として末尾に出す。
 */

/** 木の一行: order と、その深さ(最上位が 0)。 */
data class OrderTreeRow(val order: ApiOrder, val level: Int)

/** 字下げの段の上限(旧 UI の ORD_MAX_LEVEL。これより深い行は上限の段で字下げする)。 */
const val ORDER_TREE_MAX_INDENT = 4

fun orderTreeOf(list: List<ApiOrder>): List<OrderTreeRow> {
    val ids = list.mapTo(HashSet()) { it.orderId }
    val children = HashMap<String, MutableList<ApiOrder>>()
    val roots = mutableListOf<ApiOrder>()
    for (order in list) {
        val parent = order.parentOrderId?.takeIf { it in ids && it != order.orderId }
        if (parent == null) roots.add(order) else children.getOrPut(parent) { mutableListOf() }.add(order)
    }
    val out = mutableListOf<OrderTreeRow>()
    val seen = HashSet<String>()
    fun walk(order: ApiOrder, level: Int) {
        if (!seen.add(order.orderId)) return
        out.add(OrderTreeRow(order, level))
        children[order.orderId].orEmpty().forEach { walk(it, level + 1) }
    }
    roots.forEach { walk(it, 0) }
    // どの根からも届かなかった行(データの循環のみ)も、最上位として出す。
    list.forEach { walk(it, 0) }
    return out
}
