# kivra

> kivra self-dev test

自己開発型 kigawa 補助 AI。設計の正本は GitHub Issue（設計 #9〜#12、および #60 / #61 / #64）。本リポジトリに `docs/design/` は無い（設計文書は別 PR / Issue で管理）。

現在は M0（Core 骨格と DB スキーマ）、M1 の ExecutionPort（#29）、M2 の Agent Manager 最小実装（#30）まで。Adapter / Orchestrator / Policy 等は後続マイルストーン。

## 構成

| モジュール | 内容 |
|---|---|
| `kivra-core` | ドメイン型、状態機械（DevelopmentRun / Agent / ExecutionSession / OperationStatus）、Port インターフェース（TaskProvider / MemoryPort / SchedulePort、定義のみ）、`LeaseStore` |
| `kivra-observability` | Trace 土台（`Tracer` / `SpanExporter` Port）、メトリクス（`MetricsRegistry`、Prometheus 形式）、指標カタログ、コスト Port（観測専用。`BudgetCheck.verdict` の `WITHIN_KNOWN_LIMIT` も承認ではない。Issue #73）、秘密を出さない属性フィルタ。外部依存なし（[docs/observability.md](docs/observability.md)） |
| `kivra-scheduler` | SchedulePort の具体型（#38）。`ScheduleClient`（Job の作成・取得・一時停止/再開/削除、Execution の取得・cancel・retry）、`FakeScheduleClient`、`AiSchedulerAdapter`（Ktor。Bearer は `ScheduleTokenProvider` 差し込み）、起動契約（`ScheduleTriggerHandler` で `(jobId, scheduledAt)` を冪等化、`SchedulePoller`）。**Orchestrator は ai-scheduler が止まっても動く**（ai-scheduler は起動役に限定）。Orchestrator（#36）とは未結線。実 API の確認結果と要確認事項は [docs/ai-scheduler-requests.md](docs/ai-scheduler-requests.md)（結果通知は Callback 不可で Polling、認証は Phase 1 無し=ネットワーク限定、`feat/timeout` 未マージ） |
| `kivra-exkes` | `ExecutionPort`（唯一の定義。core のプレースホルダは #30 で削除）、`ExkesAdapter`（Ktor クライアント、Bearer は `TokenProvider`）、`FakeExecutionPort`、共通契約テスト。DB・Run 状態機械には依存しない（#29）。exkes 側の不足機能は [docs/exkes-requests.md](docs/exkes-requests.md) |
| `kivra-github` | GitHub Adapter（#34）。`GitHubPort` + Ktor HTTP 実装。ブランチ・PR・Checks・レビュー取得、Squash merge。Force push / main への push は Port に存在しない。Tool Gateway（#32）とは未結線 |
| `kivra-model-gateway` | Model Gateway 最小実装(#35): 型・エラー分類、Model Registry(YAML)、制約付きルーティング、予算(予約/確定/解放)、Anthropic Adapter(Ktor)、監査/メトリクスのフック(永続化は別 Issue)。Core / DB には依存しない。Registry 例は `kivra-model-gateway/config/model-registry.example.yaml`(価格等はプレースホルダ)。API キーは信頼管理者が固定する `CredentialBinding`(providerId→許可ホスト/ラベル/環境変数名)経由でのみ解決し、Registry の endpoint/credentialRef は任意の秘密を指せない(HTTPS・ホスト一致必須、リダイレクト不追従)。同一 requestId は `RequestLedger`(予約・結果保存・照合、eviction なし、SHA-256 の正規化 Request Digest で束縛)で冪等化する。永続 Ledger は未対応(M0 の DB 契約に依存)で、`InMemoryRequestLedger` は dev/test 専用として `devAllowInMemoryLedger = true` を明示しない限り Gateway 構築が失敗する。送信済みの可能性がある失敗(5xx・接続断・解析失敗・ストリーム途中失敗・HTTP 応答を伴う 4xx/429 を含む)は予約を見積額で確定し、同一 requestId の Retry / Fallback を行わない(新しい requestId が必要)。送信前に確実に失敗した場合(資格情報未設定・接続拒否・DNS 失敗)のみ予約を解放し Retry / Fallback する。呼出し側 Agent が DataClass を下げられないよう、DataClass は Trusted Orchestrator/Policy Gateway が固定した値を渡すこと(M4 公開 API の前提)。Budget はインメモリで本番の Hard Limit としては使わない(永続化・インスタンス跨ぎは別 Issue) |
| `kivra-fusha-task` | fusha-task 向け `TaskProvider` 実装（read-only の `FushaTaskAdapter`、`FakeTaskProvider`、Bearer 認証、timeout / retry / backoff）。M1 #27 |
| `kivra-memory` | `MemoryProvider` ポートと型（MemoryEntry / ScopeRef / SourceRef / ContextBundle / HandoffRecord）、`InMemoryWorkingMemory`、`FakeMemoryPort`、縮退層 `MemoryService`（Write Queue・circuit breaker）、Secret フィルタ、recl 向け `ReclAdapter`（**計画 API に基づく骨格。実 API は存在せず未接続**）。M5 #37 |
| `kivra-app` | Ktor アプリ、設定読込、`/health`、`/metrics`、Flyway マイグレーション、Exposed テーブル定義、Journal / Outbox / Inbox / Audit / Lease / Agent のリポジトリ、`net.kigawa.kivra.agent.AgentManager`（M2: 1 Run に 1 Agent / 1 Workspace、Lease 保持下で ExecutionPort 経由に Workspace→Runtime→Execution を起動し、各副作用を Operation Journal に記録。UNKNOWN は照合のみで再送しない。Hold・Lease 喪失で停止、Cancel は回収後に CANCELLED。#30） |

DB スキーマは `kivra-app/src/main/resources/db/migration/`（V1 コア、V2 照合証拠列・Outbox claim token・RunCompletionMode / PR 証拠列、V3 approvals（PR #55 / #33。M0 で取り込み済み）、V4 Evidence Authority 束縛列（#82。M0 でも追加済み））。Run の正本は `development_runs` の DevelopmentRun のみ（[ADR 0001](docs/adr/0001-run-source-of-truth.md)）。構文は MariaDB と H2 MODE=MySQL の両方で動くものに限定している。このブランチに V5 は含めない（PR #92 の V5 は別途）。マージ順は #43 → #55 → #86 で固定し、V4 適用済み DB への V3 後付けは行わない（Issue #88、fail-closed）。V2 は未マージのため編集中。マージ後は V5 以降のみ追加。

## ローカルで試す（クイックスタート）

`compose.dev.yml` で、HTTP API と MariaDB 11 を Docker だけで起動し、ダミーの投入 API を試す。JDK 21 も Gradle も不要（アプリのビルドは `Dockerfile` 内で行う）。

**前提**: Docker と Docker Compose V2（`docker compose` サブコマンド）。`127.0.0.1:8080` が空いていること。初回はイメージのビルドと依存解決に数分かかる。以下はリポジトリのルートで実行する。

```bash
# 1. 起動（MariaDB と app の healthcheck が通るまで待つ。Flyway migration は app の起動時に実行される）
docker compose -f compose.dev.yml up --build -d --wait

# 2. ヘルスチェック
curl -fsS http://localhost:8080/health
# 想定応答: {"status":"ok"}

# 3. ダミーのタスク一覧（SIMULATED。固定のデモ値）
curl -fsS http://localhost:8080/api/dev/tasks
# 想定応答: {"mode":"SIMULATED","tasks":[{"id":"demo-1","title":"Inspect a repository issue","state":"READY"},{"id":"demo-2","title":"Draft a review plan","state":"READY"}]}

# 3'. ダミーのタスク投入（title は 1〜256 文字、idempotencyKey は 1〜128 文字）
curl -fsS -X POST -H 'Content-Type: application/json' \
  -d '{"title":"ローカル確認","idempotencyKey":"quickstart-1"}' \
  http://localhost:8080/api/dev/tasks
# 想定応答（HTTP 202）: {"runId":"<UUID>","title":"ローカル確認","state":"NOT_EXECUTED","mode":"SIMULATED"}
# 同じ idempotencyKey と同じ title で再送すると同じ runId が返る（202）。同じキーで title が違えば 409。

# 3''. 投入した Run の参照（上の runId を入れる）
curl -fsS http://localhost:8080/api/dev/runs/<runId>
# 想定応答（HTTP 200）: 投入時と同じ JSON。存在しない id は 404 {"error":"run not found"}

# 4. 後片付け（DB のボリュームも削除する）
docker compose -f compose.dev.yml down -v
```

### 何が動き、何が偽で、何が未接続か

| 区分 | 内容 |
|---|---|
| 動く | HTTP API（`/health`、`/api/dev/*`）。MariaDB 11 の起動と Flyway migration（起動時に適用され、`development_runs` などのテーブルが作られる）。ダミー投入の冪等性（同じ idempotencyKey は同じ runId。ただし**プロセス内メモリ**のみで、再起動で消え、DB には書かない） |
| 偽（SIMULATED） | `/api/dev/*` の一覧は固定のデモ値。投入された Run は `state: NOT_EXECUTED`、`mode: SIMULATED`。Agent の起動、LLM の呼び出し、exkes / GitHub / recl への書き込みは一切行わない |
| 未接続 | 実 Provider（fusha-task への接続、GitHub への書き込み、exkes での実行）、外部 LLM API、ローカル LLM（`kivra-model-gateway`）、予算管理、Memory（recl）。`kivra-app` はこれらのモジュールに依存していない、または dev 起動経路から呼ばれない（`KIVRA_FUSHA_*` も渡していない） |

- **本番用の認証付き Task API（`/api/tasks`、OIDC）とは別物**。上記「Task API」節のとおり、そちらは MariaDB の `development_runs` に永続化する。この compose では `KIVRA_AUTH_MODE` が未設定なので Task API は登録されず、`/api/tasks` は 404 になる。
- **`KIVRA_DEV_DEMO_ENABLED=true` を、サーバーやクラスタなどリモートの環境で有効にしないこと**。`/api/dev/*` には認証がなく、誰でも呼べる。`KIVRA_RUNTIME_ENV=development` 以外では起動に失敗する（「設定」節）。この compose はポートを `127.0.0.1` にだけ公開している。外部に公開するように変更しない。

## ビルド・テスト

JDK 21 を使う（Gradle wrapper は 8.14。JDK 25 では動かない）。既定の `java` が 21 でなければ `JAVA_HOME` を明示する。

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew test          # テスト（H2 インメモリ。外部 DB 不要）
./gradlew build         # テスト + ビルド
./gradlew :kivra-app:run   # 起動（MariaDB が必要）
```

### MariaDB 実機でのテスト

既定のテストは H2（MODE=MySQL）。Flyway migration・unique 制約・Lease・transaction・Outbox の競合テストは、同じテストスイートを
MariaDB 11 に向けて実行できる（`KIVRA_TEST_DB=mariadb`。各テストが専用スキーマ `kivra_t_<nanos>` を作成・削除する。接続ユーザーに CREATE/DROP DATABASE 権限が必要）。

```bash
# 1. MariaDB 11 を起動（docker があれば）
docker run -d --name kivra-test-mariadb -e MARIADB_ROOT_PASSWORD=test -p 33306:3306 mariadb:11
# 起動待ち: docker exec kivra-test-mariadb mariadb -uroot -ptest -e 'select 1'

# 2. 同じテストを MariaDB に対して実行
export JAVA_HOME=/path/to/jdk-21
KIVRA_TEST_DB=mariadb \
KIVRA_TEST_MARIADB_URL=jdbc:mariadb://127.0.0.1:33306/ \
KIVRA_TEST_MARIADB_USER=root KIVRA_TEST_MARIADB_PASSWORD=test \
./gradlew :kivra-app:test --rerun-tasks      # 1 テスト数秒かかり全体で 2〜3 分

# 3. 片付け
docker rm -f kivra-test-mariadb
```

Testcontainers は使っていない（docker ソケット権限・CI の service container との二重管理を避けるため、環境変数で接続先を渡す方式に統一）。
CI（`.github/workflows/ci.yml`、JDK 21）は `./gradlew test`（H2）→ MariaDB 11 service container に対する `:kivra-app:test` → `./gradlew build` の順に実行する。
マージ前に、このワークフローを必須チェックに設定すること（リポジトリ設定側の作業）。

## 観測

`GET /metrics`（Prometheus text format）は既定で無効。`KIVRA_METRICS_ENABLED=true` と `KIVRA_METRICS_BEARER_TOKEN`（必須、未設定なら起動失敗）の両方で有効化し、`Authorization: Bearer <token>` で呼ぶ。別ポート化は未実装。
要確認: 別ポート（loopback）化の要否、収集基盤（Prometheus / OpenTelemetry）の採用、Metrics の実計測への結線（未対応）。指標の一覧と秘密の扱いは [docs/observability.md](docs/observability.md)。

## 設定

HOCON（`kivra-app/src/main/resources/application.conf`）。環境変数で上書きできる。

| 環境変数 | 既定 | 内容 |
|---|---|---|
| `PORT` | 8080 | HTTP ポート |
| `KIVRA_DB_JDBC_URL` | `jdbc:mariadb://localhost:3306/kivra?sessionVariables=time_zone='+00:00'` | JDBC URL |
| `KIVRA_DB_USER` / `KIVRA_DB_PASSWORD` | `kivra` / `change-this-in-production` | DB 認証情報（本番では必ず上書き） |
| `KIVRA_DB_MIGRATION_USER` / `KIVRA_DB_MIGRATION_PASSWORD` | （未設定） | 任意。Flyway（スキーマの作成・変更、DDL）専用の認証情報。両方設定したときだけ有効で、アプリの接続プールは `KIVRA_DB_USER` / `KIVRA_DB_PASSWORD` を使う（DML のみを想定）。両方未設定なら従来どおり 1 組の認証情報で migration とアプリを動かす。片方だけの設定は起動失敗 |
| `KIVRA_LEASE_TTL_SECONDS` | 30 | Run Lease の TTL |
| `KIVRA_LEASE_GRACE_SECONDS` | 5 | 期限切れ後、奪取を許可するまでの猶予 |
| `KIVRA_METRICS_ENABLED` | false | `/metrics` の有効化（true のとき `KIVRA_METRICS_BEARER_TOKEN` が必須） |
| `KIVRA_METRICS_BEARER_TOKEN` | （空） | `/metrics` の Bearer トークン |
| `KIVRA_AUTH_MODE` | （未設定） | Task API の認証。`oidc-jwt`（本番）/ `oauth2-proxy`・`none`（development のみ）/ 未設定（ルートを登録しない）。下記「Task API」 |
| `KIVRA_OIDC_ISSUER` / `KIVRA_OIDC_AUDIENCE` / `KIVRA_OIDC_REQUIRED_ROLE` | （`oidc-jwt` で必須） | JWT の検証条件。`KIVRA_OIDC_JWKS_URL`（任意）。下記「認証」 |
| `KIVRA_RUNTIME_ENV` | （未設定） | `none` を使うには `development` が必須 |

fusha-task（`kivra.fusha.*`、**すべて要確認**: 環境ごとの URL・機械向け認証方式は未確定）:

| 環境変数 | 既定 | 内容 |
|---|---|---|
| `KIVRA_FUSHA_BASE_URL` | `http://localhost:8787/api/v1` | fusha-task の API ルート（`/api/v1` を含む） |
| `KIVRA_FUSHA_ENV` | `dev` | TaskId (`fusha:{env}:{id}`) の環境ラベル |
| `KIVRA_FUSHA_TOKEN` | 空 | Bearer トークン（Keycloak ID token）。client_credentials が fusha-task の aud 検証を通るかは未検証 |
| `KIVRA_FUSHA_REQUEST_TIMEOUT_MS` / `KIVRA_FUSHA_MAX_RETRIES` | 10000 / 3 | タイムアウトと GET の再試行回数（5xx・429・通信エラーを指数バックオフ + jitter、401 はトークン再取得を 1 回、400/403 は再試行しない） |

M1 の `FushaTaskAdapter` は **read-only**（get / list のみ）。update / comment は `TaskWriteResult.Unsupported` を返す
（fusha-task に楽観ロック / ETag が無く、機械認証も未検証のため。要確認）。

### Task → Run admission（Issue #26、本番未配線）

- `RunAdmission.admit(task, requestId)`（`DbRunAdmission`）は、Task を 1 件の DevelopmentRun に対応づける。`task_bindings`（V11、`provider / environment / task_id` の複合主キー）と Run の作成を **1 トランザクション**で書く。同じ Task の active な Run は常に 1 つ。
- 判定は純粋関数 `AdmissionEvaluator` が行い、条件が不明なものは fail closed で `Blocked`（DB に何も書かない）。`AdmissionPolicy` が無い（既定）場合は全件 `Blocked`。
- 同じ Task の Run が未終端なら `AlreadyActive`、終端済みなら `AlreadyTerminal`（新しい Run は作らない）。古い `updatedAt` では Binding を後退させない。
- 再実行は `requestRerun`（運用者の明示操作のみ）。旧 Run が終端のときだけ `generation` を進め、次の admit で新しい Run を作る。監査に operator と理由を残す。HTTP の露出は無い。
- **本番（`Application.kt`）には配線していない。** poller → admission の接続、状態の書き戻し、運用者向けの HTTP は未対応（#26 の後続、#28）。
### Memory（`kivra-memory`、M5 #37）

recl（kigawa-net/recl）の現状: **設計書（`docs/design.md`）と内部 Core / InMemory Storage のみ main にマージ済み**
（recl PR #17 / #19）。**実 REST / MCP サーバーと Fortis Adapter は未提供**。したがって `ReclAdapter` は設計 #6 §4 の
**想定契約**（Kivra 側の仮 API）に基づく骨格であり、実サーバーには一度も接続していない。
エンドポイント・フィールド名・メタデータ領域・認証方式・URL prefix・DTO・MCP identity はすべて **要確認 / 未確定**。
recl のドメイン Status（`proposed / confirmed / superseded / archived`）と本 Adapter の `MemoryStatus`（`active / superseded / deleted`）も一致しない。
UUIDv7 の Context ID は再試行で安定させる前提だが、recl 側の確定仕様ではない。
Supersede（2 Context 間の原子性）と REDACT は本番提供できない前提で扱う。`GET` の 404 は以前の write が未適用だった証拠にはならない。

**実互換性ゲート**: [recl #20](https://github.com/kigawa-net/recl/issues/20)（Trusted Mutation Envelope・Canonical Digest・UUIDv7 境界）と
[Kivra #71](https://github.com/kigawa-net/kivra/issues/71)（Provider Compatibility Matrix & Production Capability Gate）が閉じるまで、本番接続可能とは表現しない。
Mock の成功は Production Provider 検証ではない。要確認項目は [docs/recl-requests.md](docs/recl-requests.md)。

検証は Fake と、Fake を背後に置いたモック recl（MockEngine）に対する共通契約テストで行う。kivra-app には未配線（接続先が無いため設定項目も未追加）。
**変更系（save / update / supersede / delete）は `ReclConfig.mutationsEnabled = false` が既定で、有効化しない限り `UNSUPPORTED` となり送信しない。**
サーバー側の冪等性・権限・Receipt・契約テストが合格するまで有効化しない（読み取りは有効化不要）。

- 縮退: 読み取りは失敗・タイムアウト・circuit open のいずれでも「長期記憶は空」＋`MEMORY_UNAVAILABLE` 警告で続行する。書き込みは `Write Queue`（インメモリ、上限・TTL あり）に保留し同一 idempotency key で再送する。永続 Outbox は後続。
- 再送規則: `ReclAdapter` は GET のみ自動再試行し、POST / PATCH / DELETE は自動再送しない。Write Queue から再送するのは「送信されなかった」と確定した失敗（`UNAVAILABLE`）だけ。
  結果不明（`OUTCOME_UNKNOWN`、未知の例外を含む）の書込は、`IDEMPOTENT_SAVE` を宣言していても **再送しない**。Queue にも入れず、要照合（held）として保持し、監査には件数のみ記録する（`WRITE_HELD_RECONCILE`）。
  **永続 Journal 連携（M0 の `external_operations`）まで再送しない。** 照合（Journal の証拠に基づく判定）が入るまで自動再送を無効にしている。
- 安全規則: https 必須・リダイレクト非追従・トークンは Authorization ヘッダのみ。応答の同一性（要求 id と応答 id、version の前進、許可 Scope 内）を検証し、検索結果は Scope / 機密区分 / kind で再フィルタ。例外メッセージ・`toString`・ログに本文、クエリ全文、トークンを出さない。
- Memory はデータであり命令ではない: 取得内容は `UntrustedText`（`CharSequence` でなく `toString` で中身を出さない）で保持し、`ContextBundle.render()` のフェンス付き参照データブロックにしか出力されない。Memory を認可・Tool 実行の判断に使わない。
- Secret: `SecretFilter`（既知トークン形式・秘密鍵ヘッダ・`.env` 形式・エントロピー）。`ReclAdapter` は送信前に必ず拒否、`FilteringMemoryProvider` は REJECT / MASK を選べる。検出は完全ではない。
- kivra-core の M0 `MemoryPort`（最小のプレースホルダ）は **変更していない**（他 PR との衝突回避）。Orchestrator 連携は `kivra-memory` の `MemoryProvider` / `MemoryService` に結び、core 側の置換はマージ順が決まってから行う。

時刻は UTC。MariaDB のセッションを UTC にする（既定の URL は設定済み）。

## Task API（Issue #112）

認証付きで Task を投入し、MariaDB の `development_runs` に永続化する API。**実行は SIMULATED**: Run は CREATED で作られるだけで、
Agent の起動、LLM の呼び出し、exkes / GitHub / recl への書き込みは一切行わない（`executed` は常に `false`）。

| メソッド・パス | 内容 |
|---|---|
| `POST /api/tasks` | body `{"title": "...", "idempotencyKey": "..."}`。新規は 202、同じ (呼び出し元, idempotencyKey) の再送は 200 で同じ `runId` を返す。同じキーで title が違えば 409 |
| `GET /api/runs/{id}` | 自分の Run。他人の Run、存在しない Run は 404 |
| `GET /api/runs` | 自分の Run を新しい順に最大 50 件。`?projectId=` で絞り込める（後述） |
| `GET /api/admin/runs`、`GET /api/selfdev/runs`、`GET /api/selfdev/goals`、`GET /api/selfdev/orders`、`GET /api/selfdev/stop-requests?state=PENDING`、`GET /api/selfdev/memory`、`GET /api/selfdev/assignees` | 一覧。`?projectId=<プロジェクトID>` でそのプロジェクトの行だけ、`?projectId=unassigned` でどのプロジェクトにも属さない行（NULL）だけ。省略時は従来どおり全件。その他の値は 400。応答に `projectId` を含む（ADR 0022 PR-D、`docs/runbook-selfdev.md` 4.17）。担当は、プロジェクトのメンバー（`project_members`）で絞る |
| `POST /api/selfdev/goals`、`POST /api/selfdev/orders` | 廃止（ADR 0022 PR-G）。410 Gone と `Link`（`/api/projects/{id}/tasks`、`/api/projects/{id}/orders`）を返す。認証と権限は従来どおり先に判定する（401・403 は変わらない）。何も作られない。手順は `docs/runbook-selfdev.md` 4.21 |
| `GET` / `POST /api/projects/{id}/runs` | 自己開発の Run（PR のみ）の一覧・投入の別名（ADR 0022 PR-I）。`GET /api/selfdev/runs?projectId=` と `POST /api/selfdev/tasks` と同じ応答。旧ルートは `projectId` に ULID を指定したときだけ `Deprecation` と `Link` を返す（全体・`unassigned` には付かない）。投入の body の `projectId` は省略可、あれば path と一致が必要（不一致は 400） |
| `POST /api/selfdev/tasks`、`POST /api/selfdev/orders/{id}/stop-requests`、停止要求の承認・却下・取消、目標の承認・停止、Task の承認・却下 | `KIVRA_SELFDEV_PROJECT_MODE`（既定 `off`）が `shadow` なら、プロジェクトのメンバーでない操作を記録だけする（応答は変えない）。`enforce` なら、メンバーでなければ 403（グローバル ADMIN は除く）。プロジェクトを特定できない行（NULL）は従来の判定だけ（ADR 0022 PR-D） |
| `GET /api/projects/{id}/dashboard` | プロジェクトのダッシュボード（読み取りのみ、READ 権限）。`{project, scope, tasks, recentRuns, spend, activity}`。`scope` は `ALL`（運用者: プロジェクトの全 Run を数える）か `OWN`（それ以外: 自分の Run だけ）。`tasks` は Run の総数と状態別件数、`recentRuns` は最大 10 件、`spend` は Run に紐づく予算台帳の費用（円の文字列。確定・予約中・見積もりで計上した分。Planner の費用は含まない。プロジェクトごとの上限は無い。運用者（ORDER_MANAGE）にだけ、`budget`（今月の予算。上限・確定・予約中・残額・警告）を返す。閲覧者では `budget` は null。PR-E）、`activity` は Run の監査イベントの新しい順 最大 30 件（固定の code のみ）。存在しないプロジェクトは 404 |
| `GET /api/projects/{id}/repos` | プロジェクトの repo（owner/name）の一覧（READ 権限）。存在しないプロジェクトは 404 |
| `PUT /api/projects/{id}/repos` | body `{"repoKey": "owner/name"}` で repo を登録（ORDER_MANAGE、JSON）。登録は 201。形式が違えば 400、既に別の（または同じ）プロジェクトに登録済みなら 409、存在しないプロジェクトは 404 |
| `DELETE /api/projects/{id}/repos?repoKey=owner/name` | repo の登録を外す（ORDER_MANAGE、JSON、本文は `{}`）。204。登録がなければ 404 |
| `DELETE /api/projects/{id}` | プロジェクトを削除する（ORDER_MANAGE、JSON、本文は `{}`）。空のプロジェクト（repo・メンバー・予算・行がない）だけ 204。何か残っていれば 409 `{"error":"PROJECT_NOT_EMPTY","counts":{…種類ごとの件数}}` で、何も消さない（子の行は消さず、未分類にも戻さない）。存在しなければ 404。手順は `docs/runbook-selfdev.md` 4.22 |
| `POST /api/projects/backfill?dryRun=true` | 既存データの振り分け（ORDER_MANAGE、JSON）。既定は dry-run（何も書かない）。`dryRun=false` で実行。`project_id` が NULL の行だけを repo の対応で更新する。テーブルごとに `matched`（対応あり）・`updated`（書いた件数）・`unmatched`（対応なしで NULL のまま）を返す。冪等。手順は `docs/runbook-selfdev.md` 4.16 |
| `GET /api/projects/{id}/members` | プロジェクトのメンバー（担当の `assigneeId`、表示名、role、state）の一覧（ORDER_MANAGE）。存在しないプロジェクトは 404 |
| `PUT /api/projects/{id}/members/{assigneeId}` | body `{"role": "ADMIN\|OPERATOR\|APPROVER\|AI\|VIEWER"}` で担当を追加、または role を変える（ORDER_MANAGE、JSON）。追加は 201、同じ role の再送と role の変更は 200（冪等）。role が不正なら 400、存在しない project・担当は 404、そのプロジェクトの最後の ADMIN を降格するなら 409（ADR 0022 PR-D2） |
| `DELETE /api/projects/{id}/members/{assigneeId}` | メンバーから外す（ORDER_MANAGE、JSON、本文は `{}`）。204。メンバーでなければ 404、そのプロジェクトの最後の ADMIN なら 409 |
| `POST /api/projects/{id}/members/import-all?dryRun=true` | 有効（ACTIVE）で未登録の全担当を、body `{"role": ...}` の role でメンバーに追加（ORDER_MANAGE、JSON）。既定は dry-run（何も書かない）。`dryRun=false` で実行。既存メンバーの role は変えない。無効（DISABLED）の担当は対象外。`candidates`・`added`・`alreadyMember`・`disabled` の件数を返す。冪等。存在しないプロジェクトは 404。手順は `docs/runbook-selfdev.md` 4.17 |
| `GET /api/projects/{id}/budget` | プロジェクトの今月の予算（ORDER_MANAGE）。`{projectId, period, monthlyLimitJpy, softRatio, softLimitJpy, committedJpy, reservedJpy, remainingJpy, softExceeded}`（円の文字列。上限が無ければ上限系は null）。対象の月は日本時間の暦月。存在しないプロジェクトは 404（ADR 0022 PR-E） |
| `PUT /api/projects/{id}/budget` | body `{"monthlyLimitJpy": 10000, "softRatio": 0.8}` で月の上限（円、0 以上の整数）と警告の基準（0 より大きく 1 以下、小数 3 桁まで。省略は 0.8）を設定（ORDER_MANAGE、JSON）。200 で設定後の状態を返す。値が不正なら 400 で何も変えない。存在しないプロジェクトは 404。上限を超えると、そのプロジェクトの Run のコーディング CLI の割り当てだけが拒否される（他のプロジェクトと全体には影響しない） |
| `DELETE /api/projects/{id}/budget` | 月の上限を解除する（ORDER_MANAGE、JSON、本文は `{}`）。204（上限が無くても 204）。消化額は残る。存在しないプロジェクトは 404。手順は `docs/runbook-selfdev.md` 4.18 |
| `GET` / `POST /api/projects/{id}/tasks` | 開発目標（`/api/selfdev/goals` と同じ応答）の一覧・投入（ADR 0022 PR-E2）。投入の body の `projectId` は省略可で、あれば path と一致が必要（不一致は 400）。path のプロジェクトが存在しなければ 404。認可は path のプロジェクトで判定する |
| `GET` / `POST /api/projects/{id}/orders` | order の一覧・作成（`/api/selfdev/orders` と同じ応答）。body の `projectId` の規則は tasks と同じ（PR-E2） |
| `GET /api/projects/{id}/approvals?state=PENDING` | 承認待ちの停止要求（`/api/selfdev/stop-requests` と同じ応答）。`state=PENDING` は必須（PR-E2） |
| `GET /api/projects/{id}/memory` | 記憶の一覧（`/api/selfdev/memory` と同じ検索条件と応答）（PR-E2） |
| `GET /api/projects/{id}/assignees` | そのプロジェクトのメンバー（担当）の一覧（`/api/selfdev/assignees` と同じ応答）（PR-E2） |
| 旧 `/api/selfdev/goals`、`/api/selfdev/orders`、`/api/selfdev/stop-requests`、`/api/selfdev/memory`、`/api/selfdev/assignees`（projectId を受け付ける GET と POST の一部） | 応答は変えない。`Deprecation: true` と `Link: <…/api/projects/{id}/…>; rel="successor-version"` を付ける（PR-E2）。`/api/selfdev/runs` と `POST /api/selfdev/tasks` は移行先がまだないので対象外。手順は `docs/runbook-selfdev.md` 4.19 |
| `GET /api/runs/{id}/operations` | 自分の Run の Operation を作成順に最大 100 件（読み取り専用）。他人の Run・存在しない Run は同じ 404。`{schemaVersion, runId, operations, limit, truncated}`。`?after=<operationId>` で次ページを取得でき、`truncated=true` のとき応答の `nextAfter` がその値（不正な `after` は 400）。`state` は Journal の状態名をそのまま返す（UNKNOWN / RECONCILIATION_REQUIRED を成功・完了とは表示しない）。トークン、request hash、外部参照、生のエラー、Evidence は含まない |

応答（Run のスナップショット）: `{runId, taskId, title, state, mode, executed, effect, reasonCode, createdAt, updatedAt, ...}`。投入は `state` が `CREATED`、`mode` が `SIMULATED`、`executed` が `false`。自己開発の Run は `mode` が `SELF_DEV` で、`title` と `executed` は null（タイトルは返さない）、`effect` は `CREATES_PULL_REQUEST`。PR の番号とリンクは DB の binding から作る（GitHub は呼ばない）。
入力: `title` は 1〜200 文字（前後の空白は除去）、`idempotencyKey` は 1〜128 文字、どちらも制御文字を含まないこと。違反は 400。

**認証（`KIVRA_AUTH_MODE`）**

- `oidc-jwt`（本番）: Keycloak のアクセストークン（JWT）を kivra 自身が検証する。トークンは `Authorization: Bearer <JWT>`、無ければ `X-Forwarded-Access-Token` から取る。
  検証は、署名（JWKS、RS256 のみ）、`iss`、`aud`、`exp`、`nbf`（時計のずれは 30 秒まで許容）、必須ロール（`resource_access.<クライアント>.roles`）。
  principal は `email`（`email_verified` が true のときだけ）、無ければ `preferred_username`、無ければ `sub`。検証に失敗したら全て 401（理由は本文に出さない）。
  JWKS はキャッシュし、未知の kid のときだけ、最小 30 秒の間隔で再取得する（ローテーション対応。読み取りのみ）。
  - `KIVRA_OIDC_ISSUER`（必須。例 `https://user.kigawa.net/realms/kigawa-net`）
  - `KIVRA_OIDC_AUDIENCE`（必須。例 `kivra-dev`）
  - `KIVRA_OIDC_REQUIRED_ROLE`（必須。`<クライアント>:<ロール>` 形式。例 `admin-panel:admin`）
  - `KIVRA_OIDC_JWKS_URL`（任意。既定は `<issuer>/protocol/openid-connect/certs`）
  - 必須設定が無い、またはロール形式が不正なら起動に失敗する。
  - oauth2-proxy を使う場合は `--pass-access-token=true`（`--pass-authorization-header=true` でも可）を付ける。
- `oauth2-proxy`（development のみ）: `X-Auth-Request-Email`（無ければ `X-Auth-Request-User`）を**信頼する**。どちらも無い、空、256 文字超、制御文字を含む場合は 401。`KIVRA_RUNTIME_ENV=development` 以外で指定すると起動に失敗する。
- `none`: 認証なし。全員が同じ開発用の principal になる。`KIVRA_RUNTIME_ENV=development` のときだけ許可し、それ以外で指定すると起動に失敗する。本番では使わない。
- 未設定（既定）: Task API のルートを登録しない（`/api/tasks` は 404）。fail-closed。

**なぜヘッダー信頼（`oauth2-proxy`）をサーバーで使わないか**: ヘッダーを信頼するモードは、kivra に oauth2-proxy を経由せずに到達できれば、誰でもヘッダーを偽装して他人になりすませる。
対策として NetworkPolicy で他の経路を塞ぐ案があったが、**クラスタの CNI は Flannel で、NetworkPolicy は実行されない**（実測: 同じ namespace の oauth2-proxy 以外の Pod から、偽装ヘッダー付きの `/api/runs` が 200 になった）。
ネットワークの分離を保証できないため、サーバーではトークンの署名検証（`oidc-jwt`）で認証し、ヘッダーは信頼しない。
偽装されたトークンは署名検証で拒否されるので、ネットワーク構成に依存しない。

**未対応**: トークンの失効確認（introspection）はしない。失効させたトークンは `exp` まで有効。

**永続化**: 既存の `DevelopmentRunRepository` が正本。V9（`task_title`, `principal`, `idempotency_key` 列と、`(principal, idempotency_key)` の UNIQUE）で追加する。
Run の `task_id` は `api:<runId>`（fusha-task のタスクとは紐づかない）、`completion_mode` は `PR_ONLY`（RELEASE には進めない）。
再起動後も同じ Run を取得できる。同じキーの並行 POST は 1 行に収束する。

**#111 のダミー API との関係**: `/api/dev/*` は `KIVRA_DEV_DEMO_ENABLED=true` かつ `KIVRA_RUNTIME_ENV=development` のときだけ有効な、メモリ上の開発用ダミーで、そのまま残す。サーバーでは使わない。

## UI（Compose Web UI）

`GET /ui/compose` で、Compose Multiplatform（Wasm）で作った Web UI を配信する。`/ui` と `/ui/` は `/ui/compose` へ 302 で転送する（旧 vanilla UI は撤去済み）。ルート `/` も `/ui/compose` へ転送する。ソースは `kivra-compose-ui/`（共通の画面）と `kivra-web/`（Wasm のビルド）。`kivra-app` の `copyComposeWebDist` タスクが `kivra-web` の Wasm 成果物を `resources/ui/compose/` に入れ、jar に同梱される。

- **有効化**: Task API と同じく `KIVRA_AUTH_MODE` が設定されているときだけ登録する。未設定なら `/ui`、`/ui/compose` は 404。
- **認証**: 画面の HTML と Wasm 自体には秘密が無いので `/ui/compose` には認証を掛けていない。画面が呼ぶ `/api/*` は Task API の認証（本番は oauth2-proxy が付ける Keycloak の JWT を kivra が検証）で保護される。
- **画面の場所**: 画面の切り替えはブラウザのハッシュ（`#/runs/<id>` など）で行い、戻る・進むと同期する。旧 UI のハッシュ形式と同じものを Compose が解釈する。
- **CSP**: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; worker-src 'self'; child-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`。あわせて `X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、`Cache-Control: no-store`。
- **ログイン・ログアウト**: ログイン後は `/ui/compose` へ戻る。ログアウト後の戻り先（Keycloak の `post_logout_redirect_uri`）も `/ui/compose`。Keycloak 側の許可 URI の登録は、このリポジトリの外の設定。


## デプロイ

- イメージ: `harbor.kigawa.net/library/kivra:<tag>`（`Dockerfile`、ビルドは `installDist`。ファット JAR は使わない）。`ENTRYPOINT` は `/app/bin/kivra-app`、ポートは 8080。実行ユーザーは非 root の `kivra`。
- 起動時に Flyway が `db/migration`（V1〜V9 など）を自動で migrate する。DB 接続先に既存スキーマがあれば未適用分だけが流れる。
- 必須で上書きする環境変数（既定値のままでは本番で使えない）: `KIVRA_DB_JDBC_URL`（既定は localhost）、`KIVRA_DB_USER`、`KIVRA_DB_PASSWORD`（既定はプレースホルダ）。JDBC URL の `time_zone='+00:00'` は UTC 固定のため残すこと。
- 本番では、アプリ用（`KIVRA_DB_USER` / `KIVRA_DB_PASSWORD`、DML のみ）と migration 用（`KIVRA_DB_MIGRATION_USER` / `KIVRA_DB_MIGRATION_PASSWORD`、DDL 権限あり）を分けること。両方を設定すると、起動時の Flyway だけが migration 用で接続し、完了後に閉じる。JDBC URL は同じものを使う。
- 任意: `PORT`（既定 8080）、`KIVRA_METRICS_ENABLED`（既定 `false`）。`true` にする場合は `KIVRA_METRICS_BEARER_TOKEN` も必須。
- ヘルスチェック: `GET /health`（イメージの `HEALTHCHECK` も同じ）。DB に触れず常に 200 を返す（liveness 用）。
- Readiness: `GET /ready` は DB に `SELECT 1`（既定 2 秒でタイムアウト、結果は 3 秒キャッシュ）を発行し、成功なら 200 `{"status":"ready"}`、失敗・タイムアウトなら 503 `{"status":"not_ready"}`。k8s の readinessProbe は `/ready`、livenessProbe は `/health` を使う（DB 障害で liveness が落ちてアプリが再起動を繰り返すのを避けるため）。
- 本番の既定値: Recl への mutation は無効（`MutationCapability.DISABLED` / `ReclConfig.mutationsEnabled=false`）、Reaper の自動削除は無効（`autoDeleteEnabled=false`）。Replay について切り替え用の環境変数は無い。
- CI/CD（`.github/workflows/cd.yml`）: PR はイメージのビルドと push のみ（`pr-<番号>-<sha>`）。main への push と main での手動実行は `main-<sha>` を push したうえで、dev のマニフェスト（`kigawa-net/kigawa-net-k8s` の `kivra/dev/kivra.yaml`）を更新する PR を作成する。テストは `ci.yml` が担当する。

## 実装上の要点

- **単一の Run 正本**: `development_runs` / `DevelopmentRunState`（CREATED / PLANNING / PREPARING / DEVELOPING / TESTING / REVIEWING /
  PULL_REQUEST / WAITING_APPROVAL / RELEASING / VERIFYING / RETRY_WAITING / COMPLETED / FAILED / CANCELLED）と Stage/Attempt の `stage_executions`。
  fusha-task の task は `task_id` 参照のみで、タスク側の状態は Projection（ADR 0001）。
- **Run Lease**（設計 #10 §5.2 / #11 §3）: `development_runs` 行に `lease_owner / lease_generation / lease_expires_at` を同居させ、
  取得は期限切れ（または未保有）のときだけ `generation + 1`。renew と状態遷移は
  `lease_owner` と `lease_generation` の一致に加えて **`lease_expires_at > NOW(6)`** が必須（期限判定は DB 時計のみ）。
  期限切れ後は renew で復活できず、再取得は新しい acquire のみ。
- **Hold / Cancel は実行中の Worker にも適用（fail-closed）**: `hold_reason` が付くと acquire だけでなく renew（false）、
  `requireLease` を通る全書き込み（Run 遷移・Journal・Outbox）が `RunHaltedException` で失敗する。`cancel_requested` も同様で、
  Worker に許されるのは CANCELLED への遷移（cancel の受領）だけ。Worker が消えた場合は `cancelUnowned`。RELEASING/VERIFYING は cancel 不可（rollback は DEC）。
- **Operation Journal（`external_operations`）**: `(run_id, op_type, idempotency_key)` の一意制約、`PREPARED → IN_FLIGHT → …` の状態機械 + `(status, version)` の CAS。
  書き込みは全て Lease の検証（行ロック）から始まり、旧 Worker は Journal も更新できない。
  - **IN_FLIGHT の出口は証拠なしで `CONFIRMED` と `UNKNOWN` だけ**。timeout / 503 は「送信されなかった」の証明にならない。
    `IN_FLIGHT → RETRYABLE` は `markNotSent(ref)`、`IN_FLIGHT → FAILED` は `markRejected(ref)` のみ（`ref` は `VerifiedEvidenceRef`）。
    汎用 `transition()` からは到達できない（拒否される）。呼出し側は証拠を自分で作れず、`TrustedEvidenceAuthority`（#82）が検証・永続化した不透明な ref だけを渡せる。
    Journal は ref を行の subject（tenant / run / op / provider / action / request digest / contract version）と purpose に照らして解決し、期限切れ・失効・別 subject・Capability 降格・Authority 利用不可は拒否する（行は変更されない）。
  - **UNKNOWN は fail-closed**: 証拠なしに出られるのは `RECONCILIATION_REQUIRED` のみ。`UNKNOWN → RETRYABLE/CONFIRMED/FAILED`（と
    `RECONCILIATION_REQUIRED → CONFIRMED/FAILED`）は `reconcile()` に検証済み ref を渡したときだけ可能で、Evidence ID / digest / authority principal / capability / 期限は行に永続化される（secret は保存しない）。
    未来の observedAt、行の現状態より前の observedAt、purpose と遷移の不一致は拒否する。
  - **RETRYABLE に戻れるのは `AUTHORITATIVE_ABSENCE` / `AUTHORITATIVE_IDEMPOTENCY` の ABSENT だけ**。`LOOKUP_BY_STABLE_REF` の list/get 未検出や
    `MANUAL_OPERATOR` では戻れない（exkes の Runtime / Execution POST は Idempotency-Key を持たないため、未検出だけでは外部作成済みを否定できない）。
    外部に既に存在していた（EXISTS）なら CONFIRMED で取り込み、再送しない。
  - **maxAttempts**: `RETRYABLE → IN_FLIGHT` は `attempt < max_attempts` のときだけ。超過時は IN_FLIGHT にせず `FAILED`（理由 `MAX_ATTEMPTS_EXCEEDED`）に倒す。
    呼び出し側は返り値の status を確認してから送信すること。
  - **再起動時スイープ**: Run Lease の acquire は同一トランザクションで、前世代が残した `IN_FLIGHT` を `UNKNOWN`（理由 `SWEPT_STALE_IN_FLIGHT`）へ移す。
    新しい Owner は、その呼び出しを再送しない。
- **Outbox 配信契約**: **at-least-once**。`claim(publisherId, limit, ttl)` が条件付き UPDATE で時間制限付きの claim を取り、
  生きた claim の間は他の Publisher に渡らない。Publisher が落ちて claim が切れると別 Publisher が再配信するので、
  **Consumer は `event_id` で重複排除すること**（`inbox_events`）。順序は Run 内のみ保証（同一 Run は最小 version の PENDING だけ claim 可能、DEAD は後続を塞がない）。
  各 claim は新しい `claim_token`（UUID）を持ち、`markPublished(eventId, publisherId, claimToken)` / `markFailed(...)` は
  その claim が生きている間だけ成功する。同じ publisherId で再 claim された後に古い Attempt が報告しても拒否される（ABA 防止）。`markFailed` は行ロック + `publish_attempts` の CAS で lost update なし、
  上限で DEAD。Publisher / Consumer worker 自体は未実装。
- **Run completion mode（M8）**: `development_runs.completion_mode`（PR_ONLY / RELEASE）は Run Admission で一度だけ決め、以後変更する経路はない（[ADR 0001](docs/adr/0001-run-source-of-truth.md)）。
  - RELEASE: RELEASING → VERIFYING → COMPLETED（従来どおり）。PR_ONLY: 汎用 `transition()` から COMPLETED / RELEASING / VERIFYING へは行けない。
  - PR_ONLY は `completePrOnlyRun(lease, version, actor)` だけで PULL_REQUEST / WAITING_APPROVAL → COMPLETED になる（Issue #53）。
    呼び出し側は証拠を渡せない。Repository は注入された `TrustedPrMergeAuthority` を呼び、その検証結果だけで完了する。
    - Repository はコンストラクタで Authority を必須で受け取る（デフォルトなし）。実装は `PrMergeAuthorityBase` を継承し、
      `observe()` で GitHub の応答を報告する。`verifyMerge()` は final で、core の内部 verifier が束縛と照合する。
      `VerifiedPrMerge` のコンストラクタは private で、生成は core の内部だけ。kivra-core の外から verifier は呼べない。
    - Run は `bindPullRequest()` で repository / repository id（不変 ID）/ PR 番号 / node id / base ref / head SHA / policy revision / review manifest digest を記録する（V7 で追加した列を含む）。
    - 完了は、束縛と全項目が一致し、`merged == true`、Merge commit SHA が 40 桁の hex、観測時刻が未来でなく 10 分以内（`PrMergeObservationPolicy`）の場合だけ。
      Squash / Rebase の merge commit SHA は head SHA と異なってよい。
    - 検証の前に読んだ束縛と、トランザクション内で読み直した束縛を再照合する。検証中に再 bind（push 等）されていれば拒否される。
    - Authority の拒否・利用不可（5xx / 401 / 403 / rate limit / I/O）・例外では Run、audit、outbox のいずれも変わらない。
      GitHub の 404・3xx（追跡しない）・不正応答は拒否扱い。close のみ・別 PR・別 head の merge は未完了のまま。
    - V7 以前の行（束縛列が NULL）は Authority を呼ばずに完了を拒否する（fail-closed）。
    - 未対応: Authority の本番結線（app からの組み立て。現状は組み立てコードなし）、Webhook の HMAC 検証、`policy_revision` / `review_manifest_digest` の生成と検証（#49 / #50。現状は bind 時の値をそのまま照合する）、Merge Queue の `merge_group` SHA。
    COMPLETED は終端なので、重複完了は lease 喪失で拒否され、`RunCompleted` Outbox は 1 回だけ。
- **将来の作り直し（#64）**: 最終ストレージが Fortis になれば、この SQL 実装（`FOR UPDATE` / `NOW(6)` / 生成列 / claim のロック）は作り直しになり得る。
  今回の契約（Run / Operation の状態機械、証拠規則、Evidence Authority の束縛規則、PR_ONLY 完了規則、Outbox の claim token 規則）は Storage に依存しない要件として維持する。
- **Outbox / Audit**: Run の状態遷移・監査イベント（ハッシュ連鎖）・Outbox イベントを同一トランザクションで書く。
- **SchedulePort の前提（#38）**: 起動の重複抑止（`TriggerDedupStore`）と `triggerNow` の重複抑止は、どちらも永続・原子的な claim が必要。
  `InMemoryTriggerDedupStore` は開発・テスト専用で、本番では使わない（再起動で消えるため、再送で二重起動し得る）。永続実装（Operation Journal 相当の一意キー）は未実装。
  claim が IN_FLIGHT のまま残った場合は起動時に UNKNOWN へ移し、照合されるまで再起動しない。
  `upsertJob` は名前で一意でなく lookup→POST が原子的でないため、**Leader Lease 下で単一 Worker が実行すること**が前提。重複は作成後の検出で fail-closed（Conflict）にするが、防止はしない。
  ai-scheduler 側の一意制約または Idempotency-Key 対応が入るまでの残存リスク。平文 HTTP は Bearer なし・明示 opt-in のクラスタ内/loopback のみ。
- 実 Authority（署名検証・Provider Contract・Capability 登録）は #60 / #61 / #71 の範囲で、本リポジトリには interface と test 専用の `FakeTrustedEvidenceAuthority` だけがある。
- テストは既定で H2（MODE=MySQL）、`KIVRA_TEST_DB=mariadb` で MariaDB 11 実機（上記）。CI は両方を実行する。

## GitHub Adapter（`kivra-github`、Issue #34）

- **決定的ブランチ名** `kivra/run-<runId>`。PR 本文に `kivra-run:<runId>` と `kivra-head-sha:<sha>` を埋め込み、head ブランチで open/closed PR を検索して照合する（`ensurePullRequest`）。マーカーの無い PR が同じ head にあれば `FOREIGN_OBJECT` で隔離（採用しない）。
- **POST は自動再送しない**: タイムアウト・I/O エラー・5xx は `GitHubResult.Unknown`。`ensure*` は直後に名前で照合し、見つかれば採用、見つからなければ `Unknown` のまま返す。GET のみ 5xx / I/O で最大 3 回リトライ、401 はトークンを破棄して 1 回だけ再試行。
- **失敗分類**: AUTH / PERMISSION / RATE_LIMITED（`retryAfterSeconds`）/ NOT_FOUND / CONFLICT / VALIDATION / SERVER_ERROR / NETWORK / IDENTITY_MISMATCH / FOREIGN_OBJECT / PROTOCOL。
- **対象同一性検証**: レスポンスの owner/repo・PR 番号・head ブランチ・SHA（Check Run の `head_sha`、Status の `sha`）が要求と一致しなければ `IDENTITY_MISMATCH`（PR #47 レビューの教訓）。
- **認証**: `TokenProvider` 差し込み口。`InstallationTokenProvider`（キャッシュ + 期限前更新）、`JwtSigner`（`RsaJwtSigner` は PKCS#8 PEM。鍵は設定の参照先から読み、リポジトリにコミットしない）。
- Merge は M3 では人間。Port のテストが merge/force/push/delete/update 系メソッドの不在を固定する。

### 要確認（外部仕様・未検証。実 GitHub には接続していない）

- 対象リポジトリの Ruleset / Required Checks の実態。`CheckRunsSummary.allPassed` は「全 Run 完了かつ失敗なし、かつ空でない」だけで、Required Checks 名との突合は呼び出し側（Policy）の責務。
- Reviewer Agent（App / Bot）の承認が必須レビューに数えられるか。`getReviews` は生の state と `commitId` を返すだけで、承認の有効性判定はしない（古い SHA の APPROVED を流用しないこと、#49）。
- Kivra 用 GitHub App の発行方法と最終的なトークン方式（#1 §10 M3）。GitHub の秘密鍵は PKCS#1 で配布されるため PKCS#8 への変換が必要。
- 同一 head/base の open PR 重複が GitHub 側で拒否される（422 "already exists"）前提。メッセージ文言に依存した CONFLICT 判定は実機確認が必要。
- `GET /git/ref/heads/...` の 404 はリポジトリ欠如と区別できないため、404 時に `GET /repos/{o}/{r}` で確認している。
- Check Run / Status の件数上限・ページング挙動（最大 10 ページで打ち切り、超過時は `complete=false`）。
- Tool Gateway / Operation Journal への結線は #32・#25 側（本モジュールは DB・状態機械に依存しない）。
## Approval Gate / Audit 永続層（M3, #33）

- `ApprovalRepository`（`ApprovalConsumer` 実装）: 承認の依頼・承認・却下・失効。`consume(approvalId, digest, decisionId)` は
  `status='APPROVED' AND digest=? AND expires_at>NOW(6)` の条件付き UPDATE 1 本で 1 回限り（同時実行でも勝者 1 件）。
  状態更新と `audit_events` への追記は **同一 TX（run 行ロック下）** で行い、監査が書けなければ状態更新もロールバックして false（fail-closed）。
  期限切れ掃除 `expireDue` も EXPIRED ごとに監査を同一 TX で書く。
- 承認者は `VerifiedHuman` のみ。信頼された認証基盤が検証した人間の資格を表す型で、コンストラクタは private、生成は `internal fun issue` だけ。
  文字列の subject から直接作る経路は無い。資格の生成は `HumanAssertionVerifier`（port）の実装に任せる。資格の有効期限は承認時に検査する。
  Agent / Orchestrator の `Principal` からは作れず、申請 Agent と同一なら不可（four-eyes。DB の CHECK 制約でも担保）。
  **制限**: 境界はプロセス内であり、人間の同一性の真正性は `HumanAssertionVerifier` 実装（OIDC の署名・iss・aud・exp 検証）に依存する。
  OIDC の verifier アダプタは本 PR に含まれず未実装。
- `OrphanIntentReaper`: 猶予後の孤立 INTENT（終端記録なし）を閉じる。
  - `ABANDONED` は「承認が必要かつ未消費」かつ `ExecutionPhaseAuthority`（実行側の権威的な状態照会）が `NOT_STARTED` を返した場合のみ。
    その後 `consume` は同じ decisionId を拒否するため、閉じた決定の handler は開始できない（承認自体は別の decisionId で再利用可）。
  - それ以外（承認消費済み、承認不要、`STARTED`、未確認、照会の例外）はすべて `UNKNOWN`（照合必須。推測で再実行しない）。
  - `ExecutionPhaseAuthority` の本番実装（Gateway / Coordinator 側の実行フェーズ store と、開始との原子的な CAS）と、定期実行の配線は未実装。
- 監査ログ（ハッシュ連鎖 `audit_events`）の保証範囲:
  - 検知できるもの: 残っているイベントの改変、途中の削除、並べ替え（`verifyChain`）。
  - **検知できないもの**: DB 書込み権限を持つ主体はハッシュを再計算できるため、改竄の隠蔽は防げない。
    **最新イベントの末尾切り詰め、および run の全イベント削除は、チェーン単独では検知できない**（残ったチェーンは有効のまま）。
  - 外部アンカー: 監査 Tx 内で `enqueueAuditHeadAnchor(tx, audit, outbox, runId)` が現在の head（seq・hash）を `AUDIT_ANCHOR`
    種別の Outbox イベントとして書くだけで、外部 I/O は行わない（DB Lock・Tx Rollback と外部障害を切り離す）。
    配送は Tx コミット後に `AuditAnchorPublisher.publishDue()` が Outbox 経由で行い、冪等キーは event_id。
    `AuditHeadAnchor` 実装（WORM ストア、別アカウントのログ、署名付きノート等）は event_id で重複排除すること。
    アンカー先の永続化と publisher の定期実行スケジュールは未実装。
    保存済みの head と現在の head を比べれば末尾切り詰めを検知できる。
  - 運用で必要な前提: 運用 DB ロールに `audit_events` の UPDATE / DELETE を付与しない（マイグレーションでは強制していない）。
    強い監査保証を Admission 条件にするには、外部アンカーの実装と権限分離が前提になる。
