package net.kigawa.kivra.ui

/*
 * 投入(Task と Goal)の純粋ロジック。画面と ApiClient に依存しない。
 * 旧 UI(kivra-app/src/main/resources/ui/app.js)の規則に合わせる: MAX_TITLE / MAX_BODY / CONTROL_CHARS /
 * BODY_CONTROL_CHARS、keyForSubmission / keyForGoal(投入キー)、onSubmit / onGoalSubmit の応答別の文言(MESSAGES / AP)、
 * updateKindControls / applySelfDevAvailability(種別の出し分け)。
 * サーバー(TaskApiRoutes.kt の validatedText、SelfDevRoutes.kt の validatedBody、SelfDevGoalRoutes.kt)の検査は、
 * 旧 UI と同じか、その一部。双方向の制御文字(bidi)はサーバーが 400 にするので、画面でも送る前に止める。
 */

/** タイトルの上限(コードポイント)。app.js の MAX_TITLE。 */
const val TASK_TITLE_MAX = 200

/** 本文の上限(コードポイント)。app.js の MAX_BODY。本文は自己開発と Goal だけが持つ。 */
const val TASK_BODY_MAX = 8000

private const val KEY_PREFIX = "compose-"
private const val HEX_DIGITS = "0123456789abcdef"

/** 投入の種別。app.js の SIMULATED_KIND / SELF_DEV_KIND。 */
enum class SubmitKind(val label: String) {
    SIMULATED("シミュレーション"),
    SELF_DEV("自己開発"),
}

/* コードポイントの数は [codePointCount](ApprovalRules.kt)を使う。app.js の Array.from(s).length と同じ数え方。 */

private fun isControl(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

/** 制御文字(タブや改行を含む)。app.js の CONTROL_CHARS。 */
fun hasControlChars(text: String): Boolean = text.any { isControl(it) }

/** 本文の制御文字。改行とタブだけは許す。app.js の BODY_CONTROL_CHARS。 */
fun hasBodyControlChars(text: String): Boolean = text.any { isControl(it) && it != '\n' && it != '\t' }

/** 双方向の制御文字。サーバーの isBidiControl と同じ集合(ZWJ などは含めない)。 */
fun hasBidiControl(text: String): Boolean = text.any { c ->
    c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 || c.code == 0x200E || c.code == 0x200F || c.code == 0x061C
}

/** タイトルの検査。前後の空白を除き、1〜200 コードポイント、制御文字なし。通れば trim 済みの値、通らなければ null。 */
fun validatedTaskTitle(raw: String): String? {
    val text = raw.trim()
    val n = codePointCount(text)
    if (n == 0 || n > TASK_TITLE_MAX || hasControlChars(text) || hasBidiControl(text)) return null
    return text
}

/**
 * 本文の検査。前後の空白を除き、0〜8000 コードポイント、改行とタブ以外の制御文字なし。通れば trim 済みの値(空なら ""、送らない)、
 * 通らなければ null。
 */
fun validatedBody(raw: String): String? {
    val text = raw.trim()
    if (codePointCount(text) > TASK_BODY_MAX || hasBodyControlChars(text) || hasBidiControl(text)) return null
    return text
}

/** 投入キーの新しい値。サーバーは 1〜128 文字を求める(32 文字)。 */
fun newIdempotencyKey(): String = KEY_PREFIX + (1..24).map { HEX_DIGITS.random() }.joinToString("")

/** 投入の内容(種別・タイトル・本文・プロジェクト)。内容が変われば投入キーを作り直す。 */
data class TaskSignature(val kind: SubmitKind, val title: String, val body: String, val projectId: String?)

/**
 * 投入キーの管理(app.js の keyForSubmission / keyForGoal と newSubmitKey)。
 * 同じ内容の再送は同じキー(ネットワークの失敗後の再送が同じ投入になる)。内容が変わったらキーを作り直す。
 * 成功したら [rotate] で新しいキーにする。409 のときも作り直す。
 */
class IdempotencyKeys(private val generate: () -> String = ::newIdempotencyKey) {
    private var key: String = generate()
    private var signature: Any? = null
    private var hasSignature = false

    /** [signature] に対応するキー。直前の内容と違えば作り直す(最初の一回は作り直さない)。 */
    fun keyFor(signature: Any): String {
        if (!hasSignature || this.signature != signature) {
            if (hasSignature) key = generate()
            this.signature = signature
            hasSignature = true
        }
        return key
    }

    /** 次の投入で新しいキーを使う。 */
    fun rotate() {
        key = generate()
        signature = null
        hasSignature = false
    }
}

/** 投入の応答の結果。202 は [Created]、200(同じ投入の再送で同じ Run)は [Replayed]、それ以外は [Failed]。 */
sealed interface SubmitResult {
    /** [id] は Run の id(Task)か Goal の taskId。[state] は応答の状態(表示用、コード)。 */
    data class Created(val id: String, val state: String) : SubmitResult
    data class Replayed(val id: String, val state: String) : SubmitResult
    data class Failed(val failure: SubmitFailure) : SubmitResult
}

/** 投入の失敗の種類。サーバーの本文は表示せず、この種類ごとの固定の文言を出す。 */
enum class SubmitFailure { BAD_REQUEST, SESSION, FORBIDDEN, NOT_FOUND, CONFLICT, TOO_MANY, UNSUPPORTED, DISABLED, SERVER, NETWORK, INVALID }

/** HTTP のステータス(通信の失敗は null)から失敗の種類。app.js の kindOf と同じ区分に、403 と 503 を足す。 */
fun submitFailureOf(status: Int?): SubmitFailure = when (status) {
    null -> SubmitFailure.NETWORK
    400 -> SubmitFailure.BAD_REQUEST
    401 -> SubmitFailure.SESSION
    403 -> SubmitFailure.FORBIDDEN
    404 -> SubmitFailure.NOT_FOUND
    409 -> SubmitFailure.CONFLICT
    415 -> SubmitFailure.UNSUPPORTED
    429 -> SubmitFailure.TOO_MANY
    503 -> SubmitFailure.DISABLED
    else -> SubmitFailure.SERVER
}

/** 2xx の応答から結果。202 は作成、200 は再送(同じ Run)。id が無い応答は形の違い。 */
fun submitResultOfSuccess(status: Int, id: String?, state: String?): SubmitResult = when {
    id.isNullOrEmpty() -> SubmitResult.Failed(SubmitFailure.INVALID)
    status == 202 -> SubmitResult.Created(id, state.orEmpty())
    status == 200 -> SubmitResult.Replayed(id, state.orEmpty())
    else -> SubmitResult.Failed(SubmitFailure.INVALID)
}

/** Task の投入の失敗の文言(app.js の MESSAGES)。通信の失敗は「Run が作られたか分からない」と言う。 */
fun taskFailureText(failure: SubmitFailure, kind: SubmitKind): String = when (failure) {
    SubmitFailure.BAD_REQUEST -> "入力内容を確認してください（1〜200 文字、制御文字は使えません）。"
    SubmitFailure.SESSION -> SESSION_EXPIRED_MESSAGE
    SubmitFailure.FORBIDDEN -> "この操作は、あなたの権限では行えません。"
    SubmitFailure.NOT_FOUND -> if (kind == SubmitKind.SELF_DEV) PROJECT_NOT_FOUND_MESSAGE else "見つかりません。"
    SubmitFailure.CONFLICT -> "この投入キーは、別の内容（別のタイトル、または別の種別）で既に使われていました。投入キーを作り直しました。内容を確認して、もう一度投入してください。"
    SubmitFailure.TOO_MANY -> "自己開発の Run が上限に達しています。完了か解放を待ってから、もう一度投入してください。"
    SubmitFailure.UNSUPPORTED -> "送信の形式がサーバーに受け付けられませんでした。ページを再読み込みしてから、もう一度お試しください。"
    SubmitFailure.DISABLED, SubmitFailure.SERVER -> "サーバーでエラーが起きました。時間をおいてもう一度お試しください。"
    SubmitFailure.NETWORK -> "通信に失敗しました。Run が作成されたかどうかは確認できていません。同じ内容で投入し直しても、同じ Run として扱われます（二重には作られません）。"
    SubmitFailure.INVALID -> "応答の形式が想定と違います。ログインが切れている可能性があります。ページを再読み込みしてください。"
}

/** Task の投入の成功の文言(app.js の onSubmit と同じ)。202 は受け付け、200 は既に受け付け済み。失敗は [taskFailureText]。 */
fun taskSuccessText(result: SubmitResult, kind: SubmitKind): String = when (result) {
    is SubmitResult.Created -> "受け付けました。Run ID: ${result.id} / 状態: ${result.state}（${kind.label}）"
    is SubmitResult.Replayed -> "同じ投入として既に受け付け済みです（既存の Run）。Run ID: ${result.id} / 状態: ${result.state}（${kind.label}）"
    is SubmitResult.Failed -> taskFailureText(result.failure, kind)
}

const val SESSION_EXPIRED_MESSAGE = "セッションが切れました（ログインが必要です）。「再読み込みしてログインし直す」からログインし直してください。"
const val PROJECT_NOT_FOUND_MESSAGE = "選んだプロジェクトが見つかりません。一覧を読み直してから、もう一度お試しください。"
const val PROJECT_REQUIRED_MESSAGE = "プロジェクトを選んでください。"

/** 自己開発の注意書き(app.js の renderKindNote)。LOADING は最初の読み取りの前で、取得失敗と同じ文言。 */
fun selfDevNoteText(availability: SelfDevAvailability): String = when (availability) {
    SelfDevAvailability.ENABLED -> "自己開発を選ぶと、GitHub に PR を作成する Run になります（kivra は PR をマージしません）。"
    SelfDevAvailability.DISABLED -> "自己開発は無効のため、シミュレーションだけ投入できます。"
    SelfDevAvailability.LOADING, SelfDevAvailability.UNKNOWN -> "自己開発の設定を取得できていないため、自己開発は選べません。"
}

/** 自己開発を選べるか。`/api/selfdev/status` が enabled のときだけ(推測で選ばせない)。 */
fun selfDevSelectable(availability: SelfDevAvailability): Boolean = availability == SelfDevAvailability.ENABLED

/** 今の種別。自己開発を選べなくなったら、シミュレーションに戻す(app.js の applySelfDevAvailability)。 */
fun effectiveKind(requested: SubmitKind, availability: SelfDevAvailability): SubmitKind =
    if (requested == SubmitKind.SELF_DEV && !selfDevSelectable(availability)) SubmitKind.SIMULATED else requested

/** 送信中、または セッション切れの間は、送信も種別の変更もできない(app.js の updateSubmitEnabled)。 */
fun submitBlocked(submitting: Boolean, sessionExpired: Boolean): Boolean = submitting || sessionExpired

/** 本文の欄は自己開発だけ(旧 UI: 本文は SELF_DEV のみ送る)。 */
fun showsBodyField(kind: SubmitKind): Boolean = kind == SubmitKind.SELF_DEV

private val PROJECT_ID_SHAPE = Regex("[0-9A-Z]{26}")

/** プロジェクトの id の形(ULID)。app.js の PROJECT_ID_PATTERN。 */
fun isProjectId(raw: String): Boolean = PROJECT_ID_SHAPE.matches(raw)
