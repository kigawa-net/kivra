# ADR 0024: order に assignee を割り当てる(assign 機能)

- ステータス: 提案(Proposed)。運用者の承認待ち。承認まで実装は始めない(この PR は文書だけ)
- 日付: 2026-11-17
- 関連: ADR 0015(order)、ADR 0020(担当とロール。本 ADR の用語整理を参照)、ADR 0022(プロジェクトのスコープ。`project_members`)、ADR 0023(プロジェクトチャット。`order_create_child`)。V61(本 ADR の `selfdev_order_assignments`)

## 背景

- order は作業の単位である。assignee(担当。ADR 0020)は権限の主体である。両者は別のものである。
- 両者は `project_id` を経由して間接的に関わる(order はプロジェクトに属し、assignee は `project_members` でプロジェクトに属す)。しかし「この order を、誰が担当するか」を表す、直接の紐付けは無い。
- 運用者は、order を作るときや作った後に、担当を決めたい。「order と assignee を 1 つの概念に合わせる」のではなく、運用者の決定(2026-11-17)で、**assign という新しい機能**を設ける。

## 決定

order に assignee を割り当てる機能として、**assign** を新設する。order と assignee のエンティティは変えない。紐付けだけを別に持つ。

### 1. DB(Flyway V61、expand のみ。既存の移行は編集しない)

別テーブル `selfdev_order_assignments`。`selfdev_orders` と `selfdev_assignees` には列を足さない。追記専用の履歴とし、現在の担当は「`unassigned_at` が NULL の行」である。

DDL のスケッチ(実装 PR で確定する。型や索引は実装で調整してよい)。

```sql
CREATE TABLE selfdev_order_assignments (
  assignment_id  CHAR(26)     NOT NULL PRIMARY KEY,   -- ULID
  order_id       CHAR(26)     NOT NULL,
  assignee_id    CHAR(26)     NOT NULL,
  assigned_by    CHAR(12)     NOT NULL,               -- 指定した主体の参照名(actor_ref)。owner key は入れない
  assigned_at    DATETIME(6)  NOT NULL,               -- 「いつから」
  unassigned_at  DATETIME(6)  NULL,                   -- 解除・変更の時刻。NULL は現在の担当
  active_slot    TINYINT      NULL,                   -- 現在の担当の行だけ 1。UNIQUE(order_id, active_slot)で、現在の担当は 1 行まで
  CONSTRAINT fk_order_assignments_order    FOREIGN KEY (order_id)    REFERENCES selfdev_orders (order_id),
  CONSTRAINT fk_order_assignments_assignee FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id),
  CONSTRAINT uk_order_assignments_active   UNIQUE (order_id, active_slot),
  CONSTRAINT ck_order_assignments_slot     CHECK ((unassigned_at IS NULL AND active_slot = 1) OR (unassigned_at IS NOT NULL AND active_slot IS NULL))
);
CREATE INDEX ix_order_assignments_assignee ON selfdev_order_assignments (assignee_id, unassigned_at);
```

- 担当の変更は、同じ transaction で、現在の行に `unassigned_at` を入れ(`active_slot` を NULL にする)、新しい行を足す。担当の解除は、前半だけである。
- 履歴の行は、更新も削除もしない(`unassigned_at` と `active_slot` を除く)。

### 2. ドメイン(`OrderAssignment`)

`OrderAssignment` の責任は、次の 4 つだけである。**どの order を、どの assignee が、いつから、誰の指定で**担当するか。

- 持つもの: `orderId`、`assigneeId`、`assignedAt`、`assignedBy`(と、解除の時刻)。
- 持たないもの: order の状態や進行(ADR 0015 の order の責任)、assignee のロールや権限(ADR 0020 の責任)、通知、承認の判断。
- 担当は、order の実行(周の開始、承認)の**権限を変えない**。権限は、これまでどおり ADR 0020 のロールで決まる(担当は情報であり、認可の根拠にしない)。

### 3. API(REST)

| 操作 | メソッドとパス(案。プロジェクト配下。ADR 0022 に揃える) | 権限 |
|---|---|---|
| 作成時の指定 | order 作成のリクエストに任意の `assignee_id` を足す | `ORDER_MANAGE` と `ORDER_ASSIGN` |
| 担当の変更 | `PUT /api/projects/{projectId}/orders/{orderId}/assignee` | `ORDER_ASSIGN` |
| 担当の解除 | `DELETE /api/projects/{projectId}/orders/{orderId}/assignee` | `ORDER_ASSIGN` |
| 履歴の取得 | `GET /api/projects/{projectId}/orders/{orderId}/assignments` | `READ` |
| 一覧の絞り込み | order の一覧に `?assignee_id=`(と、未担当の `?unassigned=true`) | `READ` |

- order の一覧と詳細の応答に、現在の `assigneeId` と `assignedAt` を足す(担当なしは null)。
- 変更と解除は、競合に備えて現在の行を `FOR UPDATE` で読んでから行う。同じ担当への変更は、何もしない(成功を返す)。

### 4. MCP とチャット

- **MCP**: 読み取りツール(`kivra_order_*`)の応答に `assigneeId` と `assignedAt` を足す。変更系のツールは、MVP では作らない。
- **チャット**: `order_create_child`(ADR 0023 追補 6)に、任意の `assignee_id` を足す。検証は REST と同じ(§6)。チャットの AI が、担当の**変更**を行うツールは作らない。

### 5. UI(Compose UI)

- `OrderCreateCard` に、担当のドロップダウンを足す(選べるのは、§6 を満たすプロジェクトのメンバーだけ。既定は未担当)。
- `OrdersScreen` で、担当を表示し、`ORDER_ASSIGN` を持つ運用者は変更・解除できる。表示名は `textContent` として表示する(ADR 0020 §3)。

### 6. 権限と検証

- 権限 `ORDER_ASSIGN` を新設する。ロールは OPERATOR と ADMIN に付ける(APPROVER、AI、VIEWER には付けない)。権限の表(ADR 0020 §2)にも、1 行足す。アーキテクチャテスト(`RolesCoverageArchitectureTest`)の対象にする。
- 割り当てるとき、サーバーは次をすべて検証する。1 つでも満たさなければ、拒否する(フェイルクローズ)。
  1. assignee が `ACTIVE` である(`DISABLED` は不可)。
  2. assignee が、その order のプロジェクトの `project_members` に属する。
  3. assignee のロールが `AI` ではない(**MVP では AI を除外**。AI は担当にならない)。
- 作成時の指定と、チャットの `order_create_child` でも、同じ検証を使う。検証の実装は 1 か所にまとめる。
- ログとイベントに、owner key と表示名は出さない(`assignee_id` と参照名だけ)。ADR 0020 §3 に従う。

### 7. ADR 0020 との用語整理

- **assignee** は、ADR 0020 の「担当」のままである。権限の主体(ロールを持つ者)であり、本 ADR で意味を変えない。
- **assign** は、「order に assignee を紐づける行為」を指す。assignment(`OrderAssignment`)は、その紐付けの記録である。
- 「担当」という日本語は、ADR 0020 では assignee を指す。本 ADR では、混乱を避けるため、紐付けの意味では「assign(割り当て)」と書き、「担当する」は assignee が order を受け持つ状態の説明にだけ使う。

## 非決定事項(この ADR では決めない)

- **複数担当**: 1 つの order に複数の assignee を付けること。MVP は 1 人まで(`active_slot` の UNIQUE)。
- **担当による通知**: 割り当てられた assignee への通知。
- **子 order への継承**: 親 order の担当を、子 order の既定にするか。MVP では継承しない(子は、指定がなければ未担当)。
- **MCP とチャットでの担当の変更**: 変更系ツールの追加。MVP では作らない。
- AI を担当にすること(MVP では除外。解禁する場合は別の ADR)。
- 担当を認可の根拠にすること(§2 のとおり、MVP では情報だけ)。

## MVP と拡張の区切り

- **MVP**: V61、`OrderAssignment`、REST(作成時の指定、変更・解除、履歴、一覧の絞り込み)、MCP の読み取りの応答、`order_create_child` の `assignee_id`、UI(ドロップダウンと表示)、`ORDER_ASSIGN`、§6 の検証。
- **拡張**(別の PR・ADR): 複数担当、通知、子 order への継承、MCP とチャットでの変更、AI の担当、担当による絞り込みの画面の強化。

## 影響を受けるファイル(予定。実装 PR で確定する)

- 新規: `kivra-app/src/main/resources/db/migration/V61__selfdev_order_assignments.sql`、`OrderAssignment` のドメインとリポジトリ(`kivra-core` と `kivra-app`)、割り当ての検証のサービス、REST のルート。
- 変更: `auth/Permissions.kt`(`ORDER_ASSIGN`、OPERATOR と ADMIN)、`routes/SelfDevOrderRoutes.kt`(作成の入力、一覧の絞り込み、応答)、MCP の order ツール(応答の項目)、`chat/ProjectChatReplier.kt` と `ChatToolExecutor`(`order_create_child` の `assignee_id`)。
- UI: `kivra-compose-ui` の `OrderCreateCard`、`OrdersScreen`、API クライアントとモデル。
- 文書: ADR 0020(権限の表に `ORDER_ASSIGN` の 1 行。追補)。
- テスト: 検証(ACTIVE、メンバー、AI の除外)、変更と解除の履歴、権限(`ORDER_ASSIGN` の有無)、アーキテクチャテストの更新。

## 結果

- order と assignee の関係が明示され、「誰がどの order を担当するか」を、画面・API・チャットで扱える。
- 紐付けを別テーブルにしたので、既存の表は変わらない(expand のみ)。履歴が残り、「いつから、誰の指定で」を追える。
- 担当は情報であり、認可を変えない。権限の境界(ADR 0020)は、そのまま保たれる。

追記(2026-11-25): V61 は expand のみだが、ADR 0022 の project scope のゲート(V52 の保留)が解除されていることが前提である。保留中は V52 以降がすべて適用されないため、`selfdev_order_assignments` が無く、order の一覧・詳細が失敗する。詳細と復旧は ADR 0022 の追補(2026-11-25)と runbook 4.20 を参照する。
