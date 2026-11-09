package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.NavDestination.Dashboard
import net.kigawa.kivra.ui.NavDestination.Goals
import net.kigawa.kivra.ui.NavDestination.Home
import net.kigawa.kivra.ui.NavDestination.Orders
import net.kigawa.kivra.ui.NavDestination.Projects
import net.kigawa.kivra.ui.NavDestination.Tasks

/**
 * 画面のルート。ハッシュ表現は docs/design/16-site-structure.md §2 に従う。
 *
 * - 全体: `#/`(ホーム)、`#/projects`(プロジェクト一覧)、`#/budget`、`#/autopilot`、`#/assignees`(全体の担当)。
 * - プロジェクト配下: `#/projects/<id>`(ダッシュボードへ)、`#/projects/<id>/<page>`(page は [NavDestination.projectPages] の route)。
 * - 詳細: `#/projects/<id>/goals/<taskId>`、`#/projects/<id>/orders/<orderId>`、`#/projects/<id>/orders/<orderId>/cycles`、
 *   `#/projects/<id>/runs/<runId>`。旧い形(プロジェクトを持たない `#/goals/<id>` など)も同じ画面を開く。
 * - 旧い一覧(`#/tasks` `#/goals` `#/orders` `#/approvals` `#/memory`、`#/runs`)は [Route.Legacy]。プロジェクトが決まったら
 *   [resolveLegacy] で [Route.ProjectPage] に置き換え、決まらなければ [Route.ProjectChooser] にする。
 *
 * 解釈と生成は純粋関数([parseRoute] / [toHash])。ブラウザとの同期は呼び出し側(App.kt)が行う。
 */
sealed interface Route {
    /** 属するサイドナビの項目(選択の表示と、ページタイトルに使う)。不正なリンクは null。 */
    val nav: NavDestination?

    /** 全体の画面([NavDestination.globalPages] のいずれか)。 */
    data class Page(val dest: NavDestination) : Route {
        init {
            require(dest in NavDestination.globalPages) { "$dest is not a global page" }
        }

        override val nav: NavDestination? get() = dest
    }

    /** 選択中のプロジェクトの画面([NavDestination.projectPages] のいずれか)。 */
    data class ProjectPage(val projectId: String, val dest: NavDestination) : Route {
        init {
            require(dest in NavDestination.projectPages) { "$dest is not a project page" }
        }

        override val nav: NavDestination? get() = dest
    }

    /**
     * プロジェクトを持たない旧い一覧の URL(`#/goals` など)。中間状態で、App が [resolveLegacy] で置き換える。
     * 置き換えるまでは「読み込み中」を出す。
     */
    data class Legacy(val dest: NavDestination) : Route {
        override val nav: NavDestination? get() = dest
    }

    /** 旧い一覧の行き先が 1 つに決まらなかったときの画面。プロジェクト一覧を出し、どの画面の入口かを示す。 */
    data class ProjectChooser(val dest: NavDestination) : Route {
        override val nav: NavDestination? get() = Projects
    }

    /** Run 詳細。[projectId] があれば、そのプロジェクトの配下の Run。 */
    data class RunDetail(val runId: String, val projectId: String? = null) : Route {
        override val nav: NavDestination? get() = Tasks
    }

    /** 開発目標の詳細。 */
    data class GoalDetail(val taskId: String, val projectId: String? = null) : Route {
        override val nav: NavDestination? get() = Goals
    }

    /** order の詳細。 */
    data class OrderDetail(val orderId: String, val projectId: String? = null) : Route {
        override val nav: NavDestination? get() = Orders
    }

    /** order の周(cycles)の一覧。 */
    data class OrderCycles(val orderId: String, val projectId: String? = null) : Route {
        override val nav: NavDestination? get() = Orders
    }

    /** 解釈できないハッシュ(id のパーセント符号化が壊れている等)。画面に「リンクが正しくありません」と出す。 */
    data class Invalid(val hash: String) : Route {
        override val nav: NavDestination? get() = null
    }
}

/** パンくずの 1 要素。[route] が null の要素は現在地で、クリックしない。 */
data class Crumb(val label: String, val route: Route?)

/**
 * ハッシュ(`#/runs/abc` や `runs/abc`、クエリ `?...` は無視)をルートへ変換する。空や `#` は Home。
 * 知らない一覧名は Home(旧 UI と同じく既定へ戻す)。詳細の形に合わないもの、id が壊れているものは [Route.Invalid]。
 */
fun parseRoute(hash: String): Route {
    val path = hash.removePrefix("#").substringBefore('?').trim('/')
    if (path.isEmpty()) return Route.Page(Home)
    val segments = path.split('/')
    if (segments.size == 1) {
        val head = segments[0]
        if (head == "runs") return Route.Legacy(Tasks)
        NavDestination.globalPages.find { it.route == head }?.let { return Route.Page(it) }
        NavDestination.legacyProjectPages.find { it.route == head }?.let { return Route.Legacy(it) }
        return Route.Page(Home)
    }
    val id = percentDecode(segments[1])
    if (id.isNullOrEmpty()) return Route.Invalid(hash)
    if (segments[0] == "projects") return projectRoute(id, segments.drop(2)) ?: Route.Invalid(hash)
    return globalDetailRoute(segments[0], id, segments.drop(2)) ?: Route.Invalid(hash)
}

/** `#/projects/<id>` の後ろ(rest)を解釈する。rest が空はダッシュボード。 */
private fun projectRoute(projectId: String, rest: List<String>): Route? {
    if (rest.isEmpty()) return Route.ProjectPage(projectId, Dashboard)
    if (rest.size == 1) {
        return NavDestination.projectPages.find { it.route == rest[0] }?.let { Route.ProjectPage(projectId, it) }
    }
    val detailId = percentDecode(rest[1])?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        rest.size == 2 && rest[0] == "goals" -> Route.GoalDetail(detailId, projectId)
        rest.size == 2 && rest[0] == "orders" -> Route.OrderDetail(detailId, projectId)
        rest.size == 2 && rest[0] == "runs" -> Route.RunDetail(detailId, projectId)
        rest.size == 3 && rest[0] == "orders" && rest[2] == "cycles" -> Route.OrderCycles(detailId, projectId)
        else -> null
    }
}

/** プロジェクトを持たない詳細(`#/goals/<id>` など)。旧い形の URL で、同じ画面を開く。 */
private fun globalDetailRoute(kind: String, id: String, rest: List<String>): Route? = when {
    rest.isEmpty() && kind == "runs" -> Route.RunDetail(id)
    rest.isEmpty() && kind == "goals" -> Route.GoalDetail(id)
    rest.isEmpty() && kind == "orders" -> Route.OrderDetail(id)
    rest == listOf("cycles") && kind == "orders" -> Route.OrderCycles(id)
    else -> null
}

/**
 * 旧い一覧の URL の行き先。最後に選んだプロジェクト([lastProjectId])が今のプロジェクトの中にあればそれ、
 * なければプロジェクトが 1 つだけならそれ、どちらでもなければ [Route.ProjectChooser]。
 * [knownIds] が null は一覧の取得に失敗したとき(確かめられないので、[lastProjectId] をそのまま使う)。
 */
fun resolveLegacy(dest: NavDestination, knownIds: List<String>?, lastProjectId: String?): Route {
    val chosen = when {
        knownIds == null -> lastProjectId
        lastProjectId != null && lastProjectId in knownIds -> lastProjectId
        knownIds.size == 1 -> knownIds[0]
        else -> null
    }
    return if (chosen == null) Route.ProjectChooser(dest) else Route.ProjectPage(chosen, dest)
}

/** ルートをハッシュ(`#/...`)へ変換する。[parseRoute] と往復で同じルートになる(例外: [Route.ProjectChooser] は `#/projects`)。 */
fun Route.toHash(): String = when (this) {
    is Route.Page -> if (dest == Home) "#/" else "#/${dest.route}"
    is Route.ProjectPage -> "#/projects/${percentEncode(projectId)}/${dest.route}"
    is Route.Legacy -> "#/${dest.route}"
    is Route.ProjectChooser -> "#/projects"
    is Route.RunDetail -> detailHash(projectId, "runs", runId)
    is Route.GoalDetail -> detailHash(projectId, "goals", taskId)
    is Route.OrderDetail -> detailHash(projectId, "orders", orderId)
    is Route.OrderCycles -> detailHash(projectId, "orders", orderId) + "/cycles"
    is Route.Invalid -> "#/"
}

private fun detailHash(projectId: String?, kind: String, id: String): String =
    if (projectId == null) "#/$kind/${percentEncode(id)}"
    else "#/projects/${percentEncode(projectId)}/$kind/${percentEncode(id)}"

/** この画面が指しているプロジェクトの id(プロジェクト配下の画面と、プロジェクトの中の詳細だけ)。全体の画面は null。 */
val Route.projectIdOrNull: String?
    get() = when (this) {
        is Route.ProjectPage -> projectId
        is Route.RunDetail -> projectId
        is Route.GoalDetail -> projectId
        is Route.OrderDetail -> projectId
        is Route.OrderCycles -> projectId
        else -> null
    }

/** 詳細(プロジェクトを持たない)を、今のプロジェクトの中の詳細にする。プロジェクトを持つ詳細や他の画面はそのまま。 */
fun Route.withProjectOf(current: Route): Route {
    val projectId = current.projectIdOrNull ?: return this
    return when (this) {
        is Route.RunDetail -> if (this.projectId == null) copy(projectId = projectId) else this
        is Route.GoalDetail -> if (this.projectId == null) copy(projectId = projectId) else this
        is Route.OrderDetail -> if (this.projectId == null) copy(projectId = projectId) else this
        is Route.OrderCycles -> if (this.projectId == null) copy(projectId = projectId) else this
        else -> this
    }
}

/**
 * プロジェクトの切替(ヘッダー)で開くルート。今の画面と同じ種類の画面を、そのプロジェクトで開く。
 * 項目がプロジェクト配下の一覧ならその一覧、詳細はその詳細の属する一覧、全体の画面と解釈できないリンクはダッシュボード。
 */
fun Route.inProjectContext(projectId: String): Route {
    val dest = nav?.takeIf { it in NavDestination.projectPages } ?: Dashboard
    return Route.ProjectPage(projectId, dest)
}

/**
 * パンくず: 「プロジェクト › プロジェクト名 › ページ › 詳細」。全体の画面は「全体 › 項目」か「プロジェクト」。
 * [projectName] はプロジェクトの id から表示名を引く(読めていないときは null で、id をそのまま出す)。
 * Home と不正なリンクは空。
 */
fun Route.breadcrumbs(projectName: (String) -> String? = { null }): List<Crumb> {
    val projectsCrumb = Crumb("プロジェクト", Route.Page(Projects))
    fun projectHead(projectId: String) = listOf(
        projectsCrumb,
        Crumb(projectName(projectId) ?: projectId, Route.ProjectPage(projectId, Dashboard)),
    )
    fun detail(projectId: String?, page: NavDestination, leaf: List<Crumb>): List<Crumb> {
        val head = if (projectId == null) listOf(projectsCrumb) else projectHead(projectId)
        val pageRoute = if (projectId == null) Route.Legacy(page) else Route.ProjectPage(projectId, page)
        return head + Crumb(page.label, pageRoute) + leaf
    }
    return when (this) {
        is Route.Invalid -> emptyList()
        is Route.Page -> when (dest) {
            Home -> emptyList()
            Projects -> listOf(Crumb("プロジェクト", null))
            else -> listOf(Crumb("全体", null), Crumb(dest.label, null))
        }
        is Route.ProjectChooser -> listOf(Crumb("プロジェクト", null))
        is Route.Legacy -> listOf(projectsCrumb, Crumb(dest.label, null))
        is Route.ProjectPage -> projectHead(projectId) + Crumb(dest.label, null)
        is Route.RunDetail -> detail(projectId, Tasks, listOf(Crumb(runId, null)))
        is Route.GoalDetail -> detail(projectId, Goals, listOf(Crumb(taskId, null)))
        is Route.OrderDetail -> detail(projectId, Orders, listOf(Crumb(orderId, null)))
        is Route.OrderCycles -> detail(
            projectId,
            Orders,
            listOf(Crumb(orderId, Route.OrderDetail(orderId, projectId)), Crumb("cycles", null)),
        )
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** パス断片の符号化(RFC 3986 の非予約文字以外を UTF-8 のバイトごとに %XX にする)。JavaScript の encodeURIComponent と同じ範囲。 */
fun percentEncode(value: String): String {
    val sb = StringBuilder()
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%').append(HEX_DIGITS[c shr 4]).append(HEX_DIGITS[c and 0xF])
        }
    }
    return sb.toString()
}

/** [percentEncode] の逆。`%` の後ろが 16 進 2 桁でなければ null。 */
fun percentDecode(value: String): String? {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        if (value[i] == '%') {
            if (i + 2 >= value.length) return null
            val hi = hexValue(value[i + 1]) ?: return null
            val lo = hexValue(value[i + 2]) ?: return null
            bytes.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            // A run of plain characters (ASCII or already decoded text) is taken as UTF-8 as a whole.
            val next = value.indexOf('%', i).let { if (it < 0) value.length else it }
            for (b in value.substring(i, next).encodeToByteArray()) bytes.add(b)
            i = next
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}
