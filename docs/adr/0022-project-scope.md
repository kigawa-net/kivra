# ADR 0022: 全エンティティをプロジェクト配下にする(方針と段階。第1段は expand のみ)

- ステータス: 提案(Proposed)。この PR は文書と、第1段(PR-A)のスキーマ変更だけを含む。
- 日付: 2026-11-08
- 関連: ADR 0020(担当とロール)、ADR 0021(停止要求の承認)、V38(projects)、V42(orders と assignees の project_id)

## 背景

現状、`development_runs`、`selfdev_orders`、`selfdev_assignees` は `project_id` を持つ(V38、V42。いずれも `ON DELETE SET NULL`)。一方で `selfdev_tasks` と `selfdev_memory_records` にはプロジェクトの列がなく、プロジェクトの単位で見る・権限を分ける・予算を分けることができない。

## 決定

1. **デフォルトプロジェクトは作らない。** 「どこにも属さない」を表す特別なプロジェクトや `is_default` 列は持たない。属さない行は `project_id` が NULL のまま残す。
2. **既存データは後続の PR-B で振り分ける。** 既存の `projects` に対して、`project_repos`(repo_key から project_id への対応)を使って行を割り当てる。対応が不明な行は NULL のまま残し、推測で割り当てない。
3. **新規作成は将来、`projectId` を必須にする(PR-C で 400)。** 今の挙動は変えない。
4. **FK は `ON DELETE RESTRICT`。** 新しく付ける列では、プロジェクトに行が残っている間は削除できないようにする。行が黙って未割り当てに戻ることを防ぐ。(V38、V42 の `SET NULL` は既存のまま変えない。)
5. **すべて expand のみ。** 列とテーブルの追加だけ。既存の INSERT(新しい列を書かないもの)は通り、新しい列は NULL になる。

## 段階

| 段階 | 内容 | 挙動の変化 |
|---|---|---|
| PR-A(この PR) | V50: `selfdev_tasks.project_id`、`selfdev_memory_records.project_id`(NULL 可、FK RESTRICT、INDEX)。V51: `project_members`、`project_repos`、`project_budgets` | なし |
| PR-B | 既存行の振り分け(`project_repos` による backfill。対応不明は NULL のまま) | 読み出しの絞り込み |
| PR-C | 新規作成で `projectId` を必須にする(欠けたら 400) | 新規作成の API |
| PR-D | 権限の判定を `project_members` で行う | 権限 |
| PR-E | 予算を `project_budgets` で行う | 予算の判定 |
| PR-F | NULL の行を消す(Contract)。NULL のまま残す行がなくなった後にだけ行う | 不変条件の強化 |

PR-F まで NULL を残すのは、PR-B で対応が不明な行を推測で割り当てないためである。

## テーブル(V50、V51)

- `project_members(project_id, assignee_id, role, created_at)`: PK は `(project_id, assignee_id)`。role は `selfdev_assignees.role` と同じ集合。
- `project_repos(project_id, repo_key, created_at)`: `repo_key`(owner/name)は UNIQUE で、1 つのリポジトリは高々 1 つのプロジェクトに属する。
- `project_budgets(project_id, monthly_limit_jpy, soft_ratio, updated_at)`: PK は `project_id`。金額は `DECIMAL(12,0)`(JPY)。`soft_ratio` は 0 から 1 の警告しきい値(既定 0.800)。

型、文字コード、FK の方針は `projects` と `selfdev_assignees` に合わせる(CHAR(26) の ULID、`DATETIME(6)`、FK の名前は `fk_<テーブル>_<先>`)。

## 確認

- `MigrationChainTest` に V50、V51 を追加。`MigrationExpandOnlyTest` は全ファイルを走査し、非 expand の変更がないことを確かめる。
- 「V49 までの列だけで書く INSERT が V51 の後でも通り、`project_id` が NULL になる」と「値のあるプロジェクトを削除すると失敗する(RESTRICT)」を `MigrationChainTest` で確かめる。

## 進捗(PR-C: 書き込み経路、ブランチ `feat/project-scope-write`)

- 書き込み経路で `project_id` を書く。マイグレーションは追加しない。
  - Task の投入(`SelfDevOperatorRepository.submitTask`)は引数 `projectId` を書く。Planner の子タスクは親(計画の根)から継承する(`commitPlan`)。
  - order の根は `createRoot` の `projectId` を書く。子の order は親から継承する(`createChild`)。order の周回の Task は、その order から継承する(`startCycleIn`)。
  - Run(`DevelopmentRunRepository.submitTask`)は `projectId` を書く。自律ループ(`AutopilotDispatcher`)は Task の `project_id` を Run へコピーする。
  - 記憶(`SelfDevMemoryRepository`)は、文脈の goal・plan の Task、次に run の順に `project_id` を読んで書く。どれも持たなければ NULL。
- API と MCP では `projectId`(MCP の引数は `project_id`)を必須にする。欠けた値と形式の誤りは 400、存在しない ID は 404(プロジェクト API と同じ)。対象は `POST /api/selfdev/goals`、`POST /api/selfdev/orders`、`POST /api/selfdev/tasks`、MCP の `kivra_selfdev_start`。
  - MCP には order を作るツールがない(`kivra_order_list`、`kivra_order_get`、`kivra_order_request_stop` のみ)。そのため order 系ツールには引数を足していない。
- 依存は同じプロジェクトの間だけ。`beginSubmission` は、依存の `project_id` が Task と一致しないとき(別プロジェクト、または片方だけ NULL)は投入しない。NULL 同士は従来どおり。`commitPlan` の依存は同じ計画の根の配下のタスクなので、構造上は必ず同じプロジェクトになる。
- 読み取りと自律ループは NULL の行を引き続き扱う(フィルタは入れていない)。
- 画面(`/ui`)は、目的(goal)と order の作成フォームにプロジェクトの選択を足した。選ぶまで送信しない。
- 未対応(後続): システムが起票する Task(インシデントの自動修正など)は、プロジェクトを持たないので NULL のまま。PR-B の振り分けで扱う。

## 進捗(PR-B: 既存データの振り分け、ブランチ `feat/project-scope-backfill`)

- マイグレーションは追加しない。V50、V51 の列と表だけを使う。デフォルトのプロジェクトは作らない。
- 対応(`project_repos`)の登録は運用者が行う。API は `GET/PUT /api/projects/{id}/repos`、`DELETE /api/projects/{id}/repos?repoKey=`。repo_key は owner/name の形式、UNIQUE 違反は 409、存在しない project は 404。権限は `ORDER_MANAGE`。
- 振り分けは `POST /api/projects/backfill?dryRun=true|false`(既定は dry-run)。`project_id` が NULL の行だけを、repo の対応で更新する。値のある行は変えない。対応がない行は NULL のまま。テーブルごとに `matched` `updated` `unmatched` を返す。500 件のバッチ(LIMIT)で書き、各バッチは別のトランザクション。何度実行しても結果は同じ(冪等)。
  - 行の対応の見方: Task は `target_repo`、order は `target_repo`(子の order は根と同じ repo)、Run はその Task(`selfdev_tasks.run_id`)、記憶は goal の Task、plan の Task、Run の順(PR-C の `projectOfContext` と同じ順)。dry-run と実行で同じ式を使う。
- システムが起票する Task(`kivra-incident`)は、作成時に `target_repo` の対応からプロジェクトを継承する。対応がなければ NULL。冪等の再送では、継承した値を比較しない。
- 監査は構造化ログ(`net.kigawa.kivra.project.admin`)に、固定の code と件数、実行者のハッシュだけを残す。repo 名は残さない。
- 画面(プロジェクトのダッシュボード)に、運用者だけの「リポジトリの対応」と「既存データの振り分け」(dry-run、確認ダイアログ付きの実行)を足した。運用の手順は `docs/runbook-selfdev.md` 4.16。
- 残り(後続): PR-D(権限を `project_members` で判定)、PR-E(予算)、PR-F(NULL の行を消す)。プロジェクトの削除は、登録済みの repo や行があると FK RESTRICT で失敗する(今は 500 になる。扱いは後続で決める)。
