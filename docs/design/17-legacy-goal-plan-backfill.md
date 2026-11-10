# 17. V40 で消えた Goal / Plan / PlanTask の backfill(設計と運用手順)

- ステータス: 提案。コードは `kivra-app/src/main/kotlin/net/kigawa/kivra/db/LegacyGoalPlanBackfill.kt`。Flyway には載せない。
- 関連: V18(旧テーブルの定義)、V40(`flatten_tasks`、旧テーブルを DROP して `selfdev_tasks` を作る)、V44(`parent_task_id`)、V50 / V52(`project_id`、NOT NULL)、ADR 0022、Issue #408
- 退避の仕組み: `LegacyGoalPlanArchive.kt`。V40 の前に `selfdev_goals` / `selfdev_plans` / `selfdev_plan_tasks` を `<table>_pre_v40` へコピーする。

## 1. 目的と範囲

V40 は旧 Goal/Plan 系テーブルを DROP し、旧レコードを新 Task へ移す処理を持たない。このドキュメントは、退避済みの `*_pre_v40` テーブル(または 2026-10-30 の V40 前ダンプを復元した DB の退避テーブル)から、現行の `selfdev_tasks` へ**安全な状態で**取り込む手順を定める。

範囲外:

- Flyway migration の追加や V40 の書き換え(チェックサムが変わるため行わない)。
- 起動時の自動実行、HTTP route の追加。importer は operator が明示的に呼ぶ関数だけである。
- 本体 DB へ旧ダンプを restore すること(後続の V42 / V43 / V40 後の書き込みを巻き戻すため)。

## 2. 実行の前提

1. 対象 DB に `selfdev_goals_pre_v40` / `selfdev_plans_pre_v40` / `selfdev_plan_tasks_pre_v40` がある。無ければ importer は `IllegalStateException` で止まる。
2. `selfdev_tasks.project_id` は V52 で NOT NULL になっている。対象 DB は V52 以降のスキーマであること。
3. `project_repos` に、取り込み対象の `target_repo` すべての対応が入っていること(ADR 0022 の振り分け表)。
4. 書き込みと dispatch は止める、または運用上の影響がないことを確認する(取り込んだ行は dispatch されないが、同時に運用者が承認操作をすると混ざるため)。

**ダンプからの退避テーブルの作り方。** 現在の dev DB は V40 を退避コードの導入前に適用しているため、`*_pre_v40` が無い可能性が高い。V40 前ダンプを一時 DB に復元し、退避テーブルを次の SQL で作る(`archiveLegacyGoalPlanTables` と同じ形)。

```sql
CREATE TABLE selfdev_goals_pre_v40 AS SELECT * FROM selfdev_goals;
CREATE TABLE selfdev_plans_pre_v40 AS SELECT * FROM selfdev_plans;
CREATE TABLE selfdev_plan_tasks_pre_v40 AS SELECT * FROM selfdev_plan_tasks;
```

その後、一時 DB を現行スキーマ(V52 まで)へ migrate してから importer を実行する。一時 DB は本体に接続させない。

## 3. 実行手順

1. 一時 DB で `runLegacyGoalPlanBackfill(ds, DRY_RUN)` を実行し、報告を確認する(§6 の計数と突き合わせる)。
2. 報告に問題がなければ `APPLY` を実行する。APPLY は 1 トランザクションで、失敗したら全体を rollback する。
3. 同じ `APPLY` をもう一度実行し、`insertedTasks = 0` と `alreadyPresentTasks` が前回の `insertedTasks` と一致することを確認する。
4. 本体 DB への適用は、この手順を一時 DB で確認し、データ保全の承認を別に取ってから行う。

`DRY_RUN` は同じ分類を行い、必ず rollback する。書き込みは発生しない。ログには mode と件数だけを出し、title / body / id は出さない。

## 4. 列マッピング

### 4.1 selfdev_goals → 根の Task(plan root)

| 旧(selfdev_goals) | 新(selfdev_tasks) | 備考 |
|---|---|---|
| goal_id | (新規 ULID。`task_id`) | 旧 id は保存しない。対応は key `v40:goal:<goal_id>` で追える |
| created_by | created_by | そのまま |
| idempotency_key | (使わない) | 旧 key は新 key と衝突しうるため、`v40:goal:<goal_id>` を使う |
| title / body | title / body | そのまま |
| target_repo | target_repo | そのまま |
| state | state = `SKIPPED`, state_reason = NULL | §5 |
| revision | revision | 0〜3。根が計画済みであることを保つため |
| created_at / updated_at | created_at / updated_at | そのまま |
| resume_state / resume_reason | (捨てる) | PAUSED の戻り先は新モデルの意味と違う |
| claim_token / claimed_until | (捨てる) | 計画の claim は無効化する |
| version | version = 0 | 新しい行 |
| (なし) | project_id | §7 |
| (なし) | parent_task_id = NULL, order_id = NULL, cycle_no = NULL | 根であり、order には属さない |

### 4.2 selfdev_plans → 行には写さない

Plan は新モデルに対応する表がない。ライブ plan(§5)の選び方だけに使い、行は作らない。

| 旧 | 扱い |
|---|---|
| plan_id | 行に写さない。key `v40:task:<plan_id>:<ordinal>` の一部として残す |
| revision / state | ライブ plan の判定に使う(§5) |
| summary | 捨てる(新テーブルに列がない。archive に残る) |
| planner_model / output_sha256 / request_ledger_id | 捨てる(監査は archive に残る) |

### 4.3 selfdev_plan_tasks → 子の Task

| 旧(selfdev_plan_tasks) | 新(selfdev_tasks) | 備考 |
|---|---|---|
| (plan_id, ordinal) | (新規 ULID。`task_id`) | key `v40:task:<plan_id>:<ordinal>` で一意 |
| (由来の goal の created_by) | created_by | plan_task には作成者がないので goal の値を使う |
| title / body | title / body | そのまま。長さ制限を超えるものは取り込まない |
| (goal の target_repo) | target_repo | plan_task に repo はない。goal の値 |
| state | state = `PENDING_APPROVAL` | §5 |
| state_reason | (捨てる) | 固定コード。新しい理由は付けない |
| depends_on(ordinal の配列) | dependencies(task_id の JSON 配列) | §4.4 |
| requires_approval | requires_approval | そのまま。承認は必要なまま |
| target_paths | (捨てる) | 新モデルに列がない。archive に残る |
| size_estimate | (捨てる) | 同上 |
| run_id | (捨てる。`NULL`) | 実行の紐付けは取り込まない(§5) |
| attempts | attempts = 0 | 次の提出の鍵 `ap:<id>:a<attempts>` を 0 から始めるため |
| version | version = 0 | |
| created_at / updated_at | created_at / updated_at | そのまま |
| approved_by / approved_at | (捨てる) | 新しい承認を取り直す |
| idempotency_key(旧 `ap:…`) | (使わない) | 新 key を使う |
| (なし) | parent_task_id = 根の task_id | 同じ goal の根 |
| (なし) | project_id | §7 |

### 4.4 depends_on の写し方

- 旧 depends_on は同じ plan の ordinal の配列で、各 ordinal は自分より小さい(V18 の注記)。
- 依存先が**取り込み済み**なら、その新 task_id を入れる。
- 依存先が **COMPLETED** なら、その依存は満たされている(旧 dispatcher もそう判断する)。依存から外し、件数を `satisfiedDependenciesDropped` に数える。COMPLETED の行そのものは取り込まない。
- 依存先が取り込まれず、かつ COMPLETED でもないなら、取り込みも行わない(§5 `DEPENDENCY_NOT_IMPORTED`)。満たされないまま承認待ちに置くと、どの経路でも解けない。
- 形式が不正、または自分以上の ordinal や存在しない ordinal を指すなら取り込まない(`DEPENDENCY_INVALID`)。

## 5. 状態の写し方と取り込まない行

### 5.1 取り込む行と、その状態

- **子の Task は `PENDING_APPROVAL` だけに置く。** 理由は §5.3 で示す。
- 旧 `QUEUED` も `PENDING_APPROVAL` にする。旧 QUEUED は「承認済みで、まだ提出されていない」だった。新モデルでは `QUEUED` が dispatch 対象なので、そのまま写すと自動提出になる。もう一度運用者が承認する。
- **根(goal)は `SKIPPED` にする。** 根は計画の入れ物で、実行対象ではない。`PENDING_APPROVAL` の根は承認と settlement の対象になるため、取り込みでは使わない。

### 5.2 取り込まない行(理由つき)

| 理由コード | 対象 | 取り込まない理由 |
|---|---|---|
| `PLAN_NOT_LIVE` | SUPERSEDED / REJECTED の plan の task、古い PROPOSED revision の task、plan が無い task | ライブ plan は goal の PROPOSED のうち最新 revision。それ以外は既に置き換えられているか、却下されている |
| `TASK_COMPLETED` | COMPLETED | 作業は完了している。取り込むと同じ作業を繰り返す |
| `TASK_TERMINAL` | FAILED / CANCELLED / SKIPPED | 結果が確定している。再実行するかは backfill ではなく運用者が判断する |
| `TASK_IN_FLIGHT` | SUBMITTING / SUBMITTED | Run や PR が存在しうる。run_id を写さないため、重複の実行や PR の作成を招く。archive の `run_id` で個別に確認する |
| `INVALID_ROW` | 旧 V18 の CHECK にない state | 状態を推測しない |
| `GOAL_ENDED` | goal が DONE / STOPPED の task | 終わった goal の未完了 task を生き返らせない |
| `NO_PROJECT_MAPPING` | `project_repos` に target_repo の対応がない | `project_id` は NOT NULL。推測で割り当てない(ADR 0022 の方針) |
| `DEPENDENCY_INVALID` | depends_on が不正 | 依存を解釈できない |
| `DEPENDENCY_NOT_IMPORTED` | 依存先が取り込まれず COMPLETED でもない | 満たされない依存を持つ行は意味がない |
| `INVALID_TEXT` | title / body / target_repo が CHECK に合わない | 切り詰めると内容が変わる |

根(goal)は、取り込み済みの子が 1 件以上ある場合だけ作る。子が 1 件も取り込まれない goal は `goalsNotImported` に数えるだけで、根も作らない。

**取り込まない行は消さない。** `*_pre_v40` の archive はそのまま残る。

### 5.3 PENDING_APPROVAL にする理由(コードで確認した事実)

- `dispatchQueued` は `state = 'QUEUED'` の task だけを提出する(`AutopilotDispatcher.kt`、`queuedTaskIds`)。
- Planner は `state = 'TASK_SUBMITTED' AND parent_task_id IS NULL` だけを取る(`submittedTaskIds`)。LLM 呼び出しを伴うので、取り込みで TASK_SUBMITTED にしてはならない。
- `resubmitInterrupted` は `SUBMITTING AND run_id IS NULL` だけを対象にする。
- `clearDispatchBlocks` は `BLOCKED` だけを対象にする。
- `settleFinishedPlans` は `PENDING_APPROVAL` かつ `revision > 0` の根だけを対象にする。取り込みの根は `SKIPPED` なので対象外。
- `PENDING_APPROVAL` から `QUEUED` へは、運用者の `approveTask`(`OPERATOR_APPROVED`)か、Planner の commit(`AUTO_APPROVED`、新しい計画を書くときだけ)でしか動かない。取り込みでは Planner を通さないため、AUTO_APPROVED は起きない。
- `SKIPPED` は終端で、遷移の辺が無い。根を SKIPPED にすると、根自身は承認も dispatch もされない。

`LegacyGoalPlanBackfillTest` の `no imported row is dispatchable by the autopilot` が、上の問いのうち queuedTaskIds / submittedTaskIds / interruptedSubmissionIds / blockedTasks / settleFinishedPlans の結果を検証している。

**運用者が承認すると dispatch される。** 承認した子は、その時点のリポジトリに対して新しい Run として実行される。古い依存関係や古い本文が今も正しいかは、承認の前に運用者が判断する。

## 6. 報告の計数(ログには件数だけ)

- `archivedGoals` / `archivedPlans` / `archivedPlanTasks`、`planTasksByLegacyState`(旧 state ごとの件数)。
- `insertedRoots` / `alreadyPresentRoots`、`insertedTasks` / `alreadyPresentTasks`(DRY_RUN では「書かれるはずの件数」)。
- `notImportedTasks`(理由コードごとの件数)、`goalsNotImported`、`satisfiedDependenciesDropped`。
- 検算: `importedTasks + Σ notImportedTasks = archivedPlanTasks`。

## 7. project_id の決め方

- 行の project は `project_repos.repo_key = target_repo` で引く(ADR 0022 PR-B と同じ規則)。
- 対応が無い行は取り込まない(`NO_PROJECT_MAPPING`)。デフォルトのプロジェクトは作らない。
- 根と子は同じ goal の target_repo を使うので、同じ project になる。

## 8. 冪等性(二重取り込みの防止)

- key は `(created_by, idempotency_key)` の一意制約(V40 の `uk_selfdev_tasks_idem`)に載せる。
  - 根: `v40:goal:<goal_id>`
  - 子: `v40:task:<plan_id>:<ordinal>`
- 取り込みの前に同じ `(created_by, idempotency_key)` を探す。あれば挿入せず `alreadyPresent` に数え、その task_id を依存の解決に使う。
- APPLY は 1 トランザクションなので、途中の失敗で半分だけ残ることはない。
- 2 回目の APPLY は何も挿入しない(テストで検証)。
- 運用者が手で同じ key を作ることは想定していない。

## 9. 判断の根拠と不確実な点

以下は、実データなしには推測のみの部分である。

1. **ライブ plan の判定。** 旧 `PROPOSED` の最新 revision をライブとした。旧コードで承認後に plan の state が変わったかどうかは、このリポジトリからは確認できなかった。承認済みの plan が `PROPOSED` のままか、別の状態になるかを、旧ダンプで確認する必要がある。
2. **旧 `QUEUED` の意味。** 旧 QUEUED は承認済みだったと見なしたが、自動承認(`requires_approval = false`)で入った行が含まれるかは不明。取り込み後は運用者が再承認するため、どちらでも安全側に倒れる。
3. **旧 goal の DONE / STOPPED。** 終端の goal には未完了 task が残らないと想定したが、旧コードの整合性は未確認。残っていれば `GOAL_ENDED` として数えられるので、件数で気づける。
4. **根を SKIPPED にする判断。** 根に実行の意味を持たせないための選択。運用画面で根をどう見せるかは別途決める。
5. **依存先 COMPLETED を満たしたとみなす判断。** 旧 dispatcher の判断と同じにしたが、COMPLETED の PR が本当に main に残っているかは確認していない。
6. **実データの件数。** handoff の集計(goals=8 / plans=7 / plan_tasks=27)は V40 前の本体 DB の読み取り集計。ダンプを復元した後の件数とは照合していない。
7. **project_repos の中身。** 本番の対応表は未確認。対応が無い goal は全件 `NO_PROJECT_MAPPING` になりうる。
8. **旧 ID の対応。** 旧 goal_id / plan_id と新 task_id の対応は、key の文字列でのみ追える。レコードの内容が本体の現行 Task と一致するかの照合は行っていない。

## 10. 運用で確認すること(この設計の外)

- 本体 DB への APPLY は、一時 DB での DRY_RUN と APPLY の結果を見た後、データ保全の承認を取ってから行う。
- 本体 DB に V40 前ダンプを restore しない(V42 / V43 / V40 後の書き込みを戻すため)。
- 取り込み後の承認は、件数と内容を見て一件ずつ行う。一括承認はしない。
- 取り込みに使った一時 DB と、archive テーブルの保管先を記録する。
