package net.kigawa.kivra.ui

/**
 * 一覧と作成の API の path(ADR 0022 PR-E)。選択中のプロジェクトがあるときは別名ルート `/api/projects/{id}/...` を使い、
 * 全体(null)のときは旧ルートのまま。別名ルートの応答は旧ルートに projectId を付けたものと同じ(実体は同じ関数)。
 *
 * 旧ルートのまま残すもの(移行先がない、または全体の意味を持つ): `/api/runs`・`/api/selfdev/runs`・`POST /api/selfdev/tasks`、
 * 記憶の削除(`/api/selfdev/memory/...`)、担当・トークンの操作。
 */
object ProjectApi {
    /** 開発目標の一覧。 */
    fun goals(projectId: String?): String = under(projectId, "/api/selfdev/goals", "tasks")

    /** 開発目標の投入(`POST`)。projectId は必須なので、path は常に別名。 */
    fun goalSubmit(projectId: String): String = under(projectId, "/api/selfdev/goals", "tasks")

    /** order の一覧。 */
    fun orders(projectId: String?): String = under(projectId, "/api/selfdev/orders", "orders")

    /** order の作成(`POST`)。path の project が作成先になり、本文の projectId と一致させる。 */
    fun orderCreate(projectId: String): String = under(projectId, "/api/selfdev/orders", "orders")

    /** 承認待ちの停止要求(`state=PENDING`)。 */
    fun approvals(projectId: String?): String =
        under(projectId, "/api/selfdev/stop-requests?state=PENDING", "approvals?state=PENDING")

    /** 担当の一覧。 */
    fun assignees(projectId: String?): String = under(projectId, "/api/selfdev/assignees", "assignees")

    /** 記憶の一覧。別名は project を path で渡すので、一覧の query に projectId は入れない。 */
    fun memory(projectId: String?): String = under(projectId, "/api/selfdev/memory", "memory")

    private fun under(projectId: String?, legacy: String, suffix: String): String =
        if (projectId == null) legacy else "/api/projects/${percentEncode(projectId)}/$suffix"
}
