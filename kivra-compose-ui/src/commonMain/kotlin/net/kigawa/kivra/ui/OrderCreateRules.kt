package net.kigawa.kivra.ui

/*
 * order の作成の純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js の ORDER_CREATE_MSG と
 * orderCreateFormState)とサーバー(SelfDevOrderRoutes.kt の POST /api/selfdev/orders)の規則に合わせる。
 * 題名は 1〜200 コードポイント、方針は 1〜2000 コードポイント(改行とタブは可、他の制御文字と双方向の制御文字は不可)、
 * 最大の周の数は 1〜10(サーバーの上限は設定で、超えると MAX_CYCLES_INVALID の 400)。
 */

const val ORDER_TITLE_MAX = 200
const val ORDER_POLICY_MAX = 2000
const val ORDER_MAX_CYCLES_MIN = 1
const val ORDER_MAX_CYCLES_MAX = 10

/** 題名の検査。Goal の目的と同じ規則(trim、1〜200、制御文字と双方向の制御文字なし)。 */
fun validatedOrderTitle(raw: String): String? = validatedTaskTitle(raw)

/**
 * 方針の検査。trim 後、1〜2000 コードポイント。改行とタブは許し、それ以外の制御文字と双方向の制御文字は拒否する
 * (サーバーの validatedPolicy と同じ)。通れば trim 済みの値、通らなければ null。
 */
fun validatedOrderPolicy(raw: String): String? {
    val text = raw.trim()
    val n = codePointCount(text)
    if (n == 0 || n > ORDER_POLICY_MAX) return null
    if (text.any { (isControlCodeUnit(it) && it != '\n' && it != '\t') || hasBidiControl(it.toString()) }) return null
    return text
}

private fun isControlCodeUnit(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

/**
 * 最大の周の数の読み取り(旧 UI の orderCreateFormState)。空欄は 1。1〜2 桁の数字だけを数に読む。
 * 1〜10 の外、数字でない、は null(画面は「最大の周の数は 1 以上の整数で…」と出す)。
 */
fun parseMaxCycles(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return ORDER_MAX_CYCLES_MIN
    if (!Regex("[0-9]{1,2}").matches(text)) return null
    val n = text.toInt()
    return if (n in ORDER_MAX_CYCLES_MIN..ORDER_MAX_CYCLES_MAX) n else null
}

const val ORDER_CREATED_MESSAGE = "order を作成しました。"
const val ORDER_NO_PROJECT_MESSAGE = "プロジェクトを選んでください。"
const val ORDER_BAD_MESSAGE = "題名は 1〜200 文字、方針は 1〜2000 文字で入力してください（制御文字は使えません）"
const val ORDER_MAX_CYCLES_MESSAGE = "最大の周の数は 1 以上の整数で、サーバーの上限以下にしてください"
const val ORDER_SENDING_MESSAGE = "送信中です…"

/**
 * 作成の失敗の文言(旧 UI の orderCreateFailText)。サーバーの本文と機械の code は表示しない(400 の MAX_CYCLES_INVALID だけ見分ける)。
 * 409 はルートの order が既にある(409 は作成でこれだけ)。
 */
fun orderCreateFailureText(status: Int?, code: String?): String = when (status) {
    401 -> SESSION_EXPIRED_MESSAGE
    409 -> "ルートの order は既にあります"
    404 -> "選んだプロジェクトが見つかりません。画面を読み直してから、もう一度選んでください。"
    400 -> if (code == "MAX_CYCLES_INVALID") ORDER_MAX_CYCLES_MESSAGE else "題名/方針が不正です（長さ、制御文字）"
    403 -> "この操作は許可されていません"
    503 -> "order は無効です"
    else -> "作成に失敗しました"
}

/**
 * 作成フォームを出すか。order は自律運転が有効なときだけ(旧 UI の order-create は ORDER_MANAGE、セクションは自律運転の有無)。
 */
fun showsOrderCreateForm(autopilot: AutopilotAvailability, perms: CallerPermissions): Boolean =
    autopilot == AutopilotAvailability.ENABLED && perms.can(GatedOperation.CREATE_ORDER)
