# ai-scheduler 連携: 現状の API と要確認・依頼事項 (Issue #38)

調査元: `kigawa-net/ai-scheduler` の `origin/main`（2026-10-12 時点。`routes/JobRoutes.kt`, `routes/ExecutionRoutes.kt`, `V6__snapshot_and_idempotency.sql`, `docs/decisions.md`）。
設計: #1 §4.4 / §10 M6 / §11、#3 §11 / §15 C5。実 API には接続していない（テストは MockEngine / Fake のみ）。

## 原則

ai-scheduler は「いつ起こすか」だけを担う。**Orchestrator は ai-scheduler が止まっても動く**（自前ループ／タイマが主、ai-scheduler は追加の起動役）。
Run の正本は Kivra DB で、ai-scheduler の Execution 状態は Run 状態に写さない。`kivra-scheduler` のどの呼び出しも失敗し得て、失敗は「追加の起動が無い」だけを意味する。

## 実 API の確認結果

| 項目 | 実 API (main) | `kivra-scheduler` の扱い |
|---|---|---|
| Job 作成 | `POST /v1/jobs`（name, trigger CRON/ONCE/MANUAL, model, prompt 必須）。**name の一意制約なし** | 決定的な名前 `kivra:fusha:{env}:{id}` で `GET /v1/jobs` を先に検索し、無い場合のみ POST。UNKNOWN 後は名前で照合 |
| Job 取得/一覧 | `GET /v1/jobs`, `GET /v1/jobs/{id}`（`trigger.lastRunAt/nextRunAt` を含む） | 応答の id 一致を検証 |
| Job 更新 | `PATCH /v1/jobs/{id}`（**trigger は変更不可**、enabled 等は可） | 一時停止/再開 = `enabled` の PATCH。trigger 差異は `ScheduleConflictException` |
| Job 削除 | **なし** | `deleteJob` = 無効化（論理削除。名前は予約されたまま） |
| 手動実行 | `POST /v1/jobs/{id}/executions`（`scheduledAt = now`、クライアント指定不可） | 1 回だけ送信。同一キーはプロセス内で同一 Execution を返し、UNKNOWN 後は再送せず拒否 |
| Execution 取得 | `GET /v1/executions/{id}` | id 一致を検証。output/error は保持しない |
| Execution 一覧 | **なし**（`GET /v1/jobs/{id}/executions` は無い。POST のみ。Web UI は HTML） | `listExecutions` は `ScheduleUnsupportedException`（Fake は対応） |
| cancel / retry | `POST /v1/executions/{id}/cancel|retry`。終端/非対象は 409 | 409 → `ScheduleConflictException` |
| Idempotency Key | **ヘッダは未実装**。サーバ側は `UNIQUE(job_id, scheduled_at)` のみ | `Idempotency-Key` ヘッダを送る（サーバ未対応でも無害）。実際の抑止は Kivra 側 `(jobId, scheduledAt)` の重複排除 |
| 認証 | **なし**（Phase 1） | `ScheduleTokenProvider`（既定 NoAuth）で Bearer を差し込める |
| Callback / Webhook | **なし**（Job は AI の prompt を実行するもので、外部 URL を呼ぶ手段が無い） | Polling（`SchedulePoller`: Job の `lastRunAt` を観測し `(jobId, scheduledAt)` で重複排除） |
| `feat/timeout` | `origin/feat/timeout` に実装済み・**main 未マージ**（2026-10-12 時点） | 依存しない。タイムアウトは Kivra 側の応答待ちで扱う |

## 要確認（結果通知方式・認証・未マージ）

1. **結果通知方式**: Callback / Webhook は現状不可。Polling のみ可能（`lastRunAt` 観測は近似で、Job 内の個別 Execution までは追えない）。Callback 対応の予定があるか。
2. **認証導入時期**: Phase 1 は無認証。導入時の方式（Bearer / OIDC）とトークン発行元。導入までは **ネットワーク限定**（NetworkPolicy で Kivra Pod のみ許可）。
3. **`feat/timeout` のマージ時期**（#1 §11-4、#2 §13-6）。
4. Job に prompt/model が必須。wake-up 専用 Job でも AI 呼び出しが走るのか（no-op provider の有無）。現状は設定値 `provider=none, model=none, placeholderPrompt` を送る想定で、実環境で受理されるかは未確認。

## ai-scheduler への依頼候補（スコープ外: Kivra からは改修しない）

- `GET /v1/jobs/{id}/executions`（新しい順、`since` 指定）: UNKNOWN の照合と Polling の精度に必要。
- `POST /v1/jobs/{id}/executions` が `Idempotency-Key` / 指定 `scheduledAt` を受け付ける（at-least-once の重複起動をサーバ側でも防ぐ）。
- `DELETE /v1/jobs/{id}`、name の一意制約（決定的名前による照合の競合防止）。
- trigger を更新できる PATCH。
- Callback（Execution 終端時に Kivra の起動エンドポイントへ通知）と認証。

## 接続設定（ネットワーク限定を前提）

- `AiSchedulerConfig.baseUrl` は **https が既定**。クラスタ内 http は `allowInsecureHttp = true` の明示指定が必要。userinfo / query / fragment を含む URL は拒否。
- リダイレクトは追従しない（`aiSchedulerHttpClient` が `followRedirects = false`。3xx は失敗扱い）。Bearer が別ホストへ渡るのを防ぐ。
- 到達性は NetworkPolicy / Service で Kivra のみに限定する。Ingress で公開しない。
- タイムアウト: 接続 5s / リクエスト 15s（既定）。GET は最大 3 回まで再試行、POST/PATCH は自動再送しない（タイムアウト・転送エラー・5xx は `ScheduleUnknownException`）。
- トークン・prompt・応答本文は例外メッセージ / `toString` に出さない。

## UNKNOWN の照合手順

- Job 作成: 同じ `upsertJob` を再実行すると名前で既存 Job を検出する（二重作成しない）。並行作成は競合し得るため Leader Lease 下で実行する。
- `triggerNow`: ai-scheduler は Execution 一覧もキー照合も持たず、手動実行が起きたか確認できない。呼び出し側は Operation Journal（UNKNOWN → RECONCILIATION_REQUIRED）で扱い、証拠なしに再送しない。同一キーは本アダプタも再送を拒否する。
