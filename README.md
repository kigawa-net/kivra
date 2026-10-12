# kivra

自己開発型 kigawa 補助 AI。設計の正本は GitHub Issue（設計 #9〜#12、および #60 / #61 / #64）。本リポジトリに `docs/design/` は無い（設計文書は別 PR / Issue で管理）。

現在は M0（Core 骨格と DB スキーマ）まで。Adapter / Orchestrator / Policy 等は後続マイルストーン。

## 構成

| モジュール | 内容 |
|---|---|
| `kivra-core` | ドメイン型、状態機械（DevelopmentRun / Agent / ExecutionSession / OperationStatus）、Port インターフェース（ExecutionPort / TaskProvider / MemoryPort / SchedulePort、定義のみ）、`LeaseStore` |
| `kivra-model-gateway` | Model Gateway 最小実装(#35): 型・エラー分類、Model Registry(YAML)、制約付きルーティング、予算(予約/確定/解放)、Anthropic Adapter(Ktor)、監査/メトリクスのフック(永続化は別 Issue)。Core / DB には依存しない。Registry 例は `kivra-model-gateway/config/model-registry.example.yaml`(価格等はプレースホルダ)。API キーは信頼管理者が固定する `CredentialBinding`(providerId→許可ホスト/ラベル/環境変数名)経由でのみ解決し、Registry の endpoint/credentialRef は任意の秘密を指せない(HTTPS・ホスト一致必須、リダイレクト不追従)。同一 requestId は `RequestLedger`(予約・結果保存・照合、eviction なし、SHA-256 の正規化 Request Digest で束縛)で冪等化する。永続 Ledger は未対応(M0 の DB 契約に依存)で、`InMemoryRequestLedger` は dev/test 専用として `devAllowInMemoryLedger = true` を明示しない限り Gateway 構築が失敗する。送信済みの可能性がある失敗(5xx・接続断・解析失敗・ストリーム途中失敗・HTTP 応答を伴う 4xx/429 を含む)は予約を見積額で確定し、同一 requestId の Retry / Fallback を行わない(新しい requestId が必要)。送信前に確実に失敗した場合(資格情報未設定・接続拒否・DNS 失敗)のみ予約を解放し Retry / Fallback する。呼出し側 Agent が DataClass を下げられないよう、DataClass は Trusted Orchestrator/Policy Gateway が固定した値を渡すこと(M4 公開 API の前提)。Budget はインメモリで本番の Hard Limit としては使わない(永続化・インスタンス跨ぎは別 Issue) |
| `kivra-fusha-task` | fusha-task 向け `TaskProvider` 実装（read-only の `FushaTaskAdapter`、`FakeTaskProvider`、Bearer 認証、timeout / retry / backoff）。M1 #27 |
| `kivra-memory` | `MemoryProvider` ポートと型（MemoryEntry / ScopeRef / SourceRef / ContextBundle / HandoffRecord）、`InMemoryWorkingMemory`、`FakeMemoryPort`、縮退層 `MemoryService`（Write Queue・circuit breaker）、Secret フィルタ、recl 向け `ReclAdapter`（**計画 API に基づく骨格。実 API は存在せず未接続**）。M5 #37 |
| `kivra-app` | Ktor アプリ、設定読込、`/health`、Flyway マイグレーション、Exposed テーブル定義、Journal / Outbox / Inbox / Audit / Lease のリポジトリ |

DB スキーマは `kivra-app/src/main/resources/db/migration/`（V1 コア、V2 照合証拠列・Outbox claim token・RunCompletionMode / PR 証拠列。V2 は未マージのため編集中、マージ後は V3 以降のみ追加）。Run の正本は `development_runs` の DevelopmentRun のみ（[ADR 0001](docs/adr/0001-run-source-of-truth.md)）。構文は MariaDB と H2 MODE=MySQL の両方で動くものに限定している。

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

## 設定

HOCON（`kivra-app/src/main/resources/application.conf`）。環境変数で上書きできる。

| 環境変数 | 既定 | 内容 |
|---|---|---|
| `PORT` | 8080 | HTTP ポート |
| `KIVRA_DB_JDBC_URL` | `jdbc:mariadb://localhost:3306/kivra?sessionVariables=time_zone='+00:00'` | JDBC URL |
| `KIVRA_DB_USER` / `KIVRA_DB_PASSWORD` | `kivra` / `change-this-in-production` | DB 認証情報（本番では必ず上書き） |
| `KIVRA_LEASE_TTL_SECONDS` | 30 | Run Lease の TTL |
| `KIVRA_LEASE_GRACE_SECONDS` | 5 | 期限切れ後、奪取を許可するまでの猶予 |

fusha-task（`kivra.fusha.*`、**すべて要確認**: 環境ごとの URL・機械向け認証方式は未確定）:

| 環境変数 | 既定 | 内容 |
|---|---|---|
| `KIVRA_FUSHA_BASE_URL` | `http://localhost:8787/api/v1` | fusha-task の API ルート（`/api/v1` を含む） |
| `KIVRA_FUSHA_ENV` | `dev` | TaskId (`fusha:{env}:{id}`) の環境ラベル |
| `KIVRA_FUSHA_TOKEN` | 空 | Bearer トークン（Keycloak ID token）。client_credentials が fusha-task の aud 検証を通るかは未検証 |
| `KIVRA_FUSHA_REQUEST_TIMEOUT_MS` / `KIVRA_FUSHA_MAX_RETRIES` | 10000 / 3 | タイムアウトと GET の再試行回数（5xx・429・通信エラーを指数バックオフ + jitter、401 はトークン再取得を 1 回、400/403 は再試行しない） |

M1 の `FushaTaskAdapter` は **read-only**（get / list のみ）。update / comment は `TaskWriteResult.Unsupported` を返す
（fusha-task に楽観ロック / ETag が無く、機械認証も未検証のため。要確認）。
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
    `IN_FLIGHT → RETRYABLE` は `markNotSent(NotSentProof)`、`IN_FLIGHT → FAILED` は `markRejected(RejectedProof)` のみ。
    汎用 `transition()` からは到達できない（拒否される）。Proof は run / op id / request hash に束縛され、発行元は信頼 issuer の許可リストで検証する。
  - **UNKNOWN は fail-closed**: 証拠なしに出られるのは `RECONCILIATION_REQUIRED` のみ。`UNKNOWN → RETRYABLE/CONFIRMED/FAILED`（と
    `RECONCILIATION_REQUIRED → CONFIRMED/FAILED`）は `reconcile()` に `ReconciliationEvidence` を渡したときだけ可能で、証拠は行に永続化される。
    証拠は run / op id / request hash に束縛し、許可リスト外の issuer、未来の observedAt、行の現状態より前の observedAt は拒否する。
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
  - PR_ONLY は `completePrOnlyRun()` だけで PULL_REQUEST / WAITING_APPROVAL → COMPLETED になる。GitHub の検証済み証拠（`merged == true`、Merge SHA、
    repository と head SHA が run の `bindPullRequest` で記録した値と一致、信頼 issuer）が必要。close のみ・別 head の merge・未確定の結果は未完了のまま。
    COMPLETED は終端なので、重複完了は lease 喪失で拒否され、`RunCompleted` Outbox は 1 回だけ。
- **将来の作り直し（#64）**: 最終ストレージが Fortis になれば、この SQL 実装（`FOR UPDATE` / `NOW(6)` / 生成列 / claim のロック）は作り直しになり得る。
  今回の契約（Run / Operation の状態機械、証拠規則、Proof 規則、PR_ONLY 完了規則、Outbox の claim token 規則）は Storage に依存しない要件として維持する。
- **Outbox / Audit**: Run の状態遷移・監査イベント（ハッシュ連鎖）・Outbox イベントを同一トランザクションで書く。
- テストは既定で H2（MODE=MySQL）、`KIVRA_TEST_DB=mariadb` で MariaDB 11 実機（上記）。CI は両方を実行する。
