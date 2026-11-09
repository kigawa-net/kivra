package net.kigawa.kivra.ui

/*
 * プロジェクトの要約(ホームとプロジェクト一覧に出す件数)の純粋ロジック。件数はプロジェクトのダッシュボードの
 * tasks.byState(scope の中の Run の状態別件数)から数える。ホームの旧定義(HomeScreen の FAILED_STATES・TERMINAL_STATES)と同じ。
 */

/** 終わった Run の状態。これ以外の状態の Run は実行中とみなす。 */
private val TERMINAL_RUN_STATES = setOf("FAILED", "COMPLETED", "CANCELLED", "NOT_EXECUTED")

/** 要対応の Run の状態: 失敗と、運用者の解放待ち(UNKNOWN。ADR 0009)。 */
private val ATTENTION_RUN_STATES = setOf("FAILED", "UNKNOWN")

/** 実行中の Run の件数(終わった状態以外の合計)。 */
fun runningCountOf(byState: Map<String, Long>): Long =
    byState.filterKeys { it !in TERMINAL_RUN_STATES }.values.sum()

/** 失敗・解放待ちの Run の件数。 */
fun attentionRunCountOf(byState: Map<String, Long>): Long =
    byState.filterKeys { it in ATTENTION_RUN_STATES }.values.sum()
