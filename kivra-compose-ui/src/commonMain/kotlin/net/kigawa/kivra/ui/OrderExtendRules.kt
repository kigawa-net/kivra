package net.kigawa.kivra.ui

/*
 * order の延長の純粋ロジック(画面に依存しない)。ADR 0015 の 2026-11-10 追補(§2.6 の改め)と、サーバーの
 * POST /api/selfdev/orders/{id}/extend(kivra-app の routes/SelfDevOrderRoutes.kt)の規則に合わせる。
 * 上限(設定の周の上限)はサーバーが判定する。期限には天井が無い(延長日数の 3650 は入力の検査)。画面は形式と、固定コードの表示だけを持つ。
 */

/** 延長する日数の上限。サーバーの EXTEND_DAYS_LIMIT と同じ(1 から 3650)。期限の天井ではなく、入力の検査(ADR 0015 追補 2026-11-11)。 */
const val EXTEND_DAYS_MAX = 3650

/** 延長の入力の補足。 */
const val ORDER_EXTEND_HINT = "追加する周数と延長する日数は、どちらか一方か両方を入れます（両方空は不可）。"

/** 延長の確認文。周は始めず、予算は変えないことを先に言う。 */
const val ORDER_EXTEND_CONFIRM = "延長しても周は始まりません。予算は変わりません。" +
    "期限は、今と現在の期限の遅い方から延長日数だけ後になります。"

/** 延長の成功の文言。 */
const val ORDER_EXTENDED_DONE = "order を延長しました。周は始まりません。止まっている order は、別に再開してください（自動では再開しません）。"

/** 延長の入力の数の形(10 進の数字、9 桁まで)。 */
private val COUNT_SHAPE = Regex("[0-9]{1,9}")

/** 延長できる order か。ルート(親が無い)で、ACTIVE か PAUSED のとき(子は NOT_ROOT、STOP_REQUESTED・STOPPED は 409)。 */
fun canExtendOrder(state: String, parentOrderId: String?): Boolean =
    parentOrderId == null && (state == "ACTIVE" || state == "PAUSED")

/** 追加する周数の入力が形式に合うか。空欄は「追加しない」として許す。空でなければ 1 以上の整数。 */
fun isValidAddCycles(raw: String): Boolean {
    val text = raw.trim()
    return text.isEmpty() || (COUNT_SHAPE.matches(text) && text.toInt() >= 1)
}

/** 延長する日数の入力が形式に合うか。空欄は「延長しない」として許す。空でなければ 1 から [EXTEND_DAYS_MAX] の整数。 */
fun isValidExtendDays(raw: String): Boolean {
    val text = raw.trim()
    return text.isEmpty() || (COUNT_SHAPE.matches(text) && text.toInt() in 1..EXTEND_DAYS_MAX)
}

/** 延長の入力を送れるか。二つとも空は不可で、入れた方は形式が合うこと。 */
fun isExtendFormOk(addCycles: String, extendDays: String): Boolean {
    val add = addCycles.trim()
    val days = extendDays.trim()
    return (add.isNotEmpty() || days.isNotEmpty()) && isValidAddCycles(add) && isValidExtendDays(days)
}

/** 追加する周数を送る値に直す。空欄と形式外は null(送らない)。送る前に [isExtendFormOk] で確かめる。 */
fun addCyclesOrNull(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() && isValidAddCycles(it) }?.toInt()

/** 延長する日数を送る値に直す。空欄と形式外は null(送らない)。送る前に [isExtendFormOk] で確かめる。 */
fun extendDaysOrNull(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() && isValidExtendDays(it) }?.toInt()

/**
 * 延長の失敗の日本語の文言。[code] は応答の機械的なコード(サーバーの固定値)。400 の MAX_CYCLES_INVALID、
 * 409 の NOT_ROOT・STOP_ALREADY_PENDING・TERMINAL・EXTENSION_DOES_NOT_LIFT を文言に対応づける。code の無い 409 は版の競合、
 * code の無い 400 は入力の確認。それ以外は [writeFailureText] の共通文言。サーバーの error 本文は表示しない。
 */
fun extendFailureText(failure: WriteFailure, code: String?): String = when (failure) {
    WriteFailure.BAD_REQUEST -> when (code) {
        "MAX_CYCLES_INVALID" -> "延長後の周の上限が、設定された上限を超えています。周の数を減らしてください。"
        else -> "入力を確認してください（追加する周数は 1 以上の整数、延長する日数は 1〜$EXTEND_DAYS_MAX 日です）。"
    }
    WriteFailure.STALE -> when (code) {
        "NOT_ROOT" -> "子の order は延長できません。ルートの order を開いてください。"
        "STOP_ALREADY_PENDING" -> "停止が要求されているため、延長できません。"
        "TERMINAL" -> "停止済みの order は延長できません。"
        "EXTENSION_DOES_NOT_LIFT" -> "延長しても、周の上限と期限の両方が解消されません。" +
            "上限は開始済みの周の数より多く、期限は今より後になるように延長してください。"
        else -> writeFailureText(WriteFailure.STALE)
    }
    else -> writeFailureText(failure)
}
