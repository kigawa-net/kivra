# ADR 0020: 「担当」とロールで権限を管理する(認証と認可の分離。段階導入、既定 off)

- ステータス: 提案(Proposed)。実装は PR 1〜7 で段階的に行う(この PR 0 は文書だけ)。PR 7(k8s と Keycloak の変更)は、ユーザーの承認と作業が要る
- 日付: 2026-10-24
- 関連: ADR 0011(オートパイロット)、ADR 0013(MCP サーバー。トークンの scope)、ADR 0015(order。§4.1 と §4.6 の承認者は、この ADR で複数人にする。追補を参照)、ADR 0019(MCP の Goal ツール)、ADR 0016(記憶)

## 背景

### 1. 現状(事実)

- 権限は、Keycloak のロール `admin-panel:admin`(1 つだけ)が、**認証と認可を兼ねている**。AI 用の別ユーザーを作っても、このロールが必須で、admin-panel の強い権限(サーバーの再起動・drain、ユーザーの削除など)まで持ってしまう。
- AI の制限は、環境変数 `KIVRA_SELFDEV_AI_PRINCIPAL` の**拒否リスト**(owner key の文字列一致)だけである。新しいルートは、チェックを足さない限り AI にも開く。
- 実際に、予算の解除(`BudgetAdminRoutes`)、run の解除(`SelfDevAdminRoutes`)、Task API、`kivra_selfdev_start` には、AI のチェックが無い。
- 承認者は、環境変数 `KIVRA_SELFDEV_ROOT_APPROVER` の 1 人だけである(ADR 0015 §4.1)。

### 2. 目的

認証(本人確認)と認可(何をしてよいか)を分け、kivra の DB に「担当」を持って、権限を管理できるようにする。AI 用ユーザーが `admin-panel:admin` を持たずに、読み取りと停止要求などを、**許可リスト方式**で行えるようにする。これで、周の自動開始(実装済み、既定 off)の dev 検証(AI の主体による停止要求、別の主体の承認)が、安全にできる。

## 決定

ユーザーの決定(2026-10-24)に基づく。

1. **範囲と正本**: 主体ごとのロール管理。正本は kivra の DB。Keycloak は本人確認だけを担う。
2. **担当は、Keycloak のユーザーとは別のエンティティ**。担当(表示名とロール)を作り、Keycloak の `sub`(owner key)を担当に紐付ける。
3. **導入は off / shadow / enforce の 3 段**(既定 off)。
4. **承認者はロール(複数人)**。ADR 0015 の「承認者は 1 人」を変更する(追補を参照)。
5. **AI は許可リスト方式**。費用がかかる操作は一部だけ許す(権限表)。予算の解除・上書き系は、拒否のまま。
6. **ADMIN にも停止の承認・却下(STOP_DECIDE)を持たせる**(ユーザーの承認、2026-10-24)。ADMIN は人だけの上位ロールである。理由: 1 つの owner key は 1 つの担当にしか属せない(UNIQUE)ので、ルートの承認者(環境変数 `KIVRA_SELFDEV_ROOT_APPROVER` の主体)を ADMIN にすると承認できず、APPROVER にすると担当の管理ができない。root approver は両方を要する。ADMIN は担当を管理でき、自分に APPROVER を付ける経路が元からあるので、承認を持たせても新しい権限昇格の経路は増えない。「要求者 ≠ 承認者」は変えない。AI には承認を与えない。APPROVER は、承認だけを任せたい人向けのロールとして残す。

7. **権限の細分化: `READ_TEXT` と `GOAL_STOP`**(PR 3a)。理由: 現状の AI は、記憶の閲覧、Goal の題名の取得、Goal の停止を、拒否されている。承認済みの表(AI は読み取りと order の停止要求だけ)の「読み取り」と「停止要求」を、そのまま写すと、enforce にした時に AI の権限が**広がってしまう**。そこで、運用者の文章を含む読み取りを `READ_TEXT`、Goal の停止を `GOAL_STOP` として分け、ADMIN と OPERATOR だけに付ける(APPROVER、AI、VIEWER は持たない)。`READ`(order の一覧・詳細、run など)と、order の停止要求(`STOP_REQUEST`)は、表のままである。off のときの AI の権限(`legacyAccess`)は、今の挙動のまま(`READ_TEXT` と `GOAL_STOP` を持たない)。これは「現状の AI の拒否を保つための細分化」であり、AI に新しい権限を与えるものではない。

実装状況: PR 3c で、これまで権限の検査が無かった REST のルート(Task API、`/api/selfdev/*`、予算と run の解除、run の一覧と詳細、実行の監視)にも、同じ表(READ、SPEND_START、BUDGET_OVERRIDE)を適用した。`off` では AI にも開いたままで、`enforce` でだけ、AI の予算の解除と run の解除を閉じる。

### 1. データモデル(Flyway V33、expand のみ。既存の移行は編集しない)

DDL のスケッチ(実装 PR 1 で確定する。型や索引は実装で調整してよい)。

```sql
CREATE TABLE selfdev_assignees (
  assignee_id  CHAR(26)     NOT NULL PRIMARY KEY,           -- ULID
  display_name VARCHAR(64)  NOT NULL,                        -- NFC。制御文字と bidi 文字を含めない(アプリで検査)
  role         VARCHAR(16)  NOT NULL,
  state        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  version      BIGINT       NOT NULL DEFAULT 0,              -- CAS 用
  created_by   VARCHAR(32)  NOT NULL,                        -- actor_ref(12 hex)または 'bootstrap'
  created_at   TIMESTAMP(6) NOT NULL,
  updated_at   TIMESTAMP(6) NOT NULL,
  CONSTRAINT selfdev_assignees_role_chk  CHECK (role  IN ('ADMIN','OPERATOR','APPROVER','AI','VIEWER')),
  CONSTRAINT selfdev_assignees_state_chk CHECK (state IN ('ACTIVE','DISABLED'))
);

CREATE TABLE selfdev_assignee_members (
  owner_key    VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL UNIQUE,  -- 1 つの owner key は 1 つの担当だけ
  member_ref   CHAR(16)     NOT NULL UNIQUE,                 -- SHA-256 の先頭 16 hex(ログ・イベント用の参照名)
  assignee_id  CHAR(26)     NOT NULL,
  created_at   TIMESTAMP(6) NOT NULL,
  CONSTRAINT selfdev_assignee_members_fk FOREIGN KEY (assignee_id) REFERENCES selfdev_assignees (assignee_id)
);

-- 追記専用(selfdev_order_events の流儀)。表示名と owner key は入れない
CREATE TABLE selfdev_assignee_events (
  event_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind         VARCHAR(16)  NOT NULL,  -- CREATED / ROLE_CHANGED / DISABLED / ENABLED / MEMBER_BOUND / MEMBER_UNBOUND
  actor_ref    CHAR(12)     NOT NULL,  -- 操作した主体の参照名(初期化は 'bootstrap')
  assignee_id  CHAR(26)     NOT NULL,
  from_role    VARCHAR(16)  NULL,
  to_role      VARCHAR(16)  NULL,
  created_at   TIMESTAMP(6) NOT NULL
);
```

### 2. ロールと権限(権限の表はコードで固定。DB が持つのは、担当とロールと紐付けだけ)

| 操作 | ADMIN | OPERATOR | APPROVER | AI | VIEWER |
|---|---|---|---|---|---|
| 読み取り(order、Goal、run、記憶、`/me`、MCP の読み取り) | ○ | ○ | ○ | ○ | ○ |
| 運用者の文章を含む読み取り(`READ_TEXT`: Goal の題名、記憶の閲覧) | ○ | ○ | - | - | - |
| 実行中の Goal の停止(`GOAL_STOP`: `kivra_goal_stop`、Goal の停止ルート) | ○ | ○ | - | - | - |
| order の作成、周の開始、一時停止、再開、設定の変更 | ○ | ○ | - | - | - |
| 停止要求(REST と `kivra_goal_stop`) | ○ | ○ | ○ | ○ | - |
| 停止の承認・却下(人のみ。AI は不可) | ○(人のみ) | - | ○ | - | - |
| Goal の投入、計画・タスクの承認、記憶の書き込み | ○ | ○ | - | - | - |
| `kivra_selfdev_start`、Task API(費用がかかるが、既存の予算・上限に従う) | ○ | ○ | - | ○(一部許可) | - |
| 予算の解除・為替(`BudgetAdminRoutes`)、run の解除(`SelfDevAdminRoutes`) | ○ | ○ | - | **-** | - |
| 担当の管理(作成、ロール変更、紐付け、無効化) | ○(人のみ) | - | - | - | - |

- AI の「一部許可」の範囲は、`kivra_selfdev_start` と Task API の投入・取り消しとする。**予算・run の解除は、AI に許さない**(上書き系のため)。実装時にユーザーへ確認する(未決事項 U-1)。
- MCP は、さらにトークンの scope(`kivra.runs.read`、`kivra.selfdev.start`、`kivra.selfdev.stop`)との**積集合**にする。ロールが許しても scope が無ければ拒否、scope があってもロールが許さなければ拒否。

### 3. 不変条件(ADR 0015 §4.6 / §8 を維持)

- 承認は、ADMIN または APPROVER の担当に属する**人**だけ。AI は承認できない。ルートの停止は、要求者と承認者が別の owner key(DB の CHECK とアプリの検査は、そのまま)。
- 環境変数 `KIVRA_SELFDEV_AI_PRINCIPAL` の owner key は、DB の内容にかかわらず、**常に AI として扱う**。DB に別のロールがあれば、エラーを記録して、AI のままにする。AI と承認者が同じ owner key なら、起動に失敗する。
- APPROVER の付与は、ADMIN(人)だけ。AI の担当は、担当を管理できない(コードで固定)。1 つの owner key は 1 つの担当にだけ属する(AI と APPROVER の兼任は不可)。
- **最後の ADMIN の削除・降格・無効化は不可**。自分自身の降格・無効化も不可。ADMIN の行を `FOR UPDATE` で読み、同じ transaction で変更する。
- 権限が引けない(DB の失敗、紐付けなし)ときは、拒否する(フェイルクローズ)。ロールは、リクエストごとに 1 回引き、**キャッシュしない**(無効化が即時に効く)。
- owner key と表示名は、ログとイベントに出さない(ハッシュの参照名だけ)。表示名は UI で `textContent` として表示する。

### 4. 認証と認可の分離

- `OidcJwtVerifier`(`auth/OidcJwt.kt`)と `McpJwtVerifier`(`mcp/McpJwtVerifier.kt`)が、任意の環境変数 `KIVRA_OIDC_MEMBER_ROLE`(`<client>:<role>`。例 `kivra:member`)も、認証として受け付ける。`admin-panel:admin` は、従来どおり(互換)。**agent ロールの拒否は、常に最初に行う**。
- member ロールだけのトークンは、担当への紐付けがなければ、権限なし(フェイルクローズ)。`admin-panel:admin` で担当に紐付けがない主体は、移行期間は OPERATOR(`KIVRA_SELFDEV_ROLES_DEFAULT=operator`)、最終的に `none`。
- 運用者のルートは、新しい `authorized(Permission)` だけを入口にする。直接 `authenticate` を呼ぶと、アーキテクチャテストが落ちる。

### 5. 導入方式と環境変数

| 環境変数 | 既定 | 意味 |
|---|---|---|
| `KIVRA_SELFDEV_ROLES_MODE` | `off` | `off` は今と完全に同じ(AI の拒否リストも、そのまま)。`shadow` は拒否せず、「拒否したはず」を固定コードでログに出す。`enforce` は拒否する |
| `KIVRA_SELFDEV_ROLES_DEFAULT` | `operator`(移行期間。最終的に `none`) | `admin-panel:admin` で担当に紐付けがない主体の扱い |
| `KIVRA_OIDC_MEMBER_ROLE` | 未設定 | 認証として受け付ける member ロール(`<client>:<role>`)。未設定なら従来どおり `admin-panel:admin` だけ |
| `KIVRA_SELFDEV_ROLES_RECOVER_ADMIN` | 未設定(一時的な復旧フラグ) | ADMIN を失ったとき、起動時の初期化の主体だけを再有効化する。復旧後は外す |

- 既存の `KIVRA_SELFDEV_ROOT_APPROVER`(承認者の初期化の入力に降格)と `KIVRA_SELFDEV_AI_PRINCIPAL`(常に AI。全期間を通じて維持)は、そのまま使う。
- 起動時の初期化: `KIVRA_SELFDEV_ROOT_APPROVER` → 担当(ADMIN)と紐付けを、**行がないときだけ**作る(降格を勝手に戻さない)。`KIVRA_SELFDEV_AI_PRINCIPAL` → AI の担当と紐付けを、行がないときだけ作る。
- `none` 認証モードは、ロールが有効(`shadow` / `enforce`)のとき、dev 用のフラグがなければ、起動に失敗する。

## 脅威の検討

| 脅威 | 緩和 |
|---|---|
| 権限昇格(AI や下位ロールが、自分や他者のロールを上げる) | 担当の管理は ADMIN(人)だけ。AI は管理不可(コードで固定)。APPROVER の付与は ADMIN の人だけ。ADMIN の承認権限は、人に限り、要求者 ≠ 承認者の検査と AI の常時拒否に従う(昇格の経路は増えない。決定 6)。1 owner key 1 担当。権限の表はコードで固定し、DB は編集しても表を変えられない |
| 最後の ADMIN を失う | 最後の ADMIN の削除・降格・無効化と、自分自身の降格・無効化は不可。ADMIN の行を `FOR UPDATE` で同じ transaction。起動時の初期化と復旧フラグ(runbook) |
| TOCTOU(権限を引いた後、無効化されるまでの隙) | ロールはリクエストごとに 1 回引く。キャッシュしない。担当の変更は CAS(`version`)。無効化は次のリクエストから効く |
| `sub` の変更・再利用(別人が同じ owner key になる、紐付けの誤り) | owner key は `iss` と `sub` から作る(`oidcOwnerKey`)。`utf8mb4_bin` で UNIQUE。紐付けの変更はイベントに残す。紐付けは ADMIN が `sub` を確認して行う(U-2) |
| キャッシュの古さ | キャッシュしない。DB の失敗は拒否(フェイルクローズ) |
| MCP の scope とロールの食い違い | scope とロールの**積集合**。どちらかが欠ければ拒否 |
| agent トークン(エージェント API 用の主体)の流用 | agent ロールの拒否を、member ロールの受け入れより前に、常に最初に行う |
| `none` 認証モード(認証なし)で、ロールが意味を失う | ロールが有効なとき、dev 用のフラグがなければ起動に失敗する |
| ログ・表示名への注入(制御文字、bidi 文字、HTML) | 表示名は ≤64、NFC、制御文字・bidi 文字なし。UI は `textContent`。ログとイベントに表示名と owner key を出さない |
| 新しいルートがチェックを持たずに AI に開く | `authorized(Permission)` だけを入口にし、直接の `authenticate` はアーキテクチャテストで失敗させる。全ルート・全 MCP ツールを全ロールで確認するテスト |

## 段階的な導入(PR の分割と、各段の進む条件)

各 PR は小さく、フラグの背後に置く。マージ前に、diff と CI を確認する。

| PR | 内容 | 進む条件 |
|---|---|---|
| 0 | この ADR、runbook、ADR 0015 の追補(文書のみ) | ユーザーの承認 |
| 1 | V33 と、担当のリポジトリ(CAS、最後の ADMIN の保護、初期化)。挙動は変えない | `MigrationChainTest`、`MigrationExpandOnlyTest`、H2 と MariaDB のテスト。最後の ADMIN の保護の同時実行テスト |
| 2 | `Permissions.kt`(`Role`、`Permission`、固定の表、`PermissionService`)、設定(`ROLES_MODE`、`ROLES_DEFAULT`)。off は現状を完全に再現 | ロール × 権限の表のテスト。off で既存のテストが無変更で通る |
| 3 | ルートと MCP の置き換え。これまでチェックがないルート(予算・run の解除、Task API、`kivra_selfdev_start`)にも足す | 全ルート・全 MCP ツールを全ロールで確認するテスト(新しいルートでチェックがなければビルドが落ちる)。不変条件のテスト |
| 4 | 認証の分離(member ロール)。共通の `hasRole` の抽出 | agent トークンの拒否、member だけのトークンの扱いのテスト |
| 5 | 担当の管理 API と `GET /api/selfdev/me`(ロールと権限名、参照名だけ) | ADMIN の人だけが操作できること。ログに owner key と表示名が出ないこと |
| 6 | UI(`/me` で表示を制御。従来の「401/403/404/503 で隠す」はフォールバック)、ADMIN 用の管理画面 | `ui-behavior.test.js` |
| 7 | 運用(ユーザーの承認と作業): Keycloak に member ロールを作る、AI ユーザーに付与して `admin-panel:admin` を外す、oauth2-proxy の `--allowed-role` を繰り返し指定(`/ui` に AI が入るため)、shadow → enforce、既定を `none` へ | dev の手動検証(下記)。shadow のログで「拒否したはず」が想定どおりであること |

dev の手動検証: AI 用の Keycloak ユーザー(member ロールだけ)で、プライベートウィンドウから `/ui` にログインする。`/me` がロール AI を返す。承認・作成・解除は 403。読み取りと停止要求は可能。別の主体(運用者)の承認が通る。AI の担当を無効化すると、次のリクエストから拒否される。最後の ADMIN の降格は拒否される。

## ロールバック

- `KIVRA_SELFDEV_ROLES_MODE=off`(環境変数と再起動)で、今の挙動に戻る。AI の拒否リスト(`KIVRA_SELFDEV_AI_PRINCIPAL`)は、全期間を通じて維持する。
- member ロールの変更を戻すときは、AI ユーザーに `admin-panel:admin` を戻す(従来の状態)。
- V33 は expand のみで、戻さない(アプリを戻しても、新しい表は無視されるだけ)。
- ADMIN を失った場合の復旧: 起動時の初期化が、行がなければ作る。行が無効なら、起動時に大きくエラーを出し、一時的な復旧フラグ `KIVRA_SELFDEV_ROLES_RECOVER_ADMIN` で、初期化した主体だけを再有効化する(手順は runbook)。

## 未決の点

- **U-1 AI の「一部許可」の範囲**: 権限表のとおり(`kivra_selfdev_start` と Task API の投入・取り消しだけ。予算・run の解除は拒否)でよいか。実装時にユーザーへ確認する。
- **U-2 `sub` を担当に紐付ける入力方法**: 管理者が Keycloak の `sub` を貼る方式を想定している。他の方式(初回ログインの申請を管理者が承認する、など)にするかを確認する。
- **U-3 PR 7 の k8s と Keycloak の変更**: `kigawa-net-k8s`(`kivra/dev/kivra.yaml`、`kivra-oauth2-proxy.yaml`)と Keycloak の変更は、すべて、ユーザーの承認と作業が要る。この ADR の PR では行わない。
- **U-4 ADR 0015 の追補の文面**: 承認者の複数化の書き方の確認(ADR 0015 の末尾の追補)。

## 確認できなかったこと

- dev の Keycloak の実際の realm の設定(member ロールの作成、oauth2-proxy の `--allowed-role` の複数指定の挙動)。PR 7 で確認する。
- 本文の DDL は、まだ実装していないスケッチである。型や制約は、PR 1 で、MariaDB と H2 の両方で確認する。
