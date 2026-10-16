# Provider Compatibility Matrix(外部 Provider の能力と本番 Capability の既定)

関連: [Kivra #71](https://github.com/kigawa-net/kivra/issues/71)(Provider Compatibility Matrix & Production Capability Gate)。
個々の外部 Operation の Dispatch / Replay Proof は #60、Trusted Resolution / Evidence は #61、Fault Injection / E2E 試験は #70 の範囲。本書は **横断的な能力の台帳** で、Operation Journal は重複して持たない。

確認基準日: **2026-10-15**。Provider 側の状態は `gh issue view -R kigawa-net/<repo>` で確認した Issue の OPEN / CLOSED と、各 Repo の `docs/*-requests.md`(2026-10-12 時点の origin/main を読んだもの)による。Kivra 側の実装は本リポジトリの `main`(fc455c8 時点)のソースによる。

## 0. 結論(最初に読む)

- **本番で有効な外部 Capability は、現時点で 0 件。** すべての Provider について、本番の既定は **無効(fail-closed)**。
- kivra-app の `src/main` は、exkes / recl / ai-scheduler / fusha-task / GitHub / Anthropic の各アダプタを**構築していない**(本番から呼ばれない)。テストと Fake だけが使う。
- 「実装が存在する」「Provider の Repo に PR が merge された」「Mock / InMemory のテストが通る」は、**本番 Capability の証拠にならない**(#71 本文 原則 1: Advertised != Verified)。
- Provider 側の API には、Kivra が必要とする冪等キー・Receipt・条件付き DELETE・照合 API の多くが**未対応**。本書の「相手側」列で確認する。

## 1. 記号

| 記号 | 意味 |
|---|---|
| 実装済み(kivra 側) | 本リポジトリの `src/main` にコードがある。**本番で呼ばれているとは限らない**(下の「本番での既定」を見る) |
| 確認済み | 相手側の実コード / 相手側 Issue・PR で、対応が確認できる(根拠を併記) |
| 未確認 | 本リポジトリ・Issue から確認できなかった。**推測で埋めない** |
| 未対応 | 相手側の Issue・文書で、未実装と明記されている |
| 有効 / 無効 | 本番での既定。本書の時点では全項目で「無効」 |

## 2. Provider 一覧と本番 Capability の状態

| Provider | Kivra 側の主なモジュール | 相手側の主な Issue(2026-10-15 時点) | 本番 Capability |
|---|---|---|---|
| exkes | `kivra-exkes`(`ExkesAdapter`) | exkes #13 #16 #25 #27 #28 #29 #30 #31 #50 #55 #58 #60 は OPEN(#7 は CLOSED、#8 は OPEN) | **無効**(未結線)。Writer Fencing / Receipt は UNSUPPORTED |
| recl | `kivra-memory`(`ReclAdapter`、`ReclConfig`) | recl #4 #14 #16 #20 #21 #32 #37 #56 #59 は OPEN | **無効**(未結線、`ReclConfig.mutationsEnabled=false`)。Trusted Principal は UNSUPPORTED |
| ai-scheduler | `kivra-scheduler`(`AiSchedulerAdapter`) | ai-scheduler #29 #30 #42 #61 は OPEN(#17 は CLOSED) | **無効**(未結線)。Provider Dispatch は UNSUPPORTED |
| fusha-task | `kivra-fusha-task`(`FushaTaskAdapter`)、`kivra-fusha-poller`(`StatusMapping`) | Kivra #26 #27 #28 は OPEN。fusha-task 側の Issue 番号は**未確認**(閲覧権限・実名を要確認) | **無効**(読み取り専用。状態書き戻しは `writeEnabled=false`、書き込みは UNSUPPORTED) |
| GitHub | `kivra-github`(`HttpGitHubAdapter`、`GitHubPrMergeAuthority`) | Kivra #34(M3 アダプタ)、#49 #50 #53(M8 設計)は OPEN | **無効**(未結線)。Merge は Kivra に存在せず、人間のみ(#50 で扱う) |
| Anthropic(モデル Provider) | `kivra-model-gateway`(`AnthropicProvider`、`ModelGateway`) | 相手側の Issue は本リポジトリに記録なし | **無効**(未結線)。`ModelGateway` は耐久的な `RequestLedger` を要求(既定 fail-fast) |
| Fortis(参考) | Kivra 側に Fortis アダプタなし(#64 #65 #68 #69 が未実装) | Fortis #17 #44 #45 #46 #53 は OPEN、#33 #34 は OPEN | **無効**(`fortis-authority` は UNSUPPORTED。MariaDB が唯一の正本) |

## 3. Provider 別の能力表

各表の「本番での既定」は、本書時点で全行とも**無効**。

### 3.1 exkes

根拠: `docs/exkes-requests.md`(origin/main 2026-10-12)、exkes Issue の状態(2026-10-15)。exkes PR #26 / #37 / #51 / #53 / #54 / #56 / #57 / #59 は merge 済み(Kivra #71 のコメントに記載)だが、いずれも API baseline / 部分的な安全性の強化であり、本番 Capability ではない。

| 項目 | kivra 側 | 相手側 | 本番での既定 |
|---|---|---|---|
| 読み取り | 実装済み(`getWorkspace` / `getRuntime` / `getExecution` / `listWorkspaces` / `listRuntimes` / `listExecutions` / `findWorkspaceByName`) | 確認済み(`GET` 系、`limit` / `offset` / `status`)。ただし Sandbox 内の実プロセス実行は exkes #8(OPEN)、E2E は exkes #16(OPEN) | 無効 |
| 変更(作成 / 停止 / 削除 / 実行) | 実装済み(`createWorkspace` / `createRuntime` / `startExecution` / `stopRuntime` / `deleteWorkspace` / `deleteRuntime`) | 202 受理は確認済み。Receipt は未対応(exkes #30 OPEN)。Writer Fencing は未対応(exkes #31 OPEN) | 無効 |
| 冪等キー | 自動再送しない(タイムアウト・5xx は UNKNOWN 扱い)。`ProviderCapability` の冪等性は主張しない | **未対応**(`Idempotency-Key` なし、`docs/exkes-requests.md` §2 #1)。受入予定は exkes #25 の epic 配下 | 無効 |
| Receipt / Operation-ID | 202 は CONFIRMED 扱い(`PortResult.toOperationStatus`)。状態は GET で追跡 | **未対応**(exkes #30 OPEN: principal 単位の operation receipt と result lookup) | 無効 |
| 条件付き DELETE / 世代 fence | Reaper の自動削除は無効(`ReaperConfig.autoDeleteEnabled=false`、`dryRun=true`)。削除は承認 port のみ(既定は全拒否) | **未対応**(exkes #58 #60 #31 OPEN)。PVC 削除の保留は exkes PR #59 で導入済みだが、世代 fence は未完 | 無効 |
| 照合 API | `findWorkspaceByName` は全件 list で完全一致照合。Runtime は client 側で label 絞り込み。Execution は帰属を断定できない | **未対応**(name / label フィルタなし、`docs/exkes-requests.md` §2 #2〜#4) | 無効 |
| Cancel | UNSUPPORTED(`cancelExecution` は `PortResult.Unsupported`) | **未対応**(exkes #27 OPEN: Cancel 要求と停止証明の分離) | 無効 |
| ログ / 成果物 | UNSUPPORTED(`readLogs`)。成果物は未使用 | **未対応**(§2 #6、#7) | 無効 |
| 認証 | `TokenProvider` を差し込み、Bearer を付与(null なら付与しない) | **未対応**(exkes #13 OPEN。現状はネットワーク限定) | 無効 |
| レート制限 | 専用の扱いは未確認 | **未確認** | 無効 |
| 課金 | 該当なし(判断材料なし) | **未確認** | — |

### 3.2 recl

根拠: `docs/recl-requests.md`(origin/main 2026-10-12)、recl Issue の状態(2026-10-15)。recl PR #23 / #31 / #34 / #36 / #38 / #53 / #55 / #58 は merge 済みだが、内部 Core / InMemory / Mock のみで（PR #60 / #64 の内部実装は merge 状態を確認していない）、**REST / MCP の本番 API は未提供**(recl #21 OPEN)。

| 項目 | kivra 側 | 相手側 | 本番での既定 |
|---|---|---|---|
| 読み取り | 実装済み(`search` / `get` / `pinned`) | **未対応**(REST / MCP 本番 API 未提供。recl #4 #21 OPEN) | 無効 |
| 変更(save / update / supersede / delete) | 実装済み。`requireMutationsEnabled` で既定は UNSUPPORTED(**送信前に拒否**、テスト `ReclAdapterTest` が固定) | **未対応**(recl #16 #20 OPEN) | 無効(`ReclConfig.mutationsEnabled=false`) |
| 冪等キー | `Idempotency-Key` ヘッダを送る。`IDEMPOTENT_SAVE` は `ReclConfig.idempotentSaveConfirmed=true` のときだけ広告(既定 false) | **未確認**(recl 側で dedupe するかは未確認。recl #16 #20 OPEN) | 無効 |
| Receipt / Operation-ID | 変更系の 429 / 5xx / 接続断は `OUTCOME_UNKNOWN`(自動再送しない)。`ReclAdapter` の `statusError` と `call` で確認 | **未対応**(send-start Receipt は recl #59 OPEN、PR #60 は内部のみ) | 無効 |
| 条件付き DELETE / CAS | `expected_version` を update / supersede に送る。`REDACT` は `redactSupported=false` で UNSUPPORTED | **未対応**(CAS は recl #16 OPEN。REDACT の専用 API は設計書に無い) | 無効 |
| 照合 API | GET の 404 は「未適用の証拠」にしない | **未対応**(UNKNOWN の照合プロトコルは recl #16 OPEN) | 無効 |
| 認証 | `ReclCredentials` で Bearer(https 強制、リダイレクト追従なし) | **未対応**(Scope 束縛の Token 方式は recl #4 #21 OPEN) | 無効 |
| レート制限 | 変更系の 429 は `OUTCOME_UNKNOWN`(Kivra #75 は OPEN のまま。main の実装は 429 を UNKNOWN へ分類済み) | **未確認**(recl 側の 429 仕様は未提示) | 無効 |
| 課金 | 該当なし | **未確認** | — |
| Trusted Principal / Source Verification | Kivra 側は内部 Evidence のみ(`MemoryProvenance.USER_STATED` は独立の Verified ではない) | **未対応**(recl #32 #37 OPEN、GitHub Live Grant 未検証) | 無効 |

### 3.3 ai-scheduler

根拠: `docs/ai-scheduler-requests.md`(origin/main 2026-10-12)、ai-scheduler Issue の状態(2026-10-15)。

| 項目 | kivra 側 | 相手側 | 本番での既定 |
|---|---|---|---|
| 読み取り | 実装済み(`getJob` / `getExecution` / `getExecutionDetail`)。`listExecutions` は `ScheduleUnsupportedException` | 確認済み(`GET /v1/jobs`、`GET /v1/jobs/{id}`、`GET /v1/executions/{id}`)。Execution 一覧は**未対応**(POST のみ) | 無効 |
| 変更(Job 作成 / 有効無効 / 手動実行 / cancel / retry) | 実装済み(`upsertJob` / `pauseJob` / `resumeJob` / `triggerNow` / `cancel` / `retry`) | 確認済み(`POST /v1/jobs`、`PATCH /v1/jobs/{id}`、`POST /v1/jobs/{id}/executions`、`POST .../cancel|retry`)。Job の削除は**未対応**(論理無効化のみ) | 無効 |
| 冪等キー | `Idempotency-Key` を送る。サーバは無視する。実効的な抑止は Kivra 側の `(jobId, scheduledAt)` 重複排除と `triggerNow` の claim | **未対応**(ヘッダ未実装、サーバは `UNIQUE(job_id, scheduled_at)` のみ。Request-ID / Digest の CAS は ai-scheduler #61 OPEN) | 無効 |
| Receipt / Dispatch 記録 | UNKNOWN(`ScheduleUnknownException`)は再送せず、照合へ回す | **未対応**(Provider Dispatch Journal は ai-scheduler #29 OPEN、UNKNOWN 隔離は #42 OPEN) | 無効 |
| 条件付き DELETE | `deleteJob` は無効化(`enabled=false`)のみ | **未対応**(Job 削除 API なし) | 無効 |
| 照合 API | Job は名前(`kivra:fusha:{env}:{id}`)で検索。Execution の照合は不可 | **未対応**(name の一意制約なし、Execution 一覧なし) | 無効 |
| 認証 | `ScheduleTokenProvider`(既定 NoAuth)で Bearer を差し込める。http は明示許可時のみ、Bearer は http に載せない | **未対応**(Phase 1 は無認証。OIDC は ai-scheduler #30 OPEN) | 無効 |
| 結果通知 | Polling のみ(`SchedulePoller`) | **未対応**(Callback / Webhook なし) | — |
| レート制限 | 未確認 | **未確認** | 無効 |
| 課金 | Job は AI の prompt を実行するため、Provider 側の課金が発生し得る。Kivra は placeholder prompt を送る | **未確認**(no-op provider の有無は未確認) | — |

### 3.4 fusha-task

根拠: `kivra-fusha-task/.../FushaTaskAdapter.kt` の契約メモ(fusha-task のソースを設計 #2 §2 で確認済み)、`kivra-app/src/main/resources/application.conf`。

| 項目 | kivra 側 | 相手側 | 本番での既定 |
|---|---|---|---|
| 読み取り | 実装済み(`GET /tasks`、`GET /tasks/:id`。GET のみ再試行) | 確認済み(`/api/v1`、一覧は `assigneeId` / `viewId` のみ、ページングなし) | 無効(未結線: poller も `main` から構築されない) |
| 変更(update / comment / create / delete) | UNSUPPORTED(M1 では意図的に未実装) | **未対応**(サーバ側の CAS なし。Adapter の契約メモによる) | 無効 |
| 状態書き戻し | `StatusMapping.writeEnabled=false` が既定。`KIVRA_FUSHA_STATUS_WRITE_ENABLED` で切替可能だが、`writesAllowed()` は key と一対一の option mapping も要求し、書き込み経路は存在しない | **未対応**(status customField の key / option は要確認) | 無効 |
| 冪等キー | 該当なし(書き込みなし) | **未確認** | — |
| Receipt / Operation-ID | なし | **未確認** | — |
| 照合 API | `get()` が null の場合、「不存在」と「閲覧不可」を区別できない。削除の証拠にしない | 確認済み(404 は不存在と閲覧不可の両方に使われる) | 無効 |
| 認証 | Bearer(`KIVRA_FUSHA_TOKEN`)。401 は token 更新で 1 回だけ再試行 | **未確認**(machine 認証の経路は Kivra #27 で未検証と記載) | 無効 |
| レート制限 | 429 / 5xx は GET のみ指数バックオフで再試行 | **未確認** | 無効 |
| 課金 | 該当なし | **未確認** | — |

### 3.5 GitHub

根拠: `kivra-github/.../GitHubPort.kt`(Merge・force push・ref 削除・既存 ref 更新・default branch への書き込みは**インタフェースに無い**。テストで固定)、Kivra #34 #49 #50 #53(OPEN)。

| 項目 | kivra 側 | 相手側 | 本番での既定 |
|---|---|---|---|
| 読み取り | 実装済み(`getBranch` / `getPullRequest` / `findPullRequestsByHead` / `getCheckRuns` / `getCombinedStatus` / `getReviews` / `listTree` / `readFile`(GET のみ、自己開発の `RepoReader` 用)) | **未確認**(本リポジトリに実 GitHub への接続試験の記録なし。テストは MockEngine) | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| 変更 | 実装済み: `createBranch` / `ensureRunBranch` / `createPullRequest` / `ensurePullRequest`。**Merge は存在しない** | **未確認**(本リポジトリの範囲外) | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| ref 更新 | 実装済み: `commitFiles`(`kivra/run-*` のみ、`force=false`、`expectedHeadSha` と不一致なら書かず `CONFLICT`、ref 更新の結果不明は照合のみで再送しない)。本番では未配線 | **未確認**(本リポジトリの範囲外) | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| 冪等キー | なし。照合は run 専用ブランチ名と head による PR 検索で行う(`ensurePullRequest`) | **未確認**(GitHub API の冪等性機構は本リポジトリでは確認していない) | 無効 |
| Receipt / Operation-ID | PR 番号と head SHA を応答と照合(`IDENTITY_MISMATCH` で拒否)。Merge 観測は `GitHubPrMergeAuthority`(観測のみ) | — | 無効 |
| 条件付き DELETE | 実装なし(ref 削除は設計上除外) | — | 無効 |
| 照合 API | `findPullRequestsByHead`、`getPullRequest`。404 は「merge されていない」と読まない | 確認済み(REST の該当 endpoint が存在することは、コードのテストが前提としている範囲のみ) | 無効 |
| 認証 | `TokenProvider` を要求ごとに呼ぶ。GitHub App の installation token を既定とする(`InstallationTokenProvider`。JWT は RS256、PKCS#1 / PKCS#8 を受け、期限の 5 分前まで再利用、同時発行は 1 回に束ねる)。PAT は `StaticTokenProvider`(互換)。App の権限は `contents:write` / `pull_requests:write` / `metadata:read` のみ、インストール先は `kigawa-net/kivra` のみ | **未確認**(本番の App の作成・インストールと実 GitHub への接続は未実施。admin-panel #224 で扱う予定) | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| レート制限 | 429、および `retry-after` / `x-ratelimit-remaining=0` の 403 を `RATE_LIMITED` に分類(自動再送は変更系では行わない) | **未確認** | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| 課金 | 該当なし | **未確認** | — |
| Merge | Kivra に Merge 経路はない。Human Merge のみ(#50 で Ruleset / 独立 Approval を設計) | — | **無効(Merge 不可)** |

### 3.6 Anthropic(モデル Provider)

根拠: `kivra-model-gateway/.../AnthropicProvider.kt`、`ModelGateway.kt`、`Errors.kt`、`AnthropicProviderTest.kt`(MockEngine)。

| 項目 | kivra 側 | 相手側 | 本番での既定 |
|---|---|---|---|
| 読み取り | 実装済み(`listModels`。`health` はローカルの資格情報確認のみで、ネットワーク呼び出し・課金なし) | **未確認**(実 API への接続試験の記録なし) | 無効(未結線) |
| 変更(推論 `invoke` / `stream`) | 実装済み。送信前の失敗のみ再試行(`SendState.NOT_SENT`)。送信後は予約を確定値で精算し、requestId を UNKNOWN で封印 | — | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| 冪等キー | Kivra 側の `requestId` を `RequestLedger` で管理。`Idempotency-Key` ヘッダは送っていない | **未確認** | 無効 |
| Receipt / Operation-ID | 応答の `usage` を読む。`usageComplete` は入出力トークンが揃ったかを示す | **未確認** | 無効 |
| 照合 API | なし。UNKNOWN の requestId は再利用せず、新しい requestId を使う | — | 無効 |
| 認証 | `x-api-key` ヘッダ(資格情報は環境変数の参照)。資格情報の値は本書・ログに出さない | **未確認**(Anthropic 側の仕様の再確認は未実施) | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| レート制限 | 429 は `RATE_LIMITED`(`retry-after` を解釈)。送信前の失敗のみ再試行 | **未確認** | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |
| 課金 | 応答の usage から費用を算出し(`CostConverter`、USD→JPY)、耐久的な予算台帳(`DbBudgetStore`)に反映。`ModelGateway` は耐久的 `RequestLedger` を要求し、既定は fail-fast | **未確認**(価格体系は本リポジトリに記録した価格カタログに依存) | 無効(自己開発: `KIVRA_SELFDEV_ENABLED=true` のときだけ構築。既定は無効。§4a) |

## 4. 本番 Capability が有効になる条件(Gate)

以下をすべて満たすまで、**該当 Capability は無効のまま**とする。Fake / Mock / InMemory の成功は、どれも本番の証拠にならない。

1. Provider 側が、認証済みの Service Identity で、対象 API の契約バージョン(`contractVersion`)を公開している。
2. Provider 側が、署名つき Receipt(または authoritative な照合 API)を実装・デプロイしている(冪等キー・Operation-ID・条件付き DELETE は、対象の操作ごとに確認する)。
3. 実 API に対する契約試験(#70 の Harness)と、Fault Injection(202 / 409 / 404 / Response Lost / Identity 不一致)が、**デプロイされた Provider の SHA**に対して合格し、結果の Manifest(`contractVersion`、テストスイートの sha256、`imageDigest`、環境)が記録されている。
4. Security Owner / Operator が承認し、Audit と four-eyes が残っている(#33 の人間承認)。Agent / LLM / Issue 本文 / 外部 Provider が自分で有効化しない。
5. Capability は Provider の Deployment・Contract・Token Scope・Endpoint に束縛され、いずれかが変わったら自動失効する(新 Deployment で黙って継承しない)。
6. Kivra 側で、外部 Mutation の直前に Capability Gate を評価し、Storage Authority / Lease / Replay Proof / Rate Permit と合わせて判定する(#60 #61)。
7. 送信済みの Mutation は、Capability を無効化しても取り消されたものとして扱わない(UNKNOWN を保持し、照合する)。

## 4a. 自己開発(ADR 0008)の有効化の手順

既定は**無効**(fail-closed)。`KIVRA_SELFDEV_ENABLED=true` のときだけ、GitHub と Anthropic のクライアントを構築し、ワーカーと `/api/selfdev/*` を登録する。無効のときは、自己開発の秘密の環境変数を一切読まない(`SelfDevSettings`、テスト `SelfDevSettingsTest` と `SelfDevAppWiringTest`)。

有効化の前提(すべて満たしてから有効にする。値はこの文書に書かない):

1. `kigawa-net/kivra` の `main` のブランチ保護(kigawa-net/infra#296 で適用済み)が有効であること。
2. GitHub の認証は、**GitHub App を推奨**する。App の権限は `contents:write` / `pull_requests:write` / `metadata:read` のみ、インストール先は `kigawa-net/kivra` のみとする。App の秘密鍵(PEM。PKCS#1 / PKCS#8)は k8s Secret を読み取り専用でマウントし、そのファイルのパスを `KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY` に渡す(PEM 本文も可)。App ID と installation ID は `KIVRA_SELFDEV_GITHUB_APP_ID` / `KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID`。互換として PAT(`KIVRA_SELFDEV_GITHUB_TOKEN`)も使えるが、PAT を使う場合は、管理者の権限を持たない専用の細粒度 PAT(`contents:write` と `pull_requests:write` のみ)に限る。**App と PAT は両方を設定しない**(両方、またはどちらも無いときは起動に失敗する)。管理者のトークンは、保護を迂回しうるため使わない。
3. Anthropic の API キーを Secret で `KIVRA_SELFDEV_ANTHROPIC_API_KEY` に渡す。予算(`KIVRA_BUDGET_*`)を設定する。
4. 為替を `KIVRA_FX_USD_JPY` と `KIVRA_FX_AS_OF` で設定する。未設定だと、USD 建ての呼び出しは `UNKNOWN_COST` で拒否される(起動時に検査し、欠けていれば起動に失敗する)。
5. モデルのレジストリを `KIVRA_SELFDEV_MODEL_REGISTRY_PATH` に置く。使うモデルだけを登録し、価格・能力・`dataHandling` を一次情報で確認する。自己開発は `DataClass.INTERNAL` でリポジトリ内容を送るため、**Anthropic の `dataHandling.status` が `CONFIRMED` であること**(`UNKNOWN` のままでは、そのプロバイダは使われず、Run は失敗する)。
6. `KIVRA_AUTH_MODE=oidc-jwt` で動かす。`KIVRA_RUNTIME_ENV=development` と同時の有効化は拒否される。
7. **旧イメージ(V13 までを知るもの)が残っていないこと**(全レプリカが新版であること)。
8. `org.mariadb` のログを `TRACE` / `DEBUG` にしない(INSERT の値がログに出る)。
9. レプリカは 1 にする。`KIVRA_SELFDEV_MAX_ACTIVE_RUNS` の上限は、プロセス内の直列化で守っている。複数のレプリカでは、同時の投入で上限を超えうる。
10. `UNKNOWN` の Run と予算の `UNKNOWN_COST` は、運用者が監査つきで解除する(`POST /api/admin/budget/acknowledge`、`admin-panel:admin`)。

設定の一覧(値は書かない): `KIVRA_SELFDEV_ENABLED`、`KIVRA_SELFDEV_TARGET_REPO`(既定 `kigawa-net/kivra`、1 つだけ。リクエストでは指定できない)、`KIVRA_SELFDEV_BASE_BRANCH`(既定 `main`)、`KIVRA_SELFDEV_GITHUB_TOKEN`(PAT、互換)または `KIVRA_SELFDEV_GITHUB_APP_ID` / `KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID` / `KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY`(App、推奨)、`KIVRA_SELFDEV_ANTHROPIC_API_KEY`、`KIVRA_SELFDEV_MODEL_REGISTRY_PATH`、`KIVRA_SELFDEV_CONTEXT_PREFIXES`(既定 `README.md,docs/`)、`KIVRA_SELFDEV_MAX_CONTEXT_FILE_BYTES`(既定 102400)、`KIVRA_SELFDEV_PR_REVIEW_TIMEOUT_DAYS`(既定 7)、`KIVRA_SELFDEV_ADVANCE_INTERVAL_SECONDS`(既定 30、最小 10)、`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`(既定 1)、`KIVRA_FX_USD_JPY`、`KIVRA_FX_AS_OF`。

## 5. 既定値の固定(テスト)

`kivra-app/src/test/kotlin/net/kigawa/kivra/capability/` のテストが、本番の既定を固定する。

- `ProductionCapabilityDefaultsTest`: 既定の `application.conf`(環境変数の上書きなし)で、状態書き戻し・Reaper 自動削除・recl 変更・dev デモ・インメモリ dedup / journal が無効であること、および開発専用の有効化に `KIVRA_*` の指定が必要なこと。
- `SelfDevCapabilityDefaultsTest`: 既定の設定で自己開発が無効であること(`KIVRA_SELFDEV_ENABLED` 未設定、共有の `application.conf`)。
- `SelfDevAppWiringTest`: 無効のとき `/api/selfdev/*` が登録されず、自己開発の秘密の環境変数が読まれないこと。有効化に `oidc-jwt` が必要なこと。
- `ProviderCompatibilityMatrixTest`: この文書があり、既知の Provider とアダプタ名をすべて列挙していること。`src/main` に新しい `*Adapter` クラスが増えたら、テストが失敗して本書の更新を促す。

## 6. 確認できなかった事実(未確認の一覧)

- exkes / recl / ai-scheduler / fusha-task / GitHub / Anthropic の、レート制限の仕様と課金の有無(すべて未確認)。
- fusha-task の machine 認証、status customField の key と option の値、fusha-task 側の Issue 番号。
- Anthropic の冪等性機構(`Idempotency-Key` の有無)と、応答 ID の仕様。
- GitHub API の冪等性と、本番の GitHub App の権限。
- recl の 429 仕様と、Token の Scope 束縛方式。
- ai-scheduler の no-op provider の有無(wake-up 専用 Job の課金)。

## 7. 本番コードの観察(変更は行っていない)

- `kivra-app` の `src/main` は、外部アダプタを構築していない。本番で有効になり得る経路は現時点でないが、後で結線する際は本書の Gate を満たすこと。
- `KIVRA_FUSHA_STATUS_WRITE_ENABLED=true` を設定しても、`StatusMapping` は key と option mapping がなければ起動時に失敗する(fail-closed)。書き込み経路自体は未実装。
- `M0MemoryMutationJournalAdapter`(`kivra-app` の `db/`)は外部 Provider ではなく、外部 Mutation の耐久的 Journal(`external_operations`)のアダプタ。`main` からは構築されず、本番の Memory Mutation は無効のまま。
- `kivra-scheduler` の `InMemoryTriggerDedupStore` の拒否(`devAllowInMemoryDedup`)と、`kivra-github` の Merge 不在のテストは、`kivra-app` のテストの依存に含まれないため、本リポジトリの `kivra-app` テストでは検査していない(各モジュールのテストに任せる)。

## 8. 更新手順(新しい Provider / 能力を追加するとき)

1. 本書の §2 の一覧と、該当する §3 の表に行を追加する。すべての列を埋める。わからないものは「未確認」と書く。
2. 相手側の根拠は、Issue / PR の番号と確認日を書く(推測で埋めない)。
3. 本番の既定は「無効」にする。有効化の条件は §4 を参照し、満たす証拠を本書に書く(有効化は別の PR で行う)。
4. 新しいアダプタクラス(`class ...Adapter(` または `class ...Provider(`)を追加したら、`ProviderCompatibilityMatrixTest` の既知リストと本書の両方に追加する。
5. テストを実行し、`ProductionCapabilityDefaultsTest` が通ることを確認する。
6. 外部 Provider の Issue の状態が変わったら(特に Receipt / 冪等 / 認証の Issue)、§2 と §3 の該当行を更新する。
