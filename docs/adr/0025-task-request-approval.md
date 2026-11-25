# ADR 0025: 承認フロー付きの Request(「この title のタスクを実行してよいか」の依頼)

- ステータス: 提案(Proposed)。本ブランチで実装する範囲は、本 ADR の「決定」と V62 まで。Compose UI、MCP、チャットは後続。
- 日付: 2026-11-18
- 関連: ADR 0008(Task API と PR のみのループ)、ADR 0020(担当と権限。Permission の表)、ADR 0021(停止要求の承認。要求者と決裁者の分離)、ADR 0023(提案の確定と却下。条件付き UPDATE の先例)。V62(`task_requests`、`task_request_events`)

## 背景

- Task API(`POST /api/tasks`)は、呼び出し元が `SPEND_START` を持つ限り、承認を挟まず即座に Run を作る。依頼者とは別の人が「この依頼を実行してよいか」を決める経路が無い。
- 停止要求(V23、V54)には、要求者と決裁者を分けるルール、決定後は変えない行、条件付き UPDATE による競合の扱いがある。依頼の承認も同じ形に合わせる。

## 前提(仕様が簡潔なため、既定として採る)

1. Request は「この `title` のタスクを実行してよいか」の依頼である。本文や対象リポジトリは持たない。承認されたときだけ、既存の `DevelopmentRunRepository.submitTask` で Run(SIMULATED、CREATED)を作る。Run の所有者は依頼者の principal になる。
2. 状態は `PENDING → APPROVED / REJECTED / CANCELLED` の 3 通り。`CANCELLED` は依頼者だけが、`PENDING` の間だけ行える。決定後の行は変わらない(`run_id` の紐付けを除く)。
3. 依頼者と承認者は別人。自己承認は、リポジトリ層の条件と DB の CHECK の両方で禁止する。
4. 権限は 2 つ。`REQUEST_SUBMIT`(依頼と取消)は、`SPEND_START` を持つ役割と同じ ADMIN、OPERATOR、AI に付ける。承認と却下の `REQUEST_DECIDE` は ADMIN と APPROVER に付ける。依頼権限を持つ役割は、もともと Run を作れるので、承認で権限が広がることは無い。
5. 依頼はプロジェクト(ADR 0022)の配下に置く。パスは `/api/projects/{projectId}/requests` で、`projectId` は ULID(`[0-9A-Z]{26}`)でなければ 404、存在しないプロジェクトも 404。作成・読み取り・承認・却下・取消のどれも、まず役割を(プロジェクト指定なしで)確認し、プロジェクトを解決したあと、そのプロジェクトでの役割を `checkInProject` で確認する(停止要求と同じ 2 回の permit)。本文に `projectId` を書くときはパスと一致しなければならず、違えば 400 `PROJECT_MISMATCH`。ほかのプロジェクトの依頼は 404。`task_requests.project_id` は CHAR(26) NOT NULL で `projects` への FK、索引は `(project_id, state, created_at)`。承認で作る Run の扱いは変えない。
6. 応答に承認者の識別子は出さない。監査の行は承認者のオーナーキーの SHA-256 先頭 12 桁(`actor_ref`)だけを持つ。
7. 却下の理由は任意で、500 文字以内の自由記述として依頼の行に保存する。ログと監査の行には出さない。
8. 件数の上限は設けない(未決事項)。

## 決定

### 1. 状態と遷移

| 遷移 | 行うこと | 誰が |
|---|---|---|
| (作成) → `PENDING` | 依頼の行を作る。同じ (依頼者, idempotencyKey) の再送は同じ行を返す。title が違えば 409 | `REQUEST_SUBMIT` を持つ者 |
| `PENDING` → `APPROVED` | 条件付き UPDATE(`state = 'PENDING'` かつ依頼者でない)。Run を作り、`run_id` を紐付ける(§3) | `REQUEST_DECIDE` を持つ、依頼者でない者 |
| `PENDING` → `REJECTED` | 条件付き UPDATE。理由は任意 | `REQUEST_DECIDE` を持つ、依頼者でない者 |
| `PENDING` → `CANCELLED` | 条件付き UPDATE | 依頼者だけ |

`APPROVED` の行は、`run_id` が NULL のまま残ることがある(§3 の回復の経路)。それ以外の変化は無い。

### 2. API

| メソッド・パス | 権限 | 応答 |
|---|---|---|
| `POST /api/projects/{projectId}/requests` | `REQUEST_SUBMIT` | body `{"title", "idempotencyKey", "projectId"(省略可)}`。同じキーを別のプロジェクトで使うと 409。新規は 201、同じ (依頼者, idempotencyKey) の再送は 200、title が違えば 409 |
| `GET /api/projects/{projectId}/requests` | `READ` | 自分の依頼を新しい順に最大 50 件。`REQUEST_DECIDE` を持つ者には、ほかの人の `PENDING` の依頼も含める(決裁できる分) |
| `GET /api/projects/{projectId}/requests/{id}` | `READ` | 依頼者本人、または `REQUEST_DECIDE` を持つ依頼者でない者。それ以外と存在しない ID は 404 |
| `POST /api/projects/{projectId}/requests/{id}/approve` | `REQUEST_DECIDE` | 200 と依頼(`runId` を含む)。依頼者本人は 403。存在しない、または見えない依頼は 404。`PENDING` でも `APPROVED`(`runId` 未紐付け)でもなければ 409 |
| `POST /api/projects/{projectId}/requests/{id}/reject` | `REQUEST_DECIDE` | body は省略可、または `{"reason": "..."}`。200。依頼者本人は 403、404、`PENDING` でなければ 409 |
| `POST /api/projects/{projectId}/requests/{id}/cancel` | `REQUEST_SUBMIT` | 200。依頼者でなければ 404(存在を見せない)。`PENDING` でなければ 409 |

- 作成は 201 とする。Task API の 202 は「非同期に実行される」ことを示すが、依頼は実行されないため、ここでは使わない。
- 依頼の応答の形: `requestId`、`projectId`、`title`、`state`、`createdAt`、`decidedAt`、`decisionNote`、`runId`。`requestedBy` は、決裁者が見る依頼(ほかの人の依頼)にだけ入る。
- 入力は `validatedText`(title 1〜200 文字、idempotencyKey 1〜128 文字、reason 500 文字。制御文字と双方向制御文字は不可)。mutating は `requireJsonSameOrigin`。認証は Task API と同じ `TaskApiIdentity`。

### 3. 承認と Run の作成(整合性)

承認は次の 3 段で行う。各段は独立したトランザクションである。

- (a) 条件付き UPDATE で `PENDING → APPROVED` にし、`APPROVED` の監査行を同じトランザクションで書く。更新件数が 0 なら、行を読み直して 404、403、409 のどれかに分ける。二人が同時に承認しても、更新されるのは 1 回だけ。
- (b) `runs.submitTask(依頼者, "request:<requestId>", title)` で Run を作る。キーは冪等キーなので、(b) を何度呼んでも同じ Run が返る。
- (c) 条件付き UPDATE で `run_id` を設定する(`run_id IS NULL` のときだけ)。監査行 `RUN_LINKED` を同じトランザクションで書く。

(b) か (c) が失敗すると、`APPROVED` で `run_id` が NULL の行が残る。この行に対する再度の承認は (b) と (c) だけを行う(応答は 200)。この回復は、別の承認者でも同じ依頼者でない者なら行える。

採らなかった案: (a) から (c) を 1 つのトランザクションにする。`submitTask` は自分のトランザクションを開いて Run と監査を書くため、呼び出し側のトランザクションに参加させるには `submitTask` の書き換えが要る。今回は既存の冪等性に頼る。

既知の限界: 依頼者が Task API で `request:` で始まるキーを使うと、(b) の冪等キーと衝突しうる。そこで Task API は `request:` で始まるキーを 400 で拒否する(予約の接頭辞)。ほかの投入口(MCP、自己開発の API)は、この予約をまだ持たない。衝突は依頼者自身の依頼にしか影響しないが、その場合 (b) は 409 になり、依頼は `APPROVED` で `run_id` が NULL のまま残る。その行は監査に見える。解消は手作業になる(未決事項)。

### 4. 権限

- `Permission` に 2 つを足す。`REQUEST_SUBMIT`: ADMIN、OPERATOR、AI。`REQUEST_DECIDE`: ADMIN、APPROVER。
- `OPERATOR_PERMISSIONS` に `REQUEST_SUBMIT` を足す(ADMIN と OPERATOR に入る)。`ADMIN` と `APPROVER` に `REQUEST_DECIDE` を足す。`AI` に `REQUEST_SUBMIT` を足す。
- OFF(既存の規則)では、root approver(`KIVRA_SELFDEV_ROOT_APPROVER`)だけが `REQUEST_DECIDE` を持つ。これは停止要求の承認(`STOP_DECIDE`)と同じ扱いである。OFF の既存の判定は変わらない。
- 依頼者であることは、`REQUEST_DECIDE` を持つ者でも承認できない理由になる(§2 の 403)。AI は決裁できない(`REQUEST_DECIDE` を持たない)。
- ルートの一覧は `RolesCoverageArchitectureTest.ROUTE_PERMISSIONS` に入れる。各ハンドラーは、最初に名前を挙げる `Permission` がその行の権限になるように書く。
- 表の検査は `PermissionsTest` の表に 2 行を足す。

### 5. 監査

- 既存の監査は Run ごとの連鎖(`AuditRepository`、`audit_events`)である。依頼は、承認されるまで Run を持たず、却下や取消の依頼は Run を持たないままになる。そのため、依頼の監査は持たない。V23 の `selfdev_order_events` と同じく、依頼専用の追記だけの表 `task_request_events` に書く。
- 行の種類は `REQUESTED`、`APPROVED`、`REJECTED`、`CANCELLED`、`RUN_LINKED` の固定の 5 つ。`actor_ref` は承認者または依頼者のオーナーキーの SHA-256 先頭 12 桁。自由記述は持たない。`run_id` は `RUN_LINKED` のときだけ入る。
- 運用者のログ行: `task request action: action=APPROVED actor=<actorRef> requestId=<id> result=...`。オーナーキー、title、理由は出さない。

### 6. データベース(V62、expand のみ)

- `task_requests`: `request_id` CHAR(26) PK、`project_id` CHAR(26) NOT NULL(FK は `projects`)、`requested_by` VARCHAR(255)、`idempotency_key` VARCHAR(128)、`title` VARCHAR(200)、`state` VARCHAR(16)、`decided_by` VARCHAR(255) NULL、`decided_at` DATETIME(6) NULL、`decision_note` VARCHAR(500) NULL、`run_id` CHAR(26) NULL(FK は `development_runs`)、`created_at` DATETIME(6)。
  - `UNIQUE (requested_by, idempotency_key)`。索引 `(project_id, state, created_at)`。
  - `CHECK state IN (...)`。
  - `CHECK` 決定の整合: `PENDING` と `CANCELLED` 以外の状態は `decided_by` が NOT NULL。
  - `CHECK (decided_by IS NULL OR decided_by <> requested_by)`(自己承認の禁止)。
  - `CHECK (run_id IS NULL OR state = 'APPROVED')`。
- `task_request_events`: `event_id` CHAR(26) PK、`request_id` CHAR(26) FK、`kind` VARCHAR(16) の CHECK、`actor_ref` CHAR(12)、`run_id` CHAR(26) NULL、`occurred_at` DATETIME(6)。アプリは INSERT だけを行う。

### 7. 認証と入力

- 認証は Task API と同じ `TaskApiIdentity`。未認証は 401。
- ルートは `configureTaskRequestRoutes` として追加し、`Application.kt` で Task API と同じ条件(`taskApiIdentityWithSession != null`)で登録する。
- Task API(`POST /api/tasks`)は、`request:` で始まる `idempotencyKey` を 400 で拒否する(§3)。

## 結果

- 依頼者は Run を直接作らず、承認を経て作る経路を持つ。承認者は依頼者の title だけを見て決める。
- 承認の結果は、(a) の時点で確定する。Run の作成が遅れても、決定は取り消されない。
- 既存の Task API の挙動は、`request:` キーの拒否を除いて変わらない。OFF の既存の判定も変わらない。
- マイグレーションは expand のみ。既存の表には触れない。

## 後続(この ADR では実装しない)

- Compose UI(依頼の一覧と承認ボタン)、MCP ツール(依頼の作成と一覧)、チャットツール(依頼の提案)。
- プロジェクトへの所属と読み取り範囲(ADR 0022 の PR-D に倣う)。
- 通知(承認待ちの依頼の知らせ)。

## 未決事項

- 承認待ちの依頼の件数上限。依頼者ごとに上限を置くか。
- `request:` の予約を、ほかの投入口(MCP、自己開発の API、`SelfDevGoalRoutes`)にも広げるか。現状は Task API だけ。
- 「`APPROVED` で `run_id` が NULL のまま」の行を、運用者が再実行する手段(今は同じ承認の再送だけ)。
