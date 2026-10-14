# ADR 0006: Run の LOST 状態は、実行の実体化と一緒に決めるまで永続化しない

- ステータス: 採用(Accepted)
- 日付: 2026-10-15
- 関連: Issue #31(Reaper)、PR #67(Reaper)、PR #144(LEASE_LAPSED の報告)、ADR 0001(Run の状態機械の正本)

## 背景

設計 #3 §4.1 / §6.3 は、ハートビートが切れ、対応するリソースが無い Run を `LOST` として扱う案を含んでいた。
一方、現在の `DevelopmentRunState` には `LOST` が無い(`kivra-core` の `States.kt`)。
Reaper は、`LEASE_LAPSED` の候補を報告するだけで、削除も状態の遷移もしない(PR #144)。
すべての Run は SIMULATED であり、実体のある実行や外部リソースは無い。

## 決定

1. `DevelopmentRunState` に `LOST` を追加しない。
2. Reaper の `LEASE_LAPSED` は報告のみとする(PR #144)。
3. 自動削除は既定でオフのまま(`dryRun=true`、`autoDeleteEnabled=false`)とする。
4. `LOST` の永続化と遷移は、実行の実体化(Phase 2。SIMULATED でない実行)の設計と一緒に決める。

## 根拠

- 現状の Run はすべて SIMULATED で、実体のある実行や外部リソースが無い。`LOST` の遷移先になる運用がまだ無い。
- 遷移先の無い状態を先に入れると、状態機械の遷移表と試験が、実際には使われない仕様で固まってしまう。
- 報告のみにすれば、観測の情報は失わずに、危険な自動操作を避けられる。

## 結果

- 影響: `LEASE_LAPSED` は報告(ログやメトリクス)として残る。Run は、Lease が切れても現在の状態に留まる。
- 残余リスク(PR #67 のレビューの P1): exkes 側に条件付きの DELETE が無く、新しい Runtime に fencing が無い。
  自動削除をオンにする前に、この二つが必要である。現状はオフなので、直ちに問題になるものではない。
- やらないこと: `LOST` の追加、Lease が切れた Run の自動的な削除、`autoDeleteEnabled` を既定で有効にすること。

## 見直しの条件

- 実 exkes のランタイムを起動する最初の PR(このときに `LOST` の扱いを決める)。
- 残余リスクの二つ(exkes 側の条件付き DELETE、Runtime の fencing)が解消されたとき。

## 実装状況

- 済: `LEASE_LAPSED` の報告(Reaper、PR #144)。自動削除は既定でオフ(PR #67)。
- 未実装: `LOST` の状態と遷移(`DevelopmentRunState` には無い。Phase 2 の設計と一緒に決める)。
- 未実装: exkes 側の条件付き DELETE と、新しい Runtime の fencing(残余リスク)。
