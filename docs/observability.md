# Observability（M0 の土台）

対象: Issue #39。設計 #1 §8、#63 の Provider Health 指標。実装はモジュール `kivra-observability`。

## 1. 相関 ID と Trace

- `TraceId`: W3C trace-id 形式（32 桁小文字 hex）。`TraceId.generate()` / `TraceId.parse()`。
- `Correlation`: `traceId` / `taskId` / `runId` / `executionId` / `agentId` の組。Span 属性として出る。
- `Tracer.withSpan(...)`: ブロックの時間計測、属性フィルタ、`SpanExporter` への送信。ブロックが例外で終わると `ERROR` で記録して再送出する。
- `SpanExporter` は Port。OpenTelemetry 等の採用は未決（設計 #1 §8 要確認）。exporter の失敗は握りつぶし、業務処理を止めない。
- traceId の伝播経路（fusha-task → Run → Agent → exkes → GitHub PR）は各 Adapter 側の実装が前提。本モジュールは土台のみ。

## 2. メトリクス

- `MetricsRegistry`: カウンタ（`counter`）・ゲージ（`gauge`）・ヒストグラム（`histogram`）。`renderPrometheus()` が text format 0.0.4 を返す。
- 外部依存なし（Micrometer は未採用。採用は収集基盤の決定後）。
- 名前・型・単位・ラベルは `MetricCatalog` が正。未宣言の名前は参照時に例外（配線ミスを起動時に検出）。記録側は例外を投げない。
- **カーディナリティ上限**: 各ファミリに `MetricSpec.maxSeries`（既定 64）。上限に達した後の新しいラベル組は記録せず捨て、`kivra_metrics_dropped_series_total{metric="..."}` を増やす。既存の Series は更新され続ける。
- **列挙値**: `MetricSpec.labelValues` で値を列挙したキーは、列挙外の値を `other` に集約する。現在の `MetricCatalog` は列挙を宣言していない（outcome / provider / model 等の値域が code 上で未確定のため）。そのため現時点で上限は `maxSeries` だけが効く。値域が確定したら列挙を追加すること。
- 禁止ラベルキー（`runId` / `executionId` / `agentId` / `taskId` / `traceId` / tenant / prompt / token 等）は `MetricCatalog.FORBIDDEN_LABEL_KEYS` に列挙し、テストで catalog に現れないことを確認する。

### 指標一覧

| 名前 | 型 | 単位 | ラベル | 用途 |
|---|---|---|---|---|
| `kivra_run_total` | counter | 1 | outcome | Run 成功率（completed / 全体） |
| `kivra_run_duration_seconds` | histogram | s | outcome | Run 所要時間 |
| `kivra_run_retry_total` | counter | 1 | operation_class | 再試行率 |
| `kivra_ci_first_attempt_total` | counter | 1 | result | CI 初回成功率 |
| `kivra_review_findings_total` | counter | 1 | severity | レビュー指摘数 |
| `kivra_rollback_total` | counter | 1 | - | Rollback 率 |
| `kivra_execution_duration_seconds` | histogram | s | outcome | exkes 実行時間 |
| `kivra_approval_wait_seconds` | histogram | s | kind | 承認待ち時間 |
| `kivra_operation_unknown_total` | counter | 1 | operation_class | UNKNOWN 件数（fail-closed） |
| `kivra_reaper_detected_total` | counter | 1 | reason | Reaper 検出数 |
| `kivra_recovery_queue_depth` | gauge | 1 | - | 照合待ち Operation 数 |
| `kivra_provider_circuit_state` | gauge | enum (0=closed,1=half_open,2=open) | provider | サーキットブレーカ状態（#63） |
| `kivra_provider_request_total` | counter | 1 | provider, outcome | Provider リクエスト結果（#63） |
| `kivra_provider_probe_total` | counter | 1 | provider, outcome | half-open プローブ（#63） |
| `kivra_provider_admission_denied_total` | counter | 1 | provider, reason | 送信前の拒否（#63） |
| `kivra_model_tokens_total` | counter | 1 | provider, model, direction | LLM トークン |
| `kivra_model_cost_micros_total` | counter | 1e-6 通貨単位（通貨は要確認） | provider, model | 既知の LLM 実コスト |
| `kivra_model_unpriced_calls_total` | counter | 1 | provider, model | 単価不明の呼び出し数 |

## 3. ラベル・属性の規則（秘密・本文を出さない）

- **メトリクスのラベル**: 低カーディナリティの列挙値だけ。`runId` / `executionId` / `agentId` / `taskId` / `traceId` / テナント / プロンプト / トークンの値は載せない。
- **許可リスト**: `MetricSpec.labels` にないキーは記録時に捨てる。Span 属性は `AttributeFilter.SPAN_ATTRIBUTE_ALLOWLIST` にないキーを捨てる。
- **値の検査** (`Redaction.isSafeValue`): 256 文字超・改行含み・秘密らしい形（Bearer、`sk-`/`ghp_`/`github_pat_`/`xox*-`/AKIA、JWT、PRIVATE KEY、`password=` 等、64 文字超の不透明な文字列）は出さない。メトリクスのラベル値は `_redacted` に置換し、Span 属性はキーごと捨てる。
- ログ: 本モジュールはロガーを持たない。ログに出す属性は `AttributeFilter` を通すこと（呼び出し側の義務）。
- テスト: `AttributeFilterTest` / `RedactionTest` / `MetricsRegistryTest` / `TraceTest` が、許可リスト外キー・秘密値・本文が出力に現れないことを確認する。

## 4. コストの Port

- `CostLedger`（`add` / `total`）: scope は `RUN` / `AGENT` / `USER`。`UsageAmount.costMicros` が null の呼び出しは `unpricedCalls` として別計上する（既知コストだけで予算を判定すると過小評価するため）。
- `BudgetPolicy` / `BudgetCheck.exceeded`: 上限超過の検知。
- 永続化はしない。`InMemoryCostLedger` はテスト・開発用で、本番の集計基盤ではない。
- Model Gateway（#35）の `GatewayMetrics` / `AuditSink` との接続は未対応（#35 が main 相当に入ってから Adapter を書く）。

## 5. /metrics エンドポイント

- `/metrics`（`text/plain; version=0.0.4`）は **既定で無効**（`kivra.metrics.enabled` = false）。無効時は登録されず 404。
- 有効化するには `kivra.metrics.enabled = true`（環境変数 `KIVRA_METRICS_ENABLED`）と `kivra.metrics.bearerToken`（環境変数 `KIVRA_METRICS_BEARER_TOKEN`）の両方が必要。トークン未設定で有効化すると起動に失敗する（fail-closed）。
- 有効時は `Authorization: Bearer <token>` が必要。欠落・不一致は 401、一致で 200。トークン比較は定数時間（`MessageDigest.isEqual`）。
- 通常 API の Listener に同居する（トークンで保護）。**別ポート・loopback 限定の Listener は未実装**（`kivra.metrics.port` は存在しない）。Prometheus の scrape 設定とトークン配布・ローテーションは運用側の決定事項。

## 6. 未対応・要確認

- **Metrics の実計測への結線は未対応**: `Application.module()` は `MetricsRegistry` を生成して `/metrics` に渡すだけで、Run / Journal / Provider / Budget / Approval の Producer には DI していない。現在の `/metrics` は HELP/TYPE 中心で実 Kivra の指標を表さない。Producer 側の結線は #60 / #61 / #62 / #63 / #35（Model Gateway）の Adapter 統合後に行う。それまで「M0 Observability E2E 完了」とは判断しない。
- `kivra_operation_unknown_total` は UNKNOWN に入った累計 Counter であり、現時点で未解決の UNKNOWN 数ではない（未解決数の Gauge は `kivra_recovery_queue_depth` 側で別途結線する）。
- 別ポート / loopback 限定の metrics Listener（上記 §5）は未実装。
- 収集基盤（OpenTelemetry / Prometheus 等）の採用有無（設計 #1 §8、#5 §16-8）。
- Provider 側の実コスト照合 API の有無（#5 §16-7）。通貨と単位（`kivra_model_cost_micros_total`）。
- `run_events` 実行履歴と監査ログ本体（#33）は DB スキーマ依存のため本 PR の対象外。
- 完了条件「1 つの Run を traceId で fusha-task から GitHub PR まで追跡」は各 Adapter（#34 等）の伝播実装待ち。
- ダッシュボード・Kill Switch・障害注入テストは対象外。
