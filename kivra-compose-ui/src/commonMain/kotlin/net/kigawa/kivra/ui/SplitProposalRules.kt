package net.kigawa.kivra.ui

/*
 * 子 order の提案(Planner が出した子の案。ADR 0015 追補 2026-11-22、PR2)の純粋ロジック。画面に依存しない。
 * 承認は運用者の操作で、サーバーが提案の内容(題名・方針・周の上限)から子 order を作る。画面が送るのは提案の ID だけ。
 * 出すかどうか(権限と状態)はここと PermissionRules が決め、最終判定はサーバーが行う。
 */

/** 提案の状態の日本語。未知の値は「不明」。 */
fun splitProposalStateText(state: String): String = when (state) {
    "PROPOSED" -> "未決定"
    "APPROVED" -> "承認済み（子 order を作成）"
    "REJECTED" -> "却下"
    "EXPIRED" -> "失効"
    else -> "不明"
}

/** 承認・却下のボタンを出すか。未決定(PROPOSED)の提案だけ。 */
fun canDecideSplitProposal(state: String): Boolean = state == "PROPOSED"

/** 承認が済んだときの文言。子 order はサーバーが作る(作成の結果は詳細の一覧に出る)。 */
const val SPLIT_APPROVED_DONE = "提案を承認しました。子 order を作成しました。"

/** 却下が済んだときの文言。 */
const val SPLIT_REJECTED_DONE = "提案を却下しました。"

/**
 * 提案の承認・却下の失敗の日本語の文言。[code] はサーバーの固定コード(409 の PROPOSAL_*、子を作れない理由の CHILD_LIMIT など)。
 * 知らないコードと、コードの無い失敗は [writeFailureText] の共通文言。サーバーの error 本文は表示しない。
 */
fun splitProposalFailureText(failure: WriteFailure, code: String?): String = when (failure) {
    WriteFailure.STALE -> when (code) {
        "PROPOSAL_APPROVED" -> "この提案は既に承認されています。一覧を読み直してください。"
        "PROPOSAL_REJECTED" -> "この提案は既に却下されています。"
        "PROPOSAL_EXPIRED" -> "この提案は失効しています（計画が差し替えられたか、order が動いていません）。"
        "SPLIT_PROPOSAL_STATE_CHANGED" -> "提案の状態が変わりました。子 order が作られている可能性があるため、一覧を読み直してください。"
        "CHILD_LIMIT" -> "親の子 order の数が上限に達しています。"
        "TREE_LIMIT" -> "order の木の大きさが上限に達しています。"
        "DEPTH_LIMIT" -> "order の深さが上限に達しています。"
        "BUDGET_EXCEEDS_PARENT" -> "親の未割り当ての予算が足りません。"
        "PARENT_NOT_ACTIVE" -> "親の order が動いていないため、子を作れません。"
        "CHILDREN_DISABLED" -> "子 order の作成は無効になっています。"
        "KEY_CONFLICT" -> "同じ鍵で別の内容が既に作られています。"
        else -> writeFailureText(WriteFailure.STALE)
    }
    else -> writeFailureText(failure)
}
