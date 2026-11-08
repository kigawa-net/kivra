package net.kigawa.kivra.ui

/*
 * 担当(Assignee)の管理の純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js の ASG_*、asgFailText、
 * asgAsk*)とサーバー(kivra-app/src/main/kotlin/net/kigawa/kivra/routes/SelfDevAssigneeRoutes.kt)の規則に合わせる。
 *
 * 画面に出すのは参照(tokenRef / memberRef)の先頭 8 文字だけ。Keycloak の sub、トークンの平文、応答の本文は
 * どの関数もこの層では扱わず、画面にも出さない。
 */

/** 担当の表示名の上限(コードポイント)。app.js の ASG_NAME_MAX。 */
const val ASSIGNEE_NAME_MAX = 64

/** トークンの既定の有効日数。app.js の ASG_TTL_DEFAULT とサーバーの DEFAULT_TTL_DAYS。 */
const val ASSIGNEE_TTL_DEFAULT_DAYS = 90

/** トークンの有効日数の上限。app.js の 1〜365 とサーバーの TTL_OUT_OF_RANGE。 */
const val ASSIGNEE_TTL_MAX_DAYS = 365

/** ロールの一覧(app.js の ASG_ROLE_LIST の順)。 */
val ASSIGNEE_ROLES: List<String> = listOf("ADMIN", "OPERATOR", "APPROVER", "AI", "VIEWER", "AI_APPROVER")

/** トークンを持てるロール(app.js の ASG_TOKEN_ROLES。サーバーの ROLE_NOT_ALLOWED と同じ)。 */
private val ASSIGNEE_TOKEN_ROLES = setOf("AI", "VIEWER")

/** ロールの表示名(app.js の ASG_ROLES と同じ)。 */
val ASSIGNEE_ROLE_LABELS: Map<String, String> = linkedMapOf(
    "ADMIN" to "ADMIN（管理者。担当の管理と停止の承認もできる）",
    "OPERATOR" to "OPERATOR（運用者）",
    "APPROVER" to "APPROVER（停止の承認だけを任せる人）",
    "AI" to "AI（AI の担当。トークンで MCP から入る）",
    "VIEWER" to "VIEWER（読み取りだけ。トークンを持てる）",
    "AI_APPROVER" to "AI_APPROVER（サーバーの AI が停止要求を承認するための担当。トークンも、人の紐付けも持てない）",
)

private val ASSIGNEE_STATE_LABELS = mapOf("ACTIVE" to "有効", "DISABLED" to "無効")
private val ASSIGNEE_TOKEN_STATE_LABELS = mapOf("ACTIVE" to "有効", "REVOKED" to "失効済み", "EXPIRED" to "期限切れ")

private val ASSIGNEE_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val ASSIGNEE_REF_SHAPE = Regex("[0-9a-f]{16}")
private val ASSIGNEE_SUB_SHAPE = Regex("[A-Za-z0-9._:-]{1,255}")
private val ASSIGNEE_TTL_SHAPE = Regex("[0-9]{1,3}")

/** 担当の ID の形(ULID)。 */
fun isValidAssigneeId(raw: String): Boolean = ASSIGNEE_ID_SHAPE.matches(raw)

/** メンバーの参照・トークンの参照の形(16 桁の小文字の 16 進数)。app.js の ASG_REF と同じ。 */
fun isValidAssigneeRef(raw: String): Boolean = ASSIGNEE_REF_SHAPE.matches(raw)

/** 参照を画面に出すときの短い形(先頭 8 文字)。 */
fun assigneeRefShort(ref: String): String = ref.take(8)

/** 表示名を検証し、前後を除いた値を返す。1〜64 文字(コードポイント)で制御文字なし。違えば null(app.js の nameBad)。 */
fun validAssigneeDisplayName(raw: String): String? {
    val name = raw.trim()
    val n = codePointCount(name)
    return name.takeIf { n in 1..ASSIGNEE_NAME_MAX && !hasControlChars(it) }
}

/** トークンの有効日数を検証する。1〜365 の整数(前後の空白は除く)。違えば null(app.js の ttlBad)。 */
fun validAssigneeTtlDays(raw: String): Int? {
    val text = raw.trim()
    if (!ASSIGNEE_TTL_SHAPE.matches(text)) return null
    val days = text.toInt()
    return days.takeIf { it in 1..ASSIGNEE_TTL_MAX_DAYS }
}

/** Keycloak の sub を検証する(半角の英数字と . _ : - だけ、1〜255 文字)。違えば null(app.js の subBad)。 */
fun validAssigneeSub(raw: String): String? {
    val sub = raw.trim()
    return sub.takeIf { ASSIGNEE_SUB_SHAPE.matches(it) }
}

/** トークンを発行できるか。AI と VIEWER で、有効な担当だけ(app.js と同じ)。 */
fun canIssueAssigneeToken(role: String, state: String): Boolean = role in ASSIGNEE_TOKEN_ROLES && state == "ACTIVE"

/** 人(sub)を紐付けられるか。AI_APPROVER は人を持てない(サーバーの HUMAN_MEMBER_NOT_ALLOWED)。 */
fun canBindAssigneeMember(role: String): Boolean = role != "AI_APPROVER"

/** 担当のロールの表示名。一覧にない値は null(呼び出し側が生の値を出す)。 */
fun assigneeRoleLabel(role: String): String? = ASSIGNEE_ROLE_LABELS[role]

/** 担当の状態の表示名。 */
fun assigneeStateLabel(state: String): String = ASSIGNEE_STATE_LABELS[state] ?: "不明"

/** トークンの状態の表示名。 */
fun assigneeTokenStateLabel(status: String): String = ASSIGNEE_TOKEN_STATE_LABELS[status] ?: "不明"

/** 件数の表示。負の値や想定外は「不明」(app.js の asgCount と同じ趣旨)。 */
fun assigneeCountText(count: Int): String = if (count >= 0) count.toString() else "不明"

/**
 * 担当の操作の失敗の文言。ステータスと機械コードだけで決める(サーバーの error 本文は使わない)。
 * 順序は app.js の asgFailText と同じ: 401 はセッション切れ、通信の失敗、既知のコード、409、403、404、429、503、400。
 */
fun assigneeFailureText(status: Int?, code: String?): String {
    if (status == 401) return ASSIGNEE_MSG_SESSION
    if (status == null) return ASSIGNEE_MSG_NETWORK
    if (code != null) ASSIGNEE_CODE_TEXT[code]?.let { return it }
    return when (status) {
        409 -> ASSIGNEE_MSG_STALE
        403 -> ASSIGNEE_CODE_TEXT.getValue("FORBIDDEN")
        404 -> ASSIGNEE_CODE_TEXT.getValue("NOT_FOUND")
        429 -> ASSIGNEE_CODE_TEXT.getValue("RATE_LIMITED")
        503 -> ASSIGNEE_CODE_TEXT.getValue("ROLES_DISABLED")
        400 -> ASSIGNEE_CODE_TEXT.getValue("INVALID_BODY")
        else -> ASSIGNEE_MSG_UNKNOWN
    }
}

// 成功・入力の文言(app.js の ASG_MSG と同じ)。
const val ASSIGNEE_MSG_CREATED = "担当を作りました。"
const val ASSIGNEE_MSG_ROLE_CHANGED = "ロールを変更しました。"
const val ASSIGNEE_MSG_DISABLED = "担当を無効にしました（トークンはすべて失効しました）。"
const val ASSIGNEE_MSG_ENABLED = "担当を有効にしました（失効したトークンは戻りません）。"
const val ASSIGNEE_MSG_BOUND = "メンバーを紐付けました。"
const val ASSIGNEE_MSG_UNBOUND = "メンバーの紐付けを解除しました。"
const val ASSIGNEE_MSG_ISSUED = "トークンを発行しました（平文は専用のダイアログに 1 度だけ表示しています）。"
const val ASSIGNEE_MSG_REVOKED = "トークンを失効しました。"
const val ASSIGNEE_MSG_NAME_BAD = "表示名は 1〜64 文字で、制御文字は使えません。"
const val ASSIGNEE_MSG_ROLE_NEEDED = "ロールを選んでください。"
const val ASSIGNEE_MSG_SUB_BAD = "Keycloak の sub を、半角の英数字と . _ : - だけで、1〜255 文字で入力してください。"
const val ASSIGNEE_MSG_TTL_BAD = "有効期限は 1〜365 日の整数で入力してください。"
const val ASSIGNEE_MSG_NETWORK = "通信に失敗しました。操作されたかどうかは、再読み込みして確認してください。"
const val ASSIGNEE_MSG_STALE = "更新されています。再読み込みしてください。"
const val ASSIGNEE_MSG_SESSION = "セッションが切れました。画面を再読み込みしてください。"
const val ASSIGNEE_MSG_UNKNOWN = "結果を確認できませんでした。再読み込みしてください。"
const val ASSIGNEE_MSG_LIST_FAILED = "一覧を取得できませんでした。"
const val ASSIGNEE_MSG_NO_CHANGE = "変更がありません（すでに同じ状態です）。"
const val ASSIGNEE_TOKEN_ONCE_TEXT = "このトークンは、この 1 回しか表示されません。安全な場所に保管してください。"

/** サーバーの機械コードの文言(app.js の ASG_CODES と同じ)。ここにない code は status で決める。 */
private val ASSIGNEE_CODE_TEXT: Map<String, String> = mapOf(
    "LAST_ADMIN" to "最後の有効な管理者（ADMIN）は、無効化・ロール変更・メンバーの解除ができません。先に別の管理者を用意してください。",
    "SELF_CHANGE" to "自分自身の担当は、ここでは変更できません。別の管理者に依頼してください。",
    "STALE_VERSION" to ASSIGNEE_MSG_STALE,
    "NO_CHANGE" to ASSIGNEE_MSG_NO_CHANGE,
    "OWNER_KEY_ALREADY_BOUND" to "この人は、すでに担当に紐付いています。",
    "NOT_BOUND" to "紐付いていません。再読み込みしてください。",
    "INVALID_DISPLAY_NAME" to ASSIGNEE_MSG_NAME_BAD,
    "INVALID_OWNER_KEY" to ASSIGNEE_MSG_SUB_BAD,
    "INVALID_SUB" to ASSIGNEE_MSG_SUB_BAD,
    "SYNTHETIC_KEY_RESERVED" to "この識別子は予約されているため、紐付けできません。",
    "INVALID_ROLE" to "ロールが正しくありません。",
    "INVALID_VERSION" to "入力を確認できませんでした。再読み込みしてください。",
    "INVALID_BODY" to "入力を確認できませんでした。再読み込みしてください。",
    "NOT_FOUND" to "見つかりません。再読み込みしてください。",
    "ASSIGNEE_NOT_FOUND" to "見つかりません。再読み込みしてください。",
    "ROLE_NOT_ALLOWED" to "この担当のロールには、トークンを発行できません（AI と VIEWER だけです）。",
    "TOO_MANY_ACTIVE" to "有効なトークンが上限（2 つ）に達しています。不要なトークンを失効してから、発行してください。",
    "TTL_OUT_OF_RANGE" to ASSIGNEE_MSG_TTL_BAD,
    "INVALID_ACTOR" to "操作した人を確認できませんでした。",
    "ALREADY_REVOKED" to "すでに失効しています。",
    "OWNER_KEY_CONFLICT" to "この担当の識別子が衝突しています。運用者に連絡してください。",
    "HUMAN_MEMBER_NOT_ALLOWED" to "AI_APPROVER の担当には、人を紐付けられません。人が紐付いている担当を AI_APPROVER にすることもできません。",
    "ROLES_DISABLED" to "担当の管理は、今は無効です。",
    "BINDING_UNAVAILABLE" to "メンバーの紐付けは、このサーバーでは使えません（OIDC の設定が必要です）。",
    "FORBIDDEN" to "この操作は、あなたの権限では行えません。",
    "RATE_LIMITED" to "操作が多すぎます。1 分ほど待ってから、もう一度お試しください。",
)
