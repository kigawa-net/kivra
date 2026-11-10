# ADR 0022: 全エンティティをプロジェクト配下にする(方針と段階。第1段は expand のみ)

> 注記(2026-11-09): 旧 vanilla UI(`/ui`、`kivra-app/src/main/resources/ui/`)は撤去済み。現在の画面は Compose UI(`/ui/compose`)。本文の旧 UI への記述は当時の記録として残す。

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
| PR-E2 | API と画面をプロジェクト配下へ移す(`/api/projects/{id}/...`、画面は `#/projects/{id}/...`)。PR-E の予算とは別の段階 | 新しいルートと画面。旧ルートは応答を変えない |
| PR-F | NULL の行を消す(Contract)。NULL のまま残す行がなくなった後にだけ行う | 不変条件の強化 |
| PR-G | 旧ルートの廃止。別名があり呼び出し元が残らない旧 POST(`/api/selfdev/goals`、`/api/selfdev/orders`)を 410 Gone にする。旧 GET は全体・未分類の画面が使うので残す | 旧 POST の応答(410 と Link) |

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
- 残り(後続): PR-D(権限を `project_members` で判定)、PR-E(予算)、PR-F(NULL の行を消す)。

## 進捗(PR-D1: 読み取りの絞り込みと、プロジェクト単位の認可の影、ブランチ `feat/project-scope-read`)

- マイグレーションは追加しない。
- 読み取り: 人が見る一覧(Run の `GET /api/runs`、`GET /api/admin/runs`、`GET /api/selfdev/runs`、目標、order、承認待ちの停止要求、記憶の検索、担当)に任意の `projectId` クエリを足した。省略は従来どおり全件。ULID は、そのプロジェクトの行だけ。`unassigned` は `project_id` が NULL の行だけ。それ以外は 400(値は返さない)。未知のプロジェクトの一覧は空。単体取得は他プロジェクトの行を 404 にしない(shadow の間の挙動は変えない)。応答には `projectId` を含める。別名ルート(`/api/projects/{id}/tasks` など)は作らない(任意)。旧 `/api/selfdev/*` の路径は変えない。
- 自律ループは絞らない。Planner、Dispatcher(`taskIdsInStates`)、周回、停止の確認、自動マージ、インシデントの修正は、全行(NULL を含む)を従来どおり処理する。絞り込みの入口は、一覧の関数の引数 `project`(既定は `ProjectFilter.All`)だけで、自律ループは既定のまま呼ぶ。テストで確かめる(`ProjectScopeReadTest`)。
- 認可: `KIVRA_SELFDEV_PROJECT_MODE`(既定 `off`、ロールが off なら設定を拒む)。`off` は何も読まない。`shadow` は現行のロール判定の結果を変えず、プロジェクトのメンバーでない操作を固定の code(`PROJECT_NOT_MEMBER`、`PROJECT_PERMISSION_DENIED`、`PROJECT_LOOKUP_FAILED`)の構造化ログに出すだけ。`enforce` は現行の判定に加えて、メンバーであり、そのプロジェクトのロールが権限を持つことを要求する(グローバル ADMIN は常に通す。store の失敗は拒否)。
  - 対象: `POST /api/selfdev/tasks`、`POST /api/selfdev/goals`、`POST /api/selfdev/orders`、停止要求の作成・承認・却下・取消、目標の承認・停止、Task の承認・却下。プロジェクトを特定できない行(NULL)は現行の判定だけ。MCP のツール(`kivra_selfdev_start` など)は対象外(後続)。
  - `project_members` の読み取りは `ProjectMemberStore`。書き込みの API はまだない(後続)。そのため、メンバーの登録手段ができるまで `enforce` は使わない(runbook 4.17)。
- 画面: サイドバーに「未分類」を足し、プロジェクトを選ぶと Run・目標・order・承認待ち・記憶・担当の一覧をサーバーの絞り込みで読み直す。クライアントでは絞らない。読み込み中に選択が変わった場合は古い応答を捨てて読み直す。
- 残り(後続): 担当の所属の登録 API と画面、MCP の一覧と Task 投入の判定、PR-E(予算の判定)、PR-F(NULL の行を消す)。

## 進捗(PR-D2: メンバーの登録、ブランチ `feat/project-members`)

- マイグレーションは追加しない。V51 の `project_members` への書き込み経路を作る。PR-D1 の「担当の所属の登録 API と画面」にあたる。
- API(ORDER_MANAGE、CSRF の JSON ガード)。`GET /api/projects/{id}/members`、`PUT /api/projects/{id}/members/{assigneeId}`(body `{role}`。追加は 201、同じ role の再送と変更は 200)、`DELETE /api/projects/{id}/members/{assigneeId}`(本文は `{}`。204)、`POST /api/projects/{id}/members/import-all?dryRun=true|false`(既定は dry-run。有効な担当で未登録の人を指定の role で追加。既存の role は変えない。無効な担当は対象外。件数を返す)。
- 不変条件: そのプロジェクトのメンバー ADMIN が 0 人になる外し・降格は 409 で拒否する。判定はプロジェクトのメンバーだけで行い、グローバル ADMIN も例外にしない。プロジェクトの行を `FOR UPDATE` で固定してから数えるので、同時の降格も両方は通らない。メンバーが 0 人のプロジェクトは対象外。
- 監査は構造化ログ(`net.kigawa.kivra.project.admin`)に固定の code、プロジェクト ID、実行者と担当のハッシュ、role だけを残す。表示名は残さない。
- 画面(運用者の欄): メンバーの一覧、追加(担当の選択と role。候補は既存の `GET /api/selfdev/assignees` で、この一覧だけはプロジェクトの絞り込みを掛けずに読む)、role の変更、外す(確認つき)、全担当の取り込み(dry-run、確認つきの実行)。
- 運用の手順は `docs/runbook-selfdev.md` 4.17 の「enforce へ進む手順」(取り込み、役割の調整、shadow で `PROJECT_NOT_MEMBER` が 0 件になるのを確かめる、enforce)。
- 残り(後続): MCP の一覧と Task 投入の判定、PR-E(予算)、PR-F(NULL の行を消す)。

## 進捗(PR-E: 予算、ブランチ `feat/project-budget`)

- マイグレーションは追加しない。V51 の `project_budgets` と既存の `budget_*` の表だけを使う。スコープは `project:<projectId>`。
- 上限は `project_budgets` の行。行が無い project は上限なし(経路に入らず、従来どおり全体の上限だけで判定される)。API は `GET/PUT/DELETE /api/projects/{id}/budget`(ORDER_MANAGE、JSON)。`monthlyLimitJpy` は 0 以上の整数(円)、`softRatio` は 0 より大きく 1 以下(小数 3 桁まで、省略は 0.8)。不正値は 400、存在しない project は 404。
- 期間は tenant と同じ JST の暦月。予約パスは `[tenant, project:<id>]`(上限がある project だけ)。決済(`recordSpend`)は上限の有無によらず project の行にも計上する。上限を後から設定しても、その月の消化額が見えるようにするため(導入前の消化は数えない)。
- 台帳の経路はコーディング CLI(`CodingCostLedger`)。claim は候補 Run の project の上限で判定し、拒否はその attempt だけを保留する(ほかの project と全体は影響しない)。報告・見積もり・失敗の計上は Run の project に計上する。Run の project が NULL なら tenant だけ。Planner の費用と、API 経由のモデル呼び出し(`budgetScopes = [tenant]`)は、この段階では tenant だけ。
- ロック順は tenant → project → run(各々 scope id、period でソート)。`lockOrder` の単体テスト(`BudgetLockOrderTest`)で固定する。上限の設定・解除は tenant のロックの下で行い、判定と直列化する。
- 進行中の予約は予約時のスコープ(`budget_reservation_scopes`)のまま決済される。上限を外すと、その後の新しい経路は `BUDGET_POLICY_MISSING` で拒否される(安全側)。
- soft は警告のみ。ダッシュボードの `softExceeded` と、決済時のログ `project budget soft limit reached`(ID と月だけ)。
- ダッシュボードは運用者(scope `ALL`)にだけ `budget` を返す。画面には「今月の予算(運用者のみ)」と、確認つきの設定・解除を置いた。手順は `docs/runbook-selfdev.md` 4.18。
- 残り(後続): API 経由のモデル呼び出し(ModelGateway)と Planner の費用の project 対応、MCP の経路、導入前の消化の遡及(必要なら集計の移行を別途)。

## 進捗(PR-E2: API と画面をプロジェクト配下へ、ブランチ `feat/project-scope-api`)

- マイグレーションは追加しない。PR-A から PR-E までの列と表だけを使う。
- **別名ルート(新)。** `GET`・`POST /api/projects/{id}/tasks`(開発目標)、`GET`・`POST /api/projects/{id}/orders`、`GET /api/projects/{id}/approvals?state=PENDING`(承認待ちの停止要求)、`GET /api/projects/{id}/memory`、`GET /api/projects/{id}/assignees`。実体は旧ルートと同じ関数を使い、重複の実装はない。path の `projectId` が正で、作成の body の `projectId` は省略可、あれば一致が必要(不一致は 400)。存在しない・形式の違う project は 404。認可は path のプロジェクトで `checkInProject` を通す(shadow はログのみ、enforce は拒否)。
  - path の値は `request.path()` の先頭 `/api/projects/<id>/` から読む。Ktor の `call.parameters` はクエリを先に並べ、同名のクエリがあると path の値が出てこない(`?projectId=` で path を差し替えられる)ため。テスト `a query cannot name the project of an alias...` で確かめる。
- **旧ルート(挙動は変えない)。** projectId を受け付ける `GET`・`POST /api/selfdev/goals`、`GET`・`POST /api/selfdev/orders`、`GET /api/selfdev/stop-requests`、`GET /api/selfdev/memory`、`GET /api/selfdev/assignees` に `Deprecation: true` と `Link: <.../api/projects/{id}/...>; rel="successor-version"` を付ける。`projectId` が無いときの Link は `{projectId}` を含むテンプレート(エンコード済み)。
  - `GET /api/selfdev/runs` と `POST /api/selfdev/tasks`(PR-only の Run)は projectId を受け付けるが、移行先の別名ルートをこの段階で作っていないので、ヘッダは付けない(後続で、Run の別名を作ってから付ける)。
- **画面。** 選択中のプロジェクトのページは `#/projects/{id}/tasks|goals|orders|approvals|memory|assignees|dashboard`。一覧は別名ルートを読む(`apiFetch` の `scopedPath`)。ヘッダのタブは選択中のプロジェクトのページへのリンク、未選択と「未分類」は従来のハッシュ。予算のタブはダッシュボード(予算はそこにある)。`#/projects/{id}/budget` はダッシュボードへ置き換える。旧ハッシュ(`#/goals` など)は、選択中のプロジェクトの行を表示する。「未分類」は旧ルートに `?projectId=unassigned` を付けて読む(従来どおり)。実行(Run)の一覧は、従来どおり `GET /api/runs?projectId=` を読む。
- **MCP。** 出力に `project_id` を足した(`kivra_selfdev_start`、`kivra_run_get` と `kivra_run_list` の Run、`kivra_order_get` と `kivra_order_list` の order)。入力は変えない(`kivra_selfdev_start` は `project_id` 必須のまま)。停止要求の出力は、order の `project_id` で足りるので変えていない。
- **既存の注意(挙動は変えていない)。** `ProjectRoutes.kt` の `/api/projects/{projectId}/...`(repos、members、budget、dashboard)と、`/api/selfdev/.../{id}` の一部も `call.parameters` を読むため、同名のクエリで path が隠れる(例: `GET /api/projects/A/members?projectId=B` は B のメンバーを返す。運用者の権限は必要)。修正済み(ブランチ `fix/path-param-shadowing`): `routes/` の path パラメータはすべて `pathParam()`(`RoutingRequest.pathVariables`、path の捕捉だけ)で読む。`call.parameters` を `routes/` で使うことは `PathParamArchitectureTest` で禁じる。
- **Compose UI(`kivra-compose-ui`)は変えていない。** 旧ルートの応答が変わらないので、そのまま動く。
- **残り(後続)。** PR-F(NULL の行を消す)。Run の別名は PR-I で追加した。

## 進捗(PR-D3: 読み取りの認可、ブランチ `feat/project-scope-read-authz`)

- マイグレーションは追加しない。新しい環境変数も増やさない。`KIVRA_SELFDEV_PROJECT_MODE` の値に従う。既定の `off` は 1 バイトも変えない(応答も、読む先も)。
- **読み取りの経路と、前後の挙動。** 「前」は PR-D2 までの enforce、「後」は PR-D3 の enforce(shadow は両方とも応答を変えない)。

| 経路 | 前(enforce) | 後(enforce) |
|---|---|---|
| `GET /api/projects` | READ があれば全件 | 所属のプロジェクトのみ。global ADMIN は全件 |
| `GET /api/projects/{id}` | 全件を返す | 所属のみ。それ以外は 404(未知と同じ) |
| `GET /api/projects/{id}/dashboard`、`/repos` | 全件を返す | 所属のみ。それ以外は 404 |
| `GET /api/projects/{id}/budget`、`/members`(ORDER_MANAGE) | 運用者なら全件 | 所属のみ(global ADMIN は全件)。それ以外は 404 |
| `GET /api/admin/runs`、`GET /api/runs`、`GET /api/selfdev/runs`(一覧、`?projectId=`) | 全件(admin)、本人の Run(Task API) | SQL で絞る。所属のみ。NULL は global ADMIN のみ |
| `GET /api/admin/runs/{id}`、`GET /api/runs/{id}`、`GET /api/selfdev/runs/{id}`、`GET /api/runs/{id}/operations`、`/api/admin/runs/{runId}/execution`(events、stream を含む) | 全件、または本人の Run | 所属の Run のみ。それ以外は 404 |
| `GET /api/selfdev/goals`(旧)、`GET /api/projects/{id}/tasks`(alias) | 全件。alias は非メンバーに 403 | 旧: 所属で絞る。alias: 非メンバーは 404 |
| `GET /api/selfdev/goals/{id}` | 全件を返す | 所属の目標のみ。それ以外は 404 |
| `GET /api/selfdev/orders`(旧)、`/api/projects/{id}/orders`(alias) | 全件。alias は非メンバーに 403 | 旧: 所属で絞る。alias: 非メンバーは 404 |
| `GET /api/selfdev/orders/{id}`、`/cycles` | 全件を返す | 所属の order のみ。それ以外は 404 |
| `GET /api/selfdev/stop-requests`(旧)、`/api/projects/{id}/approvals`(alias) | 全件(STOP_DECIDE)。alias は非メンバーに 403 | 旧: 所属で絞る。alias: 非メンバーは 404、役割の STOP_DECIDE は従来どおり判定 |
| `GET /api/selfdev/memory`(旧)、`/api/projects/{id}/memory`(alias) | 全件(READ_TEXT)。alias は非メンバーに 403 | 旧: 所属で絞る。alias: 非メンバーは 404、役割の READ_TEXT は従来どおり判定 |
| `GET /api/selfdev/assignees`(旧)、`/api/projects/{id}/assignees`、`/api/selfdev/assignees/{id}/...` | ASSIGNEE_MANAGE(実質 global ADMIN のみ) | 変更なし(担当は運用者の一覧で、プロジェクトの行ではない) |
| `GET /api/selfdev/me`、`/api/selfdev/autopilot`、`/api/selfdev/autopilot/promotion`、`/api/selfdev/status`、`/api/admin/budget/status`、`/api/admin/budget/fx` | 全体の値 | 変更なし(プロジェクト単位の行ではない) |
| `GET /api/dev/*` | DB を使わない模擬応答 | 変更なし |
| MCP の読み取り(`kivra_run_list`、`kivra_run_get`、`kivra_order_list`、`kivra_order_get` など) | 全件 | **変更なし(残り。後続で絞る)** |
| `GET /api/agent/v1/attempts/{id}/status`(エージェント向け) | エージェントのトークン | **変更なし(残り)** |

- **判定の入口(新しいもの)。** `PermissionService.readScope`(enforce のときだけ絞る。shadow と off は `All`)と `readsAsEnforce`(shadow が「enforce なら隠れる行」を数えるための判定)。`ReadScope` は `All`、`Members(ids)`、`Refused`(store の失敗)。`ProjectMemberLookup.projectsOf` を足した(所属のプロジェクトの ID の集合。1 回の SELECT)。
- **SQL で絞る。** 一覧は `ProjectFilter.Within(ids)` を `WHERE` に入れ、`LIMIT` の前に効かせる(Kotlin で後から捨てると件数が欠ける)。`Within(empty)` は 0 件。
- **shadow は応答を変えず、固定の code のログだけ。** `project read shadow: wouldHide code=PROJECT_READ_NOT_MEMBER|PROJECT_READ_UNASSIGNED|PROJECT_LOOKUP_FAILED source=... role=... route=... via=... suppressed=N`。人、プロジェクト、行の ID は出さない。同じ code は 60 秒に 1 回(既存の shadow と同じ抑制)。
- **自律ループは絞らない。** Planner、Dispatcher、周回、停止の確認、自動マージ、インシデントの修正は、読み取りの経路を通らない。`ProjectScopeReadTest` の「自律ループは NULL の行も取る」を、そのまま通す。

判断した点:

1. **非メンバーには 404 を返す(403 ではない)。** 未知のプロジェクトと同じ本文の 404 にして、存在を漏らさない。書き込みの側は従来どおり非メンバーに 403 を返す(未知は 404、非メンバーは 403 なので、存在は書き込みでは区別できる)。これは残り(下記)。
2. **「全件を見られる」のは global ADMIN(assignee の role が ADMIN)だけ。** `projectDenial` と同じ判定。global OPERATOR(ORDER_MANAGE)は、ほかの運用者と同じく所属で絞られる。運用者の「全件」は、global ADMIN にだけ残る。
3. **NULL の行は enforce では global ADMIN だけが見る。** 一般のメンバーに、どのプロジェクトのものか分からない行を見せない。shadow は `PROJECT_READ_UNASSIGNED` を出す(仕様の例の 2 つに加えた。enforce にすると隠れる行を、shadow で確かめられるようにするため)。
4. **store の失敗は enforce で安全側。** 一覧は空(行を返さない)、単体は 404。shadow は応答を変えず `PROJECT_LOOKUP_FAILED` を出す。global ADMIN は store を読まない。
5. **alias の読み取りは「所属」で判定する。** READ は全てのプロジェクトのロールが持つ(`ROLE_PERMISSIONS`)ので、READ の一覧は所属の判定だけで足りる。READ_TEXT(memory)と STOP_DECIDE(承認待ち)は、所属の判定の後に、役割の判定(`checkInProject`)を従来どおり掛ける。
6. **旧ルートの一覧は、所属で絞るだけで、役割の判定はしない。** 従来どおり(旧ルートは `checkInProject` を通らない)。役割で制限したいなら alias を使う。これは残り(役割の判定の揃え)。
7. **Task API と `/api/selfdev/runs` は、本人の Run にも所属の絞りを掛ける。** 本人が作った Run でも、抜けたプロジェクトの Run は enforce では見えない(所属の判定は「今」の所属で行う)。
8. **担当の一覧は絞らない。** `ASSIGNEE_MANAGE` は、実質 global ADMIN だけが持つ。担当はプロジェクトの行ではない。
9. **既存の `ProjectFilter` と `ProjectFilter.Within` の違い。** `Within` は、所属の集合の絞り(読み取りの範囲)専用。旧ルートの `?projectId=` の意味(`unassigned`、ULID、全件)は変えない。

確認: `ProjectReadScopeTest`(route、H2)と `ProjectDenialTest`(`readScope` の単体)、`PermissionGuardProjectTest`(fake の `projectsOf`)。

残り(後続):

- MCP の読み取り(`kivra_run_list`、`kivra_run_get`、`kivra_order_list`、`kivra_order_get`)を絞る。
- 集計の値: `/api/selfdev/status` の活動中の件数、`/api/selfdev/memory` の `tombstones`・`deletionAudit`(全体の件数)。
- `/api/agent/v1/attempts/{id}/status`(エージェント向け)。
- 書き込みの存在の区別(非メンバーの 403 と未知の 404)を、読み取りと同じ 404 に揃えるか。
- 旧ルートの役割の判定(判断 6)。
- Compose UI の 404 と空の一覧の扱い(下記の確認結果を PR 本文に書く。UI は別 PR)。
- **PR-F との関係。** PR-F(V52)の後、開発目標・order・記憶は `project_id` が必須で、NULL の行は新しく作れない(読み取りの絞りの「NULL の行はグローバル ADMIN だけ」の扱いは残るが、実際に NULL になり得るのは Run の行だけ)。

### 残りの対応(PR-D3 の続き、ブランチ `feat/project-scope-read-authz-2`)

PR-D3 の「残り」のうち、MCP の読み取り、集計、旧ルートの役割の判定を埋めた。エージェントの status は絞らないと判断した。書き込みの存在の区別と Compose UI は残り。マイグレーションと新しい環境変数は増やさない。`KIVRA_SELFDEV_PROJECT_MODE` が off のときは、どの経路も応答も読む先も変えない(テストで確かめる)。

1. **MCP の読み取り(`kivra_run_list`、`kivra_run_get`、`kivra_order_list`、`kivra_order_get`)。絞る。** `McpCaller` が `readScope`(ディスパッチャが `guard.readScope(access)` から入れる)を持つ。一覧は `ProjectFilter` で SQL に入れ、`LIMIT` の前に効かせる。単体は、他のプロジェクトの行を、存在しない行と同じ NOT_FOUND にする(応答の本文が一致する)。SHADOW は `via=mcp` のログだけ。`kivra_run_list` のカーソルは従来どおり本人の selfdev run に限り、位置を決めるためだけに使い、内容は返さない。テスト: `McpReadScopeTest`。
2. **`GET /api/agent/v1/attempts/{id}/status` は絞らない(不要)。** 根拠: (a) 呼び出し元は担当(assignee)ではなくエージェントのトークンで、プロジェクトの所属を持たない。(b) `statusOf(attemptId, agentId)` は、そのエージェントが保持する attempt だけを見つける。他のエージェントの attempt と存在しない attempt は同じ 404 になる。(c) 応答は `state` と `cancelRequested` の 2 つだけで、タイトル、本文、プロジェクトは含まない。プロジェクトの絞りは「人がどのプロジェクトの行を読めるか」を決めるもので、この経路の対象ではない。
3. **集計。** 
   - `/api/selfdev/status` の `activeRuns`: `ReadScope` で絞った活動中の Run の件数(`activeSelfDevRunIds(project=...)`)。`Refused` は 0。SHADOW は応答を変えず、記録もしない(件数だけで行の ID を持たないため)。
   - `/api/selfdev/memory`(旧ルートと alias)の `tombstones`: 絞った行の `status = 'DELETED'` を数える。
   - `deletionAudit`: 絞った見え方では **0 を返す。** 監査の行(`selfdev_memory_events`)には project がなく、オペレータの削除は `record_id` も NULL(`deleteOne`)なので、所属へ帰属できない。全体の値は、絞られない呼び出し元(global ADMIN、または enforce 以外)だけが見る。所属ごとに出すには、イベントに `project_id` を足すマイグレーションが要る(後続。推測で割り当てない)。
   - テスト: `ProjectReadRoleScopeTest`(status、memory の counts)。
4. **旧ルートの一覧の役割の確認。欠けていたので追加した。** 旧 `GET /api/selfdev/memory` は global の READ_TEXT だけを見て、そのプロジェクトでの役割を見ていなかった(alias は `permit` の `projectId` で見る)。旧 `GET /api/selfdev/stop-requests` も同じ(STOP_DECIDE)。所属で絞るだけだと、OPERATOR の所属者が STOP_DECIDE を持たないプロジェクトの承認待ちが見えてしまう。対応: `ProjectMemberLookup.projectsOf`(本文 PR-D3 の節)を `rolesOf`(所属プロジェクトと役割の対)に替え、`readScope(access, permission)` で、役割が `permission` を持つプロジェクトだけを `Members` にする。旧 memory 一覧は READ_TEXT、旧 stop-requests 一覧は STOP_DECIDE で絞る。alias の 404 は従来どおり READ で判定し、役割の 403 は `permit` のまま(応答を変えない)。
   - 確認した他の一覧: 旧 `/api/selfdev/goals` と `/api/selfdev/orders`(alias も)は READ で判定し、READ は全ての役割が持つので、役割の欠けはない。
   - 残り(判断): 目標の一覧は READ で `title` を返す。ADR 0020 の区分では title は READ_TEXT(AI、VIEWER、APPROVER は持たない)で、この不一致は PR-D3 より前からある。権限の決め方そのものの変更なので、ここでは変えない。単体の旧ルート `GET /api/selfdev/goals/{id}` も同じ理由で残り。
5. **テスト。** `McpReadScopeTest`(OFF は membership を読まない、SHADOW は応答が同じでログだけ、ENFORCE は絞る、store の失敗は空、他の行は存在しない行と同じ応答)、`ProjectReadRoleScopeTest`(OFF、ENFORCE の役割の絞り、SHADOW、status と memory の counts、store の失敗)。`ProjectDenialTest` と `PermissionGuardProjectTest` の fake は `rolesOf` に替えた。

## 進捗(PR-F: Contract、ブランチ `feat/project-scope-contract`)

- **マイグレーション。** V52 で `selfdev_tasks.project_id` と `selfdev_memory_records.project_id` を `NOT NULL` にする(`MODIFY COLUMN`)。V51 までの表は変えない。`MigrationExpandOnlyTest` の `KNOWN_CONTRACT_REQUIRED` に、この MODIFY を理由つきで 2 件だけ足した(他の expand の規則は変えていない)。
- **NULL が残る間は保留する(ゲート)。** `runMigrations` は、まず `target = 51` で V51 までを適用し、V52 が保留中なら 2 つの表の NULL の件数を数える。件数がゼロなら V52 を適用する。1 件でも残っていれば V52 を適用せず、件数をログ(`project scope contract held at V51`)に出して起動を続ける。Pod は止まらない。次の起動で件数がゼロになっていれば V52 が適用される。保留は履歴を変えない(V52 は未適用のまま)。
  - 理由: 起動時の Flyway の失敗は Pod を起動不能にする。NULL の行は PR-B で振り分けるまで残りうるので、その間に V52 の MODIFY が失敗してはならない。
  - 代替案(採用しない):
    - (a) V52 を単純に適用し、NULL があれば失敗させる。Pod が起動しなくなるので却下。
    - (b) V52 を作らず、運用者が手で `ALTER` する。履歴と DB の状態が一致せず、`validate` と「適用済みの版」が合わなくなるので却下。
    - (c) NULL の行を「未分類」の専用プロジェクトへ入れて、そのまま NOT NULL にする。決定 1(デフォルトのプロジェクトは作らない)に反するので却下。
    - (d) Flyway の Java migration で中身を分岐する。保留にすると、その版は成功として記録され、後で再試行できない。却下。
    - 採用: 件数を見てから適用する(ゲート)。保留中も、新しい書き込みは NULL を作らない(下の拒否)ので、件数は減る方向にしか動かない。
- **NULL を作る書き込みを止めた(V51 の間も同じ規則)。** 洗い出しの結果は次のとおり。

  | 書き込み | 場所 | 変更 |
  |---|---|---|
  | Task の投入(API、MCP、画面) | `SelfDevOperatorRepository.submitTask` | project は必須(PR-C)。変更なし |
  | Task の投入(システム。`kivra-incident`) | 同上、`IncidentFixWatch` | repo の対応から継承(PR-B)。対応がなければ `ProjectRequiredException`(何も書かない)。インシデントの修正は既存の再試行で `CLAIMED` に戻り、上限で `FAILED` |
  | 計画の子 Task | `commitPlan` | 根から継承。根が NULL なら `ProjectRequiredException` |
  | 周回の Task | `startCycleIn`(`SelfDevOrderRepository`) | order から継承。order が NULL なら `Refused(NO_PROJECT)`(`OrderConflict.NO_PROJECT` を追加) |
  | 記憶 | `SelfDevMemoryRepository.save` | 文脈(goal、plan、run)から継承。文脈になければ `Refused(NO_PROJECT)`(`MemoryRefusal.NO_PROJECT` を追加。監査は書かない) |
  | Run(`development_runs`) | `DevelopmentRunRepository.submitTask`、`AutopilotDispatcher`、Task API(`/api/tasks`) | 変更なし。Run の列は NULL 可のまま(下の「NULL 可の列」) |
  | order(`selfdev_orders`) | `createRoot`、`createChild` | 変更なし。根は project 必須(PR-C)、子は継承 |
  | 担当(`selfdev_assignees`) | 担当の作成 | 変更なし。列は NULL 可のまま |

  - MCP に order を作るツールはない。MCP の Task 投入(`kivra_selfdev_start`)は `project_id` が必須。
  - UPDATE で `project_id` を NULL に戻す経路はない(`ProjectBackfill` は NULL の行だけを更新する)。FK は `ON DELETE RESTRICT`。
- **NULL 可の列(判断)。** `selfdev_orders`、`development_runs`、`selfdev_assignees` の `project_id` は NULL 可のまま残す。段階表は PR-F を「NULL の行を消す」としたが、これらの列の NULL は「未分類」を表す(`ProjectFilter.Unassigned`、画面の「未分類」)。V38、V42 の `ON DELETE SET NULL` も変えない。NOT NULL にするなら未分類の表し方を別に決める必要があり、この PR の範囲を超える。
- **テスト。** `MigrationChainTest` は V52 を含む完全な連鎖を確かめる。V50 と V51 の test は V51 までの版を作って、V51 の時点の挙動(NULL が残る)を確かめる。新しい test `V52 waits while a task or memory row has no project, then applies once none is left` は、保留中に起動が失敗せず、行が残り、振り分け後に V52 が適用され、NULL が拒否されることを確かめる。
- **残り(後続)。** Task API(`/api/tasks`)が作る Run に project を付けるか(Run の列は NULL 可のまま)。旧版の Pod を止める順序は運用で行う(runbook 4.20)。
- 運用の手順は `docs/runbook-selfdev.md` 4.20。

## 進捗(PR-G: 旧ルートの廃止、ブランチ `feat/retire-legacy-selfdev-paths`)

- マイグレーションは追加しない。ルートの登録と応答だけを変える。

**監査(Deprecation と Link を付けていた旧ルートの全件)。**

| 旧ルート | 別名 | 呼び出し元(リポジトリ内) | 扱い |
|---|---|---|---|
| `GET /api/selfdev/goals` | `GET /api/projects/{id}/tasks` | Compose UI の「全体」(`ProjectApi.goals(null)`)と「未分類」(`?projectId=unassigned`)、`scripts/compose-ui-crawl/crawl.mjs` | **残す。** 別名は ULID の project を要求し、全体・未分類の表示に移せない |
| `POST /api/selfdev/goals` | `POST /api/projects/{id}/tasks` | Compose UI は別名だけ(`goalSubmit`)。テストのフィクスチャ(移した) | **410 Gone**(下の判断 1) |
| `GET /api/selfdev/orders` | `GET /api/projects/{id}/orders` | Compose UI の「全体」「未分類」、`scripts/selfdev-check.sh`、`crawl.mjs` | **残す**(GET goals と同じ) |
| `POST /api/selfdev/orders` | `POST /api/projects/{id}/orders` | Compose UI は別名だけ(`orderCreate`)。テストのフィクスチャ(移した) | **410 Gone**(判断 1) |
| `GET /api/selfdev/stop-requests` | `GET /api/projects/{id}/approvals` | Compose UI の「全体」(`ProjectApi.approvals(null)`) | **残す**(全体の承認待ちに移せない) |
| `GET /api/selfdev/memory` | `GET /api/projects/{id}/memory` | Compose UI の「全体」と「未分類」(`memory(null)`、`?projectId=unassigned`) | **残す**(同上) |
| `GET /api/selfdev/assignees` | `GET /api/projects/{id}/assignees` | Compose UI の担当の画面(`projectId` を渡さない、担当は全体) | **残す**。担当の一覧はプロジェクトの行ではない(判断 8 of PR-D3) |

別名がない旧ルート(`GET /api/selfdev/runs`、`POST /api/selfdev/tasks`)は、この PR では触らない(Deprecation を付けていない。移行先の Run の別名が後続)。MCP(`kivra_*`)、自律ループ、エージェント API は、サーバー内のリポジトリを直接呼ぶか、HTTP の旧ルートを使わないので、この表の呼び出し元には入らない。`docs/` の記述は runbook と README だけを更新した。

**判断。**

1. **旧 POST は 410 Gone にする(削除はしない)。** 理由: (a) 削除は、他のシステムや手元のスクリプトが残っていたときに、応答が 404 に変わって原因が分かりにくい。410 は「移った」ことを本文と `Link` で示せる。(b) 旧 POST の呼び出し元は、リポジトリ内では Compose UI のテストだけで、UI はすでに別名を使う。(c) 外部の呼び出し元は確認できない(リポジトリの外は見えない)ので、ログ(`net.kigawa.kivra.routes.retired`、method と path だけ)で呼び出しの件数を測る。
   - 代替案(採用しない): (a) そのまま残す(Deprecation だけ)。外部の利用が分からず、いつ終えるかの根拠がないので却下。(b) 404 にする。移行先の案内(`Link`)が出せず、呼び出し元が原因を調べにくいので却下。(c) 認証の前に 410 を返す。401・403 の応答が変わり、権限の表(`RolesCoverageArchitectureTest`)の前提が崩れるので却下。
   - 認証と権限は従来どおり先に判定する(`requireOperator` と `permit`、order は `caller` と `permit`)。よって 401 と 403 は変わらず、表の行(`GOAL_WRITE`、`ORDER_MANAGE`)もそのまま。
2. **旧 GET は残す。** 別名がなく、Compose UI の「全体」と「未分類」が読む。410 にすると画面の一覧が消える。別名を作るには「全体」の意味を別のルートに置く必要があり、この PR の範囲を超える。
   - 残る問題: 「全体」の呼び出しにも `Deprecation` と `Link`(テンプレート)が付く。移行先がないのに廃止を示すので、紛らわしい。全体の呼び出しでは `Deprecation` を付けない、という変更は後続で決める(応答の変更なので、この PR では行わない)。→ PR-I で解消(全体と未分類には `Deprecation` も `Link` も付けない)。
3. **削除の時期。** 410 の旧 POST の登録は、ログ `net.kigawa.kivra.routes.retired` に廃止から 30 日の間 1 件も出ず、外部の利用がないことを運用者が確かめてから削除する(runbook 4.21)。削除すると応答は 404 になり、表の行・README・テストも消す。

**変更。** `ProjectScope.kt` に `retiredBy`(`Deprecation`、`Link`、ログ)と `ROUTE_RETIRED` を足した。`SelfDevGoalRoutes.kt` と `SelfDevOrderRoutes.kt` の旧 POST は、認証と権限の判定のあとに 410 を返す。`SelfDevGoalRoutesTest` と `SelfDevOrderRoutesTest` の投入は別名で行い、「存在しない・形式が違う project は 404、body の projectId が違うと 400」を別名の規則として確かめる。`ProjectScopedRoutesTest` に、旧 POST が 410 を返し、何も作らず、AI と無トークンは従来どおり 403・401 になるテストを足した。

**残り(後続)。** 旧 POST の登録の削除(runbook 4.21 の条件を満たした後)。

## 進捗(PR-H: プロジェクトを空にする、ブランチ `feat/project-drain`)

`DELETE /api/projects/{id}` は空のプロジェクトだけを消す(PR-D2 以降の 409 `PROJECT_NOT_EMPTY`)。行を移す手段がなかったので、使われたプロジェクトは消せなかった。この PR は、行を別のプロジェクトへ移して空にする操作(drain)を足す。マイグレーションは追加しない。データは消さない。

**API。** `POST /api/projects/{id}/drain?dryRun=true|false`(既定は dry-run)、本文 `{"targetProjectId":"<ULID>"}`。ORDER_MANAGE、JSON の CSRF ガード、`pathParam()` で path を読む。移し先は `ProjectScope.kt` の既存の検査(`scopeOf`)で見る。形式違いと空は 400、存在しないのは 404、移し先が自分自身は 400(`same_project`)。移し先を読めない(`mayReadProject`)ときは 404。応答は `{"dryRun", "targetProjectId", "counts"}`(件数だけ)。

**何を移すか(判断 1)。**

| 行 | 扱い | 理由 |
|---|---|---|
| tasks、orders、memory records、runs、assignees | `project_id` を移し先へ更新(`UPDATE ... WHERE project_id = :from` のバッチ) | 行は消さない。移し先で見えるようにする |
| repos(`project_repos`) | 同じ `repo_key` を移し先へ付け替える | `repo_key` は UNIQUE で、移し先に同じ key は元々ない。以後の振り分けは移し先へ向く |
| budget(`project_budgets`) | 移し元の行を消す。移し先の行は変えない | 移し元に上限が残ると、空にしたあとも予算の判定が残る。消化額(`budget_periods`)は履歴として残す |
| members(`project_members`) | 移し元のメンバーを全て外す(ADMIN を含む)。移し先のメンバーは変えない | 下の判断 2 |

**判断 2: 最後の ADMIN を外せるのは drain の中だけ。** 通常の `DELETE`/`PUT` のメンバーは従来どおり `LAST_ADMIN` で 409 のまま(`ProjectMemberRepository` は変えていない)。drain は「プロジェクトを空にする」操作の一部で、空にしたあとは誰もそのプロジェクトのメンバーを必要としないので、最後の ADMIN を外す理由になる。テスト `every member of the source is removed, the last ADMIN too, and the normal removal still refuses it` で両方を確かめる。

**判断 3: 進行中の Run と予約があれば 409 `PROJECT_BUSY`(何も書かない)。** 次の 2 つのどちらかがあれば拒否する。
- 移し元の、終端状態でない Run(`COMPLETED`、`FAILED`、`CANCELLED` 以外)。
- 移し元のスコープ `project:<from>` の RESERVED の予約。

理由。予約は発行時点のスコープ(`budget_reservation_scopes`)で固定されるので、台帳の整合は崩れない。しかし進行中の Run は、付け替えのあとの支出を移し先のスコープへ計上し、以後の承認を別の上限で判定してしまう。終端の Run は支出がもう増えないので、移しても判定は変わらない。移し先の進行中の Run は拒否しない。移し先の Run は自分の project を保つので、付け替えで判定が変わらない。409 の本文は `{"error":"PROJECT_BUSY","counts":{"activeRuns":n,"reservedBudget":m}}`(件数だけ)。dry-run も同じ判定をして 409 を返すので、画面は実行前に止まる理由を出せる。dry-run は何もロックせず、期限切れの RESERVED を LAPSED に変えないので、dry-run の 409 は古い可能性がある。実行が判定の正本である。

**判断 4: ロックの順序と一つのトランザクション。**
1. 移し元と移し先の `projects` 行を `FOR UPDATE`、id の昇順(デッドロック回避)。
2. 移し元の進行中の Run を `FOR UPDATE` で読む(ロック読みは最新のコミット済みの行を見る)。
3. 各表の行を、1 バッチ 500 件(`ProjectBackfillRepository.DEFAULT_BATCH_SIZE`)で `SELECT ... FOR UPDATE` してから `UPDATE`。UPDATE の件数が選んだ件数と違えば例外で全体を戻す。
4. テナントの予算行をロックし、期限切れの予約を LAPSED にしてから、RESERVED を数える(`DbBudgetStore.lockTenantForDrain`)。
5. 移し元の `project_budgets` 行を消す。

ロックの順は ADR 0015 の順(オーダー行を先、テナントの予算行を後)に合わせた。先にテナントの予算行を取ると、オーダー行を待つサイクルの原価との間で循環待ちになりうるため。予算の判定は、テナントの予算行のロックで直列化される(予約の追加と drain の付け替えが同時に起きない)。

**件数の上限(判断 5)。** 上限は設けない。全体は 1 つのトランザクションで、移す行は行ロックを保つ。想定の規模(個人と小さなチームの運用で、表の行は数百まで)では、ロックは短い。件数が数万になるなら、表ごとに分けて実行する方式を検討する(この PR では行わない)。大きい drain は dry-run の件数で先に見てから実行する(runbook 4.23)。

**監査。** `net.kigawa.kivra.project.admin` に、固定の code(`action=PROJECT_DRAIN`、`mode=DRY_RUN|EXECUTE`、`result=OK|PROJECT_BUSY|NOT_FOUND`)、実行者、件数だけを出す。プロジェクト ID、repo 名、行の ID は出さない(テスト `the audit line ...` で確かめる)。

**残ること(判断しない)。**
- `selfdev_memory_events`(監査の行)には `project_id` がない。移すべき行がないので、drain は触らない。削除の監査をプロジェクトに帰属させることは、PR-D3 の判断のとおり後続。
- drain の直後に、移し元へ新しい行が入ると、その行は次の drain で移る(件数は実行時点の正本)。
- 判断 3 の判定と、Run の作成の間にある競合(作成が同時に移し元を選んだ場合)は、スコープの固定により台帳を壊さないが、その予算の帰属は移し先になりうる。

## 進捗(PR-I: Run の別名と Deprecation の整理、ブランチ `feat/project-alias-runs-tasks`)

- マイグレーションは追加しない。ルートの登録と応答のヘッダだけを変える。旧 POST(`/api/selfdev/goals`、`/api/selfdev/orders`)は 410 のまま。
- **Run の別名(新)。** `GET /api/projects/{id}/runs`(一覧)と `POST /api/projects/{id}/runs`(投入)。`/api/projects/{id}/tasks` は開発目標(Task)の別名で既に使われているので、Run の投入は `runs` とした(衝突しない名前)。旧 `GET /api/selfdev/runs` と `POST /api/selfdev/tasks` と同じ関数を使い、重複の実装はない。path の projectId は `pathParam("projectId")` で読む(`PathParams.kt`)。投入の body の projectId は省略可、あれば path と一致が必要(不一致は 400)。存在しない・形式の違う project は 404。認証・Origin・権限の判定は旧ルートと同じ順序で、プロジェクトの判定は path のプロジェクトで `checkInProject` を通す。権限の表 `ROUTE_PERMISSIONS` に 2 行を足した(`GET` は READ、`POST` は SPEND_START)。
- **旧ルートの応答は変えない。** 旧 `GET /api/selfdev/runs` は、projectId に ULID を指定したときだけ `Deprecation` と `Link` を付ける。旧 `POST /api/selfdev/tasks` は projectId を body で受けるので、PR-E の旧 POST(goals、orders)と同じく `Deprecation` は付けない。
- **Deprecation の付け方(判断)。** `deprecatedBy` は、projectId クエリが ULID のときだけヘッダを付ける。全体(クエリなし)、`unassigned`、ULID でない値には付けない。移行先がない、または移せない呼び出しに廃止を示さないため。テンプレートの `Link`(`%7BprojectId%7D`)は、廃止した旧 POST(410、`retiredBy`)にだけ残る。410 の応答は変えない。
- **UI。** 自己開発の投入(`ProjectApi.runSubmit`)は、ULID のプロジェクトでは別名 `POST /api/projects/{id}/runs` を使う。未分類は別名が ULID 専用なので旧ルート(サーバーは 400)のまま。画面の「タスク」は従来どおり `GET /api/runs?projectId=`(Task API)を読むので変更はない。
- **テスト。** `SelfDevRoutesTest`: 別名の投入(body なしで作られ、旧ルートの同じキーで replay される)、body の projectId 不一致は 400・存在しない project は 404 で何も作られない、一覧の絞り込みと旧ルートの `Deprecation` の有無(全体・unassigned・不正値には付かない)、401。`ProjectScopedRoutesTest`: 全体・unassigned・不正値の旧 GET に `Deprecation` が付かないこと(旧テンプレートの期待を更新)。`RolesCoverageArchitectureTest` の表に 2 行。`ProjectApiPathTest` に `runSubmit` のテスト。
- **構造テストの注意。** `RolesCoverageArchitectureTest` は、登録の本文を次の登録の手前までとして読む。最後の登録は EOF までを読むので、最後に置いた登録の権限の判定が後ろの関数の `Permission` を拾う。`listSelfDevRuns` は `submitSelfDevRun` より前に置いている(この順序を変えると GET の表と一致しなくなる)。
