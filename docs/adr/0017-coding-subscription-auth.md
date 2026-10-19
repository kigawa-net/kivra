# ADR 0017: コーディングの認証をサブスクリプション(OAuth)に切り替え、その費用を月次予算の台帳から外す

- ステータス: 提案(Proposed)。実装はしない。承認には U-1(利用規約の確認)の記録が前提
- 日付: 2026-10-19(起草)
- 関連: Issue #286(本 ADR の要件)、Issue #187(ログイン済みの Claude Code CLI をコーディングに使う)、ADR 0010(コーディングランタイム、WIF、認証情報をコンテナに置かない)、ADR 0003(UNKNOWN_COST と unpriced の解除)、ADR 0008、ADR 0011(オートパイロットの予算按分)、ADR 0012

## 背景

### 決定の経緯

ユーザーは 2026-10-19 に「サブスクは予算に含めない」と決めた(#286)。コーディング(エージェントの `claude -p`)の認証を、WIF(API の従量課金)からサブスクのログイン(OAuth)に切り替え、コーディングの費用を月次予算の台帳から外す。Planner(API)と Model Gateway は、今のまま予算に含める。

### 現状の事実(コードと runbook)

- **認証は WIF である。** ホスト側の `agent-runtime/proxy` が、Keycloak の JWT を Anthropic の短命トークンに交換し、`Authorization: Bearer` を付与する(`proxy/wif.go`、`proxy/anthropic.go`)。
- **コンテナが受け取る環境変数は限られる。** `network/run-agent.sh` が渡すのは `ANTHROPIC_BASE_URL`(proxy の `/anthropic`)、`ANTHROPIC_API_KEY=kivra-agent-dummy-not-a-credential`、`HTTPS_PROXY` などである。kivra-agent の `DockerCommandBuilder.kt` も同じダミー値を渡す。`DockerSettings.kt` の `ENV_NAMES` は、コンテナが受け取ってよい環境変数名の一覧で、`CLAUDE_CODE_OAUTH_TOKEN` は含まれない。
- **proxy はダミーの認証を捨てて差し替える。** `anthropic.go` の `hopByHop` は `authorization` と `x-api-key` を転送せず、proxy が自分の認証を `Out.Header.Set` で付ける。
- **proxy が通すのは 2 つのパスだけである。** `anthropicPaths` は `/v1/messages` と `/v1/messages/count_tokens` で、それ以外は 403 を返す。
- **agent-runtime の出口の許可リスト**(`proxy/allowlist.txt`)は、Gradle と Maven のホストだけを対象とする。Anthropic への通信は `/anthropic` の経路で扱われる。
- **1 attempt の費用の上限**は `CliCommand.kt` の `--max-budget-usd 0.50` である。`--max-turns` は agent 側の既定が 40(`CliCommand.DEFAULT_MAX_TURNS`)で、サーバー側の `CodingRuntimeConfig.maxTurns` と `CodingLimits.maxTurns` は 30 である。どちらが実際に CLI へ渡るかは未確認。最大実行時間はサーバーの既定で 30 分(`maxRuntime`)である。
- **予算の仕組み。** `CodingCostLedger.kt` の `DbCodingCostLedger` は、claim の前に予約額(0.50 USD を円に換算した額)の余裕を `budget.admission` で確かめる(`claimDenial`)。報告された `usage` は、キー `coding:<attemptId>:<世代>` で台帳に 1 回だけ計上する(`recordReported`)。報告が無い終わり方(usage の無い fail、ORPHANED、EXPIRED)は、予約額を UNPRICED で計上する(`recordEstimated`)。未確定の予約が残ると、以後の claim は UNKNOWN_COST で止まる(ADR 0003)。
- **実機の問題。** 失敗した Run(`CODING_OUTPUT_LIMIT`)が usage を報告せずに終わり、0.50 USD の未確定の予約が残った。運用者が解除するまで、Planner を含むすべての呼び出しが UNKNOWN_COST で止まった(#286 の背景)。
- **同時実行。** agent は attempt を 1 つずつ処理する(`AgentConfig.MAX_CONCURRENT = 1`、コードの定数)。`KIVRA_SELFDEV_MAX_ACTIVE_RUNS` の既定は 1 である。
- **実測。** 2026-10-17 の実測は、`--model` を指定しない既定の Opus で 1 ターン `total_cost_usd=0.12` である。Haiku 5.5 での実測は無い(runbook 6.10b。未確認)。

### 問題の整理

1. 予算に含めたままでは、コーディングの実費が月次の上限に入り、UNPRICED の 1 件が Planner まで止める。
2. 予算の除外だけを行い、認証を WIF のままにすると、実費が管理外になる。よって**認証と予算の除外は同じ設定で切り替える**。
3. サブスクの OAuth トークンをコンテナに置くと、モデルが Bash を実行できるため、プロンプトインジェクションで持ち出されうる(ADR 0010 の決定 5-4、決定 6 の根拠と同じ)。

## 公式ドキュメントで確認した事実

2026-10-19 に、code.claude.com の Authentication、Run Claude Code programmatically(headless)、Manage costs、Legal and compliance の各ページを読んだ。引用は短く示す。

| 事実 | 出典 |
| --- | --- |
| 認証の優先順位は、(1) クラウド provider、(2) `ANTHROPIC_AUTH_TOKEN`(`Authorization: Bearer`)、(3) `ANTHROPIC_API_KEY`(`X-Api-Key`)、(4) `apiKeyHelper`、(5) `CLAUDE_CODE_OAUTH_TOKEN`、(6) profile と federation、(7) `/login` の OAuth の順である。`-p` では、`ANTHROPIC_API_KEY` が存在すれば常にそれが使われる。 | Authentication, "Authentication precedence" |
| `claude setup-token` は 1 年有効の OAuth トークンを表示する。保存はしない。`CLAUDE_CODE_OAUTH_TOKEN` に設定して使う。Pro、Max、Team、Enterprise の契約が必要。Remote Control とコネクタは使えない。 | Authentication, "Generate a long-lived token" |
| 同じ節が、このトークンの用途を "For CI pipelines, scripts, or other environments where interactive browser login isn't available" と案内している。 | 同上 |
| `--bare` は OAuth の資格情報も `CLAUDE_CODE_OAUTH_TOKEN` も読まない。`--bare` は「scripted and SDK calls」に推奨され、将来 `-p` の既定になると書かれている。kivra は `--bare` を使っていない(ADR 0010)。 | Headless, "Start faster with bare mode" |
| `/login` の資格情報は Linux では `~/.claude/.credentials.json`(0600)に保存される。内容は読んでいない。 | Authentication, "Credential management" |
| `ANTHROPIC_BASE_URL` で API の宛先を変えられる。ゲートウェイ向けには `ANTHROPIC_AUTH_TOKEN` が用意されている。 | Authentication |
| ヘッドレスの記述で確認できたオプションは、`--allowedTools`、`--output-format`(stream-json を含む)、`--permission-mode dontAsk`、`--max-budget-usd`、`--json-schema`。`--max-turns`、`--no-session-persistence`、`--model` は、取得した範囲の記述には無く、未確認である。 | Headless |
| `total_cost_usd` は、クライアント側の**推定値**で、実際の請求とは異なりうると書かれている("Both figures are client-side estimates and can differ from your actual bill")。 | Headless、Manage costs |
| サブスクの利用は、5 時間と週次の枠で管理される。枠に達したときのメッセージは "You've hit your session limit" など。枠の具体的な数値は、取得したページには無い。`/usage` は Pro と Max のサブスクで、枠の使用率を表示する。 | Manage costs |
| 規約では、OAuth 認証は "intended exclusively for purchasers of Claude Free, Pro, Max, Team, and Enterprise subscription plans and is designed to support ordinary use of Claude Code and other native Anthropic applications" とされる。 | Legal and compliance, "Authentication and credential use" |
| 同じ節は、製品やサービスを作る開発者に "should use API key authentication" と求め、"route requests through Free, Pro, or Max plan credentials on behalf of their users" と、"collect, store, or intermediate Claude.ai credentials or session tokens" を禁じる。 | 同上 |
| 同じ節は、Pro と Max の表示上の枠は "ordinary, individual usage of Claude Code and the Agent SDK" を前提とするとも書く。 | Legal and compliance, "Acceptable use" |
| 同じ節は、個人の契約の範囲内で、利用者が自分の Claude のサブスクでそのまま Claude Code を使うことを妨げないとも書く。ただし、この文の射程(kivra のような運用者の自動実行を含むか)は、ページからは断定できない。 | 同上 |
| 規約の可否の確認先として、公式は "please contact sales" を案内している。 | 同上 |
| `api_retry` イベントの `error` の分類は、`authentication_failed`、`oauth_org_not_allowed`、`account_on_hold`、`billing_error`、`rate_limit`、`overloaded`、`invalid_request`、`model_not_found`、`server_error`、`max_output_tokens`、`cloud_credential_error`、`unknown` である。サブスクの枠超過が、どの分類になるかは未確認。 | Headless, "Handle API retries" |

**公式が直接答えていないこと(未確認)**: proxy が `CLAUDE_CODE_OAUTH_TOKEN` の値を Authorization に差し替える構成を、公式が認めるかどうか。ダミーのトークンを CLI が受理するか。CLI がリクエスト以外の API パス(アカウント情報など)を呼ぶか。これらは、後述の PoC(U-3)で確かめる。

## 決定(提案)

1. **認証モードを、1 つの設定で決める。** 設定名は仮に `KIVRA_CODING_AUTH_MODE` とし、値は `API_BILLED`(既定。現状の WIF の経路)と `SUBSCRIPTION` の 2 つとする。
2. **認証と予算の除外は、同じモードで同時に切り替える。** 予算の除外を別の設定にしない。したがって「WIF のまま予算だけ外す」という組み合わせは、設定上も起こらない。
3. **認証の方式は、候補の比較(次節)で、案 (a) を技術的な第一候補とする。** ただし、実装は U-1(規約の確認)の記録と、U-3(PoC の結果)の後に限る。(a) が成立するという確認は、まだ無い。
4. **`SUBSCRIPTION` のとき、コーディングの予約、計上、claim の予算の審査を、台帳に作らない。** 使用量の記録(`coding_attempt_usage`)は残す。`cost_usd` は推定値として参考に表示する。Planner と Model Gateway は、今のまま予算に含める。
5. **コーディングの上限は、円ではなく回数と時間で管理する。** 詳細は「サブスクの利用枠」の節に書く。
6. **既存の WIF の経路は削除しない。** モードを `API_BILLED` に戻せば、設定の変更だけで切り戻せる。

## 候補の比較(認証情報をサンドボックスに置かない)

前提は ADR 0010 の決定 5-4 と 6 である。コンテナ(モデルが Bash を実行できる)には、Anthropic の資格情報を置かない。

| 案 | 概要 | 安全性(コンテナからの持ち出し) | 実現可能性 | 運用(更新と失効) | 規約 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| **(a)** proxy が OAuth トークンを保持し、リクエストに付与する | コンテナには `CLAUDE_CODE_OAUTH_TOKEN` のダミー値だけを渡す。proxy は `Authorization` を差し替える。 | **最も高い。** 実トークンはコンテナに無い。現行の WIF と同じ構造である。 | 近い仕組みは実機で動作済み(ダミーの `ANTHROPIC_API_KEY` を proxy が Bearer に差し替える。ADR 0010 の PR 0 と E2E)。**未確認:** (i) ダミーの `CLAUDE_CODE_OAUTH_TOKEN` を CLI が受理するか。(ii) setup-token のトークンを `Authorization: Bearer` で送って受理されるか。追加のヘッダーが必要か。(iii) CLI が `/v1/messages` 以外を呼ぶか。呼べば proxy が 403 を返す。 | トークンは proxy 側の秘密ファイルに置く(client secret と同じ扱い)。setup-token の期限は 1 年。期限切れや失効では、proxy が 401 を受け、運用者が再発行して差し替える。proxy はリフレッシュを行わない。refresh token の有無は未確認。 | **問題になりやすい。** proxy がサブスクの資格情報を保持して付与する。規約の "intermediate" に当たるかは、U-1 で判断が要る。 | **第一候補。** 着手条件は U-1 と U-3。 |
| **(b)** コンテナに、長期の OAuth トークンを渡す | `setup-token` のトークンを、コンテナの環境変数に入れる。 | **低い。** モデルが Bash を実行できるため、プロンプトインジェクションで読み出せる。持ち出されると、失効まで第三者が枠を使える。失効の手段は、取得した公式ページでは確認できず未確認。出口の許可リストは外部への経路を絞るが、実トークンを置く理由にはならない。 | 公式は CI とスクリプト向けの方式として案内している。ただし、コンテナに置くには `DockerSettings.ENV_NAMES`、`DockerArgsGuard`(`ANTHROPIC_API_KEY` の値の検査)、`test-isolation.sh`(`CLAUDE_CODE_OAUTH_TOKEN` の不在の検査)を変える必要がある。 | 単純。ただし期限は 1 年で、更新は運用者の作業になる。 | 公式の案内の範囲内だが、規約の射程は U-1 で判断が要る。 | **採らない。** ADR 0010 の決定 5-4 と 6 を改めることになり、隔離の前提が崩れる。 |
| **(c)** 切り替えない | WIF のまま、費用の管理を別の方法で緩める(予算上限の引き上げなど)。 | 変わらない(現状どおり)。 | 確実。 | 予算の上限を超えるか、UNPRICED で止まる問題が残る。 | 問題なし(API の従量課金)。 | **次善の案。** U-1 の確認が得られない場合の既定にする。 |

**推奨は (a) である。** ただし、(a) が成立するという確認は、まだ無い。U-1 の記録と、U-3 の PoC の結果が揃うまで、実装に進まない。どちらかが否定されたら (c) に戻す。(b) は、安全性の理由から採らない。

## 予算の除外(`SUBSCRIPTION`)

### 変更箇所(提案)

| 箇所 | `API_BILLED`(現状) | `SUBSCRIPTION`(提案) |
| --- | --- | --- |
| `CodingCostWiring.kt` の `buildCodingCostLedger`(`Application.kt:102` から呼ばれる) | `DbCodingCostLedger` を返す | 予算に触れない実装(仮に `UnbudgetedCodingCost`)を返す。`claimDenial()` は `null`、`recordReported` と `recordEstimated` は usage の行だけを記録して `true` を返す。 |
| `CodingCostLedger.kt` の `DbCodingCostLedger` | 予約、計上、UNPRICED を行う | `SUBSCRIPTION` では使わない。コードは変えず、呼ばれないようにする。 |
| `CodingRuntimeService.claim`(`cost.claimDenial()`) | 予算の審査を行う | 呼び出しは変えない。無課金の実装が `null` を返すため、claim の経路は変わらない。 |
| `coding_attempts`(attempt の行) | 変更なし | attempt ごとに認証モードを記録する列を足す(マイグレーションが要る。番号は未確認)。 |
| `budget_reservations` | `coding:` の予約が増える | `coding:` の予約を作らない。既存の `coding:` の行は残す。 |
| `coding_attempt_usage` | 使用量を記録する | 変えない。ただし `cost_usd` は推定値であることを示す。 |
| `/ui` の予算カード(`kivra-app/src/main/resources/ui/index.html`) | 「CLI 経路」を金額に含める | 「サブスク(予算外)」と表示し、金額の合計から外す。未確定の一覧からは、新しい `coding:` の予約が出ない。 |

### UNKNOWN_COST が coding で起きなくなること

- `SUBSCRIPTION` の coding は、予算の審査(`budget.admission`)も、予約(`recordSpend`)も行わない。したがって、coding の attempt が UNPRICED の予約を作ることは無くなり、coding の claim が `UNKNOWN_COST` で止まることも無くなる。
- ただし、**Planner の UNPRICED は、従来どおり Planner を止める。** ADR 0003 の解除手順は変えない。切り替え前に、既存の coding の UNPRICED は、運用者が ADR 0003 の手順(`acknowledgeUnpriced`)で解除しておく。
- 上記は、コードの経路から読み取った結論である。実機での確認は、dev での S3 の段階で行う。

### モードの不整合の防止

- **起動時(fail closed):** `SUBSCRIPTION` のとき、kivra-app は、`KIVRA_PROXY_*` の WIF の設定や `KIVRA_SELFDEV_ANTHROPIC_*` の WIF の設定が残っていれば起動を拒否する。kivra-agent は、コンテナに `ANTHROPIC_API_KEY` を渡さないことと、`CLAUDE_CODE_OAUTH_TOKEN` がダミーであることを確かめる。proxy は、認証のモードが `subscription` であることを確かめる。いずれも、変数名だけを出す。
- **claim の時(fail closed):** agent は、claim のリクエストで自分の認証モードを報告する。サーバーは、報告されたモードが設定と違えば、claim を拒否する。これにより、サーバーは予算外と見ているのに、実際には WIF で課金されている、という不整合を防ぐ。
- **attempt の単位:** モードは attempt を作るときに記録し、切り替えは既存の attempt に影響しない。切り替えの前に、進行中の attempt が終わるのを待つことを運用手順に書く。

## サブスクの利用枠(回数の上限)

### 既存の設定(コードで確認した事実)

| 項目 | 既定 | 出典 |
| --- | --- | --- |
| 同時の自己開発 Run | 1 | `KIVRA_SELFDEV_MAX_ACTIVE_RUNS`(`SelfDevSettings.DEFAULT_MAX_ACTIVE_RUNS`) |
| agent の同時 attempt | 1(コードの定数) | `AgentConfig.MAX_CONCURRENT` |
| 1 日の自律実行の Run 数(オートパイロット) | 5(範囲 1〜100) | `KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY`。ただし、オートパイロットが投入した Run だけを数える。 |
| 未マージの PR の上限 | 3 | `KIVRA_SELFDEV_AUTOPILOT_MAX_PENDING_PRS` |
| 連続失敗の上限(ブレーカー) | 3 | `KIVRA_SELFDEV_AUTOPILOT_MAX_CONSECUTIVE_FAILURES`。`CIRCUIT_OPEN` になる。 |
| 予算の按分 | 50%(円) | `KIVRA_SELFDEV_AUTOPILOT_BUDGET_SHARE_PERCENT`。`SUBSCRIPTION` では円の按分の意味が無くなる(U-7)。 |
| attempt の費用の上限 | 0.50 USD | `--max-budget-usd`(`CliCommand`)。推定値であり、暴走の防止として残す。 |
| 1 attempt の最大ターン数 | 40(agent)/ 30(サーバー) | `CliCommand.DEFAULT_MAX_TURNS` / `CodingRuntimeConfig.maxTurns`。どちらが使われるかは未確認。 |
| 1 attempt の最大実行時間 | 30 分 | `CodingRuntimeConfig.maxRuntime` |

### 不足している点と提案

- **1 日の coding の attempt 数の上限がない。** オートパイロットの上限は、オートパイロットが投入した Run だけを数える。手動の投入を含めた上限が無いため、`SUBSCRIPTION` では新しく足す(`coding.subscription.maxAttemptsPerDay`、提案値は U-4)。
- **最大ターン数の不一致。** agent の 40 とサーバーの 30 を 1 つに揃える(提案は 30)。
- **円の按分の扱い。** `SUBSCRIPTION` では、coding の費用を円の按分に入れない(U-7)。

### 枠の超過(429 など)の扱い

- 現状の分類: `ApiErrorKind.RATE_LIMIT` は `RATE_LIMITED` になり、サーバーでは `FAILED` の終端状態になる。`FAILED` の Run は、オートパイロットの連続失敗として数えられ、3 回で `CIRCUIT_OPEN` になる。
- **問題。** サブスクの枠は、5 時間や週次の窓で回復する。枠の切れた状態で連続失敗が積もると、ブレーカーが開き、回復後も自律実行が止まる。
- **提案(U-5):** 枠の超過を、専用の固定コード(仮に `QUOTA_EXHAUSTED`)として分け、ブレーカーの連続失敗の数に入れない。枠の超過の判定は、stream-json の結果や `api_retry` の分類で行う。**未確認:** 枠の超過が、どの分類(`rate_limit` など)と結果の subtype で出るか。PoC(U-3)で確かめる。

## 段階的な導入と切り戻し

| 段階 | 内容 | 進んでよい条件 |
| --- | --- | --- |
| S0(本 ADR) | 提案。コード、設定、k8s、Keycloak、エージェントの配備は変えない。 | ユーザーの承認。 |
| S1(認証の PoC、dev のみ) | proxy に `subscription` モードを試作し、(i) ダミーの `CLAUDE_CODE_OAUTH_TOKEN` の受理、(ii) setup-token の Bearer の受理、(iii) 呼ばれる API パス、(iv) 枠の超過の出力、を確かめる。予算には触れない。 | U-1 の記録。PoC の結果を ADR に追記する。 |
| S2(予算の除外の実装) | モードの設定、無課金の実装、attempt のモードの記録、UI、テスト。既定は `API_BILLED` のまま。 | S1 が成功。U-4 と U-7 の承認。 |
| S3(dev で有効化) | dev で `SUBSCRIPTION` にし、1 日の attempt 数を 1 から始めて消費を測る。既存の coding の UNPRICED は解除済みであること。 | S2 のテストが通る。規約の確認の記録。 |
| S4(本番) | 本 ADR の範囲外。別の承認が要る。 | S3 の測定の結果。 |

**切り戻し:** `KIVRA_CODING_AUTH_MODE` を `API_BILLED` に戻し、kivra-agent と proxy を再起動する。設定の変更だけで戻せる。`SUBSCRIPTION` で作られた attempt は、そのモードで終わらせる。`API_BILLED` に戻すと、以後の coding は予算に計上される。(a) の場合は、proxy の OAuth トークンを、運用者が削除する。

## 脅威の検討

| 脅威 | 影響 | 対策 |
| --- | --- | --- |
| プロンプトインジェクションによるトークンの持ち出し | サブスクの枠が第三者に使われる。 | (a) を採る。コンテナに実トークンを置かない。proxy は、コンテナからの認証ヘッダーを捨てる(現行の `hopByHop`)。 |
| proxy のログや、失敗した attempt の保存(`AttemptDebugKeep`)へのトークンの混入 | トークンが残る。 | proxy は固定の文言だけを記録する(現行の `AuthError`)。保存の対象に proxy のログを含めない。トークンの値を出さないテストを足す。 |
| 環境変数や `/proc` からの読み出し | コンテナ内からの読み出し。 | 実トークンは proxy のプロセスだけが読む。proxy は kivra-agent と同じ OS ユーザーで動き、秘密はファイルで読む(現行の client secret と同じ)。`/proc` の見え方は未確認。 |
| proxy の乗っ取り | 同じホストで動く権限を持つ者が、トークンを読める。 | 既存の WIF と同じ前提である。ホストの権限の管理に含める。 |
| サブスクの枠の枯渇による、ユーザー自身の対話利用への影響 | 枠を使い切り、対話利用が止まる。 | 1 日の attempt 数、同時 1、最大ターン、最大実行時間で上限をかける。枠の超過では、新しい claim を止める。 |
| モードの取り違え(API で動いているのに、予算の外に見える) | 実費が台帳から漏れる。 | 起動時の検査、claim 時のモードの照合、attempt ごとのモードの記録。Console の支出上限を最終防壁に残す(runbook 6.13 の考え方)。 |
| 規約の違反 | アカウントの制限を受けうる。 | U-1 の記録を前提にする。 |
| 秘密情報のログへの混入 | 秘密の漏えい。 | 変数名と固定の理由だけを出す。値を出すテストを足す。 |

## 未決事項

- **U-1 利用規約の確認(運用者の責任)。** 次の 2 点が、公式の規約の範囲か。(1) 運用者本人が、自分の PC で、自分の kivra のために、`claude -p` を無人で動かすこと。(2) proxy がサブスクの資格情報を保持し、リクエストに付与すること。**推奨:** 運用者本人が規約を読み、公式の問い合わせ先(contact sales)で可否を確かめ、その記録を本 ADR に追記するまで、S2 以降に進まない。本 ADR は可否を断定しない。
- **U-2 トークンの種類。** (1) `setup-token` の 1 年のトークン、(2) `/login` の OAuth(refresh がある)のどちらを使うか。**推奨:** (a) の場合は `setup-token` を proxy の秘密ファイルに置く。refresh を proxy が行うと、CLI の資格情報ファイルの仕様に依存する(未確認)ため。
- **U-3 PoC の実施。** S1 の 4 点(受理、Bearer、呼ばれる API パス、枠の超過の出力)を dev で確かめる。**推奨:** 実施する。結果を ADR に追記してから S2 に進む。
- **U-4 サブスクの枠の数値。** 1 日の attempt 数、同時数、最大ターン数、最大実行時間、`--max-budget-usd`。**推奨:** 1 日 5 件(オートパイロットの既定に合わせる)、同時 1(固定)、最大ターン 30(agent の 40 を下げて揃える)、最大実行時間 30 分、`--max-budget-usd 0.50`(推定値の暴走防止として残す)。S3 で消費を測って調整する。
- **U-5 枠の超過の扱い。** **推奨:** 専用の固定コード(`QUOTA_EXHAUSTED`)を足し、ブレーカーの連続失敗の数に入れない。代案は、枠の回復まで attempt を待機させる(実装が大きい)。
- **U-6 Planner もサブスクにするか。** **推奨:** しない。Planner は API の従量課金のまま予算に含める。Planner の UNKNOWN_COST の解除は ADR 0003 の手順で扱う。
- **U-7 オートパイロットの予算按分。** `SUBSCRIPTION` では、coding の費用を円の按分に入れない。**推奨:** 按分の対象から coding を外し、U-4 の回数の上限に置き換える。

## 却下した案

- **予算だけ外し、認証は WIF のまま。** 実費が管理外になる。認証と予算の除外は同じモードで切り替える。
- **認証と予算の除外を、別々の設定にする。** 組み合わせによっては実費が漏れる。1 つの設定にして、不整合は起動時と claim 時に拒否する。
- **無課金の予約を 0 円で作る。** 既存の UNPRICED の仕組みと衝突し、UNKNOWN_COST を再び起こしうる。台帳に作らない。
- **コンテナに長期の OAuth トークンを置く((b))。** プロンプトインジェクションで持ち出されうる。ADR 0010 の隔離の前提を崩す。
- **`--bare` を使う。** 公式は `--bare` を推奨するが、`--bare` は `CLAUDE_CODE_OAUTH_TOKEN` を読まない。サブスクの方式と両立しない。kivra は引き続き `--bare` を使わない。
- **Planner もサブスクにする。** 規約の論点が増え、枠を Planner と分け合うことになる。U-6 で推奨しない。
- **静的な API キーを使う。** ADR 0010 の代替案 K と同じ理由で採らない(長期の秘密が増える)。

## 結果

- 承認されると、ADR 0010 の決定 5-4 と決定 6 の一部(「サブスクリプションによる方式(案 L)は採用しない」)を改める追補が必要になる。本 ADR は、ADR 0010 の文書を書き換えない。
- 承認後も、実装は S1 から段階的に行う。S3 までは、本番の既定は `API_BILLED` のままである。
- 予算の除外により、coding の費用は月次の上限の計算に入らなくなる。その分の監視は、Console の支出と、利用枠の消費(`/usage`)で行う。

## 見直しの条件

- U-1 の規約の確認の結果が、(a) の方式を認めない場合は、(c) に戻す。
- U-3 の PoC で、(i)、(ii)、(iii) のいずれかが否定された場合は、(a) の設計を見直す。
- 公式が、無人の自動実行や、proxy からの資格情報の付与について、新しい記述を出した場合。

## 確認できなかったこと(未確認)

- ダミーの `CLAUDE_CODE_OAUTH_TOKEN` を CLI が受理するか。setup-token のトークンを `Authorization: Bearer` で送って受理されるか。必要な追加ヘッダー。
- CLI が `/v1/messages` 以外の API パスを呼ぶか(呼べば proxy が 403 を返す)。
- サブスクの枠の具体的な数値と、枠の超過の出力(分類と subtype)。
- `--max-turns`、`--no-session-persistence`、`--model` の、公式ヘッドレス記述での扱い(取得した範囲には無い)。
- Haiku 5.5 での 1 attempt あたりの消費量(実測が無い)。
- `setup-token` のトークンの失効の手段。
- `coding_attempts` に列を足す際のマイグレーションの番号。
- `/proc` とプロセスの環境の見え方(コンテナ内から proxy の環境が読めないことの確認)。
- 最大ターン数の 40 と 30 のどちらが、実際に CLI へ渡るか。

## 実装状況

本 ADR は提案であり、コード、設定、DB、k8s、Keycloak は変更しない。
