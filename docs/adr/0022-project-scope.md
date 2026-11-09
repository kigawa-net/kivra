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
- **既存の注意(挙動は変えていない)。** `ProjectRoutes.kt` の `/api/projects/{projectId}/...`(repos、members、budget、dashboard)と、`/api/selfdev/.../{id}` の一部も `call.parameters` を読むため、同名のクエリで path が隠れる(例: `GET /api/projects/A/members?projectId=B` は B のメンバーを返す。運用者の権限は必要)。直すのは後続の別の変更とし、この段階では触らない。
- **Compose UI(`kivra-compose-ui`)は変えていない。** 旧ルートの応答が変わらないので、そのまま動く。
- **残り(後続)。** PR-F(NULL の行を消す)、`/api/selfdev/runs` と `POST /api/selfdev/tasks` の別名(Run の一覧・投入)と、その Deprecation、旧 `/api/selfdev/.../{id}` と `ProjectRoutes` の path の読み方の修正。
