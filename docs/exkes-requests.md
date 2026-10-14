# exkes 連携: 確認できた事実・未対応機能・exkes 側要望(Issue #29)

確認元: `kigawa-net/exkes` の `origin/main`(2026-10-12 時点。ローカルの未コミット変更は参照していない)。
`exkes-api/.../routes/{Workspace,Runtime}{Routes,Dtos}.kt`、`ErrorPages.kt`、`exkes-common/.../domain/*.kt`、DB マイグレーション。
exkes の実 API には接続していない(`kivra-exkes` のテストは MockEngine + インメモリ simulator のみ)。
「要確認」は実コードで確認できなかった/設計文書間で食い違う項目で、Adapter は確認できた事実のみに依存する。

## 1. 確認できた事実(Adapter が依存する)

| 項目 | 事実 | 出典 |
|---|---|---|
| Workspace | `POST/GET /v1/workspaces`、`GET/DELETE /v1/workspaces/{id}`。POST/DELETE は 202。状態 CREATING/READY/ARCHIVED/DELETING/DELETED/ERROR | WorkspaceRoutes, Workspace.kt |
| Workspace 名 | 正規表現 `[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}`、`workspaces.name` に UNIQUE 制約。重複 POST は 409 CONFLICT | WorkspaceService, V1__workspaces.sql, ErrorPages |
| Runtime | `POST/GET /v1/workspaces/{ws}/runtimes`、`GET/DELETE .../{id}`、`POST .../{id}/start|stop`。POST は 202(CREATING)。`templateId` 必須、`labels` 指定可。状態 CREATING/STARTING/RUNNING/STOPPING/STOPPED/FAILED/DELETING/DELETED/EXPIRED | RuntimeRoutes, Runtime.kt |
| Execution | `POST/GET /v1/runtimes/{rt}/executions`、`GET .../{id}`。POST は 202(QUEUED)。type は COMMAND/AGENT/SYSTEM。状態 QUEUED/STARTING/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT | RuntimeRoutes, Execution.kt |
| 一覧 | `limit`(最大 500)/`offset`、`status` フィルタのみ。応答は `items,total,limit,offset`。Workspace 一覧は DELETED(墓標)を返さない | WorkspaceService, WorkspaceRepository |
| エラー形式 | `{"code","message"}`(NOT_FOUND 404 / CONFLICT 409 / VALIDATION_ERROR 400 / INTERNAL 500) | ErrorPages |
| Execution 名前空間 | Execution は Runtime 配下(`/v1/runtimes/{rt}/...`)、Runtime は Workspace 配下。Ref は親 id を保持する | Routes |

注意: REST 受付が実装済みでも、Sandbox 内 runtime-agent による実プロセス実行・ログ/成果物・Cancel の E2E 完了は意味しない
(exkes PR #22/#23 は未マージの Draft)。実環境での結合試験は未実施。

## 2. 未対応・不明(要確認)と Kivra 側の暫定対処

| # | 項目 | 状態 | 暫定対処(Adapter) | exkes 側要望 |
|---|---|---|---|---|
| 1 | 冪等キー(`Idempotency-Key` / operationId) | 無し(POST 3 種すべて) | POST は自動再送しない。タイムアウト/5xx/接続断は `PortResult.Unknown`(Journal は UNKNOWN)。`ProviderCapability.AUTHORITATIVE_IDEMPOTENCY` は主張しない | 作成系 POST に `Idempotency-Key` を受け、同一キーの再送で同一リソースを返す |
| 2 | Workspace の name 検索 | 無し(list の `status` のみ) | 全件ページングしてクライアント側で完全一致。決定的名前 `kivra-run-<runId>` + サーバー側 UNIQUE により、POST 重複は 409 で防がれ、照合は `findWorkspaceByName` | `GET /v1/workspaces?name=` |
| 3 | label 検索 | 無し | Runtime は作成時に `kivra.run-id` ラベルを付け、Workspace 内の一覧をクライアント側でフィルタ | list の `label.<k>=<v>` フィルタ |
| 4 | Execution へのラベル/識別子 | `CreateExecutionRequest` に無し。Execution は照合不能 | `singleExecutionGuard`(既定 ON): Runtime に Execution が 1 件でもあれば POST せず `Conflict`。POST 結果不明なら一覧で存在確認はできるが「自分の」Execution か断定できない。Run ごとに専用 Runtime を使う前提(要確認) | Execution 作成時の `labels` と冪等キー。一覧の label フィルタ |
| 5 | Execution の取消 API | 無し(Runtime stop/delete のみ) | `cancelExecution` は `Unsupported`。回避策は `stopRuntime`/`deleteRuntime`(Runtime 内の全 Execution が止まる) | `POST /v1/runtimes/{rt}/executions/{id}/cancel`(状態表は CANCELLED を既に持つ) |
| 6 | ログ取得 API | 無し(`logObjectKey` のみ) | `readLogs` は `Unsupported`。`Execution.logObjectKey` は参照用に保持 | `GET .../executions/{id}/logs`(cursor 付き) |
| 7 | 成果物 / checkout API | 無し(`artifactObjectKeysJson` のみ、Adapter は未使用) | 未実装 | 成果物一覧/取得 API |
| 8 | 認証 | 未実装(Keycloak OIDC は後続、client credentials 想定は要確認) | `TokenProvider` を差し込み、non-null なら `Authorization: Bearer`。現状はネットワーク限定で利用 | OIDC 導入時の audience/scope/クライアント方式の提示 |
| 9 | TOCTOU | 「検索してから POST」の間に競合し得る | Workspace は UNIQUE で守られる。Runtime/Execution は完全には守れない(サーバー側の一意制約/冪等キーが入るまで冪等性を主張しない) | #1 と #4 |
| 10 | Execution 作成の前提 | Runtime が RUNNING の時のみ(Issue 記載。応答コードの詳細は未確認) | 非 RUNNING は呼び出し側で待機。409 は `Conflict` として返す | 非 RUNNING 時のエラーコードの明文化 |
| 11 | Runtime テンプレート | `templateId` 必須。Kivra がどの template を使うかは未決 | `RuntimeSpec.templateId` を呼び出し側が渡す | — |
| 12 | Agent の実体 | Execution type=AGENT で Claude Code 等を動かすか、Kivra の LLM ループを置くかは未決(#1 §11-2, #5 §13) | `ExecutionSpec` は type/commandJson/agentConfigJson をそのまま透過 | — |
| 13 | 状態名の最終形 | 実コードの現行 enum に追従。未知の名前は `UNRECOGNIZED` | — | 状態追加時の互換方針 |
| 14 | stop/delete の再実行 | 冪等かどうか未確認 | `Unknown` を返し自動再送しない(呼び出し側が GET で状態確認) | DELETE/stop の冪等性の明文化 |
| 15 | 設計文書間の食い違い | #1/#3/#7 で Runtime/Execution の実装状況の記述が異なる | 実コード(上表)を正とした | exkes README の更新 |

## 3. Operation Journal 契約との対応

M0 の `OperationStatus`/`ReconciliationEvidence` に従う。`PortResult.toOperationStatus()` の対応:

| PortResult | OperationStatus | 備考 |
|---|---|---|
| Ok | CONFIRMED | 202 受理 = 作成要求の確定。実体の状態は GET で追跡(Polling) |
| Unknown / IdentityMismatch | UNKNOWN | 自動再送禁止。照合後に `ReconciliationEvidence` で遷移 |
| Transient | RETRYABLE | 送信前に確実に失敗したもの(接続拒否・接続タイムアウト・DNS 失敗・トークン取得失敗)。NotSentProof の根拠になる唯一の型(ADR 0005) |
| Retryable | UNKNOWN | 読み取り(GET)の 5xx・429・timeout など。再試行はしてよいが未送信の証拠ではないため RETRYABLE にしない(ADR 0005) |
| Rejected / NotFound / Unsupported | FAILED | |
| Conflict | (null) | 呼び出し側で照合してから判断 |

照合手段と Evidence の目安(最終判断は Agent Manager #30 / Reaper #31):

- Workspace: `findWorkspaceByName("kivra-run-<runId>")`。存在 → `EXISTS`、不在 → `ABSENT`、いずれも `LOOKUP_BY_STABLE_REF`。
  (UNIQUE 制約により再作成しても重複しない。ただし DELETED 墓標は list に出ないため、削除済みと未作成は区別できない: 要確認)
- Runtime: `findRuntimesByLabel(ws, {kivra.run-id})`。`LOOKUP_BY_STABLE_REF`。
- Execution: 一覧で存在は分かるが帰属を断定できない。不在でも `ABSENT` を権威ある証拠として扱うのは、Runtime が Run 専用であることが前提。
  それ以外は `RECONCILIATION_REQUIRED` のまま人手/修復へ。

## 4. レスポンス同一性検証(PR #47 レビューの教訓)

応答の id / 親 id / name / type が要求と食い違う場合は `PortResult.IdentityMismatch` を返し、応答を採用しない
(GET・作成・stop/delete・一覧の各要素に適用)。作成系での不一致は効果の有無が不明なため UNKNOWN 扱い。
