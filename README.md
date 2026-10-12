# kivra

自己開発型 kigawa 補助 AI。設計の正本は GitHub Issue（設計 #9〜#12、および #60 / #61 / #64）。本リポジトリに `docs/design/` は無い（設計文書は別 PR / Issue で管理）。

現在は M0（Core 骨格と DB スキーマ）まで。Adapter / Orchestrator / Policy 等は後続マイルストーン。

## 構成

| モジュール | 内容 |
|---|---|
| `kivra-core` | ドメイン型、状態機械（DevelopmentRun / Agent / ExecutionSession / OperationStatus）、Port インターフェース（ExecutionPort / TaskProvider / MemoryPort / SchedulePort、定義のみ）、`LeaseStore` |
| `kivra-app` | Ktor アプリ、設定読込、`/health`、Flyway マイグレーション、Exposed テーブル定義、Journal / Outbox / Inbox / Audit / Lease のリポジトリ |

DB スキーマは `kivra-app/src/main/resources/db/migration/`（V1 コア、V2 照合証拠列・Outbox claim token・RunCompletionMode / PR 証拠列、V3 approvals。V2 は未マージのため編集中、マージ後は V4 以降のみ追加）。Run の正本は `development_runs` の DevelopmentRun のみ（[ADR 0001](docs/adr/0001-run-source-of-truth.md)）。構文は MariaDB と H2 MODE=MySQL の両方で動くものに限定している。

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
