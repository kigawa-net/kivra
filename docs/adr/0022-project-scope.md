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

## 進捗(PR-D1: 読み取りの絞り込みと、プロジェクト単位の認可の影、ブランチ `feat/project-scope-read`)

- マイグレーションは追加しない。
- 読み取り: 人が見る一覧(Run の `GET /api/runs`、`GET /api/admin/runs`、`GET /api/selfdev/runs`、目標、order、承認待ちの停止要求、記憶の検索、担当)に任意の `projectId` クエリを足した。省略は従来どおり全件。ULID は、そのプロジェクトの行だけ。`unassigned` は `project_id` が NULL の行だけ。それ以外は 400(値は返さない)。未知のプロジェクトの一覧は空。単体取得は他プロジェクトの行を 404 にしない(shadow の間の挙動は変えない)。応答には `projectId` を含める。別名ルート(`/api/projects/{id}/tasks` など)は作らない(任意)。旧 `/api/selfdev/*` の路径は変えない。
- 自律ループは絞らない。Planner、Dispatcher(`taskIdsInStates`)、周回、停止の確認、自動マージ、インシデントの修正は、全行(NULL を含む)を従来どおり処理する。絞り込みの入口は、一覧の関数の引数 `project`(既定は `ProjectFilter.All`)だけで、自律ループは既定のまま呼ぶ。テストで確かめる(`ProjectScopeReadTest`)。
- 認可: `KIVRA_SELFDEV_PROJECT_MODE`(既定 `off`、ロールが off なら設定を拒む)。`off` は何も読まない。`shadow` は現行のロール判定の結果を変えず、プロジェクトのメンバーでない操作を固定の code(`PROJECT_NOT_MEMBER`、`PROJECT_PERMISSION_DENIED`、`PROJECT_LOOKUP_FAILED`)の構造化ログに出すだけ。`enforce` は現行の判定に加えて、メンバーであり、そのプロジェクトのロールが権限を持つことを要求する(グローバル ADMIN は常に通す。store の失敗は拒否)。
  - 対象: `POST /api/selfdev/tasks`、`POST /api/selfdev/goals`、`POST /api/selfdev/orders`、停止要求の作成・承認・却下・取消、目標の承認・停止、Task の承認・却下。プロジェクトを特定できない行(NULL)は現行の判定だけ。MCP のツール(`kivra_selfdev_start` など)は対象外(後続)。
  - `project_members` の読み取りは `ProjectMemberStore`。書き込みの API はまだない(後続)。そのため、メンバーの登録手段ができるまで `enforce` は使わない(runbook 4.17)。
- 画面: サイドバーに「未分類」を足し、プロジェクトを選ぶと Run・目標・order・承認待ち・記憶・担当の一覧をサーバーの絞り込みで読み直す。クライアントでは絞らない。読み込み中に選択が変わった場合は古い応答を捨てて読み直す。
- 残り(後続): 担当の所属の登録 API と画面、MCP の一覧と Task 投入の判定、PR-D2(予算)、PR-E(予算の判定)、PR-F(NULL の行を消す)。

## 進捗(PR-D2: メンバーの登録、ブランチ `feat/project-members`)

- マイグレーションは追加しない。V51 の `project_members` への書き込み経路を作る。PR-D1 の「担当の所属の登録 API と画面」にあたる。
- API(ORDER_MANAGE、CSRF の JSON ガード)。`GET /api/projects/{id}/members`、`PUT /api/projects/{id}/members/{assigneeId}`(body `{role}`。追加は 201、同じ role の再送と変更は 200)、`DELETE /api/projects/{id}/members/{assigneeId}`(本文は `{}`。204)、`POST /api/projects/{id}/members/import-all?dryRun=true|false`(既定は dry-run。有効な担当で未登録の人を指定の role で追加。既存の role は変えない。無効な担当は対象外。件数を返す)。
- 不変条件: そのプロジェクトのメンバー ADMIN が 0 人になる外し・降格は 409 で拒否する。判定はプロジェクトのメンバーだけで行い、グローバル ADMIN も例外にしない。プロジェクトの行を `FOR UPDATE` で固定してから数えるので、同時の降格も両方は通らない。メンバーが 0 人のプロジェクトは対象外。
- 監査は構造化ログ(`net.kigawa.kivra.project.admin`)に固定の code、プロジェクト ID、実行者と担当のハッシュ、role だけを残す。表示名は残さない。
- 画面(運用者の欄): メンバーの一覧、追加(担当の選択と role。候補は既存の `GET /api/selfdev/assignees` で、この一覧だけはプロジェクトの絞り込みを掛けずに読む)、role の変更、外す(確認つき)、全担当の取り込み(dry-run、確認つきの実行)。
- 運用の手順は `docs/runbook-selfdev.md` 4.17 の「enforce へ進む手順」(取り込み、役割の調整、shadow で `PROJECT_NOT_MEMBER` が 0 件になるのを確かめる、enforce)。
- 残り(後続): MCP の一覧と Task 投入の判定、PR-E(予算。表の PR-E と同じ)、PR-F(NULL の行を消す)。PR-D1 の節の「PR-D2(予算)」の表記は、予算を PR-E とする上の表に合わせて読む。
