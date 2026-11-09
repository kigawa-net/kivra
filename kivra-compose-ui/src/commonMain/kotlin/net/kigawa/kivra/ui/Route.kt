package net.kigawa.kivra.ui

/**
 * 画面のルート。一覧(項目そのもの)と詳細(id 付き)を表す。ハッシュ表現は docs/design/16-site-structure.md §2 に従う。
 *
 * - `#/`、`#/<一覧>`(`#/tasks` `#/goals` `#/orders` `#/approvals` `#/budget` `#/memory` `#/assignees`)
 * - `#/runs` は Run 一覧(タスク画面に含まれる)、`#/runs/<id>` は Run 詳細
 * - `#/goals/<id>` は開発目標の詳細、`#/orders/<id>` は order の詳細、`#/orders/<id>/cycles` は order の周の一覧
 * - `#/projects/<id>/dashboard` はプロジェクトのダッシュボード(旧 UI と同じ形)
 * - `#/projects/<id>/<page>` は選択中のプロジェクトの一覧(page は [projectPages] のいずれか)。ADR 0022 PR-E2 と同じ形。
 *   `#/projects/<id>/budget` は予算がダッシュボードにあるので、ダッシュボードのルートとして解釈する([canonicalHash] で URL も置き換える)。
 *
 * 解釈と生成は純粋関数([parseRoute] / [toHash])。ブラウザとの同期は呼び出し側(App.kt)が行う。
 */
sealed interface Route {
    /** 属する一覧項目(サイドナビの選択と、パンくずの一覧に使う)。不正なリンクは null。 */
    val nav: NavDestination?

    data class Page(val dest: NavDestination) : Route {
        override val nav: NavDestination? get() = dest
    }

    /** Run 詳細。Run の一覧はタスク画面にあるので、属する項目はタスク。 */
    data class RunDetail(val runId: String) : Route {
        override val nav: NavDestination? get() = NavDestination.Tasks
    }

    data class GoalDetail(val taskId: String) : Route {
        override val nav: NavDestination? get() = NavDestination.Goals
    }

    data class OrderDetail(val orderId: String) : Route {
        override val nav: NavDestination? get() = NavDestination.Orders
    }

    /** order の周(cycles)の一覧。 */
    data class OrderCycles(val orderId: String) : Route {
        override val nav: NavDestination? get() = NavDestination.Orders
    }

    /** プロジェクトのダッシュボード(`#/projects/<id>/dashboard`、旧 UI と同じ形)。属する項目はプロジェクト。 */
    data class ProjectDashboard(val projectId: String) : Route {
        override val nav: NavDestination? get() = NavDestination.Projects
    }

    /**
     * 選択中のプロジェクトの一覧(`#/projects/<id>/<page>`)。[dest] は [projectPages] のいずれか。
     * 表示は [Page] と同じ画面に、そのプロジェクトの id を渡す。
     */
    data class ProjectPage(val projectId: String, val dest: NavDestination) : Route {
        init {
            require(dest in projectPages) { "$dest is not a page under a project" }
        }

        override val nav: NavDestination? get() = dest
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
    if (path.isEmpty()) return Route.Page(NavDestination.Home)
    val segments = path.split('/')
    if (segments.size == 1) {
        val head = segments[0]
        if (head == "runs") return Route.Page(NavDestination.Tasks)
        return Route.Page(NavDestination.all.find { it.route == head } ?: NavDestination.Home)
    }
    val id = percentDecode(segments[1])
    if (id.isNullOrEmpty()) return Route.Invalid(hash)
    return when {
        segments.size == 2 && segments[0] == "runs" -> Route.RunDetail(id)
        segments.size == 2 && segments[0] == "goals" -> Route.GoalDetail(id)
        segments.size == 2 && segments[0] == "orders" -> Route.OrderDetail(id)
        segments.size == 3 && segments[0] == "orders" && segments[2] == "cycles" -> Route.OrderCycles(id)
        segments.size == 3 && segments[0] == "projects" -> projectRouteOf(id, segments[2]) ?: Route.Invalid(hash)
        else -> Route.Invalid(hash)
    }
}

/** プロジェクト配下の項目(`#/projects/<id>/<page>`)として表すもの。予算はダッシュボードにあるので含めない。 */
val projectPages: Set<NavDestination> = setOf(
    NavDestination.Tasks,
    NavDestination.Goals,
    NavDestination.Orders,
    NavDestination.Approvals,
    NavDestination.Memory,
    NavDestination.Assignees,
)

/** `#/projects/<id>/<page>` の page を解釈する。知らない page は null(呼び出し側で [Route.Invalid])。 */
private fun projectRouteOf(projectId: String, page: String): Route? = when (page) {
    "dashboard", "budget" -> Route.ProjectDashboard(projectId)
    else -> NavDestination.all.find { it.route == page }
        ?.takeIf { it in projectPages }
        ?.let { Route.ProjectPage(projectId, it) }
}

/** ルートをハッシュ(`#/...`)へ変換する。[parseRoute] と往復で同じルートになる(Home は `#/`)。 */
fun Route.toHash(): String = when (this) {
    is Route.Page -> if (dest == NavDestination.Home) "#/" else "#/${dest.route}"
    is Route.RunDetail -> "#/runs/${percentEncode(runId)}"
    is Route.GoalDetail -> "#/goals/${percentEncode(taskId)}"
    is Route.OrderDetail -> "#/orders/${percentEncode(orderId)}"
    is Route.OrderCycles -> "#/orders/${percentEncode(orderId)}/cycles"
    is Route.ProjectDashboard -> "#/projects/${percentEncode(projectId)}/dashboard"
    is Route.ProjectPage -> "#/projects/${percentEncode(projectId)}/${dest.route}"
    is Route.Invalid -> "#/"
}

/**
 * ブラウザに出すハッシュの正。旧い形の `#/projects/<id>/budget` はダッシュボードのハッシュへ置き換える(旧 UI と同じ)。
 * それ以外は同じ文字列を返す。id が壊れている場合も同じ文字列(解釈は [Route.Invalid])。
 */
fun canonicalHash(hash: String): String {
    val segments = hash.removePrefix("#").substringBefore('?').trim('/').split('/')
    if (segments.size == 3 && segments[0] == "projects" && segments[2] == "budget") {
        val route = parseRoute(hash)
        if (route is Route.ProjectDashboard) return route.toHash()
    }
    return hash
}

/** 選択中のプロジェクトでこの項目を開くときのルート。項目が [projectPages] なら項目のページ、予算はダッシュボード、それ以外は全体のまま。 */
fun NavDestination.routeFor(projectId: String?): Route = when {
    projectId == null -> Route.Page(this)
    this == NavDestination.Budget -> Route.ProjectDashboard(projectId)
    this in projectPages -> Route.ProjectPage(projectId, this)
    else -> Route.Page(this)
}

/**
 * プロジェクトの選択を [projectId] に変えたときに開くルート(旧 UI の chooseProjectContext と同じく、今の画面の続きを開く)。
 * 全体(null)にすると、プロジェクト配下の一覧は全体の一覧へ、ダッシュボードはホームへ戻る。
 * 詳細は属する一覧へ移す(旧 UI が現在の一覧を開くのと同じ)。
 */
fun Route.inProjectContext(projectId: String?): Route = when (this) {
    is Route.Page -> if (projectId == null) this else dest.routeFor(projectId)
    is Route.ProjectPage -> dest.routeFor(projectId)
    is Route.ProjectDashboard -> if (projectId == null) Route.Page(NavDestination.Home) else Route.ProjectDashboard(projectId)
    else -> nav?.routeFor(projectId) ?: Route.Page(NavDestination.Home)
}

/** この画面が指しているプロジェクトの id(プロジェクト配下のルートだけ)。全体の画面や詳細は null。 */
val Route.projectIdOrNull: String?
    get() = when (this) {
        is Route.ProjectPage -> projectId
        is Route.ProjectDashboard -> projectId
        else -> null
    }

/**
 * パンくず: 「グループ › 一覧 › 詳細」。グループ見出しは、そのグループの先頭項目へ遷移する。
 * 例: order の cycles は 作業 › order › <id> › cycles。Home と不正なリンクは空。
 */
fun Route.breadcrumbs(): List<Crumb> {
    val dest = nav ?: return emptyList()
    val group = dest.group?.let { g ->
        NavDestination.firstIn(g)?.let { Crumb(g.label, Route.Page(it)) }
    }
    val head = listOfNotNull(group)
    return when (this) {
        is Route.Page -> head + Crumb(dest.label, null)
        is Route.ProjectPage -> head + Crumb(dest.label, null)
        is Route.RunDetail -> head + Crumb(NavDestination.Tasks.label, Route.Page(NavDestination.Tasks)) + Crumb(runId, null)
        is Route.GoalDetail -> head + Crumb(NavDestination.Goals.label, Route.Page(NavDestination.Goals)) + Crumb(taskId, null)
        is Route.OrderDetail -> head + Crumb(NavDestination.Orders.label, Route.Page(NavDestination.Orders)) + Crumb(orderId, null)
        is Route.OrderCycles -> head +
            Crumb(NavDestination.Orders.label, Route.Page(NavDestination.Orders)) +
            Crumb(orderId, Route.OrderDetail(orderId)) +
            Crumb("cycles", null)
        is Route.ProjectDashboard -> head + Crumb(NavDestination.Projects.label, Route.Page(NavDestination.Projects)) +
            Crumb(projectId, null) + Crumb("ダッシュボード", null)
        is Route.Invalid -> emptyList()
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
