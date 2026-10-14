# ADR 0004: 予算の可否の正本は BudgetPort とし、BudgetCheck は観測専用とする

- ステータス: 採用(Accepted)
- 日付: 2026-10-15
- 関連: Issue #73(真偽値の CostCheck による予算承認)、#109(予算)、#35 / #69(durable gate)、PR #66(観測の射影)

## 背景

予算の判定には、二つの経路があった。
- `kivra-model-gateway` の `BudgetPort`。永続化実装は `DbBudgetStore`(B1、V10)、開発用のインメモリ実装は `BudgetController`。
- `kivra-observability` の `BudgetCheck`(PR #66)。既知のコストを上限と比較する読み取り専用の関数。

`BudgetCheck` は未価格の呼び出しや部分的な応答を考慮しないため、承認の判定に使うと、上限超過を見逃す。

## 決定

1. 支出の可否(ALLOW / DENY / UNKNOWN_COST)を決める唯一の正本は `BudgetPort` とする。永続化された実装は `DbBudgetStore`、開発用は `BudgetController`。
2. `BudgetCheck` は既知のコストの観測(テレメトリ)専用の読み取り射影とし、可否の判断に使わない。
3. `BudgetCheck` の真偽値を返す関数は、誤用されない名前(既知のコストが上限を超えたか、だけを表す名前)にし、KDoc に「承認に使ってはならない」と明記する。

## 根拠

- 承認の判定には、未価格・部分ストリーム・応答喪失・会計の commit 失敗を含めた判定が必要であり、それは `BudgetPort` の予約(reserve)でのみ扱える。
- 観測用の関数を承認に流用すると、既知コストだけが見えて安全側の判定が抜ける(#73)。
- 名前で用途を限定すれば、呼び出し側の誤用を防げる。

## 結果

- 影響: 予算の承認を行う箇所は `BudgetPort` を経由する。ダッシュボードや監視は `BudgetCheck` を使ってよい。
- やらないこと: `BudgetCheck` を承認ゲートに使うこと。`BudgetCheck.verdict()` の `WITHIN_KNOWN_LIMIT` を「承認」と読むこと(本 ADR の範囲外の判定でも、承認ではない)。

## 見直しの条件

- 観測と承認の経路を一つに統合する設計変更が必要になったとき(その場合は新しい ADR で決める)。

## 実装状況

- 済: `BudgetPort` の永続化実装(`DbBudgetStore`、V10)と、開発用の `BudgetController`。`BudgetCheck` は観測用として存在する。
- 済(main 上): `BudgetCheck` の真偽値関数は `knownCostExceeded` に改名され、KDoc に「観測専用」「承認に使ってはならない」と明記されている(main の `kivra-observability` の `CostPort.kt` で確認。改名の作業は既に main に入っている)。
- 別作業: `BudgetCheck.verdict()` の承認ゲートでの扱い(#35 / #69 の durable gate)は未実装。
