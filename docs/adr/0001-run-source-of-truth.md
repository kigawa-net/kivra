# ADR 0001: Run の状態機械の単一正本は DevelopmentRun とする

- ステータス: 採用(M0 PR #43 のレビュー指摘 P1-2 / Issue #44 への対応)
- 日付: 2026-10-12
- 関連: 設計 #9 §3(DevelopmentRun / Stage / Attempt / Checkpoint)、#10 §3-4(状態・DDL)、#11 §3-5(Lease・Journal・Outbox)

## 背景

M0 の初版は設計 #1 由来の `task_runs` / `TaskRunState`(ACCEPTED / PLANNING / RUNNING / WAITING_APPROVAL /
RETRY_WAITING / RECONCILING / SUCCEEDED / FAILED / CANCELLED)を実装した。一方、設計 #9 / #10 / #11 は Run の正本を
`development_runs` の DevelopmentRun(CREATED / PLANNING / PREPARING / DEVELOPING / TESTING / REVIEWING / PULL_REQUEST /
WAITING_APPROVAL / RELEASING / VERIFYING / RETRY_WAITING / COMPLETED / FAILED / CANCELLED)と、その下の Stage / Attempt
(`stage_executions`)として定義している。このままマージすると M1 以降に「Run の状態機械が 2 つ」存在するか、
M1 が既存 DDL を置換することになる。

## 決定

1. **唯一の正本は `development_runs` / `DevelopmentRunState`**(設計 #9 §3.3 の状態と遷移図)。
   Run の進行・Lease・Hold/Cancel・楽観ロック(`version`)・`active_flag` による task 単位の active 一意はすべてこの行に載せる。
2. **Stage / Attempt の正本は `stage_executions`**(`(run_id, stage, attempt_no)` 一意)。Run 内の進行を Stage 階層で表し、
   `current_stage` は `state` から導出できる補助列とする。Run の `attempt` は通算回数。
3. **タスクレベルの概念(fusha-task の task)は正本ではない**。`task_id` は fusha-task への参照に限り、
   `ACCEPTED` や `SUCCEEDED` のようなタスク側の状態は Kivra の DB に持たない。fusha-task に見せる状態が必要なら
   DevelopmentRun から導出する **Projection**(Outbox イベント経由)として作る。別テーブルに独立の状態機械を持たない。
4. **`RECONCILING` は Run 状態から除く**。外部副作用の不明状態は Operation Journal(`external_operations`)側の
   `UNKNOWN` / `RECONCILIATION_REQUIRED` で表し、Run は Journal を待つ間も本来の Stage(例: DEVELOPING)に留まる
   (#11 §4。Run と Journal に二重の「不明」状態を作らない)。
5. 命名は設計に揃える: `development_runs`、`external_operations`(Operation Journal の表。#10 §4 / #11 §4.2)、
   `stage_executions`、Kotlin は `DevelopmentRun` / `DevelopmentRunState` / `DevelopmentRunRepository`。
   Lease 列は #11 §3.2 の `lease_owner / lease_generation / lease_expires_at`(`generation` ではなく `lease_generation`)。
6. 後退遷移・終端・RELEASING の扱いは #9 §3.3 に従う(TESTING / REVIEWING から DEVELOPING、PULL_REQUEST から TESTING、
   RELEASING は CANCELLED 不可で DEC への rollback 依頼のみ、終端からの遷移なし)。

## V1 マイグレーションを置換した理由

`V1__core_schema.sql` を新規マイグレーション(V2)で `task_runs` から改名・変換するのではなく **V1 自体を置換**した。
理由: M0 PR は Draft で未マージ、どの環境にも V1 を適用していない(`flyway_schema_history` が存在しない)ため、
改名用の移行を残すと「存在しなかった旧スキーマ」を永久に履歴へ残すことになる。
**マージ後(V1 が適用され得る後)は V1 を変更せず、必ず新しい Vn を追加する**(Flyway のチェックサム検証が壊れる)。
V2(`reconciliation_evidence_and_outbox_claim`)は置換ではなく列追加として分けてある。

## M0 では実装せず後続に残すもの(スキーマを増やしすぎないため)

- `developer_instance` / `target_instance` / `repository` / `branch` / `pr_ref` などの列、`execution_checkpoints`、
  `development_events`(#10 §4)。M1 以降のマイグレーションで追加する(DevelopmentRun を拡張するだけで、新しい Run 表は作らない)。
- `stage_executions` のリポジトリ実装(表と一意制約のみ M0 に含む)。

## 影響

- M1 / M2 は `development_runs` を拡張する。`task_runs` や `TaskRunState` を再導入してはならない。
- 設計 #9〜#12 の Draft が確定前に変わった場合は、この ADR と V1(未マージの間のみ)を更新する。
