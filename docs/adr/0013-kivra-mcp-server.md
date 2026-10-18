# ADR 0013: kivra の自己開発を、認証つきの Streamable HTTP MCP サーバーとして公開する(Control Plane に限定し、Run の正本は増やさない)

- ステータス: 提案(Proposed)。ユーザーの承認待ち。承認まで実装は始めない
- 日付: 2026-10-18
- 関連: Issue #181(本 ADR の対象)、#157、#174、#56、#71、#112、#187(コードを変えるのは kivra-agent の経路)、#220、#229(参照の解決)、#223 / #224(監視)、kigawa-net/admin-panel の #200 / #236(先行事例)、kigawa01/k8s-system の #273(Keycloak の IaC)。ADR 0001(Run の正本)、ADR 0008、ADR 0009、ADR 0010(CLI 経路)、ADR 0011(監査とログの衛生)、ADR 0012(実行イベントと停止)

## 背景

- Issue #181 は、Claude Code や OpenCode などの MCP クライアントから、kivra の自己開発(Issue を指定して Run を始める、Run の状態を読む)を使えるようにすることを求めている。
- 既存の経路は次のとおり(`kivra-app/src/main/kotlin/net/kigawa/kivra/routes/SelfDevRoutes.kt` で確認)。
  - `POST /api/selfdev/tasks`: 投入。`title`(1〜200 文字)、`idempotencyKey`(1〜128 文字)、`body`(任意、8,000 文字以下)。同じ key で内容が違えば 409、同時に進む自己開発 Run が上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`、既定 1)に達すれば 429。
  - `GET /api/selfdev/runs/{id}` と `GET /api/selfdev/runs`: 自分の Run だけを返す。他人の Run と存在しない Run は同じ 404。
  - 認証は Task API の identity(`OidcJwtVerifier`)。所有者は検証済みの `iss` と `sub` から作る `<n>:<iss>:<sub>`(`oidcOwnerKey`)で、`email` は使わない(`auth/OidcJwt.kt`)。
- `title` が Issue の参照(`https://github.com/<owner>/<repo>/issues/<n>`、`#<n>`、`<owner>/<repo>#<n>`)のときは、kivra が対象リポジトリの Issue を読んで本文にする(#229、ADR 0010 追補 5。`selfdev/TaskReference.kt`)。
- 現在の dev の入口は `kivra-dev.kigawa.net` の oauth2-proxy の背後にあり、ブラウザの Cookie で認証する(`kivra/dev/ingress.yaml`、`docs/runbook-selfdev.md`)。MCP クライアントは Cookie を送らず、`Authorization: Bearer` を送るため、今の入口では届かない。
- エージェント用の経路は、同じホストで `/api/agent/` だけを oauth2-proxy を通さず kivra に渡す ingress(`kivra-agent-ingress`、HAProxy)で作られている(`kigawa-net/kigawa-net-k8s` の `kivra/dev/kivra-agent-ingress.yaml`、確認済み)。
- 先行事例は admin-panel の `/mcp`(`kigawa-net/admin-panel`、origin/main で確認)。MCP 専用の JWT 検証(`aud=https://admin.kigawa.net/mcp`、`server/.../RbacAuth.kt`)、`/.well-known/oauth-protected-resource`、未認証の `401` と `WWW-Authenticate: Bearer resource_metadata=...`、`protocolVersion` の既定値 `2025-06-18` を返す実装がある(版のネゴシエーションは確認していない)。Keycloak のクライアントは `kigawa01/k8s-system` の `keycloak/admin-panel-mcp-oauth-job.yaml` にあり、public client、PKCE(S256)、DCR を使わず、redirect は `localhost:8765` と claude.ai の callback だけ。この作業の E2E(Keycloak からのトークン取得と、クライアントからのブラウザログイン)は admin-panel #236 で未完了のため、**確認できていない**。
- #181 の 2026-10-17 のコメントでの方針は次のとおり。MCP は Control Plane(投入、取得、一覧、停止)に限る。コード修正は #187 の kivra-agent に委譲する。MCP のクライアントから Claude のログイン情報、環境変数、任意のコマンドを渡せないスキーマにする。書き込みは #174、#220、#157、#71 が揃うまで無効にする。
- 既存の Run 停止の HTTP 経路は、運用者向けには無い。`CodingRuntimeService.cancel` と `DevelopmentRunRepository.requestCancel` はあるが、HTTP の route は ADR 0012 §6 の計画であり、未実装(`routes/` に cancel の route は無い。確認済み)。
- Run の実行経過の読み取り API は ADR 0012 PR 3 で、まだ無い(`docs/adr/0012` の実装状況)。

## 決定

### 1. 役割と非目標

MCP サーバーは **Control Plane のアダプター**とする。新しい Run の正本、新しい状態機械、新しい実行経路を作らない。既存の `SelfDevRouteDeps` と同じ application service(`DevelopmentRunRepository` と既存の投入の経路)を呼ぶ。

- できること: 投入(`kivra_selfdev_start`)、1 件の取得(`kivra_run_get`)、一覧(`kivra_run_list`)。後の段階で、停止(`kivra_run_stop`)と実行の経過(`kivra_run_execution`)。
- できないこと(非目標):
  - コードの編集、コマンドの実行、ファイルの読み書き。実装は #187 の kivra-agent の経路だけが行う(ADR 0010)。
  - 認証情報、環境変数、Claude のログイン情報、任意のコマンドの受け渡し。入力のスキーマに、そのような欄を作らない。
  - マージ、デプロイ、ブランチの保護の変更。MCP にはマージの権限を与えない(ADR 0008 のとおり、マージは人間が行う)。
  - 予算の迂回。MCP の投入は既存の予算、許可リスト、同時 Run の上限を通る。
  - 独立した Run の正本。Run の状態は既存の `development_runs` だけが持つ(ADR 0001)。
- MCP から渡せる入力は、Issue の参照(URL または `#n`)、または title(と任意の本文)だけとする。本文は UNTRUSTED のデータとして扱う(§5、§9)。

### 2. プロトコル

- Streamable HTTP の MCP エンドポイントを `/mcp` に置く。HTTPS のみ。JSON-RPC 2.0。
- **ステートレス**とする。`Mcp-Session-Id` を発行しない。各 `POST` が Bearer トークンを持ち、サーバーは毎回検証する。これで、Pod の再起動や複数 Pod でも、セッションの固定(sticky)を要求しない。
- `initialize` で protocolVersion を**ネゴシエーション**する。サーバーが対応する版の一覧を持ち、クライアントの版が一覧にあればそれを返し、無ければ一覧の中で最も新しい版を返す。対応する版の一覧は、PR 1 の実装時に MCP 仕様で確認して決める。既定の候補は `2025-06-18`(admin-panel と同じ)。仕様で確認していない版は書かない。
- 通知(`id` が無い、または `notifications/*`)には `202 Accepted` を本文なしで返す。
- `GET /mcp` は `405 Method Not Allowed` とする。サーバーからの push のストリームは持たない(ステートレスのため)。
- admin-panel の実装が依存する挙動(`initialize` の既定値を省略しない、`explicitNulls=false`)は、kivra で独立に同じ作法を取る。**別リポジトリなので、コードは共有しない**(ライブラリの共通化はしない)。同じ作法に揃える。

### 3. ツール

| ツール | 種別 | 必要なスコープ | 入力 | 備考 |
|---|---|---|---|---|
| `kivra_run_list` | 読み取り | `kivra.runs.read` | `limit`(1〜50、既定 20。実装で 100 から 50 に変更)、`cursor`(任意) | 自分の Run だけ。`cursor` は新しい(現行の `listTasks` は件数の固定だけで、cursor が無い。PR 1 で足す) |
| `kivra_run_get` | 読み取り | `kivra.runs.read` | `run_id`(26 文字の ULID 形) | 他人の Run は 404 |
| `kivra_selfdev_start` | **書き込み** | `kivra.selfdev.start` | `title`(1〜200 文字、必須)、`body`(0〜8,000 文字、任意)、`idempotency_key`(1〜128 文字、**必須**) | 既定で無効(§5)。`title` に Issue の参照を置ける(#229) |
| `kivra_run_stop` | 書き込み | `kivra.selfdev.stop` | `run_id` | PR 5 まで**存在しない**(tools/list に出さない)。停止の安全な意味が検証されるまで無効 |
| `kivra_run_execution` | 読み取り | `kivra.runs.read` | `run_id` | ADR 0012 PR 3 の読み取り API の後。それまで出さない |

- `idempotency_key` は **クライアントが生成して必須**とする。サーバーが自動で作ると、リトライのたびに別の Run ができる。REST の `idempotencyKey` と同じ規則(`SubmitSelfDevTaskRequest`)。
- 出力は機械可読にする。MCP の `structuredContent` を正とし、`content` の text にも同じ JSON 文字列を入れる。フィールドは次のとおり。`run_id`、`state`、`stage`(段階)、`pr_url`(あれば)、`reason_code`(固定の文字列)。
- 偽の成功を返さない。`state` が `FAILED` や `UNKNOWN` のときは、そのまま返す。「PR ができた」は PR が実際に存在するときだけ言う(既存の `pullRequestOf` と同じ照合)。
- 理由コード(固定の列挙。値は PR 1 で確定する。候補): `UNAUTHENTICATED`、`FORBIDDEN_SCOPE`、`NOT_FOUND`、`INVALID_INPUT`、`CAPABILITY_UNAVAILABLE`(書き込みが無効、またはツールが未実装)、`KILL_SWITCH_OFF`(停止中)、`CONCURRENCY_LIMIT`(既存の 429 に当たる)、`IDEMPOTENCY_CONFLICT`(既存の 409 に当たる)、`RATE_LIMITED`、`OUTCOME_UNKNOWN`(結果が不明。同じ `idempotency_key` で再照会する)、`TASK_REFERENCE_UNRESOLVED`(#229 の失敗理由をそのまま使う)。

### 4. 認証と認可

- **Keycloak の OIDC**。`kigawa-net` realm のアクセストークン(RS256)を検証する。検証は既存の `OidcJwtVerifier` と同じ項目(署名、`iss`、`exp`、`nbf`、ロール)に加えて、次を行う。
  - **MCP 専用の audience**: `aud` に `https://kivra-dev.kigawa.net/mcp` を要求する(例。本番の値は未決)。REST 用の `kivra-dev` の audience とは別の値にする。`OidcJwtVerifier` は現在 `aud` を 1 つだけ検査する(`config.audience`)ので、MCP 用には別のインスタンスを作る(admin-panel の `RbacAuth.kt` と同じ方式)。
  - **スコープ**: `scope` クレームを読み、ツールごとに `kivra.runs.read`、`kivra.selfdev.start`、`kivra.selfdev.stop` のいずれかを要求する。**現在の `OidcJwtVerifier` にはスコープの検査が無い**ので、これは新しく作る部分である。
- **所有者**: `oidcOwnerKey(iss, sub)` で作る主体を使う。既存の `OperatorAuth` と同じ所有者の規則で、`email` は使わない。
- **エージェントの主体(kivra-agent)は 403**。エージェントのトークンは `azp=kivra-agent` と `resource_access.kivra-agent.roles` を持つ(`auth/AgentIdentity.kt`)。ただし、そのトークンは MCP の audience を持たないため、通常は audience の検査で **401** になる。明示的な 403 は、`OidcJwtVerifier` の `agentRole`(既存の `AgentRefused` の仕組み)で、MCP の audience を持つエージェントのトークンを拒否するための多層防御として置く。
- **クロスオーナーの読み取りは 404**(他人の Run と存在しない Run を区別しない)。
- **静的な共有トークンは使わない**。MCP のクライアントには、個人のトークンだけを OAuth で渡す。
- **認可の規則**: 認証、スコープ、ロール、所有者の 4 つを、すべてのツールの呼び出しで検査する。どれか 1 つでも欠けたら fail closed(拒否)にする。

### 5. 書き込みの保護

書き込み(`kivra_selfdev_start`)は、次の**すべて**が揃ったときだけ動く。

1. `KIVRA_MCP_ENABLED=true`(既定 off)。off のとき `/mcp` を登録しない。既存の `KIVRA_SELFDEV_ENABLED` と同じ方式で、off の間は他の MCP の設定を読まない。
2. `KIVRA_MCP_WRITE_ENABLED=true`(既定 off)。off のとき、`kivra_selfdev_start` は `tools/list` に出さず、呼ばれても `CAPABILITY_UNAVAILABLE` を返す。
3. `KIVRA_SELFDEV_ENABLED=true`(既存のキルスイッチ)。off のときは `KILL_SWITCH_OFF`。
4. トークンが `kivra.selfdev.start` のスコープを持つこと。
5. 既存の検査をそのまま通ること: 対象リポジトリは設定の 1 つ(`KIVRA_SELFDEV_TARGET_REPO`)に固定され、同時 Run の上限は `DevelopmentRunRepository.submitTask` が検査する(429 相当)。予算は投入の時点では検査されず、実行の段階の既存の判定に任せる(`submitTask` を読んで確認。ADR 0004)。MCP のために別の経路を作らない。
6. #174(durable admission と CAS)、#220(資格情報を隔離したログイン済み CLI の worker)、#157(Draft PR だけの E2E)、#71(Provider の Capability の証跡)が検証されていること。これは #181 のコメントの条件で、設定値では代えられない。

追加の制限:

- **レート制限**: 投入は所有者ごとに制限する(値は未決。暫定: 1 分に 5 件)。超えたら `RATE_LIMITED`。
- **ペイロードの上限**: `title` 200 文字、`body` 8,000 文字、`idempotency_key` 128 文字。超えた入力は、Run を作る前に `INVALID_INPUT` で拒否する。既存の検査(`validatedText`、`validatedBody`)をそのまま使う。
- **idempotency**: 同じ key で内容(`title` か `body`)が違えば `IDEMPOTENCY_CONFLICT`(既存の 409 に当たる)。同じ内容の再送は、元の Run をそのまま返す(重複の Run を作らない)。
- **書き込みの段階的な有効化**: dev では、最初に読み取りだけを有効にする(PR 1 と PR 3 の後)。書き込みは PR 4 の後、dev で `KIVRA_MCP_WRITE_ENABLED=true` を入れる。本番の既定は off のままにする。

### 6. MCP 固有の防御

- **Origin と Host の検証**(DNS リバインディング対策): `Host` は設定の許可リスト(`kivra-dev.kigawa.net`)に一致すること。`Origin` があるときは許可リストに一致すること。`Origin` の無いリクエスト(Claude Code などの非ブラウザのクライアント)は通す。既存の `requireJsonSameOrigin` は Cookie による CSRF のための検査で、MCP の Bearer には役割が違うため、MCP 用に別の検査を書く。
- **HTTPS のみ**。ingress の TLS で終端する(既存の `kivra-dev` と同じ)。
- **Content-Type** は `application/json` を要求する。`Accept` は仕様に従う。`application/json` を受け付けられない要求は `406` で返す(PR 1 で確定)。
- **ログと監査に本文を出さない**: `title`、`body`、`sub`、トークン、クレームの値、Issue の本文を出さない。出してよいのは、固定の理由コード、ツール名、Run ID、`owner/repo#n`、文字数(ADR 0011 §7.1 と ADR 0010 追補 5 の規則に従う)。
- **エラーの区別**(クライアントに見せる種類。内部の詳細は出さない):
  - 認証の失敗: HTTP `401` と `WWW-Authenticate: Bearer resource_metadata="..."`、本文は JSON-RPC の error。
  - 能力なし: `CAPABILITY_UNAVAILABLE`(書き込みが無効、またはツールが未実装)。
  - 停止中: `KILL_SWITCH_OFF`。
  - 結果不明: `OUTCOME_UNKNOWN`。同じ `idempotency_key` で再照会する手順を、結果に書く。
  - 権限や存在の不一致: `FORBIDDEN_SCOPE` と `NOT_FOUND`。
- **protected resource metadata**: `/.well-known/oauth-protected-resource` を公開し、`resource` は MCP の audience と一致させ、`authorization_servers` に Keycloak の issuer を書く(admin-panel と同じ形)。

### 7. ネットワーク経路

- 現在の `kivra-ingress`(`path: /`、oauth2-proxy の背後)は、MCP の Bearer を通さない。
- `/mcp` だけを、oauth2-proxy を通さない ingress に分ける。`kivra-agent-ingress` と同じ作法(同じホスト、`ingressClassName: haproxy`、パスを `/mcp` に限る、kivra のサービス `kivra-svc` へ直接渡す)。パスが長いほうが優先される点は、HAProxy の ingress の設定で**未確認**のため、PR 3 で dev のクラスタに対して確かめる。確かめられない場合は、別のホスト名を使う案(「未決の点」の U-5)に切り替える。
- ingress の変更は `kigawa-net/kigawa-net-k8s`(`kivra/dev/`)で行う。kivra 本体の変更ではない。
- `kivra-netpol.yaml` は、クラスタの CNI(Flannel)が NetworkPolicy を強制しないため、防御の根拠にしない(ADR 0010 と runbook の記述どおり。確認済み)。MCP の防御は、トークンの検証と §6 の検査で行う。

### 8. クライアント

- **Claude Code**: `claude mcp add --transport http kivra https://kivra-dev.kigawa.net/mcp` と、OAuth のブラウザログイン。Claude Code が OAuth の登録をどう扱うか(固定の client ID の指定、CIMD、DCR のどれを使うか)は、**未確認**。admin-panel の IaC は localhost の `8765` の redirect を使っているが、それが Claude Code の既定の動作と合うかは確認していない。
- **OpenCode**: リモートの MCP と OAuth の対応は**未確認**。PR 4 で実際に試す。
- **ChatGPT 系のリモート MCP クライアント**(#181 の本文にある)は、この ADR の範囲外とする(「未決の点」の U-9)。
- **クライアントの登録(Keycloak 側)**: 方式の候補は次のとおり。
  - (a) **固定の public client を IaC で作る**(admin-panel の `admin-panel-mcp-oauth-job.yaml` と同じ方式)。PKCE(S256)必須、implicit と direct access grant と service account は無効、redirect URI は既知の値だけ(ワイルドカード無し)。**既定の案**。
  - (b) **CIMD(Client ID Metadata Documents)**。Keycloak の版がこれを扱えるかは**未確認**(`k8s-system` の `keycloak/keycloak.yml` からは版が読めなかった)。
  - (c) **DCR(動的クライアント登録)**。採らない。`admin-panel-mcp-oauth-job.yaml` と同じ理由で、匿名の登録を開けないため。
- Keycloak 側の変更は `kigawa01/k8s-system`(`keycloak/` の curl の Job。`KeycloakRealmImport` では既存の realm を更新できない)で行う(admin-panel の #273 と同じ方式)。
- スコープをユーザーのロールに結びつけ、書き込みのスコープを一部のユーザーだけに渡す方式(Keycloak の client scope の role mapping など)は、PR 2 で検証する。**未確認**。
- 注意: `kivra-oidc-client-job.yaml` のコメントによれば、`kigawa-net` realm は `registrationAllowed=true`(誰でも自己登録できる)。ユーザーの自己登録は、ロールが無ければ kivra の入口では通らない。ただし、MCP のスコープをロールに結びつけない限り、この点は MCP の防御の前提になる(§9 の表)。

### 9. 脅威分析

| 脅威 | 対策 | 残るリスク |
|---|---|---|
| 盗まれたトークンでの投入 | 短いアクセストークン、MCP 専用の audience、`kivra.selfdev.start` のスコープ、所有者の規則、既存の同時 Run の上限と予算、書き込みのキルスイッチ | トークンが生きている間は、そのユーザーの権限で投入できる。失効の手順が要る(未決) |
| スコープの昇格 | ツールごとのスコープ検査、スコープをロールに結びつける(PR 2 で検証)、書き込みのスコープを付けない既定 | スコープをロールに結びつけられなければ、読み取りのユーザーに書き込みのスコープが付く(未確認) |
| Origin の偽装、DNS リバインディング | Host と Origin の許可リスト、Bearer 必須(Cookie を使わないので CSRF の経路は無い) | 非ブラウザのクライアントは Origin を送らない。これは Bearer で防ぐ |
| クロスオーナー | 所有者の規則(`iss`+`sub`)、他人の Run は 404 | 旧 email 所有の Run は到達不能(既存の挙動) |
| 投入の洪水 | 所有者ごとのレート制限、同時 Run の上限(既定 1)、ペイロードの上限 | レート制限の値は暫定 |
| プロンプトインジェクション(`title`、`body`、Issue の本文) | 本文は UNTRUSTED のデータとして扱う(ADR 0010 追補 5)、PatchPolicy が最終ゲート、MCP はプロンプトの指示を変えない | モデルが本文に従う可能性は残る。防御は PatchPolicy と commitFiles の構造にある |
| 停止した Run への投入 | キルスイッチ(`KILL_SWITCH_OFF`)を投入の前に検査する | 投入と停止の競合は、既存の `submitTask` の判定に従う |
| MCP 経由の予算の迂回 | MCP は既存の投入の経路だけを通り、別の実行経路を作らない。予算は既存の実行の段階の判定に従う | 投入の時点では予算を見ていない(未決の点 U-11) |
| 応答と監査からの秘密の漏れ | 応答に入れるのは §3 の欄だけ、ログに本文とトークンを出さない | 例外処理の経路の漏れは、テストで確かめる |
| エージェントの主体による誤用 | エージェントのトークンは MCP の audience を持たない(401)。`agentRole` による 403 も置く | なし |

### 10. 実装の分割と順序

- **PR 1**: kivra の `/mcp` の骨格。OIDC の検証(MCP の audience、スコープの検査、所有者の規則)、Host と Origin の検査、`initialize`、`tools/list`、`tools/call`、読み取りの 2 ツール(`kivra_run_list`、`kivra_run_get`)、テスト(認証の失敗、audience の不一致、スコープ不足、クロスオーナーの 404、Origin の拒否、通知の 202、GET の 405)。
- **PR 2**: `kigawa01/k8s-system` の Keycloak。MCP 用の client scope、audience mapper(`https://kivra-dev.kigawa.net/mcp`)、public client(PKCE S256、固定の redirect)、`kivra.runs.read` / `kivra.selfdev.start` / `kivra.selfdev.stop` のスコープ。スコープとロールの対応を検証する。
- **PR 3**: `kigawa-net/kigawa-net-k8s`(`kivra/dev/`)の ingress(`/mcp` を oauth2-proxy の外へ)と env(`KIVRA_MCP_ENABLED`、`KIVRA_MCP_WRITE_ENABLED=false`、`KIVRA_MCP_ALLOWED_HOSTS`、`KIVRA_MCP_AUDIENCE`)。パスの優先順位を dev で確かめる。
- **PR 4**: `kivra_selfdev_start`(書き込み、既定 off)。`idempotency_key` の検査、レート制限、既存の投入の経路の呼び出し、テスト(同じ key の再送で 1 件だけできる、キルスイッチ、書き込みが無効、同時 Run の上限)。
- **PR 5**: `kivra_run_stop` と `kivra_run_execution`。停止は ADR 0012 §6 の既存の取消し要求を使い、停止の安全な意味(CLAIMED と RUNNING の扱い)を先に検証する。経過は ADR 0012 PR 3 の読み取り API の後。
- **段階的な有効化**: (1) dev で読み取りだけ(PR 1〜3 の後)、(2) dev で書き込みを有効化(PR 4 と #174、#220、#157、#71 の後)、(3) 本番の既定は、この ADR の承認の後、別に決める。

## 未決の点

以下は、ユーザーの確認が要るものである。各項目の暫定の既定値を括弧で示す。異論があれば、実装の前に変える。

- **U-1 クライアントの登録方式**: 固定の public client(既定)、CIMD、DCR のどれか。CIMD が Keycloak の版で使えるかは未確認。Claude Code と OpenCode の対応は未確認。
- **U-2 スコープの粒度**: 3 つのスコープ(既定)か、`kivra.selfdev.start` を読み取りと分けた細かい単位か。スコープをロールに結びつける方式は未確認(PR 2)。
- **U-3 書き込みを dev で有効にする時期**: PR 4 の後、#174、#220、#157、#71 の検証が済んだとき(既定)。
- **U-4 停止ツールの有無**: PR 5 で作る(既定)か、作らないか。停止の安全な意味が検証されるまで、tools/list に出さない。
- **U-5 ホストの分け方**: 同じホストの `/mcp`(既定。`kivra-agent-ingress` と同じ作法)か、別のホスト名。HAProxy のパスの優先順位は PR 3 で確認する。
- **U-6 MCP の audience の値**: `https://kivra-dev.kigawa.net/mcp`(既定。例として #181 に書かれた値)。本番の値は、本番の入口を決めるときに決める。
- **U-7 レート制限の値**: 1 分に 5 件(暫定。実装では既定 60 件に変更。「実装状況」の決定を参照)。
- **U-8 対応する protocolVersion の一覧**: 決定済み(「実装状況」の決定を参照)。
- **U-9 ChatGPT 系のリモート MCP クライアント**: この ADR の範囲外(既定)。対応するなら、登録方式と redirect を別に確認する。
- **U-10 トークンの失効**: Keycloak の側での失効の手順(既定: 運用者がユーザーを無効にする)。kivra 側の失効リストは作らない。
- **U-11 投入の時点の予算の検査**: 投入の経路は予算を検査しない(確認済み)。MCP の投入だけに投入時の予算の検査を足すか(既定: 足さない。既存の実行段階の判定に任せる)。足すなら、既存の経路との差を別の PR で決める。

## 検討した代替案

- **A. kivra の中に、MCP 専用の Run の状態と投入の経路を作る。** ADR 0001 の Run の正本に反する。#181 の「second Run authority を作らない」にも反する。採らない。
- **B. 共有の静的トークン(API キー)を MCP のクライアントに渡す。** 盗まれたときに失効の範囲が広く、所有者の規則が成り立たない。#181 の「static shared admin bearer token exposed to clients」に反する。採らない。
- **C. oauth2-proxy の Cookie を MCP のクライアントに使わせる。** クライアントは Cookie のブラウザのログインを扱えない。また、ブラウザの認証と API の認証の境界が崩れる。採らない。
- **D. admin-panel と共通のライブラリを作る。** 別リポジトリで、リリースの周期が違う。共通化の利益より結合の害が大きい。同じ作法に揃えるだけにする。採らない。
- **E. DCR でクライアントを登録させる。** 匿名の登録を開けることになる。admin-panel の IaC と同じ理由で採らない。
- **F. 書き込みを最初から有効にする。** #181 の条件(#174、#220、#157、#71)が揃っていない。採らない。

## 結果

- MCP のクライアントから、Issue を指定した自己開発の投入と、Run の状態の読み取りができる。経路は既存の application service を通り、Run の正本は増えない。
- 書き込みは既定で無効で、有効化には複数の条件が要る。事故が起きても、既存のキルスイッチと上限で止まる。
- 認証は、MCP 専用の audience とスコープで、REST とエージェントのトークンから分けられる。新しいスコープの検査は、このための新しいコードである。
- 運用の物が増える。Keycloak の client と scope、ingress の経路、MCP の env の 3 つを、別々のリポジトリで管理する。
- Keycloak 側の仕組み(CIMD、スコープとロールの対応)が確認できないと、PR 2 の範囲が変わる。
- admin-panel の E2E(#236)が終わっていないため、同じ作法の実際の動作は、この ADR の段階では確認されていない。

## 見直しの条件

- admin-panel の #236 の E2E が完了し、Keycloak からのブラウザログインの手順が確かめられたとき(この ADR の Keycloak の部分を見直す)。
- Claude Code か OpenCode の OAuth の仕様が変わり、登録方式(U-1)の前提が崩れたとき。
- HAProxy の ingress でパスの優先順位が使えないと確かめられたとき(U-5 を見直す)。
- MCP の仕様の新しい版で、ステートレスの扱いや認可の規則が変わったとき(§2 と §4)。
- #174、#220、#157、#71 のどれかの前提が変わり、書き込みの条件(§5)が増減するとき。

## 実装状況

確認時点は 2026-10-18。PR の状態は `gh pr view` で確認した。マージされるまでは「作成済み・未マージ」として扱う。ADR のステータスは「提案(Proposed)」のままで、承認の有無は確認していない。

| 段階 | PR | 状態 | 範囲 |
|---|---|---|---|
| PR 1 | kigawa-net/kivra#263 | 作成済み・未マージ | kivra の `/mcp` の骨格、OIDC の検証、読み取りの 2 ツール |
| PR 2 | kigawa01/k8s-system#280 | 作成済み・未マージ | Keycloak の public client `kivra-mcp`、スコープ、audience mapper |
| PR 3 | kigawa-net/kigawa-net-k8s#369 | 作成済み・未マージ | `/mcp` と `/.well-known/oauth-protected-resource` を oauth2-proxy の外へ出す ingress |

### PR 1(kivra#263)の範囲

- `POST /mcp`(ステートレスの JSON-RPC 2.0。`Mcp-Session-Id` は発行しない)。`initialize`、`ping`、`tools/list`、`tools/call` を実装する。`GET /mcp` は 405。
- `/.well-known/oauth-protected-resource`(RFC 9728)。
- `McpJwtVerifier`: RS256、JWKS の署名、`iss`、`aud`(MCP の値)、`exp`、`nbf`、必須のロール、`scope` クレーム。
- Host と Origin の許可リスト。Host が一致しなければ 403。Origin があれば一致が必要。Origin が無いリクエストは通す。
- レート制限(主体ごと。超過は 429)。
- 読み取りの 2 ツール: `kivra_run_list`、`kivra_run_get`。自分の Run だけを返す。他人の Run と存在しない Run は同じ NOT_FOUND。
- `KIVRA_MCP_ENABLED` が未設定、`false`、空のときは、他の MCP の env を読まず、`/mcp` と `/.well-known/oauth-protected-resource` を登録しない(404)。

### PR 2(k8s-system#280)の範囲

- `kigawa-net` realm に public client `kivra-mcp` を作る。PKCE(S256)必須。implicit、direct access grant、service account は無効。redirect URI は `http://localhost:8765/callback` と claude.ai の callback だけ(ワイルドカード無し)。
- client scope: `kivra.runs.read`(default)、`kivra.selfdev.start`(optional)、`kivra.selfdev.stop`(optional)。optional のスコープは、クライアントが scope パラメータで要求したときだけ発行される。
- audience mapper: access token の `aud` に `https://kivra-dev.kigawa.net/mcp` を入れる。
- **Keycloak 側では、admin-panel:admin を持つユーザーだけにスコープを発行することはできない。** client scope の role mapping は token の中のロールを絞るだけで、スコープの発行は絞れないため。kivra 側が `resource_access.admin-panel.roles` を検査して判定する(既存の `OperatorAuth` と同じ)。この判定は PR 1 の検査が前提になる。

### PR 3(kigawa-net-k8s#369)の範囲

- `/mcp` と `/.well-known/oauth-protected-resource` だけを、oauth2-proxy を通さず `kivra-svc` へ渡す ingress(`kivra-mcp-ingress`、HAProxy)。他のパスは従来どおり `kivra-ingress`(oauth2-proxy の背後)。
- この PR では env を追加しない。`KIVRA_MCP_ENABLED` は未設定のままで、MCP は無効。有効化は別の PR で行う。
- HAProxy で `/mcp` の経路が `kivra-ingress`(`path: /`)より優先されることは、マニフェストだけでは確認できていない(**未確認**)。マージ後に dev で確かめる。404 なら kivra に届いている。302 なら oauth2-proxy に届いている。

### 決定(実装で確定した点)

- **protocolVersion(U-8)**: 対応する版は `2025-06-18` と `2025-03-26`。要求された版がこの一覧に無いときは `2025-06-18` を返す(クライアントが続けるかを判断する)。最新の仕様 `2026-07-28` は未対応で、`server/discover` と `_meta` による別の交渉の扱いは後続で判断が要る。実際の MCP クライアントが対応している版は**未確認**。
- **レート制限(U-7)**: 既定は主体ごとに 1 分 60 件(`KIVRA_MCP_RATE_PER_MINUTE`、1〜6000)。本文の暫定値 5 件は採らない。
- **`kivra_run_list` の `limit`**: 1〜50(既定 20)。§3 の 100 は実装で 50 に変えた。
- **Bearer の読み取り元**: `Authorization` ヘッダーだけ。REST の `X-Forwarded-Access-Token` へのフォールバックは使わない。
- **X-Forwarded-Proto**: 信用しない。実装はこのヘッダーを読まない(コードに参照が無く、Forwarded ヘッダーのプラグインも入れていない)。
- **スコープ不足**: HTTP 403 と `WWW-Authenticate: Bearer error="insufficient_scope", scope="..."`。JSON-RPC の本文も返す。
- **`KIVRA_AGENT_REQUIRED_ROLE`**: 任意。設定すると、`<client>:<role>` の形のロールを持つ token は 403 になる。エージェントの token は audience の検査で先に 401 になるので、これは多層防御。
- **env 名**: MCP の audience は `KIVRA_MCP_AUDIENCE`。ADR の §10 にあった `KIVRA_MCP_OIDC_AUDIENCE` は誤りで、実装の名前に合わせて直した。

### 未実装・残件

- PR 4: 書き込みツール `kivra_selfdev_start`(既定で無効)。
- PR 5: `kivra_run_stop` と `kivra_run_execution`。
- 実際の MCP クライアント(Claude Code、OpenCode など)からの接続の確認。**未確認**。
- 最新の仕様 `2026-07-28` への対応。**未対応**。
- 有効化の PR(`KIVRA_MCP_ENABLED` などの env を dev に入れる PR)。未作成。
- HAProxy のパス優先順位の確認(PR 3 のマージ後に dev で curl)。**未確認**。
