# Runbook: 自己開発(self-dev)の運用

対象: Kivra の自己開発の最小ループ(ADR 0008、Issue #56)。配線は #172(main `b3185bb`)で入った。
この文書には秘密の値(トークン、API キー、Secret の中身)を書かない。名前だけを書く。
手順の状態は次の 3 つで書く。「実装済み」はコードで確認したもの、「方針」は ADR や有効化手順に書かれているが、コードでは強制していないもの、「未実装」はコードに無いもの。

確認基準: origin/main `b3185bb`(2026-10-16)。

## 0. 現状(最初に読む)

- 計画の自動作成と投入(オートパイロット)は [ADR 0011](adr/0011-selfdev-autopilot.md)(Proposed)。基盤、Planner、Dispatcher、UI と HTTP(PR A〜D)は main に実装済みで、既定では無効。運用手順は §7。E2E は PR E で追加(テストは `AutopilotE2ETest` / `AutopilotE2EAbnormalTest`)。dev での有効化は未実施(§7.5)。
- CLI 実行のイベント記録と Run ライブモニターの設計は [ADR 0012](adr/0012-run-events-and-live-monitor.md)。未実装(提案)。運用手順は実装 PR で足す。
- 外部の MCP クライアントから kivra の自己開発を使う設計は [ADR 0013](adr/0013-kivra-mcp-server.md)。PR 1(読み取り)は main に実装済みで、既定では無効(§8)。PR 2(Keycloak)と PR 3(ingress)は未マージ。書き込みと停止は未実装。
- 自己開発は既定で無効。`KIVRA_SELFDEV_ENABLED` が正確に `true` のときだけ有効になる。無効のときは、自己開発の他の環境変数を一切読まず、ワーカーも `/api/selfdev/*` も登録しない(`selfdev/SelfDevSettings.kt`、`Application.kt`)。
- 有効化すると、`SelfDevWorker` が起動し、`POST /api/selfdev/tasks`、`GET /api/selfdev/runs`、`GET /api/selfdev/runs/{id}` が登録される(`routes/SelfDevRoutes.kt`)。
- 本番で動かすには、ブランチ保護、GitHub 認証(App 推奨。PAT は代替)、Anthropic 認証(WIF 推奨。静的キーは代替)、モデル registry、為替の設定が必要。§1 を参照する。
- マージ検証(ADR 0008 の ⑩)は配線済み。有効時は `GitHubPrMergeAuthority` が、作者 `KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID` と head SHA を照合して、マージ済みの PR の Run を `COMPLETED` にする(§4.3)。無効時は `NoPrMergeAuthority`(完了しない)。
- binding が無い既存の `PULL_REQUEST` の Run(binding の記録より前に作られたもの)は、マージされても `BINDING_MISSING` で待つ。運用者が §4.1b の API で解放する(`CANCELLED`。PR は残る)。

## 1. 有効化の前提

### 1.1 起動時に検査されるもの(満たさないと起動に失敗する)

| 項目 | 名前 | 内容 |
|---|---|---|
| キルスイッチ | `KIVRA_SELFDEV_ENABLED` | `true` のときだけ有効。`false` または空は無効。それ以外の値は起動失敗。実装済み |
| 認証 | `KIVRA_AUTH_MODE` | `oidc-jwt` が必須。 |
| 実行環境 | `KIVRA_RUNTIME_ENV` | `development` と同時の有効化は拒否される |
| GitHub 認証 | 推奨: GitHub App の `KIVRA_SELFDEV_GITHUB_APP_ID`、`KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID`、`KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY` の 3 つ。代替: PAT の `KIVRA_SELFDEV_GITHUB_TOKEN` | どちらか一方だけ。両方、またはどちらも無いと起動に失敗する。App の秘密鍵は、値が既存のファイルのパスならそのファイルを読み、そうでなければ PEM 本文として扱う。読めない鍵は起動に失敗する。PAT を使う場合は、管理者の権限を持たない専用の細粒度 PAT(`contents:write` と `pull_requests:write` のみ) |
| GitHub の PR 作者 | `KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID` | 必須(GitHub App でも PAT でも)。PR の作者として期待する GitHub の user id の正の整数。値は秘密ではないが、起動のエラーには値を出さない。未設定・非数値・0 以下は起動に失敗する。PAT を使う場合は PAT の持ち主の user id を指定する。調べ方: `gh api /users/<app-slug>%5Bbot%5D --jq .id`(kivra-gh は 343288952)。マージ検証は、この id が開いた PR だけを完了の根拠にする |
| Anthropic 認証 | 推奨: Workload Identity Federation の `KIVRA_SELFDEV_ANTHROPIC_FEDERATION_RULE_ID`、`_ORGANIZATION_ID`、`_SERVICE_ACCOUNT_ID`、`_IDENTITY_TOKEN_FILE` の 4 つ(任意で `_WORKSPACE_ID`)。代替: 静的キーの `KIVRA_SELFDEV_ANTHROPIC_API_KEY` | どちらか一方だけ。WIF の一部だけ、両方、どちらも無いと起動に失敗する。ID の書式は、rule が `fdrl_`、service account が `svac_`、workspace が `wrkspc_`、organization が UUID。トークンファイルは起動時には読まず、交換のたびに読み直す。値は読み取るだけで、ログや設定の表示には出ない |
| モデル registry | `KIVRA_SELFDEV_MODEL_REGISTRY_PATH` | 必須。読み込めなければ起動に失敗する。エラーに path や内容は出ない |
| 為替 | `KIVRA_FX_USD_JPY`、`KIVRA_FX_AS_OF` | 両方必須。`KIVRA_FX_USD_JPY` は 1 以上 1000 以下。`KIVRA_FX_AS_OF` は ISO-8601 の瞬間か日付(日付は UTC 0 時) |

`KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID` は、無効のときは読まれない。有効化には、k8s の設定にこの値(kivra-gh は 343288952)を入れてからデプロイする。未設定の Pod は自己開発が有効でも起動に失敗する。

### 1.2 任意の設定(既定値つき)

| 名前 | 既定 | 内容 |
|---|---|---|
| `KIVRA_SELFDEV_TARGET_REPO` | `kigawa-net/kivra` | 対象は 1 つだけ。リクエストでは指定できない |
| `KIVRA_SELFDEV_BASE_BRANCH` | `main` | |
| `KIVRA_SELFDEV_CONTEXT_PREFIXES` | `README.md,docs/` | LLM に見せるファイルの接頭辞 |
| `KIVRA_SELFDEV_MAX_CONTEXT_FILE_BYTES` | `102400` | |
| `KIVRA_SELFDEV_PR_REVIEW_TIMEOUT_DAYS` | `7` | PR が開いたまま、最終更新からこの日数を過ぎると Run を中止する |
| `KIVRA_SELFDEV_ADVANCE_INTERVAL_SECONDS` | `30`(最小 10) | ワーカーの周期 |
| `KIVRA_SELFDEV_MAX_ACTIVE_RUNS` | `1` | 同時に進行する自己開発 Run の上限 |
| `KIVRA_SELFDEV_RUNTIME` | `api` | コーディングの実行方式。`api` は従来の 1 回の LLM 呼び出し。`cli` はローカル PC のエージェント方式(ADR 0010)。**`cli` は `KIVRA_AGENT_API_ENABLED=true` のときだけ許可し、それ以外は起動時に拒否される**(§1.2.1、§6.9) |
| `KIVRA_FX_MARGIN_RATIO` | `0.10` | 為替の余裕 |
| `KIVRA_FX_MAX_AGE_DAYS` | `7` | レートの最大の古さ |
| `KIVRA_BUDGET_TENANT_ID` | `default` | 予算のテナント |
| `KIVRA_BUDGET_MONTHLY_LIMIT_JPY` | `10000` | 月の上限(円)。ADR 0008 の記載どおり |
| `KIVRA_BUDGET_SOFT_RATIO` | `0.8` | 警告の比率 |

### 1.2.1 `KIVRA_SELFDEV_RUNTIME`(`api` / `cli`)

- 既定は `api`(従来どおり。LLM が 1 回で PatchSet を返す。動作は変わらない)。空や `api` の前後の空白も `api` と同じ。それ以外の値は起動失敗(値は出さず、変数名だけを出す)。
- `cli` は、ローカル PC の常駐エージェント(kivra-agent)が Claude Code CLI で実行する方式(ADR 0010)。**`KIVRA_AGENT_API_ENABLED=true` のときだけ起動を許可する**。それ以外の `cli` の起動は、変数名と ADR 0010 を含む理由で拒否する(値は出さない)。エージェント API が無効のままでは、キューに積んだ attempt を誰も取らないため、拒否は fail-closed の意図どおり。許可されるのは起動の条件だけで、既定の `api` と、エージェント API の既定(無効)は変えていない。結果の流れと失敗時の Run の状態は §6.9 を参照。
- この変数は、自己開発が有効(`KIVRA_SELFDEV_ENABLED=true`)のときだけ読まれる。無効のときは読まれず、拒否もされない。
- 本番の既定を変えるのは、エージェント本体と Keycloak / ingress の作業が終わり、動作確認が済んでから。それまで `api` のままにする。
- `cli` の場合の中身(設計、状態遷移、結果の検査、reconcile の方針)は `docs/design/15-coding-attempts.md` の §6 を参照。

### 1.3 有効化の前提のうち、コードでは強制していないもの(方針)

- `kigawa-net/kivra` の `main` のブランチ保護。2026-10-16 に API で確認したところ、PR 必須、force push 禁止、削除禁止が有効で、`enforce_admins` は `false`。承認数は確認していない。
- モデル registry の Anthropic の `dataHandling.status` が `CONFIRMED` であること。`CONFIRMED` には `confirmedBy`、`confirmedAt`、`sourceUrl` が必要。自己開発は `DataClass.INTERNAL` でリポジトリの内容を送るため、`UNKNOWN` のままだと、そのプロバイダは使われず Run は失敗する(`docs/provider-compatibility-matrix.md` §4a)。起動時には検査しない。
- 価格と能力の一次情報による確認。

### 1.4 Secret と認証の運用

- **GitHub App は `kigawa-net/kivra` のみにインストールする。** "All repositories" にしない。権限は `contents:write`、`pull_requests:write`、`metadata:read` のみ(`docs/provider-compatibility-matrix.md` §3.5、ADR 0008 の追補)。
- **App の秘密鍵は Bitwarden(Secrets Manager)に置き、`BitwardenSecret` で k8s Secret に同期する。** 鍵を git に置かない。Secret は読み取り専用で pod にマウントし、`KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY` にそのファイルのパスを渡す。
- **WIF のルール(Anthropic 側の設定。本リポジトリには無い)**: `oauth_scope` は `workspace:inference`、`token_lifetime_seconds` は `3600`、ワークスペースは専用のものを使う(`KIVRA_SELFDEV_ANTHROPIC_WORKSPACE_ID` で指定する)。ルールが `600` 秒のままだと、JWT は 1 回限りのため、再送を待つ間に認証が止まる。
- **inline JWKS の更新**: k8s の inline JWKS は、クラスターの署名鍵のローテーションに合わせて更新する。更新を忘れると交換が失敗する(`docs/provider-compatibility-matrix.md` §3.6)。
- **許容している既知のリスク(2026-10-17、運用者の判断。上の推奨を満たしていない現状)**:
  - GitHub App `kivra-gh` のインストール対象が "All repositories" のまま。秘密鍵が漏れると、kigawa-net の全リポジトリに `contents:write` と `pull_requests:write` ができる。kivra 自身は、インストールトークンを `kivra` リポジトリだけに限定して要求する(`GitHubAppTokenMinter`)ので、通常の動作では他のリポジトリに書かない。
  - Anthropic の WIF ルールの `oauth_scope` が `workspace:developer` のまま(推奨は `workspace:inference`)。専用ワークスペースの中で、Messages 以外(Files、Skills、Managed Agents)も呼べる。kivra は Messages だけを呼ぶ。
  - どちらも、設定画面(GitHub / Claude Console)での変更だけで解消でき、kivra の再デプロイは要らない。本番相当の環境に広げる前に、推奨の設定にする。
- **既知の制限**: 起動直後に一時的な通信失敗が起きると、同じ JWT は再送できないため、kubelet によるトークンの更新を待つことになる。運用上の目安は最大 48 分(有効期限 3600 秒の 80% に相当する)。この数値は本リポジトリの文書やコードでは確認していない。その間は LLM の呼び出しが失敗する。

## 2. 有効化の順序

1. §1 の前提をすべて満たす。
2. 全レプリカを新しいイメージにする。新旧が混在した状態で有効にしない。
3. レプリカを 1 にする。上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)の判定は、JVM 内の `synchronized` で直列化している(`DevelopmentRunRepository.submitTask`)。複数のレプリカでは、同時の投入で上限を超えうる。
4. `KIVRA_SELFDEV_ENABLED=true` にして起動する。環境変数は起動時に一度だけ読まれるため、変更には再起動が要る。
5. ロールバックの準備として、新しい状態の Run(`PATCH_READY` と `UNKNOWN`)が残っていないことを確認する。読み取りの例は次のとおり。

   ```sql
   SELECT state, COUNT(*) FROM development_runs WHERE state IN ('PATCH_READY', 'UNKNOWN') GROUP BY state;
   ```

   旧イメージ(V13 までを知るもの)が残っていないことも確認する(`docs/provider-compatibility-matrix.md` §4a の 7)。ロールバックするときは、新しい状態の Run が残らない状態にしてから旧イメージに戻す。残っている `UNKNOWN` の Run は §4.1 の手順で解放する。解放しないまま旧イメージに戻すと、解放されない `UNKNOWN` が残る。

## 3. やってはいけないこと

- `org.mariadb` のログを TRACE または DEBUG にしない。INSERT の値(根拠・理由)がログに出る(運用メモによる)。
- レプリカを増やさない(§2 の 3)。
- GitHub の認証(App または PAT)に admin 権限を付けない。ブランチ保護の `enforce_admins` は `false` のため、管理者の権限を持つトークンは保護を迂回できる。App を `kigawa-net/kivra` 以外にインストールしない。
- kivra から PR をマージしない、PR を閉じない。マージの API は `GitHubPort` に無く、テストで固定されている。PR を閉じるのは人間だけである。
- Secret の値を、ログ、Issue、PR、本書に書かない。

## 4. 運用手順

### 4.1 UNKNOWN の Run を解放する

**実装済み(ADR 0009)。** `POST /api/admin/selfdev/runs/{id}/release`。`KIVRA_AUTH_MODE` が設定されているときに登録される(`Application.kt`)。自己開発の無効化とは独立しており、無効化したあとの UNKNOWN も解放できる。

- 対象: 自己開発の Run(`taskId` が `selfdev:` で始まるもの)で、状態が `UNKNOWN` のもの。遷移は `UNKNOWN` → `CANCELLED` だけである。
- 認証: 予算の acknowledge(§4.2)と同じ。オペレーターは認証された主体になる。
- リクエスト: `{"reason":"<理由。1〜500 文字>"}`。ほかのフィールドは 400。
- 応答: 200 `{"runId","state":"CANCELLED","reasonCode":"OPERATOR_RELEASED"}`、400 不正な入力、401 未認証、404 該当なし(自己開発でない Run を含む)、409 `UNKNOWN` ではない(解放済みを含む)。
- 監査: 同じトランザクションで `RUN_RELEASED` を監査に、`RunStateChanged` を outbox に書く。理由は監査の payload にだけ入り、ログには出ない。
- 解放した Run は、同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)を占有しない。
- 解放は原因を直さない。原因が残っていれば、次の Run も `UNKNOWN` になる。解放する前に、下の理由を確認する。
- kivra は PR を閉じず、マージもしない。解放しても、GitHub 上の branch と PR は残る。その扱いは人が決める。

```sh
curl -X POST "https://<kivra-host>/api/admin/selfdev/runs/<runId>/release" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"reason":"<理由>"}'
```

画面(`/ui`)の詳細の「運用者の解放…」から行える(§4.7)。

`UNKNOWN` になる主な理由(コード上の事実):

- PR が見つからない(`PR_NOT_FOUND`)。同じ head の PR が複数ある(`PR_AMBIGUOUS`)。
- 再開時に、ブランチが現在の base の head に無い(base が動いただけでも起きる)。
- LLM の呼び出し結果が不明。GitHub の書き込みの結果が不明。

### 4.1b 束縛の無い PULL_REQUEST の Run を解放する

**実装済み(ADR 0009 追補)。** `POST /api/admin/selfdev/runs/{id}/release-unbound-pr`。`KIVRA_AUTH_MODE` が設定されているときに登録される(`Application.kt`)。§4.1 の解放とは別の経路である。

- 対象: 自己開発の Run(`taskId` が `selfdev:` で始まるもの)で、状態が `PULL_REQUEST`、かつ束縛(binding)が無いもの。
  束縛の記録(PR2)より前に作られた Run が該当し、`BINDING_MISSING` で待つ(§4.3)。遷移は `PULL_REQUEST` → `CANCELLED` だけである。
- 対象外: 束縛がある `PULL_REQUEST` の Run(409)。マージされれば自動で `COMPLETED` になる。人が止める経路は、この手順には無い(ADR 0009 追補の「未対応」)。
- 認証・CSRF: §4.1 と同じ。ブラウザのセッション(oauth2-proxy)か、`Authorization: Bearer` の JWT で行う。`Content-Type: application/json` が必須(text/plain は 415)。
- リクエスト: `{"reason":"<理由。1〜500 文字>"}`。ほかのフィールドは 400。
- 応答: 200 `{"runId","state":"CANCELLED","reasonCode":"OPERATOR_RELEASED_UNBOUND_PR"}`、400 不正な入力、401 未認証、404 該当なし(自己開発でない Run を含む)、409 `PULL_REQUEST` ではない、または束縛がある(解放済みを含む)。
- 監査: 同じトランザクションで `RUN_RELEASED` を監査に(payload に理由)、`RunStateChanged` を outbox に書く。理由はログに出ない。
- **GitHub には何もしない。** kivra は PR を閉じず、マージもしない。解放後、PR は GitHub に残る。人がマージしても、Run は `CANCELLED` のままで、kivra は追跡しない。PR を閉じるかどうかは人が決める。
- 解放した Run は、同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)を占有しない。

画面(`/ui`)の詳細から解放できる(§4.7)。次のブラウザのコンソールのスニペットは、画面が使えないときの代替である(管理画面を開いたタブで実行。`<runId>` と理由を書き換える)。`<runId>` は 26 文字の ULID:

```js
const runId = "<runId>";
const res = await fetch(`/api/admin/selfdev/runs/${runId}/release-unbound-pr`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ reason: "<理由>" }),
});
console.log(res.status, await res.text());
```

curl で行う場合:

```sh
curl -X POST "https://<kivra-host>/api/admin/selfdev/runs/<runId>/release-unbound-pr" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"reason":"<理由>"}'
```

解放の前に、その PR が本当に束縛されていないことと、GitHub 上の状態を確認する。解放は PR に触れないため、解放後も PR は残る。

### 4.2 UNKNOWN_COST の acknowledge(予算の予約)

**実装済み。** `POST /api/admin/budget/acknowledge`。`KIVRA_AUTH_MODE` が設定されているときに登録される(`Application.kt`)。

- 認証: Task API と同じ。オペレーターは認証された主体になり、リクエストの `operatorId` は無視される。
- リクエストの項目: `scopeId`、`period`、`reservationKey`、`actualAmountJpy`(任意。省略は「実費不明」、`"0"` は「検証済みの請求なし」)、`evidence`、`reason`
- 応答: 200 `ACKNOWLEDGED` または `ALREADY_ACKNOWLEDGED`、400 不正な入力、401 未認証、404 該当なし、409 未価格の予約ではない、503 ストアが判断できない

これは予算の予約だけが対象である。Run の `UNKNOWN` の解放は §4.1 を参照する。

```sh
curl -X POST "https://<kivra-host>/api/admin/budget/acknowledge" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"scopeId":"<scopeId>","period":"<period>","reservationKey":"<reservationKey>","actualAmountJpy":"0","evidence":"<根拠の参照>","reason":"<理由>"}'
```

### 4.3 PR_ABANDONED と、マージ済みの PR

**実装済み(自動)。** `PULL_REQUEST` の Run は、次のいずれかで `CANCELLED` になり、監査の理由コードは `PR_ABANDONED` になる(`selfdev/SelfDevRunner.kt`、`db/DevelopmentRunRepository.kt`)。

- PR が閉じられた(マージされずに)。
- 最終更新から `KIVRA_SELFDEV_PR_REVIEW_TIMEOUT_DAYS`(既定 7 日)を過ぎた。

kivra は PR を閉じない。オペレーターが手動で `PR_ABANDONED` を記録する手順は無い。

**マージ済みの PR(ADR 0008 の ⑩、ADR 0011)。** PR を開いた直後に、Run は PR の束縛(binding: リポジトリ、PR 番号、node id、base、kivra が commit した head SHA)を記録する。マージ済みの PR は、binding があれば `TrustedPrMergeAuthority` で検証し、検証に通ったときだけ `COMPLETED` になる(理由 `PR_MERGED`)。検証は GitHub の読み取りだけで行い、PR 本文や人の申告は根拠にしない。kivra は PR をマージも閉じもしない。

有効時、本番の認証器は `GitHubPrMergeAuthority` で、作者が `KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID` と一致し、head が kivra の commit と一致する PR だけが完了の根拠になる。ワーカーは認証器を差し込んでから起動する。認証器が GitHub を読めない場合は `AWAITING_MERGE_VERIFICATION` で待機し、次の tick で再試行する。

**既存の占有 Run は運用者が解放する。** binding の記録(PR2)より前に作られた `PULL_REQUEST` の Run は binding が無いため、マージ済みでも `BINDING_MISSING` で待つ。kivra は binding を推測しないので、自動では完了しない。束縛の無い `PULL_REQUEST` の Run は §4.1b の経路で `CANCELLED` にする(PR は残る)。

待機理由と意味:

- `AWAITING_MERGE_VERIFICATION`: 認証器が答えない(未配線、または GitHub の一時的な障害)。自動で再試行する。
- `MERGE_NOT_VERIFIED`: 認証器が検証を拒否した。ログの `code` に固定の理由が出る(例: `HEAD_SHA_MISMATCH` は、マージ後に head が変わった、`AUTHOR_MISMATCH` は PR の作者が kivra の App ではない、`MERGE_COMMIT_SHA_MISSING` はマージの SHA が無い)。自動では解決しない。運用者が PR と Run を確認する。
- `BINDING_MISSING`: PR は開いているが binding が無い。binding の記録より前に開かれた Run(既存の占有 Run)がこれに当たる。kivra は binding を自分で組み立てない(GitHub の値から推測しない)ため、完了させない。運用者が確認して解消する。束縛が無い Run は §4.1b で解放する(束縛がある Run は、この待機理由にならない)。

ログは `self-development run waiting: runId=... reason=... code=...` の形で出る(`code` は固定の理由コードだけ)。

### 4.4 キルスイッチで止める

**実装済み。** `KIVRA_SELFDEV_ENABLED` を `false` にして再起動する。無効のときは、ワーカーが止まり、`/api/selfdev/*` は登録されず、自己開発の秘密の環境変数は読まれない。

- 止まるのは Run の進行と新規の投入だけである。作成済みの PR は残る。kivra は PR を閉じない。
- `UNKNOWN` の Run は、無効化しても自動では解放されない。解放は §4.1 の API で行う。

### 4.5 POLICY_REJECTED の原因を調べる

**実装済み。** Run が `FAILED` で `POLICY_REJECTED` になったら、ワーカーのログで原因の種類を見る。

- ログの `self-development patch rejected` の行(WARN、1 行)に `runId`、`reasons`(固定の理由コードの列挙名、最大 10 件)、`count`(理由の総数)が出る。例: `reasons=UNSEEN_OVERWRITE,PATH_DENIED count=2`。
- 理由の名前は、どのルールに違反したかだけを示す。ファイルのパス、内容、差分、モデルの出力、タスクの本文は出ない。どのファイルかは、Run の内容と差分の照合で確かめる。
- `UNSEEN_OVERWRITE` は、モデルに見せなかった既存ファイルを書き換えようとしたことを示す。文脈の選択は §1.2 の `KIVRA_SELFDEV_CONTEXT_PREFIXES` と上限で決まる。
- `reasonCode` は `POLICY_REJECTED` のまま(理由コードは含まない)。理由はログだけに出る。

### 4.6 大きなファイルや docs/ の多いディレクトリを変更させる

**実装済み。** LLM に見せるファイルは、接頭辞(§1.2)に合うもののうち上限内で、パス順に選ばれる。`docs/` に多くのファイルがあると、変更したいファイルが上限の外に出ることがある。

- タスクのタイトル(本文)に、リポジトリ内のファイルのパスを書くと、そのファイルが文脈に優先して含まれる。例: `docs/selfdev-hello.md に 1 行追記する`。最大 5 件まで。
- 優先されるのは、リポジトリに実在し、読み取れ、上限内で、書き込みの許可パス(`kivra-*/src/**`、`docs/**`、`README.md`)かつ拒否パスでないもの。`..`、絶対パス、`.git` で始まるものは無視する。
- 優先されたファイルも上限(ファイル数、1 ファイルのサイズ、合計バイト)の内側に入る。上限は変わらない。
- 優先されても、モデルに見せていないファイルへの書き込みは従来どおり拒否される(`UNSEEN_OVERWRITE`)。
- 大きなファイル(目安として 50 KB か 1,000 行以上)の全体を 1 タスクで読み書きしないよう、Planner のプロンプトで指示している(#283。プロンプトだけの制約で、検査は無い)。大きなファイルの変更は、Planner が複数のタスクに分ける。

### 4.7 Web 画面(`/ui`)で Run を見る

`KIVRA_AUTH_MODE` が設定されているときに `https://<kivra-host>/ui` で開ける。認証は API と同じで、画面自体には秘密を含まない。この画面からできるのは、Run の一覧と詳細の確認、Task の投入(種別の選択を含む)、自己開発の Run の解放、予算の確認と未確定の予約の解除(運用者向け。下の「予算」)である。

- **投入(種別の選択)**: 投入フォームの「種別」で、シミュレーション(`POST /api/tasks`)か自己開発(`POST /api/selfdev/tasks`、GitHub に PR を作る)を選ぶ。自己開発は、`GET /api/selfdev/status` の `enabled` が `true` のときだけ選べる。無効のとき、または状態を取れないときは選べない。
  - 冪等キーは画面が作る。種別を切り替えたとき、またはタイトルを変えて送るときは、キーを作り直す。送信が成功するまでは、同じ内容の再送は同じキーになる(ネットワーク失敗のあとに押し直しても二重にはならない)。
  - 送信中はボタンを無効化する。結果: 202「受け付けました」(Run ID つき)、200「既に受け付け済み」、409「同じキーで別の内容」(キーを作り直す。内容を確かめてから投入し直す)、429「自己開発の Run が上限に達している」、400・401・415・その他は固定の文言。応答の本文は画面に出さない。
- **解放(詳細の「運用者の解放…」「解放（束縛なし）…」)**: 対象は、自己開発の Run で状態が `UNKNOWN` のもの(§4.1)、または `PULL_REQUEST` で束縛が無いもの(§4.1b)。ボタンは理由(1〜500 文字、必須、空白だけは不可)を入力する確認の画面(ダイアログ)を開く。理由を入れて「解放する」を押すと、`/release` か `/release-unbound-pr` を呼ぶ。成功すると詳細を読み直す。PR は閉じず、マージもしない(GitHub には書き込まない)。
  - 応答: 200「解放しました（CANCELLED）」、409「解放できる状態ではない（状態が既に変わっている）」、404「見つからない」、401 はセッション切れの案内、400・415・その他は固定の文言。
- **予算(運用者向け、「予算（運用者向け）」の欄)**: `GET /api/admin/budget/status` が 200 を返すユーザーにだけ出る(運用者でない、または予算の API が無効なら出ない。401・403・404 では、セッション切れにもしない)。内容と見方は §6.13 の「運用者の確認手順(画面)」を参照する。
  - 月の使用額(確定 + 予約中)の棒と、上限、残り。上限を超えたときは文字でも出す。経路別の内訳(API 経路 / CLI 経路)。
  - 為替: `GET /api/admin/budget/fx`。残り 2 日以下は黄色の注意、失効は赤のエラーで、どちらも文字で「失効」「残り N 日」と書く。
  - 未確定の予約の一覧(UNPRICED と期限切れの LAPSED)。予約キーは短く表示する(CLI は attempt ID と世代)。「解除…」は二段階: 入力の画面、次に確認の画面、そのあと「解除する」。実額は任意(空欄は実額不明)。`POST /api/admin/budget/acknowledge` と同じ。拒否の理由は固定の文言で出す(実額が計上額の 100 倍を超える、単位が合わない、など)。
- **開発目標(Goal)の欄**: オートパイロット(ADR 0011)が有効のときだけ出る。Goal の入力、計画の承認、タスクの承認と見送り、中止、再計画、全体の一時停止とブレーカーの再開。手順は §7.4。
- **上部の注意書き**: `GET /api/selfdev/status` の `enabled` で出し分ける。有効なら「自己開発の Run は GitHub に PR を作成します(実際に実行されます)」、無効なら「自己開発は無効です」。状態を取れなければ「設定を取得できていません」と出す(有効・無効は推測しない)。
- **実行の経過(自己開発の CLI 経路の Run の詳細にだけ出る節、ADR 0012 PR 4)**: `GET /api/admin/runs/{id}/execution` が 200 で試行(attempt)を返す Run にだけ出る。API 経路の Run、シミュレーションの Run、404・403、試行がまだ無い Run では、節ごと出ない。詳細の画面を開いたときに一度だけ判定する(出ない Run は、再読み込みで判定し直す)。
  - 表示: 試行の状態と世代、ターン数、ツール呼び出しの合計と種類別の件数(Read / Edit / Write / Bash / Grep / Glob / Task / TodoWrite / その他の固定の名前だけ)、ツール結果の失敗、権限の拒否、警告・取りこぼし・上限到達、費用、停止の段階。Run の本文・タイトル・パス・プロンプトは出さない。進み具合は割合にせず、「ターン N」と件数で示す。活動中の試行には「不確定」の棒を出す(上限が分からないため)。
  - 費用: 「確定」は CLI が結果で報告した額(終了後に確定する)。「推定」は予約からの見積りで、確定ではない。実行中の費用は推定だけになりうる(assistant の usage が実行中に届くかは未確認)。
  - 停止: この画面に停止ボタンは無い(PR 5)。「停止要求あり(エージェントの確認待ち)」は停止済みを意味しない。エージェントの報告は観測であり、検証された事実ではない。
  - 更新: Run が終端(COMPLETED / FAILED / CANCELLED)になるまで、画面を開いている間だけ 5 秒ごとにスナップショットを取り直す。タブが隠れている間は 30 秒ごと。失敗が続くと 10・20・40 秒と延ばし、上限は 60 秒。画面を離れると止まる。古い応答(`revision` が表示中のものより古い)は捨てる。
  - SSE(`/execution/stream`): 使えるブラウザでだけ試す。接続が 3 回続けて失敗する、または `resync` が 2 回来ると、SSE を止めて定期取得だけに戻る。SSE は任意の最適化で、定期取得だけでも同じ内容が出る(SSE が oauth2-proxy を通るかは未確認。§6.19)。
  - **鮮度**: `STALE` は「最終の受信から N 分経過。表示は古い可能性があります」を赤く出す。監視が途切れているだけで、Run が止まっているとは限らない。SSE の切断、定期取得の失敗、画面を隠している間の停止は、Run の障害が解消した証拠にも、成功の証拠にもならない。「監視の情報を取得できませんでした」が出たら、下の表示は最後に取れた時点のものである。
- **一覧**: 既定は「自分の Run」(`GET /api/runs`)。「全員の Run(運用者向け)」は `GET /api/admin/runs` で、作成者(`createdBy`)を併記する。ロールを持つ運用者は、どの Run も見られる。
- **種別バッジ**: `シミュレーション`(SIMULATED。AI や GitHub は呼ばれない)、`自己開発(PR を作成)`(SELF_DEV。GitHub に PR を作る)。想定外の値は「不明な種別」と値そのものを出す。
- **タイトル**: 種別によらず、投入した本人と運用者の画面に表示する(`textContent` で表示し、長さは表示側で制限する)。MVP では、認証済みの運用者が投入したタイトルを、同じ運用者の画面に表示する。将来 Issue 由来のテキストを扱う場合(ADR 0008 の ⑪)は、信頼できない入力として再検討する(`textContent` のみ、長さ制限は維持)。種別が不明な Run は、タイトルを表示しない。(ADR 0008 の追補、2026-10-17)
- **状態**: 日本語の説明を併記する。`UNKNOWN` は「結果不明。自動では動かない。運用者が確認して解放するまで、同時上限を占有する」。知らない値は値をそのまま出す。
- **理由コード**(`reasonCode`): 次の意味で出す。`PR_ABANDONED` PR が閉じられた(マージされず)。`OPERATOR_RELEASED` 運用者が解放した。`CANCEL_REQUESTED` 中止が要求された。`PR_MERGED` PR がマージされ、マージを検証した。`PR_NOT_FOUND` / `PR_AMBIGUOUS` PR が見つからない / 一つに決まらない(GitHub 側の確認が要る)。`GITHUB_UNKNOWN_OUTCOME` GitHub の結果が不明。`COMMIT_NOT_APPLIED_TWICE` コミットが二度とも反映されなかった。`POLICY_REJECTED` 変更がポリシーに通らなかった。`BRANCH_ADVANCED_BEFORE_COMMIT` コミットの前にブランチが進んでいた。`DEVELOPER_*` モデルの結果を使えなかった(コードの末尾が詳細)。知らないコードは、コードだけを出す。
- **PR**: 番号と、リポジトリ。リンクになるのは、リポジトリが設定の対象(`kigawa-net/kivra` など)と一致し、URL が `https://github.com/<owner>/<repo>/pull/<番号>` の形のときだけ。それ以外は文字だけ出す。
- **詳細**(`#/runs/<runId>`): 種別、状態と説明、理由、PR、Run ID、Task ID、作成・更新の時刻と、「状態の経過」(監査の出来事。時刻、動作、結果、遷移先と理由コード)。運用者の画面(`scope=all`)では作成者も出す。監査の実行者(actor)と、解放の理由の本文は出さない。
- **セッション切れ**: 401 や再ログインが要る応答は、従来どおり「セッションが切れました」のバナーになる。「再読み込みしてログインし直す」から、もう一度ログインする。


### 4.8 為替(`KIVRA_FX_USD_JPY` / `KIVRA_FX_AS_OF`)の自動更新

`KIVRA_FX_AS_OF` は基準日から 7 日(`KIVRA_FX_MAX_AGE_DAYS`)で失効する。失効すると USD の呼び出しが UNKNOWN_COST で止まるため、週 2 回の更新が要る。

**仕組み(実装済み。実機の実行は未確認)**: `.github/workflows/fx-refresh.yml` が月・木 01:00 UTC と手動で走り、`scripts/fx-refresh.py` が kigawa-net-k8s の `kivra/dev/kivra.yaml` にある 2 つの value だけを書き換えた PR を作る。cd.yml と同じ方式で、OIDC ブローカーから短命トークンを取る。

- 取得元: Frankfurter(ECB の参照レート。キー不要)。現在値と、今日(UTC)から 364 日前までの日次レート。
- 式: `rate = floor(max(現在値, 52 週最高値)) + 1`(整数、円)。費用を多めに見積もる方針は変えない。kivra がさらに余裕 10%(`KIVRA_FX_MARGIN_RATIO`)と税 10%(`KIVRA_TAX_MARGIN_RATIO`)を上乗せする。
- `KIVRA_FX_AS_OF` は実行日(UTC)。
- 検査: 現在値は 1 以上 1000 以下で、日付は今日から 5 日以内。52 週は 100 件以上で、各値は 1 以上 1000 以下。1 つでも外れたら何も書き換えず、ジョブを失敗させる。
- 変動の警告: 新しいレートが旧レートから 10% 以上動いたら、PR のタイトルと本文に警告を出す。
- 同じ値なら変更なし(PR は作らない)。`KIVRA_FX_AS_OF` は毎回更新されるので、レートが同じでも日付だけの PR が出ることがある。
- 既存の PR: 接頭辞 `chore/kivra-dev-fx-` の open な PR があれば、新しく作らず、そのブランチを更新する。無ければ `chore/kivra-dev-fx-<日付>` を作る。

**人がやること**: 更新 PR(タイトル `chore(kivra-dev): 為替を更新 …`)の本文と 2 行の差分を確認し、マージする。警告が出ていれば、取得値を確かめてからマージする。マージで ArgoCD が dev に反映される。自動マージはしない。マージされないまま 7 日を過ぎると、USD の呼び出しが止まる。

**失効時の症状**: USD を使う呼び出しが UNKNOWN_COST で止まる。JPY の呼び出しは影響を受けない。止まった Run の扱いは §4.1 を参照する。

**失効の警告(kivra-app、起動時と 1 日 1 回)**: 為替が設定されていると、kivra は残り日数を計算してログに出す(`FxExpiryWatcher`、`FxExpiryPolicy`)。残りが 2 日以下なら WARN、失効済みなら ERROR で、次の固定形式の 1 行だけを出す(日付と日数のみ。レートや秘密は出さない)。

- `fx rate expires soon: asOf=<日付> daysLeft=<n>`(残り 2 日以下。`daysLeft=0` は失効日当日で、まだ使える)
- `fx rate expired: asOf=<日付>`(失効済み。USD の呼び出しは止まっている)

残り日数は、基準日(UTC)から `KIVRA_FX_MAX_AGE_DAYS` 日後の時刻までの端数を切り上げた日数。為替は、API 経路(`KIVRA_AGENT_API_ENABLED`)か自己開発が有効なときだけ読む。為替が未設定の構成(USD を使わない)では何も読まず、何も出ない。`KIVRA_FX_USD_JPY` と `KIVRA_FX_AS_OF` の値が不正なときは、固定の WARN が 1 行出て、警告だけが止まる(起動は続く)。

**状態の確認(読み取り専用)**: `GET /api/admin/budget/fx`(認証は §4.2 の acknowledge と同じ。未認証は 401)。応答は `{"fx":{"asOf":"2026-10-08","daysLeft":2,"expired":false}}`。為替が未設定なら `{"fx":null}`。

**警告が出たら(運用者がやること)**: 残り 2 日以下の WARN が出たら、§4.8 の更新 PR(`chore(kivra-dev): 為替を更新 …`)を探し、本文と差分を確認してマージを依頼する(マージ自体は人が行う)。PR が無ければ、下の手動の更新を行う。ERROR(失効済み)は、USD の呼び出しが止まっているので、同日中に更新する。

**手動の更新(自動が失敗した、または PR が作れないとき)**:
1. GitHub Actions の `fx-refresh` を `workflow_dispatch` で再実行する。ログの `Refresh FX rate` に `CHANGED`、`NEW_RATE`、`AS_OF` などが出る。
2. 手元で更新する場合: `python3 -I scripts/fx-refresh.py <kigawa-net-k8s の kivra/dev/kivra.yaml のパス>`(取得は実 API。ネットワークが要る)。終了コード 0 は書き換えた、3 は変更なし、1 は失敗(何も書き換えない)。
3. 最後の手段として、`KIVRA_FX_USD_JPY` と `KIVRA_FX_AS_OF` の value を手で直す。レートは 1 以上 1000 以下の整数、AS_OF は `YYYY-MM-DD`(UTC)。

**実行の確認**: `gh run list --repo kigawa-net/kivra --workflow fx-refresh.yml`。PR は `gh pr list --repo kigawa-net/kigawa-net-k8s --state open --json headRefName,title,url` で、接頭辞 `chore/kivra-dev-fx-` のものを見る。

**方針を変える**: 式は `scripts/fx-refresh.py` の `compute_rate`。52 週の窓は `WINDOW_DAYS`、警告の閾値は `BIG_MOVE_RATIO`、検査の範囲は `MIN_RATE` / `MAX_RATE` / `MAX_STALE_DAYS` / `MIN_RANGE_POINTS`。変更は kivra リポジトリの PR で行い、`scripts/test_fx_refresh.py` を更新する。頻度は `fx-refresh.yml` の `cron`(7 日の失効より十分短く保つ)。余裕と税の比率は k8s 側の環境変数で、kivra の設定を変える。

**k8s の他の PR との関係**: cd.yml の deploy PR も同じファイルを更新するが、触る行が違うので通常は競合しない。競合したら、この節の式で値を直し直す。

### 4.9 自己開発 PR のブランチを main に追従させる(Issue #276、既定は無効)

**実装済み(既定は無効)。** kivra の Run に束縛された、人のレビュー待ちの自己開発 PR が `main` より遅れていて(compare API の `behind_by` > 0)、コンフリクトが無いとき、`PUT /pulls/{n}/update-branch`(`expected_head_sha` つき)でブランチを更新する。更新後の head は、kivra が検証してから Run に保存した head を更新する。検証が通らないときは、Run をそのまま人に残す。失敗にはしない。

**有効化**
- `KIVRA_SELFDEV_PR_REBASE_ENABLED=true`(既定は `false`。`true` と `false` と空以外は起動失敗)。自己開発が有効(`KIVRA_SELFDEV_ENABLED=true`)のときだけ読まれる。
- 環境変数は起動時に一度だけ読まれるため、変更には再起動が要る。
- 有効化の前に、「App の権限」(下記)を確認する。

**止め方**
- `KIVRA_SELFDEV_PR_REBASE_ENABLED` を `false` にして再起動する。無効のときは、この機能の経路は一切動かない(GitHub への追加の呼び出しも、監査行も無い)。

**更新の契機**(すべて満たしたときだけ、更新の要求を出す)
- 追従の契機は、`mergeable_state` の `behind` ではなく、compare API `GET /repos/{owner}/{repo}/compare/main...{head}` の `behind_by` > 0。`behind` は、ブランチ保護の「マージ前にブランチを最新にする」が有効なときだけ返り、このリポジトリの `main` では返らない(実機で確認。PR #271 は 9 コミット遅れでも `unstable`)。
- `mergeable` が `true`(計算中の `null`、コンフリクトの `false` は触らない)。`mergeable_state` が `dirty` のときも触らない。
- `behind_by` が整数でない応答、または読めないときは、何もしない(追従しない)。

**再束縛の条件**(すべて満たしたときだけ、Run に保存した head を新しい head に更新する)
- (a) 新しい head がマージコミットで、親がちょうど 2 つ(異なる 2 つのコミット)で、一方が「旧 head」、もう一方が P。P は「現在の main の先頭」またはその祖先(compare API `main...P` の `ahead_by` が 0)。更新の後に main がさらに進んでも、P が main の履歴にあれば通る。
- (b) PR 自身の差分が、更新の前後で同一。2 段で確かめる。
  - 段 1: 差分は、旧 head と main の merge-base からの差分と、main から新しい head までの差分で、ファイルの一覧、モード、内容のハッシュ(git の blob id)で比べる。一致すれば通す。
  - 段 2(段 1 が一致しないときだけ): main が同じファイルの別の箇所を変えると blob が必ず違うため、変更行を比べる。compare API `GET /compare/{merge-base}...{旧 head}` と `GET /compare/P...{新 head}` の `files` を読み、次をすべて満たすときだけ通す。(i) パスの集合が同じ。(ii) 各ファイルの `status` が `added`、`modified`、`removed` のいずれかで、両側で同じ(`renamed`、`copied` は通さない)。(iii) 各ファイルに `patch` がある(無いのはバイナリや巨大な差分)。(iv) 各ファイルの `+` と `-` の行(`@@` のヘッダーと文脈行を除く。`\ No newline at end of file` は含める)の列が、順序も含めて同じ。(v) ファイルの数が 300 未満。(vi) 変更したパスの mode が、旧 head と新 head の tree で同じ。(vii) patch の合計が 256 KiB 以下。
  - 段 2 のログと監査にも、パス、ファイル名、patch の内容は出さない(固定コードと runId、先頭 12 文字だけ)。
- ポリシー(PatchPolicy)は再実行しない。差分が同一なら、ポリシーの結果も同じだからである。
- マージ済みの PR で、head が Run に保存した head と違うとき(更新が kivra の記録の後に届いた、または人が push した)も、同じ検証を通ったときだけ保存した head を更新する。更新リクエストは送らないので、上限(1 時間 1 回、合計 3 回)には数えない。通らなければ何もしない。その後のマージの照合(PrMergeVerifier)は変えず、`HEAD_SHA_MISMATCH` で待機のまま残る。
- 限界(段 2): 文脈行、`@@` のヘッダー、mode 以外のメタデータは比べない。巨大な差分(GitHub が patch を省くもの)とバイナリは通らず、人に残る。
- 上限: 1 つの PR につき、更新の要求は 1 時間に 1 回、合計 3 回まで。

**更新の前の事前チェック**(更新の要求の前に行う。読み取りだけで、試行にも上限にも数えない)
- PR の変更するパスと、main が merge-base からの間に変更したパスを比べる(パスだけ見る。path、mode、blob の一致は見ない)。両方が変更したパスが 1 つでもあれば、更新の要求を送らず、`PR_REBASE_OVERLAPPING_PATHS` で人に残る。この事前チェックは 2 段の検証(下記)の導入で変えていない。同じ行を両方が変えた PR を更新すると、(b) の段 2 でも不一致になり、Run が詰むためである。
- 読み取りに失敗したとき、ツリーが切り詰められたときは、更新しない(何もしない)。更新を行うのは、重ならないと確認できたときだけ。
- 結果は (PR の head SHA, main の head SHA) の組ごとにメモリに保持する(16 組までの LRU。再起動で消える)。同じ組では、merge-base とツリーの読み取りを繰り返さない。main の head の読み取り(ブランチの取得)は、毎周期 1 回ある。

**監査ログ**(`audit_events`、Run の ID で引く)
- `PR_REBASE_ATTEMPTED`: 更新の要求の前に書く。要求の回数は、この行の数で数える(要求が失敗しても数える)。
- `PR_REBASE_HELD`: 要求のあとに人に残った。理由コードを持つ。
- `PR_REBASE_REBOUND`: 再束縛した。payload は `{"from": 旧 head の先頭 12 文字, "to": 新 head の先頭 12 文字, "reasonCode": "PR_REBASE_VERIFIED"}`。
- 題名、本文、パス、ファイルの内容は、監査にも、ログにも出さない。

**人に残るケース**(Run は PULL_REQUEST のまま。`Waiting` の理由コードに出る)

| 理由コード | 意味 | 対応 |
|---|---|---|
| (コードなし) | コンフリクト(`mergeable` が `false` または `mergeable_state` が `dirty`)、`mergeable` が未計算(`null`)、作者が bot でない、または `behind_by` が 0 | 人がレビューする。kivra は触らない(未計算のときは次の周期に回る) |
| `GITHUB_CONFLICT` | 更新の要求が拒否された(head が動いた、またはコンフリクト) | 人がレビューする |
| `GITHUB_PERMISSION` | App に権限が無い(403)。**有効化の前の確認事項**(下記) | App の権限を確認する |
| `PR_REBASE_NOT_OBSERVED` | 要求は通ったが、新しい head が見えなかった | 後から届いた更新は、次の周期で検証されて再束縛される。届かなければ人が確認する |
| `PR_REBASE_UPDATE_UNKNOWN` | 要求の応答が不明(再送はしない) | `NOT_OBSERVED` と同じ扱い |
| `PR_REBASE_PARENTS_MISMATCH` | 新しい head の親が「旧 head と、main の先頭またはその祖先」の組にならない(人の push の可能性を含む) | 人が確認する |
| `PR_REBASE_DIFF_CHANGED` | PR の差分が変わった(改ざんの可能性を含む)。段 1 と段 2 の両方で一致しない。再束縛しない | 人が差分を確認する |
| `PR_REBASE_VERIFY_FAILED` | 読み取りに失敗した(ツリー、または compare API の変更ファイル一覧)、応答の形が不正、またはツリーが切り詰められた | 次の周期で再試行される。続くなら人が確認する |
| `PR_REBASE_REBIND_FAILED` | 再束縛の DB 書き込みに失敗した(旧 head のまま。同じ transaction で巻き戻る) | DB とログを確認する |
| `PR_REBASE_LIMIT_TOTAL` | 合計 3 回を使い切った | 人が PR を確認する |
| `PR_REBASE_OVERLAPPING_PATHS` | main が PR と同じパスを変更している。更新すると検証に通らず Run が詰むため、更新しない(試行にも数えない) | 人が必要に応じて main を手元で取り込む、またはそのままマージする(head が変わらないので Run は完了する) |

**確認事項(未確認): App の権限**
- kivra の GitHub App のトークンは、`contents:write` と `pull_requests:write` を要求する(`kivra-github` の `GitHubAppTokenMinter`)。
- この実装では、`update-branch` に必要な権限と、App の実際の付与(GitHub 上の設定)は確認していない。有効化の前に、dev で `PUT /pulls/{n}/update-branch` が 403 にならないことを確認する。403 のときは `GITHUB_PERMISSION` で人に残り、その試行も上限に数えられる。
- 権限の追加は共有基盤の変更であり、リポジトリ管理者の承認が要る。

### 4.10 自動マージの判定ロジック(部品。未配線)

ADR 0014 の第 1 の部品。`net.kigawa.kivra.selfdev.automerge` の `decideAutoMerge` は、呼び出し側が読んだ事実だけを受け取り、判定の結果(`Eligible`、`Hold(理由)`、`Refuse(理由)`)を返す純粋な関数である。I/O、ログ、DB、GitHub の読み取りとマージの呼び出しは持たない。

**配線されていない。** `Application.kt`、ルート、DB、マイグレーション、環境変数の読み込み、GitHub の読み取り、マージの呼び出しは、この部品では作っていない。この判定が本番や dev の動作を変えることはない。配線は別の PR で、ADR 0014 の段階 S1(影の判定)から始める。

**S1 で影の判定として配線された(§4.11)。** 判定は監査に記録するだけで、マージは呼ばない。マージの配線(S2)は、まだ無い。

- **条件**(ADR 0014 §2 の C1 から C9。C10 は、同じ関数を読み直した事実で呼び直すことで実現する)。
  - C1 起源: PR の作者が bot の id、head ref が `kivra/run-<runId>`、Run が `PULL_REQUEST`、binding(repo、PR 番号、node id)が一致。
  - C2 承認: 承認の種別が `AUTO_APPROVED`。運用者承認と、承認の無いタスクは拒否する。
  - C3 差分: ファイル数 1 から 3、`additions + deletions` が 200 以下、`status` は `added` か `modified`、パスは安全で対象内、mode は `100644`。
  - 対象: `kivra-*/src/test/kotlin/**/*.kt` のみ(U-1)。禁止領域(§2.1 の表)に当たるパスは、対象内でも拒否する。
  - 禁止領域のテスト側(門番自身のテストを、自動承認の変更で弱められないように。`PATH_OUT_OF_SCOPE`): `kivra-*/src/test/kotlin/**/selfdev/automerge/**`、ファイル名に `PatchPolicy`、`AutoScope`、`PathRules`、`DiffCollector`、`DiffLimits` を含む `*.kt`、`*ArchitectureTest*.kt`、`*Invariant*Test*.kt`、`kivra-policy/src/test/**`、`kivra-github/src/test/**`、`kivra-core/src/test/kotlin/net/kigawa/kivra/core/**`、`kivra-*/src/test/kotlin/**/auth/**`、`kivra-app/src/test/kotlin/net/kigawa/kivra/selfdev/**`、`kivra-app/src/test/kotlin/net/kigawa/kivra/mcp/**`、`kivra-app/src/test/kotlin/net/kigawa/kivra/routes/**`。このため、対象に残るのは、これらに当たらないテストだけになる(例: `kivra-app/src/test/kotlin/net/kigawa/kivra/util/XTest.kt`)。
  - C4 head: PR の head、binding の head、本文の `kivra-head-sha:` マーカーが同じ SHA。
  - C5 チェック: 必須の名前(U-3。設定の値)がすべて、同じ head SHA で `completed` かつ `success`。欠けると拒否。`queued` と `in_progress` は保留し、60 分を超えたら拒否。
  - C6 マージ可能: `mergeable == true` かつ `mergeable_state == "clean"`。`mergeable` が null か、state が `unknown` なら保留。draft は拒否。
  - C7 止める手段: ラベル `kivra:hold`(大文字小文字を区別しない)が付いていれば拒否。
  - C8 全体: 自動マージのフラグ、オートパイロットと自己開発のフラグが有効、一時停止が無い、ブレーカーが閉じている。`main` の直近の push CI が `success`(保留中と不明は保留、失敗は拒否)。
  - C9 レート: 処理中が上限(既定 1)未満、UTC 当日の自動マージが上限(既定 3)未満。どちらも超えたら保留。

- **判定の順序**: 拒否は見つけた時点で返す。保留は最初の 1 件を覚え、拒否が無ければそれを返す。拒否と保留が両方あれば拒否になる。

- **理由コードの一覧**(固定の enum。名前だけを使う。PR の題名、本文、パス、内容は含まない)

| 理由コード | 結果 | 条件 |
|---|---|---|
| `INVALID_FACTS` | 拒否 | 入力が不正(空の項目、負の件数、重複するパス、不正な設定) |
| `NOT_BOT_AUTHOR` | 拒否 | C1 |
| `BRANCH_NOT_RUN` | 拒否 | C1 |
| `RUN_NOT_PULL_REQUEST` | 拒否 | C1 |
| `BINDING_MISMATCH` | 拒否 | C1 |
| `OPERATOR_APPROVED_TASK` | 拒否 | C2(U-8) |
| `NOT_AUTO_APPROVED` | 拒否 | C2 |
| `FILES_OUT_OF_RANGE` | 拒否 | C3(ファイルが 0 件) |
| `TOO_MANY_FILES` | 拒否 | C3 |
| `TOO_MANY_LINES` | 拒否 | C3 |
| `STATUS_NOT_ALLOWED` | 拒否 | C3(`removed`、`renamed` など) |
| `PATH_UNSAFE` | 拒否 | C3(`..`、`\`、先頭の `/`、空や `.` の区切り、NUL、制御文字、書式文字(双方向制御を含む)) |
| `PATH_OUT_OF_SCOPE` | 拒否 | C3(対象外、または禁止領域) |
| `MODE_NOT_REGULAR` | 拒否 | C3 |
| `HEAD_MISMATCH` | 拒否 | C4 |
| `CHECK_MISSING` | 拒否 | C5(同じ head の実行が無い。必須の名前が空の設定も含む) |
| `CHECK_NOT_SUCCESS` | 拒否 | C5(`neutral`、`skipped`、`cancelled`、失敗、結論の無い完了) |
| `CHECK_PENDING` | 保留 | C5 |
| `CHECK_PENDING_TIMEOUT` | 拒否 | C5(保留が 60 分を超えた) |
| `DRAFT` | 拒否 | C6 |
| `NOT_MERGEABLE` | 拒否 | C6 |
| `MERGEABLE_UNKNOWN` | 保留 | C6 |
| `HOLD_LABEL` | 拒否 | C7 |
| `FLAG_OFF` | 拒否 | C8 |
| `PAUSED` | 拒否 | C8 |
| `BREAKER_OPEN` | 拒否 | C8 |
| `MAIN_CI_NOT_GREEN` | 保留(保留中、不明)または拒否(失敗) | C8 |
| `CONCURRENCY_LIMIT` | 保留 | C9 |
| `DAILY_LIMIT` | 保留 | C9 |

- **テスト**: `./gradlew :kivra-app:test --tests 'net.kigawa.kivra.selfdev.automerge.*'`。各条件を 1 つずつ崩したケース、境界(3 と 4 件、200 と 201 行、60 分と 60 分 1 秒、1 日 2 件と 3 件、同時 0 件と 1 件)、パスの表、全体の停止、運用者承認の拒否、決定性を確かめる。

### 4.11 自動マージの影の判定(S1、ADR 0014、既定は無効)

ADR 0014 の段階 S1。オートパイロットが有効なとき、`AutoMergeShadowWatch` が 60 秒ごとに、PULL_REQUEST の Run の PR を §4.10 の `decideAutoMerge` で判定し、結果を監査(`audit_events`)に記録する。**マージ、ラベル、コメント、ブランチの更新は、どれも行わない。** GitHub への書き込みの呼び出しは、この経路のコードに無い(アーキテクチャテストで固定)。

- **有効化**: 次の 2 つが必要。どちらも無ければ、ワーカーは起動せず、判定の変数も読まない。
  - `KIVRA_SELFDEV_AUTOPILOT_ENABLED=true`(オートパイロットの有効化の前提。§7.5)
  - `KIVRA_SELFDEV_AUTOMERGE_SHADOW_ENABLED=true`(既定は `false`。`true` と `false` 以外の値は起動に失敗する。空は `false`)
- **必須チェックの名前**: `KIVRA_SELFDEV_AUTOMERGE_REQUIRED_CHECKS`(既定 `test,build,boot`。カンマ区切りで 1 から 10 件、名前は重複しない。名前に使えるのは英数字、空白、`._()/-`)。空白は名前の中に入れられる(例: `deploy to dev`)。このフラグが `false` のときは読まない。
- **読み取りの上限**: 1 周に最大 5 件の PR(古い順)。1 周の GitHub の読み取りは最大 40 回(port の呼び出し数。HTTP の要求数ではない)。上限を超えた読み取りは、読めなかったものとして扱われ、判定は保留か拒否になる。
- **`checks:read` が必要**: 必須チェックの読み取り(`commits/{sha}/check-runs`)と、`main` の CI の読み取りには、GitHub App の `checks:read` の権限が要る。権限が無いと 403 になり、チェックを読めなかったとして扱われる。その場合の判定は、必ず `REFUSE`(`CHECK_MISSING`)か `HOLD`(`MAIN_CI_NOT_GREEN`)になり、`ELIGIBLE` にはならない。この権限の追加は共有基盤の変更で、運用者が後で行う(ADR 0014 U-4)。
- **判定の中身**: 事実は GitHub の PR の値と、DB の Run、タスクの承認の種別、全体の一時停止とブレーカーから組み立てる。`main` の CI は、`main` の head コミットの必須チェックから導く(`PENDING` と `UNKNOWN` は保留、`FAILURE` は拒否)。PR の本文は、`kivra-head-sha:` の行(40 桁の 16 進)だけを読み、それ以外は使わない。本文は保存も記録もしない。
- **自動マージの一時停止(V31、ADR 0014 U-10)**: `selfdev_autopilot_control.auto_merge_paused`(既定 `FALSE`)が `TRUE` のあいだ、影の判定は GitHub を読まず、判定もしない。Run ごとに 1 件だけ `AUTO_MERGE_SHADOW_PAUSED`(結果 `PAUSED`)を監査に記録する(同じ状態のあいだは繰り返さない)。次の tick から効く。全体の一時停止(`/api/selfdev/autopilot/pause`)とは独立で、どちらか一方だけでも止まる。操作は §7.4 の `auto-merge/pause` と `auto-merge/resume`。AI の主体は拒否される。画面には「自動マージ: 一時停止中」と表示される。この操作はマージも有効化もしない。
- **判定の残りは S1 のまま**: 自動マージのフラグは環境変数と DB の値を合わせて計算する対象だが、S1 の影の判定では `autoMergeEnabled` を常に true とする(フラグの有効化は k8s 側の別の作業)。
- **監査**: 行動 `AUTO_MERGE_JUDGED`、Run の id、結果(`ELIGIBLE`、`HOLD`、`REFUSE`)。`payload` には次の項目だけを入れる。
  - `prNumber`、`headSha`、`verdict`、`reason`(§4.10 の理由コード。`PR_NOT_OPEN` などの読み取りの理由は別)、`files`(件数)、`lines`(`additions + deletions` の合計)、`checks`(判定の対象の名前)、`readFailures`(読めなかったものの固定コード)。
  - **記録しない**: PR の題名と本文、Goal と Task の題名と本文、パス、ファイルの内容、チェックの出力、プロバイダーの応答、トークン。
- **重複の抑止**: 同じ Run の (head SHA、判定の結果、理由) が前回と同じなら記録しない。判定が変わったとき、または head が変わったときだけ記録する。メモリ上の値なので、再起動の直後に 1 回だけ重複して記録されることがある。
- **確認**: 監査の行を見る(読み取りだけ)。

```sql
SELECT occurred_at, run_id, result, payload
  FROM audit_events
 WHERE action = 'AUTO_MERGE_JUDGED'
 ORDER BY occurred_at DESC
 LIMIT 50;
```
- **止め方**: `KIVRA_SELFDEV_AUTOMERGE_SHADOW_ENABLED=false` にして再起動する(判定の記録が止まる)。オートパイロットを止めれば、判定も止まる。記録は消さない(監査は追記のみ)。
- **マージしないこと**: この段階は、PR をマージしない。判定の結果が `ELIGIBLE` でも、PR は人がマージする。S1 の進む条件は ADR 0014 §6 の S1 の欄による(判定が 20 件以上、そのうち「マージ可能」と判定した件数のうち人が問題と判断した件数が 0)。
- **ダッシュボード**: 判定の一覧の画面は、部品 C の仕事で、この段階には無い。


## 5. 動作確認

トークンはプレースホルダである。認証は Task API と同じで、`Authorization: Bearer <JWT>`(無ければ `X-Forwarded-Access-Token`)。

状態を変える POST(`/api/tasks`、`/api/selfdev/tasks`、`/api/admin/selfdev/runs/{id}/release`、`/api/admin/budget/acknowledge`)は、CSRF 対策のため次を満たさないと拒否される(本文は読まれず、状態は変わらない)。

- `content-type: application/json` を必ず付ける(curl は `-H 'content-type: application/json'`。無いと 415)。
- ブラウザから呼ぶときは、同じオリジンの画面から `fetch` で呼ぶ。別のサイトのページから呼ぶと 403 になる(`Sec-Fetch-Site` が `same-origin` / `none` 以外、または `Origin` が `Host` と一致しない)。
- `Authorization: Bearer` の curl は、`Origin` も `Sec-Fetch-Site` も付かないので、そのまま通る。

```sh
# タスクの投入(項目は title と idempotencyKey、任意で body。ほかの項目は 400)
curl -X POST "https://<kivra-host>/api/selfdev/tasks" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"title":"<タイトル 1-200 文字。Issue の URL、#番号、owner/repo#番号も可>","idempotencyKey":"<キー 1-128 文字>","body":"<任意。0-8000 文字>"}'

# 状態の確認(runId は 26 文字の ULID)
curl -H "Authorization: Bearer <JWT>" "https://<kivra-host>/api/selfdev/runs/<runId>"

# 自分の自己開発 Run の一覧(最大 100 件)
curl -H "Authorization: Bearer <JWT>" "https://<kivra-host>/api/selfdev/runs"
```

応答:

- 投入: 202 `Accepted`、同じ `idempotencyKey` での再送は 200、同じキーで別のタイトルは 409、同時に進む上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)を超えると 429、認証されないと 401、本文が不正だと 400。
- 応答の項目: `runId`、`state`、`mode`(`SELF_DEV`)、`note`、`reasonCode`、`pullRequestNumber`、`pullRequestUrl`。タスクのタイトル、パッチ、モデルの出力は返さない。
- 状態の確認: 他の利用者の Run と存在しない Run は、どちらも 404。
- PR ができるまで数分かかる。`state` が `PULL_REQUEST` になり、`pullRequestUrl` が入れば、人間がレビューしてマージする。

注意: `POST /api/dev/tasks` は開発用のプレビューであり、インメモリで、`SIMULATED` の結果を返す。自己開発の Run は作らないため、動作確認には使わない。

### 5.1 本文(body)と参照(Issue #229 追補、2026-10-18)

- **body**(任意): 0〜8000 文字(コードポイント)。改行(LF)とタブは使えるが、ほかの制御文字(CR を含む)は 400。空白だけの body は body 無しと同じ。
- **title の規則は変えない**: 1〜200 文字、制御文字なし。
- **双方向制御文字**(U+202A〜U+202E、U+2066〜U+2069、U+200E、U+200F、U+061C)は、title と body のどちらにあっても 400(MCP も同じ)。ゼロ幅結合子(U+200D)などは使える。
- **参照**: title が次のどれか一つの全体に一致すると、kivra が Issue を読んで本文にする。
  - `https://github.com/<owner>/<repo>/issues/<n>`(`/pull/<n>` も受け付けて、後で PR として拒否する)
  - `#<n>`(対象リポジトリの Issue)
  - `<owner>/<repo>#<n>`
  - 対象は `KIVRA_SELFDEV_TARGET_REPO` のリポジトリだけ。それ以外は失敗(`TASK_REFERENCE_REPO_NOT_ALLOWED`)。
  - 読むのは Issue の title と本文と状態だけ(コメント、ラベル、作者は読まない)。Issue の本文は kivra の GitHub App の読み取りで取る。リダイレクトは辿らない。
- **本文の組み立て**: 参照だけなら `Issue #<n>: <title>` と空行と Issue の本文。手動の body があれば、手動の本文を先に置き、`--- 参照 ---` の区切りの後ろに参照の本文を付ける。合計は 8000 文字以内で、超えた分は切り詰めて末尾に `[truncated by kivra ...]` を付ける(claim の `bodyTruncated` は、この印で決まる)。手動の本文が長すぎて参照の余地が 200 文字未満なら、Run は失敗する(`TASK_REFERENCE_NO_ROOM_FOR_REFERENCE`)。
- **解決の時点**: Run が DEVELOPING に入り、最初に試みるとき(attempt を作る前)。一度だけ行い、結果(`task_body`、`task_source` = `owner/repo#n`)を Run に保存する。保存済みの Run は再度読まない。
- **失敗**: Run は `FAILED`、理由は `TASK_REFERENCE_UNRESOLVED`。監査の行に、固定のサブ理由が入る(`TASK_REFERENCE_REPO_NOT_ALLOWED`、`TASK_REFERENCE_PULL_REQUEST`、`TASK_REFERENCE_CLOSED`、`TASK_REFERENCE_NOT_FOUND`、`TASK_REFERENCE_FORBIDDEN`、`TASK_REFERENCE_UNAVAILABLE`、`TASK_REFERENCE_RESPONSE_TOO_LARGE`、`TASK_REFERENCE_NO_ROOM_FOR_REFERENCE`)。5xx と通信の失敗は 2 回まで再試行してから `UNAVAILABLE` にする。attempt は作らない。ほかの Run は止まらない。再試行の操作は無い。投入し直す。
- **冪等**: 同じ `idempotencyKey` で title も body も同じなら、既存の Run を返す(200)。title か body が違えば 409。body を無しにした再送は、body 無しの元の投入と同じなので 200 になる。
- **大きな Issue**: 本文は 8000 文字に切り詰める。`maxFiles` などの PatchPolicy が最終ゲートである。大きな依頼は、Planner(#233)が分割する前提で、手動の body か分割された Issue で渡す。
- **表示**: 投入者と運用者の Run 詳細に `解決元`(`owner/repo#n`)を表示する。本文と Issue の内容は、画面にもログにも監査にも出さない。

## 6. エージェント API(`/api/agent/v1`、無効が既定)

ローカル PC の常駐エージェント(kivra-agent)が、kivra から attempt を取る API(ADR 0010、PR 4a)。運用者の API とは認可を分ける。認可の方針は ADR 0010 の追補 2 を参照する。

### 6.1 有効化の前提

- 既定は無効(`KIVRA_AGENT_API_ENABLED` が未設定または `false`)。無効のときは `/api/agent/*` の全パスが 404 で、エージェント用の設定変数は一つも読まれない。
- `true` にするには、次が揃っていること。揃わなければ起動に失敗する(エラーは変数名だけを出し、値は出さない)。
  - `KIVRA_AUTH_MODE=oidc-jwt`(他のモードは不可)
  - `KIVRA_OIDC_ISSUER`、`KIVRA_OIDC_AUDIENCE`(運用者と共通。任意の `KIVRA_OIDC_JWKS_URL`)
  - `KIVRA_AGENT_AZP`(例: `kivra-agent`)
  - `KIVRA_AGENT_REQUIRED_ROLE`(`<client>:<role>`、例: `kivra-agent:agent`。`KIVRA_OIDC_REQUIRED_ROLE` と同じにはできない)
- Keycloak 側: client `kivra-agent`(PC ごと。client credentials)、ロール `kivra-agent:agent`。トークンの `aud` に kivra(`KIVRA_OIDC_AUDIENCE`)を含め、`azp` が `kivra-agent` になること。運用者の `admin-panel:admin` は、エージェントに付けない。
- 任意: `KIVRA_AGENT_ATTEMPT_RESERVE_USD`(既定 `0.50`。0 より大きく 100 以下、小数 6 桁まで)。報告の無い attempt に計上する予約額(§6.13)。`KIVRA_SELFDEV_MODEL_REGISTRY_PATH` が設定されていれば、その価格表で `model` を確かめる(未設定なら全ての model が UNPRICED)。
- `KIVRA_SELFDEV_RUNTIME=cli` は、この変数が `true` のときだけ起動を許可される(§6.9)。エージェント API を有効にすると、自己開発の `cli` 経路が結線された状態で起動する。本番の既定は変えていない。

### 6.2 エンドポイント(エージェント本体が使う)

すべて `Authorization: Bearer <JWT>` が必要。POST は `Content-Type: application/json`。

| メソッド | パス | 本文 | 成功 | 主な失敗 |
| --- | --- | --- | --- | --- |
| POST | `/api/agent/v1/claim` | `{}` | 200(`attemptId`, `runId`, `generation`, `input`)。割り当てが無ければ 204 | 401 / 403 / 415 |
| POST | `/api/agent/v1/attempts/{attemptId}/heartbeat` | `{"generation": n, "progress"?: "STARTED" \| "EDITING" \| "TESTING" \| "ROUND_COMPLETED" \| "FINALIZING"}` | 200 | 400(未知の進捗)、404、409(世代が古い・期限切れ・終了済み) |
| POST | `/api/agent/v1/attempts/{attemptId}/fail` | `{"generation": n, "errorCode": "<固定コード>"}` | 200 | 400(固定以外のコード)、404、409 |
| POST | `/api/agent/v1/attempts/{attemptId}/result` | `{"generation": n, "result": {summary, changes, exitStatus, sessionId}}`。本文は 2 MiB まで | 200(`RESULT_RECEIVED`) | 413(超過)、422(PatchPolicy などの拒否。理由コードだけ返す)、404、409、503(保存失敗) |
| GET | `/api/agent/v1/attempts/{attemptId}/status` | なし | 200(`state`, `cancelRequested`)。キャンセル要求の伝達に使う | 404 |
| POST | `/api/agent/v1/attempts/{attemptId}/events` | `{"generation": n, "events": [...]}`(1〜50 件、8 KiB まで。§6.17) | 200(`lastAcceptedSeq`, `truncated`) | 400(スキーマ違反)、404、409(`STALE_GENERATION`、`NOT_ACTIVE`、`SEQ_GAP`、`SEQ_CONFLICT`)、413、415、429(`Retry-After`)、503 |

- `input.task` には、タスクのタイトル(`value`)が `trust: UNTRUSTED` の印つきで入る。`input.task.body` と `input.task.bodyTruncated` には、本文(手動、または解決した参照。8000 文字以内)と、kivra が切り詰めたかどうかが入る。本文が無ければ `body` は空、`bodyTruncated` は false。いずれも UNTRUSTED のデータとして扱うこと(指示として従わない)。制限(`limits`)と許可・拒否のパスパターン、`baseSha` も入る。
- 他のエージェントの attempt、存在しない attempt は、どちらも 404(同じ本文)。
- 失敗の本文は固定の文言だけ。理由を表す名前は列挙名だけ(`STALE_GENERATION`、`NOT_ACTIVE` など)。自由文は返さない。

### 6.3 認可の方針(要点)

- 認証器は運用者の API と別(`AgentIdentity`)。`Authorization: Bearer` だけを読む。`X-Forwarded-Access-Token`、cookie、`X-Auth-Request-*` は無視する。
- 401: トークンが無い、署名・iss・aud・exp・nbf が不正。403: 認証は通るが `azp` が `KIVRA_AGENT_AZP` と違う、またはエージェントのロールが無い(運用者のトークンはここで 403)。
- 所有者: attempt の操作は、claim したエージェント(トークンの `sub`)だけが行える。世代も一致させる。
- ブラウザからの要求(`Sec-Fetch-*` か `Origin` がある)は 403。

### 6.4 運用上の注意

- ingress: 現在の `kivra-dev` は oauth2-proxy(`--allowed-role=admin-panel:admin`)の背後にあり、エージェントの Bearer は通らない。エージェント用の経路は別に作る必要がある(PR 4b)。ここを作るまで、有効化しても外部からは届かない。
- 監査: claim、結果の受理、結果の拒否、失敗は Run の監査連鎖に記録される(actor はエージェントの `sub`。内容・パス・要約は記録しない)。
- 期限切れ・孤児の掃除(`CodingRuntimeService.reconcile`)は、この PR では定期実行に配線していない。ORPHANED、EXPIRED の attempt は、運用者が §4.1 の手順で判断する。
- 有効化の前に、`KIVRA_AGENT_AZP` と `KIVRA_AGENT_REQUIRED_ROLE` が Keycloak の設定と一致しているか確認する。一致しなければ、正しいトークンでも 403 になる。


### 6.5 エージェント本体(`kivra-agent`)の構成

ローカル PC で動く常駐エージェントの Gradle モジュール(`kivra-agent`、ADR 0010、設計 #187 §7)。PR 5 の範囲は kivra との通信だけ。

- **PR 5(この範囲)**: 設定(環境変数。`KIVRA_AGENT_*`、起動時は fail-closed)、Keycloak の client credentials によるトークン取得(毎回の新しいトークン、期限の少し前に再取得、secret はファイルから読む)、エージェント API の client(claim・heartbeat・fail・status。応答は厳格に解析し、エラーは固定の分類)、ループ(STOP ファイル、claim、heartbeat、409/404 での即時中止、cancelRequested での CANCELLED、SIGTERM での CANCELLED、失敗時の指数バックオフ)、作業ディレクトリのロックファイル(二重起動の拒否)。
- **スタブ**: claim したアテンプトは実行せず、`fail(NEEDS_HUMAN)` で返す。NEEDS_HUMAN は kivra で UNKNOWN になり、運用者が判断する(自動再試行しない)。サーバーに NOT_IMPLEMENTED のような名前は無い。
- **PR 6(この範囲の docker 部分。§6.6)**: docker run の引数の組み立て(`DockerCommandBuilder`)、実行の直前の禁止項目の検査(`DockerArgsGuard`)、起動・タイムアウト・キャンセル・出力上限での `docker kill`、孤児掃除(`DockerRunner`)。CLI の起動は PR 7、差分は PR 8。
- **PR 7**: Claude Code CLI の起動と stream-json の解析、エラーの分類、認証の検査。
- **PR 8**: 作業ディレクトリ(`git archive` の展開)と差分(PatchSet)の生成。

設定の環境変数: `KIVRA_AGENT_KIVRA_URL`、`KIVRA_AGENT_TOKEN_URL`(https。localhost の http だけ可)、`KIVRA_AGENT_CLIENT_ID`、`KIVRA_AGENT_CLIENT_SECRET_FILE`(絶対パス。グループとその他が読めない権限 0600 が必要)、`KIVRA_AGENT_WORK_DIR`(絶対パス)、`KIVRA_AGENT_POLL_INTERVAL_MS`(既定 5000、1000〜60000)、`KIVRA_AGENT_HEARTBEAT_INTERVAL_MS`(既定 30000、5000〜120000)、`KIVRA_AGENT_STOP_FILE`(既定は作業ディレクトリ直下の `STOP`)。

### 6.6 docker の実行引数の不変条件と禁止項目(PR 6)

コード: `kivra-agent/src/main/kotlin/net/kigawa/kivra/agent/docker/`。試験は fake の docker(`FakeDocker`)で行い、実際の docker は使わない。

**二重の防御**: `DockerCommandBuilder` が許可リストの形で引数を組み立て、`DockerRunner.run` が実行の直前に `DockerArgsGuard` で同じ配列を検査する。検査が 1 つでも失敗すれば、プロセスは起動しない。

**許可リスト方式**: 認識できるオプションは固定の集合だけ。未知のオプション、短いフラグ(`-e`、`-v`、`-it` など)、スペース区切りの値、値の付いたフラグ、最後の画像名の後ろの引数は拒否する。値は必ず `--name=value` の 1 トークン。

**禁止項目(明示的に拒否。`FORBIDDEN_OPTION` / `FORBIDDEN_VALUE` 等)**:

| 項目 | 拒否の理由 |
| --- | --- |
| `--privileged`、`--cap-add`(任意の値)、`--device`、`--gpus`、`--sysctl`、`--runtime`、`--pull` | 権限・デバイスの拡張 |
| `--pid`、`--ipc`、`--uts`、`--userns`、`--cgroupns`、`--net`、`--network` の host・none・container:・bridge | ホストの名前空間 |
| `--security-opt` の値。`no-new-privileges` 以外(`seccomp=unconfined`、`apparmor=unconfined`、`no-new-privileges=false` など) | 保護の無効化 |
| `--volume`、`-v`、`--volumes-from`、`--mount` の作業ディレクトリ以外(`docker.sock`、`/var/run`、`/run`、ホストの `$HOME`、`/home/*`、`/etc`、`/proc`、`/sys`、`/dev`、`~/.ssh`、`~/.claude`、`~/.config`、`/etc/kivra-agent`) | マウントは作業ディレクトリ 1 つだけ |
| `--env-file`、`-e NAME`(名前だけ。ホストの値の継承)、許可リスト外の環境変数(`KEYCLOAK*`、`CLIENT_SECRET`、`GITHUB_TOKEN`、`GH_TOKEN`、`BWS_ACCESS_TOKEN`、`SSH_AUTH_SOCK` など) | 秘密の持ち込み |
| 環境変数の `ANTHROPIC_API_KEY` が固定のダミー以外、どの値にも `sk-ant-` を含む | 本物の API キーの混入 |
| `--cgroup-parent`、`--add-host`、`--dns`、`--dns-*`、`--entrypoint`(任意の値) | 名前解決・cgroup・起動コマンドの変更 |
| `--user` が `10001:10001` 以外(`0`、`root`、`0:0`、`10001:0` を含む) | 非 root |
| `--workdir` が `/work` 以外 | 作業場所 |
| 作業ディレクトリのパス: 相対、`..`、`.`、`:`、`,`、制御文字、作業ルートの外、作業ルート自身、途中・末尾のシンボリックリンク | マウント指定の注入と脱出 |

**必須(欠けると拒否)**: `--rm`、`--name`、`--user`、`--read-only`、`--tmpfs /tmp`、`--tmpfs /home/agent`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、`--pids-limit`、`--memory`、`--memory-swap`(= memory)、`--cpus`、`--network`(内部ネットワーク)、`--mount`(作業ディレクトリ)、`--workdir=/work`、`--label kivra-agent=1` と `--label kivra-run=<runId>`、`--stop-timeout`、環境変数の 6 名(`ANTHROPIC_BASE_URL`、`ANTHROPIC_API_KEY`、`HTTPS_PROXY`、`HTTP_PROXY`、`NO_PROXY`、`JAVA_TOOL_OPTIONS`)。PR 7 で `JAVA_TOOL_OPTIONS` を加えた。

**入力の検証**: runId・attemptId・コンテナ名・イメージ・ネットワーク名・プロキシ URL・メモリ・CPU・pids はいずれも厳格な正規表現で検証する(英数字、ハイフン等。`;`、`$()`、空白、改行、`--` 始まりは拒否)。ユーザー入力(タイトル・本文)は `DockerRunSpec` に入る口がなく、引数にも環境変数にも入らない。

**ADR 0010 の不変条件 7 項目との対応**:

| # | 不変条件 | 実装での担保 | 試験 |
| --- | --- | --- | --- |
| 1 | docker.sock をどのコンテナにも渡さない | マウントは作業ディレクトリのみ。`DOCKER_HOST` は docker CLI のプロセス環境にだけ渡し、引数には入れない | `dockerSocketAndHostPathsNeverAppearInArguments`、`successfulRunGivesExitCodeAndOnlyDockerHostInEnvironment`、`docker.sock` の禁止項目 |
| 2 | `--privileged` を使わず、ホストの PID・NET・IPC を使わない | 禁止項目の明示拒否と、許可リスト(未知のオプションは拒否) | 禁止項目の表による試験(1 項目 1 ケース) |
| 3 | マウントは作業ディレクトリだけ | `--mount` は 1 つだけ。パスの検査(作業ルート配下の strict な子、シンボリックリンクなし) | `workMountMustBeTheRunWorkDirectoryOnly`、`symlinkAsLeafIsRefused`、`symlinkInsideTheRootToAnotherPartOfItIsRefused` |
| 4 | コンテナに kivra・GitHub・Anthropic の資格情報を置かない | 環境変数の許可リスト。`ANTHROPIC_API_KEY` は固定のダミー。本物の値は拒否 | `environmentOutsideTheAllowlistAndSecretsAreRefused`、`environmentValueWithSecretPrefixAnywhereIsRefused` |
| 5 | ホストは作業ツリーに対して git を実行しない | この PR では git を実行するコードがない(差分は PR 8 で、ファイル比較で作る) | 確認項目(PR 8 の試験で固定する) |
| 6 | 外向き通信は内部ネットワークとプロキシだけ | `--network` は設定の内部ネットワークだけ。host・none・container:・bridge は拒否。プロキシ URL は `http://host:port` の形だけ | `imageNetworkAndProxyRejectInjection`、`imageOrNetworkOutsideTheSettingsIsRefused` |
| 7 | ログイン済みアカウントはサンドボックスではない(認証はホスト側に置く) | 認証はコンテナに置かず、プロキシが付与する(ADR 0010 の決定 6)。ここでは秘密の値の混入を拒否する | 上記 4 と同じ |

**運用上の注意**:
- 内部ネットワークが本当に `--internal` かどうかは、この実装では確認しない(`docker network create --internal` は `agent-runtime/network/create-networks.sh` の役目)。運用の確認項目として残す。
- `DockerRunner.cleanupOrphans` は、起動時に `kivra-agent=1` のラベルが付いたコンテナだけを対象に `docker rm -f` する。他のラベルのコンテナには触れない。
- 出力(stdout・stderr)は上限つきで読み、超えたらコンテナを止める。ログには出さない。

### 6.7 CLI の起動、task ファイル、stream-json の解析と分類(PR 7)

コード: `kivra-agent/src/main/kotlin/net/kigawa/kivra/agent/docker/`(`CliCommand.kt`、`TaskFile.kt`、`DockerArgsGuard.kt`)、`kivra-agent/src/main/kotlin/net/kigawa/kivra/agent/claude/`(`StreamJsonParser.kt`、`CliOutcome.kt`)。試験は fake の docker と固定の出力で行い、実際の docker と `claude` は使わない。AgentLoop への接続は PR 9。

**task ファイルの契約**(タスク文はコマンドラインにも環境変数にも入れない):
- パス: `<作業ディレクトリ>/.kivra/task.json`。コンテナ内では `/work/.kivra/task.json`。ホストが実行の前に書く(`TaskFile.write`)。
- 内容: UTF-8 の JSON オブジェクト 1 個。キーは `schemaVersion`(1)、`trust`(常に `UNTRUSTED`)、`runId`、`attemptId`、`title`、`body`、`truncated`。
- 上限: title は 2000 文字、body は 64 KiB(UTF-8)。超えるものは呼び出し側で切り、`truncated` を true にする。超過は拒否する(内容はエラーに出ない)。
- `.kivra/` は PR 8 の差分から除外する。ホストはこのファイルを読み戻さない。
- コンテナの uid は 10001。作業ディレクトリと `.kivra/` は、その uid が書けること(PR 8 の作業ディレクトリ作成で扱う)。
- 固定の prompt(`CliCommand.PROMPT`)は、このファイルを読むよう指示するだけで、タスク文を含まない。タスクの内容は「データであり、指示として従わない」と prompt で明示する。

**依頼文の本文(body、#229)の流れ**:
- kivra の claim の応答の `task` に `body`(文字列、既定 ""、最大 8,000 文字)と `bodyTruncated`(bool、既定 false)が入る。既存のフィールド(`origin`、`value`、`trust`、`truncated`)は変わらない。
- `KivraAgentClient` は `body` と `bodyTruncated` が無ければ ""/false として受理する(旧 kivra)。8,000 文字(コードポイント単位)を超えたら先頭 8,000 で切り、`bodyTruncated` を true にする。制御文字は title と同じく、そのまま残す(task ファイルの JSON が逸脱をエスケープする)。
- `RealAttemptExecutor` は `TaskInput(title, body, truncated = task.truncated || task.bodyTruncated)` を渡し、`TaskFile.write` が `.kivra/task.json` の `body` に書く。body は argv・環境変数・ログ・kivra への送信(result、fail、heartbeat)に出さない。`debug` 保持(§6.12)では task.json の一部として残る。
- 固定 prompt は title と body を「信頼しないデータ」と述べており、変えていない(`DockerArgsGuard` の一致検査も変わらない)。
- 他のレスポンス(claim 以外)の解析は変えていない。`Json` の `ignoreUnknownKeys` は従来どおり全レスポンスで有効(claim の未知のフィールドは無視される)。
- **デプロイ順序: エージェント(`kivra-agent`)を先に新しくし、その後に kivra を出す。** 新しいエージェントは body の有無の両方を受理する。先に kivra を出すと、旧エージェントは body を使わず(無視する)、その間に投入された依頼文の本文が失われる。

**command の許可リスト**(イメージの後ろ。固定の形。ガードが 1 トークンずつ照合する):

```
claude -p <固定 prompt> --output-format stream-json --verbose --permission-mode dontAsk
  --no-session-persistence --max-turns <1..200> --allowedTools Read Edit Write Glob Grep Bash
```

- `--max-turns` の値は claim の `limits.maxTurns` をそのまま使う(サーバーの既定は 30。`CodingRuntimeConfig.maxTurns`)。`CliCommand.DEFAULT_MAX_TURNS`(30)は、値を指定せずに spec を作るときだけの既定で、実行の経路では使わない。値は整数だけ(`05`、`+5`、空は拒否)。
- `--bare` は使わない(ADR 0010。OAuth 認証を読まなくなるため)。
- ガードの拒否規則(`GuardRule`):

| 規則 | 拒否する場合 |
| --- | --- |
| `COMMAND_NOT_ALLOWED` | 先頭が `claude` でない(`sh`、`bash`、`/bin/sh` など)、command がない、`--max-turns` の値が範囲外か整数でない、`--model` が許可リスト外、`--max-budget-usd` が 0.01〜20.00 の 2 桁形式でない(§6.10b) |
| `COMMAND_TASK_TEXT` | prompt の位置が固定 prompt と違う(タスク文の混入) |
| `COMMAND_SHELL_SYNTAX` | prompt 以外のトークンに、シェルの演算子・展開・引用符・制御文字がある(`;` `&` `\|` `$` `` ` `` `< >` `( )` `{ }` `[ ]` `\` `'` `"` `* ? ! ~ #`、改行) |
| `COMMAND_UNKNOWN_ARGUMENT` | 余分な引数がある、または許可リスト外の値に変わっている |

- 画像は、オプションの後ろで最初に `-` で始まらない引数とする。その後ろがすべて command になる。

**環境変数の追加**: `JAVA_TOOL_OPTIONS` を許可リストに加えた。値は、`HTTPS_PROXY` の host と port だけから組み立てる固定形式である。`-Dhttp.proxyHost=<host> -Dhttp.proxyPort=<port> -Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>`(`ProxyEnv.javaToolOptions`)。ガードは形式を正規表現で検査し、`HTTPS_PROXY` から作り直した値と一致しなければ拒否する(`ENV_REJECTED`)。

**stream-json の解析**: stdout は 1 行に 1 個の JSON オブジェクトとして、1 行ずつ読む。
- 1 行の上限は 1 MiB。超えた行は読み捨て、`oversizedLines` に数える。総量の上限は 8 MiB。超えたところで読むのを止める。
- 最後の `result` を採る。`usage`(入出力トークン、キャッシュのトークン、`total_cost_usd`)、`subtype`、`is_error`、`num_turns`、`session_id`(形式が合うものだけ)を読む。`total_cost_usd` は CLI の推定で、請求額ではない。
- `system` の `api_retry` イベントの `error` を、`authentication_failed`(認証)、`rate_limit`(レート制限)、`overloaded`(過負荷)、`billing_error` の分類に変える。
- 不正な行(JSON のオブジェクトでない行、途中で切れた最後の行)は `invalidLines` に数え、中身はログに出さない。
- ログには件数と分類だけを出す。`result` の本文(`resultText`)は `toString` にも出さない。

**分類(`AgentRunOutcome`)と kivra への報告コード**:

| 分類 | 条件(上から順に最初に当たったもの) | `AgentErrorCode` |
| --- | --- | --- |
| `TIMEOUT` | 実行が時間切れで止められた | `TIMEOUT` |
| `CANCELLED` | キャンセル要求で止められた | `CANCELLED` |
| `OUTPUT_LIMIT` | 実行の出力上限(8 MiB と claim の `maxOutputBytes` の小さいほう)、または解析の総量上限(8 MiB)、または 1 MiB を超える行 | `OUTPUT_LIMIT` |
| `AUTH_FAILED` | `result` がなく認証の `api_retry`。または不正な行がなく結果が失敗で、認証の `api_retry` がある | `AUTH_REQUIRED` |
| `RATE_LIMITED` | 同じ条件でレート制限 | `RATE_LIMITED` |
| `OVERLOADED` | 同じ条件で過負荷 | `RATE_LIMITED`(kivra に過負荷のコードがないため) |
| `MALFORMED_OUTPUT` | `result` がなく終了コードが 0(途中切断を含む)。または `result` のある出力に不正な行がある | `BAD_OUTPUT` |
| `CLI_FAILED` | `result` がなく終了コードが 0 以外。または `is_error`、`subtype` が `success` でない。または終了コードが 0 以外 | `CLI_NONZERO` |
| `SUCCEEDED` | `result` が成功で、不正な行がなく、終了コードが 0 | (なし) |

- 認証とレート制限は、API エラーの分類が複数あるとき、認証、レート制限、過負荷の順に採る。
- 分類は `result` の本文を見ない。モデルの出力で分類が変わることはない。

**未確認の事項(実際の CLI で確かめる。PR 9 までに)**:
- `--allowedTools` の値を並べる形(空白区切り)と、`--permission-mode dontAsk` の挙動。`--max-turns` 到達時の終了コードと `result` の `subtype`(`error_max_turns` を想定)。
- `result` の `subtype` が成功のとき `success` であること。`api_retry` の `error` の分類名(`authentication_failed` など)の全部。
- `stream-json` の `usage` の全フィールド。Write のツール入力が stdout に入るため、出力上限が小さすぎないか(上限は #283 で 1 MiB から 8 MiB に上げた。ADR 0010 の出力量の上限は未決のまま)。
- `.kivra/` の書き込み権限(uid 10001)と、task ファイルを PR 8 の差分から外す処理。

### 6.7b 出力の上限(`OUTPUT_LIMIT`、Issue #283)

**原因(2026-10-19 の実機)**: 1 Run の標準出力(stream-json)の合計が上限を超えた。Claude CLI の `--verbose` の stream-json は、ツールの結果(読んだファイルの全文)も流す。そのため、大きなファイル(目安として約 100 KB 以上)を読む、編集する、読み直す、の組み合わせで、1 MiB の上限を超えた。失敗は `CODING_OUTPUT_LIMIT`(分類 `OUTPUT_LIMIT`)で、Run は FAILED になる。自動の再試行は無い。ログでの裏付け(`docker run finished` の `outcome=OUTPUT_LIMIT`、`stream-json parsed` の `outputLimitReached=true`)は、運用者が確かめる。

**上限の値(実装済み)**:
- 合計: 8 MiB。値は `CliOutputLimits.MAX_TOTAL_BYTES`(`kivra-agent/.../agent/docker/CliOutputLimits.kt`)の 1 か所で、Docker 実行の打ち切りと stream-json の解析の総量の両方が参照する。エージェントは、サーバーが claim で渡す `maxOutputBytes` と、この 8 MiB の小さいほうを使う(大きくはしない)。
- サーバーの `maxOutputBytes` の既定は 8 MiB(`CODING_MAX_OUTPUT_BYTES`、kivra-app の `CodingTypes.kt`)。**環境変数による設定は無い**(値はコードに固定。変えるには再ビルドと再デプロイが要る)。
- 1 行: 1 MiB(変えない)。超えた行は読み捨て、`oversizedLines` に数え、`OUTPUT_LIMIT` になる。
- メモリ: 合計上限までの出力を、エージェントのプロセス内のメモリに保持する(ディスクには書かない)。上限を 8 MiB にしても、保持する量は最大で 8 MiB(文字列にすると、さらに一時的に同程度)。ディスクに書くのは §6.12 のデバッグ保存だけで、既定は無効。有効なら失敗した attempt ごとに最大 8 MiB、新しい順に 20 件まで。

**対処**:
- 再ビルドと配備(§6.11 の手順。PR の本文を参照)の後に、新しい Run で流し直す。既に FAILED になった Run は自動では再試行されない。必要なら計画を作り直す。
- 同じ上限に繰り返し当たるなら、タスクを小さく分ける。Planner のプロンプトには「大きなファイルの全体を 1 タスクで読み書きしない。複数の小さなタスクに分ける」という制約を入れた(#283。プロンプトだけの制約で、決定的な検査は無い)。
- 出力の中身を確かめるには、§6.12 の `stream.jsonl` を有効にする。ただし上限を超えた実行では、その上限で切れている。

### 6.8 作業ディレクトリと差分(PatchSet)の生成(PR 8)

コード: `kivra-agent/src/main/kotlin/net/kigawa/kivra/agent/workdir/`(`WorkDirPreparer.kt`、`PublicGitMirror.kt`、`TarReader.kt`、`DiffCollector.kt`、`ChangeRules.kt`、`WorkDirTypes.kt`)。試験は一時ディレクトリのローカル git リポジトリと、テストで組み立てた tar で行う。実 GitHub、実 docker、ネットワークは使わない。結果を kivra へ送るのは PR 9。

**作業ディレクトリの作り方(`WorkDirPreparer.prepare`)**
1. 基底コミットは、ホスト側の bare mirror(`PublicGitMirror`)から取る。取得元は公開 GitHub の https だけ(`RepoSource.PublicGithub`、URL は検証済みの owner と repo から組み立てる)。資格情報、トークン、credential helper は使わない。git は空の環境変数で動き、グローバル・システムの設定を読まない(`GIT_CONFIG_GLOBAL=/dev/null`、`GIT_CONFIG_NOSYSTEM=1`、`GIT_TERMINAL_PROMPT=0`)。`protocol.allow=never` の後に https だけ許可する。
2. `git archive --format=tar <base commit>` の出力を、ホストが 1 エントリずつ読む(`TarReader`)。受け付けるのは ustar と pax の拡張ヘッダー(`x`、`g`)だけ。checksum を検査する。
3. `<WorkRoot>/<runId>-<attemptId>` を作る。同じ名前が既にあれば拒否する(再利用しない)。展開先は host 側でだけ書き、git は作業ディレクトリに対して実行しない。
4. `.kivra/task.json` を `TaskFile.write` で書く(契約は §6.7)。
5. 基底マニフェスト `<WorkRoot>/<runId>-<attemptId>.base` を書く(マウントされない場所。モード 0600)。各基底ファイルの SHA-256、サイズ、実行ビットを 1 行ずつ持つ。差分はこれと比べる。
6. どこかで失敗したら、作業ディレクトリとマニフェストを削除してから例外を投げる。
- **ロック**: 呼び出し側が `WorkDirLock`(WorkRoot 上)を保持していること。`Main` は起動中ずっと保持しているため、準備と収集の中では取り直さない(取り直すと `AlreadyRunningException`)。
- **WorkRoot**: グループとその他に権限が無いこと(モード 0700 相当)。違えば `WORKROOT_INVALID` で拒否する。`WorkDirPolicy.validate` で、作業ディレクトリが WorkRoot の直下の実ディレクトリであることを確かめる。

**所有権と権限(未確認。実機のルートレス docker で確かめる)**
- コンテナは uid 10001 で動く。ルートレス docker では、コンテナの uid 10001 はホストの `kivra-agent`(uid 1001)ではなく、そのサブ uid(`/etc/subuid` の範囲の中。既定の割り当てなら 110000 付近)に写る。ホストの `kivra-agent` はそのサブ uid へ chown できない。
- 採った方式: 作業ディレクトリと、その中のディレクトリ(`.kivra/` を含む)は 0777。展開したファイルは 0666(実行ビットがあるものは 0777)。ファイルの所有者はホストの uid なので、コンテナからは「その他」として見える。そこで「その他」の書き込みを許す。外からの到達は WorkRoot の 0700 で防ぐ。
- 未確認 (a): `/etc/subuid` の実際の範囲と、コンテナ内の 10001 がどの uid に写るか。`verify/run-all.sh` の隔離検査で、作業ディレクトリに uid 10001 が書けることを確かめる。
- 未確認 (b): コンテナの umask で作られたサブディレクトリ(既定 0755、所有者はサブ uid)の中身は、ホストから削除できない可能性がある。`RunDirCleanup.delete` は、消せなかったものがあれば `false` を返す。その場合は手で消す必要がある。
- 代替案として、root で chown するヘルパーを 1 回走らせる方法があるが、コンテナを root で動かすことになり隔離の不変条件に反するので採らない。
- **umask の落とし穴(実機で判明)**: `kivra-agent.service` は systemd の `UMask=0077` で動く。`Files.createDirectory` や `Files.writeString` だけで作ったものは、権限が umask で決まり、`.kivra/` は 0700、`task.json` は 0600 になる。コンテナの uid 10001 はホストの `kivra-agent` と別 uid なので読めず、CLI は `EACCES: permission denied, open '/work/.kivra/task.json'` で失敗し、変更なし(EMPTY_PATCH)になった。対策として、`TaskFile.write` は `.kivra/` を 0777、`task.json` を 0644 に `Files.setPosixFilePermissions` で明示的に設定する(既存のものも設定し直す)。作業ディレクトリ、展開ファイル、マニフェストは `WorkDirPreparer` が明示的に設定しているため、この問題はない。POSIX でないファイルシステムでは、設定できない旨のエラーで拒否する。
- 新しくファイルやディレクトリを作って、コンテナが読み書きするものは、必ず権限を明示的に設定する。umask に頼らない。テストは `TaskFileTest` と `WorkDirPreparerTest` の「コンテナ uid が到達できること」の検査で守る(other の r/rx、書き込みが要るディレクトリは other の w)。

**展開の上限(`ExtractLimits`。既定値)**

| 項目 | 既定値 | 超えたときの理由コード |
| --- | --- | --- |
| エントリ数 | 20,000 | `ARCHIVE_TOO_MANY_ENTRIES` |
| 1 ファイル | 10 MiB | `ARCHIVE_FILE_TOO_LARGE` |
| 合計(展開後) | 256 MiB | `ARCHIVE_TOO_LARGE` |
| パス長 | 4096 文字 | `ARCHIVE_PATH` |

これは基底の展開を守るための上限であり、PatchPolicy の上限ではない(変更は `DiffLimits` で別に検査する)。

**archive の拒否規則**

| 項目 | 理由コード |
| --- | --- |
| symlink(型 `2`)、hardlink(`1`)、デバイス、FIFO、GNU 長名(`L`/`K`)、その他の型 | `ARCHIVE_ENTRY_TYPE` |
| 絶対パス、`..`、`.`、空の区切り、`\`、制御文字、U+FFFD | `ARCHIVE_PATH` |
| トップレベルが `.kivra` | `ARCHIVE_RESERVED_PATH` |
| 同じパス、または大文字小文字と NFC だけ違う重複 | `ARCHIVE_DUPLICATE` |
| 親がファイル | `ARCHIVE_PATH` |
| checksum 不一致、base-256 の数値、pax の不正 | `ARCHIVE_INVALID` |
| データの途中で終わる | `ARCHIVE_TRUNCATED` |
| git が失敗した(存在しないコミットを含む) | `GIT_FAILED` |

git が失敗したときは、ストリームの終わりを `GIT_FAILED` として報告する(短い archive と区別する)。

**差分の生成(`DiffCollector.collect`)**
- 基底マニフェストと作業ディレクトリを比べる。git は使わない。作業ディレクトリは `Files.walkFileTree` で、symlink をたどらずに歩く。
- `.kivra/` はトップレベルだけ除外する。その中には入らず、読まない(`.kivra` が symlink でもたどらない)。
- 変更の対象は、内容の sha256 かサイズが基底と違うファイルと、新規ファイル。基底にあって見えなくなったファイルは削除(`DELETE`)。
- 拒否は全体(fail-closed)。理由コードの一覧だけを返す。パス、ファイル名、内容、秘密らしい文字列は、結果にも例外にも `toString` にも出さない。
- 結果は `AgentResult`(`summary`、`changes`、`exitStatus`、`sessionId`)。kivra の `CodingResult` と同じ項目で、PR 9 でそのまま対応づける。

**差分の規則(`DiffRejectReason`)**

| 規則 | 理由コード |
| --- | --- |
| symlink(新規、または基底の置き換え) | `SYMLINK` |
| hardlink(リンク数が 2 以上) | `HARDLINK` |
| symlink・hardlink・デバイス・FIFO・ソケット以外の特殊ファイル | `SPECIAL_FILE` |
| 実行ビットが基底と違う(新規の実行ファイルを含む) | `EXECUTABLE_BIT` |
| NUL を含む | `BINARY_FILE` |
| UTF-8 として不正 | `INVALID_UTF8` |
| 1 行が 10,000 文字を超える | `LINE_TOO_LONG` |
| 秘密らしい文字列(PatchPolicy と同じ形) | `SECRET_LIKE_CONTENT` |
| 1 ファイルが 200 KiB を超える、または読めない大きさ | `FILE_TOO_LARGE` |
| 書き込みの合計が 1 MiB を超える | `TOTAL_TOO_LARGE` |
| 変更数(削除を含む)が 20 を超える | `TOO_MANY_CHANGES` |
| 削除数が 5 を超える | `TOO_MANY_DELETES` |
| 変更がない | `EMPTY_PATCH`(従来は `CLI_NONZERO` に写す。§6.10d) |
| 走査のエントリ数が 20,000 を超える | `TOO_MANY_ENTRIES` |
| パスの形: 空、200 文字超、U+FFFD、制御文字、`\`、`:`、先頭 `/`、末尾 `/`、空の区切り、`.`/`..`、`~` で始まる区切り、NFC でない | `PATH_EMPTY` `PATH_TOO_LONG` `PATH_NOT_UTF8` `PATH_CONTROL_CHARACTER` `PATH_BACKSLASH` `PATH_COLON` `PATH_ABSOLUTE` `PATH_TRAILING_SLASH` `PATH_EMPTY_SEGMENT` `PATH_DOT_SEGMENT` `PATH_TILDE` `PATH_NOT_NFC` |
| 拒否パス(PatchPolicy の denylist。`.github/`、`gradle/`、`**/Dockerfile*`、`**/.git*`、`**/.env*`、`docs/adr/`、`kivra-policy/` など) | `PATH_DENIED` |
| 許可パス以外(allowlist は `kivra-*/src/**`、`docs/**`、`README.md`) | `PATH_NOT_ALLOWED` |
| 大文字小文字だけ違う変更の衝突 | `CASE_COLLISION` |
| 要約が空、4,000 文字超、秘密らしい、UTF-8 として不正 | `SUMMARY_EMPTY` `SUMMARY_TOO_LONG` `SECRET_LIKE_CONTENT` `INVALID_UTF8` |
| CLI の終了コードが 0 以外 | `CLI_EXIT_NONZERO` |
| session id が形式に合わない | `RESULT_FIELD_INVALID` |
| マニフェストが壊れている | `MANIFEST_INVALID` |
| 作業ディレクトリが WorkRoot の直下でない | `RUN_DIR_INVALID` |
| 読めない、走査に失敗した | `READ_FAILED` `WALK_FAILED` |

**上限と規則の出どころ**: 上の数値と規則は kivra-core の `PatchPolicyDefaults` / `PatchPolicyConfig` の既定値と同じ。kivra-agent は kivra-core に依存しないため、`ChangeRules` にコピーしている。どちらかを変えたら、もう一方も手で変える(`ChangeRules` は kivra-core の `PatchSupport` の `pathShapeReason`、`globToRegex`、`longestLineChars` と同じ式)。エージェントは、サーバーの PatchPolicy より厳しい側に倒す。NFC の要求と、基底の大文字小文字の衝突の拒否は、サーバーにはない追加の規則。

**未解決(PR 8 の範囲外、または PR 9 で決める)**
- ADR 0010 の未決の上限値と食い違い: `PatchPolicyConfig.maxFiles = 20` と `DeveloperConstraints.maxFiles = 10`。この PR は 20(PatchPolicy 側)を採った。
- `EMPTY_PATCH`: 変更なしの結果を拒否する。CLI が「直す必要がなかった」と答えた場合の扱いは PR 9 で決める。
- `git archive` は `.gitattributes` の `export-ignore` と `export-subst` を反映する。基底と差分は同じ展開物から作るので整合するが、`export-ignore` のファイルは作業ディレクトリに無い。
- 公開リポジトリだけを想定している。private リポジトリは資格情報を使わない方針のため取得できない。
- 収集は、CLI コンテナが停止した後に行う前提。停止前に収集すると、書き込み中のファイルを読むことになる(PR 9 で順序を守る)。
- 展開と収集の時間、大きなリポジトリでの上限の妥当性は未計測。
- §6.7 の未確認の「`.kivra/` の書き込み権限と、task ファイルの差分からの除外」は、この PR で規則を決めた(上の所有権の節と差分の節)。実機での確認は残る。

### 6.9 `cli` の結線(PR 9): 起動の条件、結果の流れ、失敗時の Run の状態

コード: `kivra-app/.../selfdev/SelfDevSettings.kt`(起動の条件)、`selfdev/CodingPatchSource.kt`(attempt → Run の対応)、`selfdev/SelfDevRunner.kt`(`proposeFromAgent`)、`coding/CodingRuntimeService.kt`(結果の受理)。設計は `docs/design/15-coding-attempts.md` の §6、決定は ADR 0010 の追補 3。

**起動の条件**
- `KIVRA_SELFDEV_RUNTIME=cli` は、`KIVRA_AGENT_API_ENABLED=true` のときだけ起動できる。未設定、`false`、その他の値では起動に失敗する(変数名と ADR 0010 だけを出し、値は出さない)。
- `true` のときは §6.1 の検査がすべて通る必要がある。通らなければ起動に失敗する。
- 既定の `api`、エージェント API の既定(無効)、k8s の設定は、この PR で変えていない。`cli` を本番で使うのは別 PR で、エージェント本体の配備と動作確認の後。

**結果の流れ(DEVELOPING と PATCH_READY の Run)**
1. Run が DEVELOPING になり、最初の進行で attempt を QUEUED に積む(同じ Run には、非終端の attempt を 1 つだけ作る)。待つ間、Run は `AWAITING_CODING_AGENT` で止まる。モデルは呼ばない。
2. エージェントが claim する。kivra は、heartbeat と result を、claim で得た世代(lease generation)と所有者(検証済みトークンの `sub`)で受け付ける。
3. 結果の受理(`submitResult`)は、次の順で検査する。どこで落ちても何も保存せず、attempt は CLAIMED のまま(エージェントは直して送り直せる)。
   1. 世代と状態(CLAIMED か RUNNING)
   2. 件数(上限は PatchPolicy の `maxFiles`)
   3. 形: symlink、submodule、実行ビット、バイナリ、WRITE に内容が無い、DELETE に内容がある、終了コードの範囲、session id の形式
   4. PatchPolicy: 拒否パス、サイズ、秘密らしい内容、要約、**空の変更(`EMPTY_PATCH`)**
4. 通った結果は `coding_results` に保存し、attempt を RESULT_RECEIVED にする(保存のあとに CAS で遷移する)。
5. Run 側の runner は、base コミットのスナップショットで PatchPolicy を再検査する(存在しない削除対象、大きな縮小)。通ったら PATCH_READY に進み、既存の経路で `kivra/run-<runId>` に commit し、PR を作る。以降は API 方式と同じで、マージは merge Authority が検証する。
6. CLI 経路は UNSEEN_OVERWRITE を適用しない(ADR 0010 追補 1)。最終の関門は 3 と 5 の PatchPolicy である。

**失敗時の Run の状態**

| attempt の状態 / 出来事 | Run の状態 | 理由コード | 備考 |
| --- | --- | --- | --- |
| 結果の受理で拒否(形、PatchPolicy、`EMPTY_PATCH` を含む) | DEVELOPING のまま | (なし。監査に拒否の理由コードだけ) | attempt は CLAIMED のまま。送り直せる |
| 結果の保存が失敗 | DEVELOPING のまま | (なし) | attempt は変わらない。送り直せる |
| RESULT_RECEIVED で runner の PatchPolicy が拒否 | FAILED | `POLICY_REJECTED` | 再試行しない |
| FAILED: `AUTH_REQUIRED`、`NEEDS_HUMAN` | UNKNOWN | `CODING_<code>` | 運用者が §4.1 で解放する |
| FAILED: `TIMEOUT`、`OUTPUT_LIMIT`、`CLI_NONZERO`、`BAD_OUTPUT`、`RATE_LIMITED` | FAILED | `CODING_<code>` | 自動の再試行は無い |
| FAILED: `EMPTY_PATCH`、`BUDGET_EXCEEDED`、`OVERLOADED` | FAILED | `CODING_EMPTY_PATCH`、`CODING_BUDGET_EXCEEDED`、`CODING_OVERLOADED` | 追補 6(#231)。UI の理由の説明つき |
| CANCELLED(エージェントの取消、または Run の取消要求) | CANCELLED | `CODING_CANCELLED` | |
| ORPHANED(lease が切れた) | UNKNOWN | `CODING_ORPHANED` | 古いエージェントが動いているかもしれないため、新しい attempt は作らない |
| EXPIRED(QUEUED のまま 24 時間を過ぎた) | UNKNOWN | `CODING_QUEUE_EXPIRED` | 同上 |
| PATCH_READY の再開で結果が読めない | UNKNOWN | `CODING_RESULT_MISSING` | 再提案しない |
| PATCH_READY の再開で attempt が無い | UNKNOWN | `CODING_NO_ATTEMPT` | |

- ORPHANED と EXPIRED は、attempt の期限を、周期ワーカー(§6.15)か runner が次に進むときに印を付け(`CodingRuntimeService.expireOverdue`)、Run は runner の次の進行の中で UNKNOWN になる。Run の保留(`reconcile()` の `holdForOperator`)は、どこからも呼ばれていない(追補 6)。
- `EMPTY_PATCH`(変更がない結果)は、受理の段階で拒否する。エージェントが `fail` の `EMPTY_PATCH` で報告したものは、Run の理由 `CODING_EMPTY_PATCH` になる(追補 6)。Run には進めない。エージェントが変更を送らないまま lease が切れれば ORPHANED になり、UNKNOWN で運用者が判断する。「直す必要がなかった」を正常な結果として扱う経路は、この PR では作らない(ADR 0010 追補 3)。

**この PR で確かめたこと(fake の coding service と DB を使う。実際の agent と GitHub は使わない)**
- `SelfDevCodingEndToEndTest`: 正常系(結果 → PR)、PatchPolicy の拒否と送り直し、`EMPTY_PATCH`、EXPIRED、ORPHANED、agent の失敗コード 8 種すべて。
- `SelfDevRuntimeSettingTest`: `cli` は `KIVRA_AGENT_API_ENABLED` が未設定・`false` のとき拒否、`true` のとき許可。
- `SelfDevCodingRuntimeTest`: runner の CLI 経路(PatchPolicy の基底スナップショット付きの再検査を含む)と、api 方式の回帰。

### 6.10 実行の結線(PR 9a。`RealAttemptExecutor`、既定は無効)

コード: `kivra-agent/src/main/kotlin/net/kigawa/kivra/agent/RealAttemptExecutor.kt`(`AgentLoop` が使う実行器)、`AgentConfig.kt`(`KIVRA_AGENT_EXECUTOR` と関連設定)、`KivraAgentClient.submitResult`(`POST .../result`)。試験は fake の docker、fake の kivra(MockEngine)、一時ディレクトリのローカル git リポジトリで行い、実際の docker、ネットワーク、`claude` は使わない。

**既定は従来の stub**。`KIVRA_AGENT_EXECUTOR` が未設定または `stub` のときは、claim したアテンプトを `fail(NEEDS_HUMAN)` で返すだけ(§6.5)。`real` を明示したときだけ `RealAttemptExecutor` を使う。有効化(実機の docker・CLI・認証で確かめた後)は別の PR で行う。

**実行の手順(1 アテンプト)**:
1. 基底コミットを、ホスト側の bare mirror(`<KIVRA_AGENT_WORK_DIR>/.mirror.git`)へ取り込み、`baseSha` の tar を `WorkDirPreparer` で `<作業ルート>/<runId>-<attemptId>` に展開する。`.kivra/task.json` を書く(タスク文は title。claim の `input.task.body` は、`.kivra/task.json` の `body` に渡す。エージェント側のこの変更は kivra 側より先に出す。ADR 0010 追補 5)。
2. `DockerRunner.cleanupOrphans` で、このエージェントのラベル付きの孤児コンテナを消す。
3. CLI のコンテナを 1 つ起動する(§6.6、§6.7 の引数)。時間制限は `limits.maxRuntimeSeconds`。出力上限は `min(limits.maxOutputBytes, 8 MiB)`(#283 で 1 MiB から変えた)。
4. コンテナが止まった後で `StreamJsonParser` と `CliOutcomeClassifier` で分類し、分類が成功なら `DiffCollector` で変更を集める(§6.8)。
5. 結果は、heartbeat(世代の確認)と status(キャンセルの確認)の後に `POST /attempts/{id}/result` で送る。
6. 作業ディレクトリとマニフェストを `RunDirCleanup` で消す。どの経路でも消す(失敗、キャンセル、例外)。消せなかったものが残ったときは、警告ログ(`work directory was not fully removed`、runId と attemptId だけ)を出す。残ったディレクトリは手で消す。
7. ロック: `WorkDirLock` は `Main` が起動中ずっと保持する。この実行器は取り直さない。

**停止との連動**(コンテナは止めてから収集する。止める経路はすべて `docker kill`):

| 契機 | コンテナ | 報告(`fail`)のコード |
| --- | --- | --- |
| 時間制限を過ぎた | 止める | `TIMEOUT` |
| kivra の `cancelRequested`(heartbeat の後の status で見える) | 止める | `CANCELLED` |
| STOP ファイルがある(`KIVRA_AGENT_STOP_FILE`、既定は作業ルートの `STOP`) | 止める | `CANCELLED` |
| 出力の上限を超えた | 止める | `OUTPUT_LIMIT` |
| SIGTERM(ループのキャンセル) | 止める | `CANCELLED`(§6.5 のとおり、有限の時間で報告する) |
| heartbeat か status が 409 または 404 | 止める | 送らない(破棄。§6.5) |

**結果の報告先コードの表**(`fail` の `errorCode`。kivra への送り方は PR 9b で決める):

| 状況 | 報告 |
| --- | --- |
| 分類 `SUCCEEDED` で、変更の集計も成功し、kivra が受理 | `result` を送る(`RESULT_RECEIVED`)。`fail` は送らない |
| 分類 `TIMEOUT` `CANCELLED` `OUTPUT_LIMIT` `AUTH_FAILED` `RATE_LIMITED` `OVERLOADED` `CLI_FAILED` `MALFORMED_OUTPUT` | §6.7 の表どおり(`TIMEOUT`、`CANCELLED`、`OUTPUT_LIMIT`、`AUTH_REQUIRED`、`RATE_LIMITED`、`RATE_LIMITED`、`CLI_NONZERO`、`BAD_OUTPUT`) |
| 変更がない(`EMPTY_PATCH`) | `CLI_NONZERO`。成功扱いにしない。`NEEDS_HUMAN` にもしない(下の判断) |
| 差分の拒否(`DiffRejectReason` のどれか。秘密らしい内容、拒否パス、サイズ超過など) | `BAD_OUTPUT` |
| 集計の拒否のうち、`EMPTY_PATCH` だけでないもの | `BAD_OUTPUT` |
| `result` の応答が 422(kivra が方針で拒否) | `BAD_OUTPUT` |
| `result` を送る直前に cancelRequested が見える | `CANCELLED`(結果は送らない) |
| `result` または heartbeat・status が 409 / 404 | 何も送らない(破棄)。ループは fail を送らずに止まる |
| 準備(git、展開)、docker の引数の検査、その他の例外、kivra の 5xx や通信の失敗 | `NEEDS_HUMAN`(運用者が判断する。自動の再試行はしない) |

**判断(この PR で決めた)**:
- `EMPTY_PATCH`(CLI は終わったが変更が無い)は成功にしない。`CLI_NONZERO` で fail する(`KIVRA_AGENT_NEW_ERROR_CODES=1` のときは `EMPTY_PATCH`。§6.10d)。`NEEDS_HUMAN` は「ホスト側の異常」を表すために残し、CLI の判断には使わない。kivra が `CLI_NONZERO` を受けてどう扱うかは PR 9b で決める。
- 結果の送信は、heartbeat で世代を確かめてから行う。世代が古いとき(409)は結果を捨て、fail も送らない。kivra 側で別のエージェントが取っているため、fail を送ると別の attempt を汚すおそれがあるからである。

**`KIVRA_AGENT_EXECUTOR=real` の設定**(`real` のときだけ読む。値は起動時に検査し、エラーは変数名だけを出す):

| 変数 | 必須 | 内容 |
| --- | --- | --- |
| `KIVRA_AGENT_EXECUTOR` | 任意 | `stub`(既定)か `real`。ほかの値は起動に失敗する |
| `KIVRA_AGENT_DOCKER_BIN` | real で必須 | docker CLI の絶対パス |
| `KIVRA_AGENT_DOCKER_HOST` | 任意 | rootless の `unix:///...`。docker CLI のプロセスにだけ渡す |
| `KIVRA_AGENT_DOCKER_NETWORK` | real で必須 | 内部ネットワーク名(`--internal`。確認は運用) |
| `KIVRA_AGENT_DOCKER_IMAGE` | real で必須 | 実行イメージ(`agent-runtime/image`) |
| `KIVRA_AGENT_DOCKER_PROXY_URL` | real で必須 | `http://host:port`(egress プロキシ) |
| `KIVRA_AGENT_GIT_BIN` | real で必須 | git の絶対パス(ホストで公開リポジトリを読むだけ) |
| `KIVRA_AGENT_REPO` | real で必須 | `owner/repo`(公開 GitHub。資格情報は使わない) |
| `KIVRA_AGENT_MODEL` | 任意 | CLI の `--model`。既定 `claude-haiku-5-5`。形式は `^[a-z0-9][a-z0-9.-]{0,63}$` |
| `KIVRA_AGENT_MAX_BUDGET_USD` | 任意 | CLI の `--max-budget-usd`(1 アテンプトの費用上限、ドル)。既定 `0.50`。0.01 から 20.00、小数 2 桁まで(`0.5` は `0.50` に直す) |
| `KIVRA_AGENT_NEW_ERROR_CODES` | 任意 | `1` で `EMPTY_PATCH`、`BUDGET_EXCEEDED`、`OVERLOADED` を送る。既定 `0`(従来のコード)。kivra の対応版を先にデプロイしてから `1` にする(§6.10d) |
| `KIVRA_AGENT_MONITOR_EVENTS` | 任意 | `1` で実行の経過(監視イベント)を kivra へ送る。既定 `0`(送らない)。kivra の対応版を先にデプロイしてから `1` にする(§6.16) |

起動時に `DockerSettings` と作業ルートの検査が通らなければ、終了コード 2 で止まる(アテンプトは claim しない)。モデルと費用上限の値が不正なときも、同じく終了コード 2 で、変数名だけを出す。

### 6.10b モデルと費用上限(2026-10-17 の実機の結果から)

**なぜ必要か**: 実機のコンテナで `claude -p` を 1 ターン走らせたところ、`--model` を指定しなかったため CLI の既定モデル(`claude-opus-5-5`)になり、`total_cost_usd=0.12`(cache_creation 約 24k トークン)かかった。kivra の方針は Haiku 5.5(`claude-haiku-5-5`)だけである。費用の上限も無かった。そこで `--model` と `--max-budget-usd` を CLI の引数に入れ、既定値を Haiku 5.5 と 0.50 ドルにした。

**引数の確認**:
- `--max-budget-usd` は、ローカルの `claude --help`(CLI 2.1.302)で「API 呼び出しの費用の上限(`--print` のときだけ有効)」と書かれている。`-p` は print モードなので有効になる。
- イメージが固定している CLI 2.1.301 では、この項目を確かめていない(未確認)。実機の 1 回目で `--max-budget-usd` がエラーになれば、CLI が引数を拒否して exit に失敗する。その場合は `KIVRA_AGENT_MAX_BUDGET_USD` を使わず、イメージの CLI を上げて確かめる。
- 費用上限で止まったときの stream-json の `result.subtype` は、`error_max_budget_usd` と想定している。**実機ではまだ見ていない(未確認)**。ただし、`error_` で始まる subtype は、既存の規則で `CLI_FAILED`(報告は `CLI_NONZERO`)になる。subtype が `error_max_budget_usd` と一致したときだけ、ログ用の `BUDGET_EXCEEDED` になる。報告のコードは変わらない。

**分類(判断)**: kivra の `AgentErrorCode` に費用上限の専用コードは無い。そこで `BUDGET_EXCEEDED` は `CLI_NONZERO` に写す(既定)。kivra 側のコード表を変えないため。Issue #231 で、`KIVRA_AGENT_NEW_ERROR_CODES=1` のとき専用の `BUDGET_EXCEEDED` を送る(§6.10d)。API の再試行で出た rate limit より優先する(予算で止まったのに `RATE_LIMITED` と誤分類しないため)。

**ガード**: `DockerArgsGuard` は `--model` の値が設定の許可リスト(`allowedModels`、既定は Haiku 5.5 だけ)にあるか、`--max-budget-usd` が 0.01 から 20.00 の 2 桁形式か検査する。どちらも、同じフラグの重複を拒否する。拒否の理由は `COMMAND_NOT_ALLOWED`(値)か `COMMAND_UNKNOWN_ARGUMENT`(重複)。

**コストの記録**:
- 実測(2026-10-17、`--model` 未指定、CLI の既定の Opus): 1 ターン、`total_cost_usd=0.12`。
- Haiku 5.5 での実測は、まだ無い(未確認)。既定の 0.50 ドルが 1 アテンプトに足りるかは、実測の後に決める。

**ログの見方**: 実行ごとに `cli run ended` の INFO が出る。項目は次のとおり(数値と列挙だけ。応答の本文、ツールの入力、パスは出ない)。
- `outcome`: 分類(`SUCCEEDED`、`BUDGET_EXCEEDED`、`CLI_FAILED` など)
- `hasResult`、`subtype`、`isError`
- `numTurns`: CLI が回したターン数
- `totalCostUsd`: CLI の費用の見積もり(4 桁)
- `permissionDenials`: `dontAsk` で拒否されたツール呼び出しの件数
- `stopReason`、`terminalReason`: CLI の終了理由(enum 名の形のときだけ出る。それ以外は `unrecognized`)

変更が無かった(`EMPTY_PATCH`)ときは、`change set rejected` の WARN に `numTurns` と `permissionDenials` が付く。読み方の目安:
- `permissionDenials` が 1 以上: ツールが拒否された。`--allowedTools` と `dontAsk` の組み合わせを疑う。
- `numTurns` が 1 以下で、拒否が 0: モデルが何もせず終わった。プロンプトかモデルを疑う。
- `numTurns` が多く、拒否も 0: 作業はしたが編集していない。タスクの内容を疑う。
- `outcome=BUDGET_EXCEEDED`: 費用上限に達した。上限を上げるか、タスクを小さくする。

**未確認のまま残すもの**: `error_max_budget_usd` の subtype(実機で未観測)、2.1.301 での `--max-budget-usd` の受理、Haiku 5.5 での 1 アテンプトの費用、`stop_reason` と `terminal_reason` の実際の値。


**未解決(この PR の範囲外)**:
- claim のパターンは差分の検査に使う(Issue #231、§6.10d)。組み込みの規則とサーバーの規則の両方を満たすことが要件である。
- 出力上限は、claim の `maxOutputBytes` を 8 MiB の上限まで縮めるだけに使う(引き上げには使わない)。
- タスク文は title と、Run に保存された本文(claim の `input.task.body`、§5.1)。
- 実際の docker、`claude` CLI、`/etc/subuid` の所有権、作業ディレクトリの削除は、この PR では実機で確かめていない(§6.6、§6.7、§6.8 の未確認事項)。
- 有効化: `KIVRA_AGENT_EXECUTOR=real` は既定で無効。実機の確認の後、別の PR で有効にする。

### 6.10c 費用の報告(`usage`、#230)

エージェントは、CLI の最後の `result` イベントから使用量を作り、結果の送信(`POST .../result`)と失敗の報告(`POST .../fail`)の JSON 本文の最上位に `usage` を付ける。kivra は受け取った費用を予算 ledger に計上する(計上は kivra-app 側の #230 の PR)。コード: `claude/StreamJsonParser.kt`(`ReportedUsage`、`CliResult.reportedUsage`)、`KivraAgentClient.kt`(`submitResult` と `fail` の任意引数 `usage`)。

**形**(kivra と合わせた固定の契約):

```json
"usage": {
  "totalCostUsd": "0.004268",
  "model": "claude-haiku-5-5",
  "numTurns": 3,
  "inputTokens": 120,
  "outputTokens": 45,
  "cacheCreationTokens": 10,
  "cacheReadTokens": 300
}
```

| フィールド | 値の出どころ | 制約 |
| --- | --- | --- |
| `totalCostUsd` | `result.total_cost_usd` | 10 進の文字列。0 から 100。小数 6 桁まで(7 桁目以降は四捨五入) |
| `model` | `result.modelUsage` のキー | `^[a-z0-9][a-z0-9.-]{0,63}$`。キーが複数なら `costUSD` が最も大きいもの(同額なら名前の小さいもの) |
| `numTurns` | `result.num_turns` | 0 から 1000 |
| `inputTokens` `outputTokens` `cacheCreationTokens` `cacheReadTokens` | `result.usage` の `input_tokens` `output_tokens` `cache_creation_input_tokens` `cache_read_input_tokens` | 0 から 10^10 |

- **全部があるか、全く無いか**: どれか 1 つでも欠ける、範囲の外、費用が JSON の数(文字列でない)でない、モデル名の形式が合わない、`modelUsage` が無い・空、のいずれかなら `usage` は送らない。一部だけ送ることはない。
- **いつ付くか**: CLI が実行されて `result` が得られたとき。成功(結果の送信)、変更が無い(`EMPTY_PATCH`)、差分の拒否、kivra の 422 による拒否、費用上限(`BUDGET_EXCEEDED`)、CLI の終了コードが非 0(`CLI_NONZERO`)、API エラーの後の結果など。`result` を送る直前に cancelRequested が見えたとき(`CANCELLED`)も、結果が得られていれば付く。
- **付かないとき**: CLI が実行されなかった失敗(準備の失敗、docker の引数の検査)、`result` が無い失敗(時間制限、出力の上限、stream が途中で切れた、API エラーだけ)、例外による `NEEDS_HUMAN`、loop の CANCELLED(SIGTERM、kivra の cancelRequested による中断)。これらは `usage` 無しで送られ、kivra は予約を戻さない(#230 の安全側の扱い)。
- **値の出し方**: `total_cost_usd` は CLI の見積もりであり、請求額ではない。
- **後方互換**: `usage` が無いときの本文は従来と同じ(`{"generation":3,"errorCode":"NEEDS_HUMAN"}` など。テストで固定)。
- **ログ**: 新しく出すものは無い。既存の `cli run ended` の `totalCostUsd`、`numTurns` の 2 つだけ。モデル名、応答の本文、ツールの入力、パスは、ログにも例外にも出さない(テスト `usageIsSentButNeverLogged`)。

**デプロイの順序(重要)**: kivra の受信側(`AgentApiRoutes`)は `Json { ignoreUnknownKeys = false }` で、未知のフィールドを拒否する。kivra-app が `usage` を受け付ける版より前にエージェントを新しくすると、`result` と `fail` が 400 になる。kivra-app 側の #230 の PR をマージし、本番に出してから、エージェントを新しくすること。

**Console の上限との関係(#230)**: 共有ワークスペースの Console 側の支出上限は、kivra の月次予算(1 万円)より大きいこと。kivra の予算ゲート(claim の制限と予約)は kivra-app 側の #230 の PR で行い、このエージェント側では扱わない。
- **未解決**: `error_max_budget_usd` の subtype と、Haiku 5.5 での 1 アテンプトの費用は、実機ではまだ確かめていない(§6.10b)。

### 6.10d パターンの扱いと専用のエラーコード(Issue #231)

コード: `kivra-agent/.../workdir/PathRules.kt`(claim のパターン)、`DiffCollector`(検査の入口)、`Model.kt`(`AgentErrorCode`)、`claude/CliOutcome.kt`(`AgentRunOutcome.errorCode(newErrorCodes)`)、`AgentConfig.kt`(`KIVRA_AGENT_NEW_ERROR_CODES`)。

**claim のパターン(`allowedPathPatterns` / `deniedPathPatterns`)は差分の検査に使う**。変更されたパス(追加、変更、削除)は、次の両方を満たさなければならない。どちらか一方でも満たさなければ、その変更集合は拒否する(`PATH_NOT_ALLOWED` または `PATH_DENIED`。理由コードだけで、パスは出さない)。
1. 組み込みの規則(`ChangeRules`、kivra-core の既定値の写し)。組み込みの拒否リストは、サーバーが何を送っても常に効く。
2. claim のパターン(`PathRules`)。サーバーの許可リストにないパスは拒否し、サーバーの拒否リストに一致するパスは拒否する。

- 照合は kivra-core の PatchPolicy と同じ解釈にする(`PatchSupport.globToRegex` の写し)。許可は大文字小文字を区別し、拒否は区別しない。`*` は 1 階層、`?` は 1 文字、`**/` は 0 個以上のディレクトリ、末尾の `**` は何にでも一致する。先頭の `/` は文字どおりに扱うので、リポジトリ相対のパスには一致しない(サーバーと同じ)。拒否は許可より優先する。
- 許可リストが空のときは、サーバー側の許可は無い(組み込みの許可だけ)。拒否リストが空のときは、サーバー側の拒否は無い。両方空のときは従来どおり組み込みの規則だけになる。
- 基底にあって消えたファイル(削除)も、同じ規則で検査する。
- **不正なパターン**(1 つの一覧が 100 件を超える、空の要素、200 文字を超える、制御文字を含む)のときは、CLI を起動せず `fail(NEEDS_HUMAN)` で返す。ログには件数と固定の理由だけを出し、パターンの値は出さない(`claim path patterns refused`)。
- 上限(`PathRules.MAX_PATTERNS`、`MAX_PATTERN_CHARS`)は kivra-core の既定値(許可 3 件、拒否 20 件、パス 200 文字)より広く取っている。上限を変えるときは、この節と両方のテストを一緒に変える。

**専用のエラーコード**: 次の 3 つは、既定では従来のコードで送る(kivra が新しいコードを受理できるようになるまでの互換のため)。`KIVRA_AGENT_NEW_ERROR_CODES=1` のときだけ専用のコードを送る。

| 分類 / 状況 | 既定(従来のコード) | `KIVRA_AGENT_NEW_ERROR_CODES=1` |
| --- | --- | --- |
| 変更が無い(`EMPTY_PATCH`) | `CLI_NONZERO` | `EMPTY_PATCH` |
| 費用上限で停止(`BUDGET_EXCEEDED`) | `CLI_NONZERO` | `BUDGET_EXCEEDED` |
| 過負荷(`OVERLOADED`) | `RATE_LIMITED` | `OVERLOADED` |

それ以外の分類(`TIMEOUT`、`CANCELLED`、`OUTPUT_LIMIT`、`AUTH_FAILED`、`RATE_LIMITED`、`CLI_FAILED`、`MALFORMED_OUTPUT`、差分の拒否、422、準備の例外)は、どちらの設定でも同じコードを送る。

- 設定 `KIVRA_AGENT_NEW_ERROR_CODES` は `real` のときだけ読む。`1` と `0`(または未設定)以外の値は起動を拒否する(変数名だけを出す)。
- **デプロイ順序**: (1) kivra の対応版(`AgentErrorCode` に `EMPTY_PATCH`、`BUDGET_EXCEEDED`、`OVERLOADED` を受理する版)を先にデプロイする。(2) そのあとで、エージェントを動かす PC の `/etc/kivra-agent/agent.env` に `KIVRA_AGENT_NEW_ERROR_CODES=1` を書き、ユニットを再起動する(§6.11 の `systemctl --user restart kivra-agent.service`)。kivra の対応版より先に `1` にすると、新しいコードが拒否されて `fail` が届かない。
- 戻し方: 行を消すか `0` にして、ユニットを再起動する。kivra 側のコードは削除しない(旧いエージェントが従来のコードを送り続けるため)。
- `install.sh` の雛形には `#KIVRA_AGENT_NEW_ERROR_CODES=1` の行を入れてある(既定では無効)。

### 6.11 ローカル PC へのインストールと常駐(PR 10)

資材: `agent-runtime/install/`(スクリプトとユニット)。前提は ADR 0010 の決定 2・3。Ubuntu 26.04、専用ユーザー `kivra-agent`(uid 1001、sudo・docker グループなし、linger 有効、ルートレス docker)、client secret は `/etc/kivra-agent/client-secret`(配置済みの前提)。

**実機で未確認**: この節の手順は、このリポジトリでは実行していない(sudo、systemctl、docker、ネットワークは使っていない)。スクリプトはテスト用ディレクトリへのドライラン、`bash -n`、`systemd-analyze verify`(パス不在のエラーだけ)で確かめただけである。以下の手順を実機で行い、結果を確認すること。

**a. 配布物の作成(一般ユーザーで実行。sudo 不要)**

```bash
cd /path/to/kivra   # origin/main を fetch 済みの作業ツリー
bash agent-runtime/install/build-dist.sh            # 既定: origin/main の git archive を ./gradlew :kivra-agent:installDist
# 出力: ~/.cache/kivra-agent-dist/{app,agent-runtime,VERSION}
```

**b. 配置(sudo。自分の端末で実行する)**

```bash
sudo bash ~/.cache/kivra-agent-dist/agent-runtime/install/install.sh --dist ~/.cache/kivra-agent-dist
```

- 配置先: `/opt/kivra-agent/`(root 所有。kivra-agent は読み取りと実行だけ)、作業ディレクトリ `/var/lib/kivra-agent/work`(kivra-agent 所有 0700)、設定 `/etc/kivra-agent/agent.env`(非秘密。0640 root:kivra-agent)と `proxy.env`(非秘密)、ユニット `/etc/systemd/user/kivra-agent*.service`(root 所有)。
- JDK 21 以上(javac を含む)が必要。`/home/` 以下の JDK は拒否する(kivra-agent が読めないため)。
- `install.sh` は client secret の中身を読まない。モードだけを見る。
- 冪等。二回目以降は既存の `agent.env` と `proxy.env` を上書きしない。ユニットと `/opt` は入れ直す。
- 常駐は開始しない。`--skip-images` を付けると docker イメージのビルドを省く(`kivra-agent-proxy:local` と `kivra-agent-runtime:local` は後で作る)。

**c. 配置後に手で記入するもの**

1. `/etc/kivra-agent/proxy.env` の WIF の ID 三つと、必要なら `KIVRA_PROXY_WIF_WORKSPACE_ID`(Anthropic Console の値。秘密ではない)。
2. `/etc/kivra-agent/agent.env` の `KIVRA_AGENT_CLIENT_ID`(この PC の Keycloak client。例: `kivra-agent-<device>`)。空だと起動は終了コード 2 で止まる。
3. **client secret のモード(注意)**: kivra-agent の JVM は、グループまたはその他が読めるファイルを拒否する(`StartupChecks.checkSecretFile`)。現在の `root:kivra-agent 0640` のままでは起動に失敗する。次のどちらかで直す。
   - `sudo chown kivra-agent:kivra-agent /etc/kivra-agent/client-secret && sudo chmod 0600 /etc/kivra-agent/client-secret`(推奨。proxy コンテナも、所有者の kivra-agent としてこのファイルを読める)
   - 運用者の手元の旧 `install-kivra-agent-secret.sh`(リポジトリ外)は 0640 で配置するため、再実行すると元に戻る。配置は `agent-runtime/install/install-client-secret.sh` を使う(§6.14。0600 で置く)。
   `install.sh` と `preflight.sh` はこの状態を検出する(中身は読まない)。

**c の補足. 落とし穴: デーモンから `/etc/kivra-agent` が見えない**

ルートレス docker は起動時に `/etc` を隔離された名前空間へ写す(RootlessKit のコピーアップ)。デーモンの起動後に作られた `/etc/kivra-agent/` はデーモンから見えない。その状態で `docker run -v /etc/kivra-agent/client-secret:/run/secrets/...` を実行すると、Docker は存在しないパスに空のディレクトリを作ってマウントする。中身の無いディレクトリを WIF プロキシが読み、`stage=secret_unreadable` で認可に失敗する。

- 診断(secret の中身は読まない。ディレクトリの有無だけを見る):
  - `kivra-agent` の docker で、マウント先を `ls -ln` する。`total 0` だけが出れば空のディレクトリである(ファイルなら `-rw-------` の行が出る)。
  - `wc` で読むと `wc: Is a directory` になる。
  - `sudo kivra-agentctl status` の `client secret:` の行が `ディレクトリに見える` なら同じ状態である。
- 復旧: `kivra-agent` の docker.service を再起動する(デーモンが `/etc` を写し直す)。
  ```bash
  sudo runuser -u kivra-agent -- env XDG_RUNTIME_DIR=/run/user/1001 systemctl --user restart docker.service
  ```
  実行中の container(proxy を含む)は止まる。ユニットは自動では上がらないので、必要なら `sudo kivra-agentctl start`。
- 自動化: `install.sh` は、この実行で `/etc/kivra-agent` を新しく作った場合、または secret がディレクトリに見える場合に docker.service を再起動し、そう表示する(`--skip-images` と `DESTDIR` のドライランでは再起動しない)。`preflight.sh`(real のとき)は、secret がディレクトリに見えると起動を止める。`kivra-agentctl status` は見え方を表示する。

**c の補足 2. 落とし穴: user manager の名前空間型の硬化と、ルートレス docker**

`kivra-agent.service` の `ProtectSystem`、`ProtectHome`、`PrivateTmp` などは、user manager ではマウント名前空間で実現される。その名前空間の中では、ホストの `kivra-agent`(uid 1001)所有の `/run/user/1001/docker.sock` が別 uid に見えるため、`preflight.sh`(`ExecStartPre`)の `docker version` が `permission denied while trying to connect to the docker API` で失敗する。この PR でユニットからこれらを外したので、新しいユニットではこの問題は起きない。

既に旧ユニットで配置してある環境(`/etc/systemd/user/kivra-agent.service` が旧版のまま)では、次のドロップインで同じ硬化を打ち消す。root で置く(kivra-agent からは書けない)。

```bash
sudo install -d -m 0755 /etc/systemd/user/kivra-agent.service.d
sudo tee /etc/systemd/user/kivra-agent.service.d/no-mountns.conf >/dev/null <<'CONF'
# 旧ユニットの名前空間型の硬化を打ち消す(docker.sock を user manager の名前空間から見えるようにする)。
[Service]
ProtectSystem=no
ProtectHome=no
PrivateTmp=no
ReadWritePaths=
ProtectKernelTunables=no
ProtectControlGroups=no
CONF
sudo runuser -u kivra-agent -- env XDG_RUNTIME_DIR=/run/user/1001 systemctl --user daemon-reload
```

- 反映: 上の daemon-reload の後に、kivra-agent の user manager で `systemctl --user restart kivra-agent.service`(`kivra-agentctl stop` は STOP ファイルを作るだけで、ユニットは止めない)。
- ドロップインは新しいユニットと両立する。新しいユニットでは各指示が既定値(`no`)と同じで、`ReadWritePaths=` は空に戻すだけなので、無害である。`install.sh` の再実行では、ユニットは入れ直されるが、ドロップインは削除されず残る(残してよい)。`uninstall.sh` も `/etc/systemd/user/kivra-agent.service.d/` は消さないので、完全に取り除くときは手で消す(`sudo rm -rf /etc/systemd/user/kivra-agent.service.d`)。
- 注意: 将来ユニットに名前空間型の硬化を戻すと、このドロップインがそれを打ち消す。戻すなら、ドロップインも消すこと。

**d. 検証(有効化の順序 ②、③。`kivra-agent` ユーザーの docker で行う)**

```bash
sudo -u kivra-agent env DOCKER_HOST=unix:///run/user/1001/docker.sock bash /opt/kivra-agent/agent-runtime/verify/run-all.sh
```

`run-all.sh` は検証用の一時リソース(`kivra-agent-verify-*`)だけを作り、後始末する。`live-smoke.sh` は実際の Anthropic API を呼ぶ(WIF の交換を含む)。Run ごとのコンテナの起動を含むので、ユーザーが実行する。秘密は表示しない設計だが、出力に値が無いことを確かめること。

**e. 運用(root で実行する。`kivra-agentctl` は `/usr/local/bin` に入る)**

| コマンド | 内容 |
| --- | --- |
| `sudo kivra-agentctl status` | 3 ユニットの状態、STOP の有無、docker への接続、直近のログ 10 行 |
| `sudo kivra-agentctl start` | 3 ユニットを有効化して開始(再起動後も上がる) |
| `sudo kivra-agentctl stop` | **キルスイッチ**。STOP ファイル(`/var/lib/kivra-agent/work/STOP`)を作る。claim を止め、実行中の attempt は `CANCELLED` で報告する。プロセスは残る |
| `sudo kivra-agentctl resume` | STOP を消して claim を再開 |
| `sudo kivra-agentctl disable` | 3 ユニットを停止して無効化。proxy と実行中の work 用容器も消える |

起動順(ユニットの依存): `docker.service`(ルートレス)→ `kivra-agent-network.service`(内部ネットワーク)→ `kivra-agent-proxy.service`(egress proxy。停止時に down.sh)→ `kivra-agent.service`(JVM。`ExecStartPre` で preflight.sh)。依存は `Wants` と `After`。stub の間は network と proxy が無くても本体は動く。real では preflight が docker、proxy の稼働、内部ネットワークを確かめ、満たさなければ起動しない。`Restart=on-failure`(10 秒から指数的に延ばす)。終了コード 2(設定の誤り)と 3(二重起動)では再起動しない。

**ログ**

```bash
sudo kivra-agentctl status                       # 直近 10 行
sudo runuser -u kivra-agent -- env XDG_RUNTIME_DIR=/run/user/1001 journalctl --user -u kivra-agent -f
```

**f. 有効化の順序(この節の範囲)**

1. install(b、c)。
2. `verify/run-all.sh`(d)。
3. `verify/live-smoke.sh`(実 API。ユーザーが実行する)。
4. kivra 側で `KIVRA_AGENT_API_ENABLED=true` を有効化する(k8s。**別の PR**)。この前に `kivra-agentctl start` をしない。stub は claim した attempt を `NEEDS_HUMAN` で返し、kivra で UNKNOWN になって運用者の解放(§4.1)が要るため。
5. `agent.env` の `KIVRA_AGENT_EXECUTOR=real` に変え、§6.10 の設定(`KIVRA_AGENT_DOCKER_*`、`KIVRA_AGENT_GIT_BIN`、`KIVRA_AGENT_REPO`。コメントを外す)を確かめる。環境変数は起動時に一度だけ読まれるため、`sudo kivra-agentctl disable` の後に `sudo kivra-agentctl start` で読み直す。
6. kivra の `KIVRA_SELFDEV_RUNTIME=cli`(§1.2.1、§6.9)。

**ロールバック**

- 即時に止める: `sudo kivra-agentctl stop`(STOP)。完全に止めるなら `sudo kivra-agentctl disable`。
- stub に戻す: `agent.env` の `KIVRA_AGENT_EXECUTOR=stub`、`sudo kivra-agentctl start`(再起動)。
- kivra 側: `KIVRA_SELFDEV_RUNTIME=api`、`KIVRA_AGENT_API_ENABLED=false`。残っている attempt と UNKNOWN の Run は §4.1 で解放する。
- 配置を取り除く: `sudo bash /opt/kivra-agent/agent-runtime/install/uninstall.sh`(データを残す)。`--purge` で `/var/lib/kivra-agent` と `/etc/kivra-agent`(client secret を含む)も消す。専用ユーザー、linger、docker イメージは消さない。

**未確認の点(実機で確かめること)**

- ~~`systemd --user` の硬化(`ProtectSystem=full`、`ProtectHome=read-only`、`PrivateTmp`)が、このユーザー環境で効くか。~~ **確認済み(結果: 名前空間を使う硬化はルートレス docker と両立しない。外した)**。`kivra-agent.service` から `ProtectSystem`、`ProtectHome`、`PrivateTmp`、`ReadWritePaths`、`ProtectKernelTunables`、`ProtectControlGroups` を外した。user manager では、これらがマウント名前空間で実現される。その中では、ホストの kivra-agent(uid 1001)所有の `/run/user/1001/docker.sock` が別 uid に見え、`preflight.sh` の `docker version` が `permission denied while trying to connect to the docker API` で失敗する。残した硬化は `NoNewPrivileges`、`RestrictSUIDSGID`、`RestrictRealtime`、`LockPersonality`、`SystemCallArchitectures`、`UMask` である。配置済みの環境の扱いは §6.11 の「c の補足 2」を参照。
- `RestartSteps=` と `RestartMaxDelaySec=`(systemd 254 以上)。古い systemd では無視される。
- `%t`(`/run/user/<uid>`)による `DOCKER_HOST`、`docker.service`(ルートレス)との依存順。
- `build-dist.sh` の `installDist` の出力名(`kivra-agent/build/install/kivra-agent/bin/kivra-agent`)。スクリプトはこの名前を前提にする。
- `kivra-agentctl`(root が必要)の `start`、`stop`、`status`。このリポジトリでは root が使えないため、実機で動かしていない。
- client secret のモード(c.3)。
- `KIVRA_AGENT_KIVRA_URL` の既定 `https://kivra-dev.kigawa.net`(dev の公開 URL。本番の値は決めていない)。
- `install.sh` の docker イメージのビルド(images 段階。ネットワークを使う。ルートレス docker の接続も含む)。

### 6.12 失敗した attempt の出力の保持(デバッグ用。既定は無効)

**目的**: 実機の Run が `EMPTY_PATCH` などで失敗したとき、CLI が何を読み、何を答えたかを確かめる。作業ディレクトリは従来どおり削除するので、差分の確認は `stream.jsonl` で行う。

**共有しないこと**: 保存されるファイルには、タスク文(title)、CLI の応答本文(モデルの発言とツールの結果)、リポジトリの内容が含まれる。秘密が混ざることもありうる。Issue、PR、チャット、ログの収集に貼らない。調べ終えたら削除する。

**有効化**: `/etc/kivra-agent/agent.env` に `KIVRA_AGENT_DEBUG_KEEP=1` を書き、kivra-agent を再起動する(再起動は §6.11 の `systemctl --user restart kivra-agent.service`)。
- 読まれるのは `KIVRA_AGENT_EXECUTOR=real` のときだけ。stub では無視される。
- 値は `1`(有効)、`0` または未設定(無効)。それ以外の値は起動を拒否する(exit 2。メッセージは変数名だけ)。

**保存先**: `/var/lib/kivra-agent/work/debug/<runId>-<attemptId>/`(`KIVRA_AGENT_WORK_DIR` の下の `debug/`)。
- `stream.jsonl`: CLI の stdout(stream-json)全体。8 MiB で先頭から切る。ただし実行の出力上限(claim の `maxOutputBytes` と 8 MiB の小さいほう)を超えた実行では、その上限で既に切れている。
- `task.json`: CLI に渡った `.kivra/task.json`。渡った title が分かる。
- `outcome.txt`: `code=<失敗コード>`。差分が拒否されたときは `reasons=<理由>` も付く(例: `code=CLI_NONZERO` と `reasons=EMPTY_PATCH`)。`code=EXCEPTION` は、結果が返る前に例外や中断(キャンセル、リースの喪失など)で終わったこと。

**保存する対象**: 失敗したすべての attempt(`CLI_NONZERO`、`BAD_OUTPUT`、`EMPTY_PATCH` を含む)。結果が kivra に受理された成功では何も保存しない。

**保持期間と件数**: 起動時と各 attempt の終了時に、24 時間より古いエントリを削除する。残すのは新しい順に最大 20 件。

**権限**: `debug/` とエントリは 0700、ファイルは 0600。所有者は kivra-agent。

**ログ**: 保存したこと(`debug output kept: runId=... attemptId=...`)と、削除の件数だけを出す。title、応答本文、パスは出ない。

**確認**(ファイルの中身を全部表示しない):
```bash
# 件数と更新時刻(エントリの名前は runId と attemptId)
sudo runuser -u kivra-agent -- ls -la /var/lib/kivra-agent/work/debug/
# 保存の記録(ID だけ)
sudo runuser -u kivra-agent -- env XDG_RUNTIME_DIR=/run/user/1001 journalctl --user-unit=kivra-agent.service | grep 'debug output'
# 結果コードと拒否理由
sudo runuser -u kivra-agent -- cat /var/lib/kivra-agent/work/debug/<runId>-<attemptId>/outcome.txt
# モデルが呼んだツールの名前と回数(入力の中身は出さない)
sudo runuser -u kivra-agent -- grep -o '"name":"[A-Za-z_]*"' /var/lib/kivra-agent/work/debug/<runId>-<attemptId>/stream.jsonl | sort | uniq -c
```

**無効化**: `agent.env` の行を消すか `0` にして再起動する。無効にしても既存のエントリは消えない(消すのは有効なときの起動時と attempt 終了時の削除だけ)。すぐ消すなら、kivra-agent の所有者で `rm -rf /var/lib/kivra-agent/work/debug` を実行する。

### 6.13 CLI の費用の計上(Issue #230、ADR 0010 追補 4)

CLI 経路の費用は、kivra の月次の予算(`KIVRA_BUDGET_MONTHLY_LIMIT_JPY`)に入る。API 経路と同じ台帳(`budget_reservations`、`budget_periods`)に、キー `coding:<attemptId>:<世代>` で 1 回だけ計上する。使用量の記録は `coding_attempt_usage`(V16)にある。

**設定(すべて既定値で動く。k8s の設定は変えていない)**

| 名前 | 既定 | 内容 |
| --- | --- | --- |
| `KIVRA_AGENT_ATTEMPT_RESERVE_USD` | `0.50` | 1 attempt の予約額(USD)。報告の無い attempt に計上し、claim の前にも月次の残りがこの額(円換算後)以上あるかを確かめる |
| `KIVRA_FX_USD_JPY`、`KIVRA_FX_AS_OF` | (なし) | API 経路と同じ為替。失効(既定 7 日)または未設定なら、claim は全て拒否され(UNKNOWN_COST)、報告は金額 0 の UNPRICED になる |
| `KIVRA_FX_MARGIN_RATIO`、`KIVRA_FX_MAX_AGE_DAYS` | `0.10`、`7` | 為替の余裕と古さの上限(API 経路と同じ) |
| `KIVRA_SELFDEV_MODEL_REGISTRY_PATH` | (なし) | 価格表。報告の `model` が価格表に無い(または価格が無い)と UNPRICED になる。未設定なら全て UNPRICED |

**計上の流れ**

1. claim の前に、月次の残り(上限 − 確定額 − 予約中の額)が、予約額(`KIVRA_AGENT_ATTEMPT_RESERVE_USD` × 為替 × 余裕 × 税)以上あるかを確かめる。足りなければ claim させない(Run は待ちのまま。ログに `coding claim refused: code=BUDGET`)。UNPRICED が残っていれば `UNKNOWN_COST` で拒否する。
2. エージェントが結果または fail の本文に `usage` を付けると、その費用を円に換算して計上する。同じ attempt の同じ世代への 2 回目の報告は計上しない。`usage` が形式に合わなければ無視する(結果は受理され、警告ログ `coding usage ignored: attemptId=... reason=USAGE_INVALID` が出る)。
3. 台帳に書けない場合は、結果と fail を受理せず 503 を返す。attempt は変わらないので、エージェントは送り直せる。
4. 報告が無いまま終わった attempt(usage の無い fail、ORPHANED、EXPIRED、usage の無い結果)は、予約額を UNPRICED として計上する。

**欠けた報告の扱い**: 費用が分からないので、使った前提で予約額を計上し、UNPRICED のフラグを立てる(安全側)。この状態の間、新しい claim は `UNKNOWN_COST` で止まる。止まった Run は待ちのままで、自動では解除されない。

**運用者の確認手順**

1. UNPRICED の予約を探す(ID と金額だけを見る。値は表示しない)。

   ```sql
   SELECT reservation_key, state, amount_micro_yen, created_at
     FROM budget_reservations
    WHERE state = 'UNPRICED'
    ORDER BY created_at;
   ```

   `reservation_key` が `coding:<attemptId>:<世代>` の形のものが CLI 経路の計上である。`attemptId` で `coding_attempts` と `coding_attempt_usage` を見れば、報告があったか(`source` が `REPORTED` か `ESTIMATED_RESERVE` か)が分かる。
2. Anthropic Console の、専用ワークスペースの支出を、その期間で確認する。
3. 実額が分かったら、§4.2 の `acknowledge` を `reservationKey` を指定して呼ぶ(`actualAmountJpy` に円で実額を入れる)。実額が分からない、またはその予約の金額が 0 のとき(為替の失効で換算できなかったもの)は、`actualAmountJpy` を省略する(実費不明として残る)。金額 0 の予約に実額を入れると `TOO_LARGE` で拒否される(acknowledge の上限は計上額の 100 倍まで)。

**運用者の確認手順(画面)**: `/ui` の「予算（運用者向け）」欄で、次を見る。画面が読むのは `GET /api/admin/budget/status`(運用者の認証。金額と予約キーだけを返し、プロンプトや本文、パス、モデルの応答は返さない。応答の `paths` が経路別の内訳、`unpriced` が未確定の一覧)と、`GET /api/admin/budget/fx`。

1. 「対象の月」と棒: 確定の額と予約中の額の合計が、上限に対してどれだけか。「上限を超えています」が出たら、Console の支出と照らし合わせる(下の Console の項)。
2. 「API 経路」と「CLI 経路」: CLI は `coding:` の予約の合計。どちらが増えたかで、原因が API か CLI かを分ける。
3. 為替: 「残り N 日」が 2 日以下なら黄色、「失効」なら赤。失効中は CLI の claim が止まり、報告は金額 0 の未確定になる(§4.8 で更新する)。
4. 未確定の予約: 件数と、一覧の各行(CLI の attempt と世代、金額、理由)。理由は「費用が確定しない」(予約額のまま計上)か「期限切れ」(保守的に計上)。
5. 解除: 実額が分かったら「解除…」を押し、入力(実額は任意、根拠と理由は必須)、確認、「解除する」の順に進む。実額が 0 なら「請求が無いことを確認した」の意味になる。実額を入れると、計上額が実額に変わり、未確定の件数が 1 減る。解除は監査ログ(`budget_acknowledgements`)に残り、取り消せない。
6. 一覧が 100 件を超えるときは、一覧の下に注記が出る。残りは上の SQL で確認する。

画面は読み取りと、上の解除だけを行う。Console の支出の確認、為替の更新、attempt の調査は、この画面の外で行う。

**Console 側の上限と kivra の上限の関係**: 共有ワークスペースの支出上限(Console)は、kivra の月次の上限より大きくする。kivra の上限は計上の範囲で止める。Console は、kivra の計上が漏れた場合の最終防壁になる。

**既知の制限(この PR)**

- 入場の確認と claim は別の操作である。同時に複数のエージェントが claim すると、予約額の数件分だけ超えうる(1 台の PC を前提とする)。
- ORPHANED / EXPIRED の遷移は済んでいるが、その計上が失敗した場合は、警告ログ(`coding reserve not booked`)が出るだけで、再試行しない。周期ワーカー(§6.15)は遷移を一度しか行わないので、この欠損は再試行されない。このログが出たら、該当の attempt を §4.2 の手順で確認する。
- 為替が失効していた間に報告された費用は金額 0 で計上される。この予約には実額を入れられない(上記の確認手順 3)。
- 運用画面で API 経路と CLI 経路を区別して表示する作業(UI PR5)は、`/ui` の「予算（運用者向け）」欄に入った(§4.7)。


### 6.14 client secret の配置とローテーション(Issue #232)

資材: `agent-runtime/install/install-client-secret.sh`。取得は通常ユーザー、配置は root が行う。値は標準入力のパイプだけで渡し、引数や環境変数には載せない。
配置先は `/etc/kivra-agent/client-secret`(`kivra-agent:kivra-agent 0600`)。JVM の起動検査はグループとその他が読めるファイルを拒否し、プロキシ(コンテナ内の root = ホストの kivra-agent)は所有者として読む。

**a. 配置(初回と再配置の共通)**

```bash
# 値の取得: 秘密ではない secret の ID を調べてから、値をパイプで渡す(値は画面に出ない)
SECRET_ID="$(bws secret list 3f39dcb2-4e04-4c80-bcc4-b3e100e4e27a | jq -r '.[] | select(.key=="kivra-agent-keycloak-client-secret") | .id')"
bws secret get "$SECRET_ID" | jq -j .value | sudo bash /opt/kivra-agent/agent-runtime/install/install-client-secret.sh
```

- 空の値、`null`(jq が値を取れなかった)は拒否し、既存のファイルは変えない。配置は一時ファイルから `mv` で行う(原子的)。
- 表示するのはモード、所有者、バイト数だけ。値と長さのほかは出さない。
- 配置後、rootless docker のデーモンから secret がファイルとして見えるかを確かめる。ディレクトリに見えた場合は、kivra-agent の `docker.service` を再起動する(install.sh と同じ条件。実行中の container は止まる)。
- 冪等。何度実行してもよい。`--dry-run` と `DESTDIR` は、テスト用の一時ディレクトリへの配置(root・chown・docker を行わない)。
- `/etc/kivra-agent` は `root:kivra-agent 0750` に直す。

**b. ローテーション(client secret の再生成)**

Keycloak の client の Regenerate は旧 secret を**即座に失効**させる。猶予は無い。手順は次の順に行い、失敗したら下の「失敗時」に従う。

0. 停止: `sudo kivra-agentctl stop`(claim を止める。実行中の attempt は CANCELLED で報告される)。
1. 再生成: Keycloak 管理画面で該当 client(`KIVRA_AGENT_CLIENT_ID`)の Credentials から新しい secret を生成する。ここから旧 secret は使えない。
2. Bitwarden 更新: Bitwarden Secrets Manager の `infra` プロジェクト(ID `3f39dcb2-4e04-4c80-bcc4-b3e100e4e27a`)のキー `kivra-agent-keycloak-client-secret` を新しい値にする。Web 画面で行い、値をコマンド引数、シェル履歴、チャットに残さない。
3. 配置: 上の a の手順。
4. 再起動(proxy を先に。JVM は起動時に secret を読むため、kivra-agent の再起動が必要。proxy は毎回ファイルを読み直す):
   ```bash
   sudo runuser -u kivra-agent -- env XDG_RUNTIME_DIR=/run/user/1001 systemctl --user restart kivra-agent-proxy.service kivra-agent.service
   ```
5. 確認:
   - `sudo kivra-agentctl status` で、3 ユニットが active、client secret がファイルとして見える、直近のログにエラーが無いこと。
   - `sudo -u kivra-agent env DOCKER_HOST=unix:///run/user/1001/docker.sock bash /opt/kivra-agent/agent-runtime/verify/live-smoke.sh`(実 API を 1 回呼ぶ。`PASS` が 4 行、`FAIL` が出ないこと)。
   - ログ: `sudo kivra-agentctl status` の直近ログに、認証(トークン取得)の失敗が無いこと。
6. 再開: `sudo kivra-agentctl resume`(STOP を消す)。
7. 旧 secret の失効の確認: 1 で Regenerate した時点で失効している。Keycloak の Credentials 画面に出ている値が新しいものだけであることを確かめる。旧値を手元に残さない(クリップボード、端末のスクロールバック、Bitwarden の旧版の履歴を確かめる)。

**失敗時**

- 手順 3 の配置が失敗(空の値、`null`、所有者やモードの不一致): 既存のファイルは変わらない。Keycloak 側は既に再生成済みなので、Bitwarden の値を直して 3 から再実行する。
- 手順 4 の後に JVM が起動しない(`invalid_client` などの認証エラー): Bitwarden の値と Keycloak の値が一致するかを、値を表示せずに確かめる(再生成の直後の取り違えが多い)。合わなければ 2 から直す。`sudo kivra-agentctl stop` で claim を止めたままにする。
- 配置後、デーモンからディレクトリに見える: install-client-secret.sh が docker.service を再起動する。それでも直らない場合は §6.11 c の補足。
- **旧値には戻せない**: Keycloak の再生成で旧値は失効する。ロールバックは「新しい値を Keycloak から正として、Bitwarden の値に合わせて配置し直す」ことである。Bitwarden の値を古い版に戻しても、Keycloak 側は戻らない。
- 止める: `sudo kivra-agentctl disable`(3 ユニットを停止して無効化)。secret のファイルは残る。消すなら `sudo rm /etc/kivra-agent/client-secret`(消した状態では起動できない)。

## 7. 自己開発オートパイロット(ADR 0011、Issue #233)

状態: **基盤(PR A、#243)、Planner(PR B、#244)、Dispatcher(PR C、#245)、UI と HTTP(PR D、#250)、E2E と有効化の手順(PR E)を実装済み**。段階 2 の差分の再検査(ADR 0011 §6.2、§2.8d。PR F)を実装済み(§7.4b)。
`KIVRA_SELFDEV_AUTOPILOT_ENABLED=true` のときだけ動く。既定(無効)では何も起きない。
Planner は、`GOAL_SUBMITTED` の Goal を claim して計画を作り、`PLAN_PROPOSED` に進める。承認された Plan のタスクは、Dispatcher が §5 のガードを確かめたうえで投入する。
dev での有効化の手順は §7.5。
運用手順は §7.4(Goal の入力、承認、中止、再計画、一時停止、ブレーカーの再開)。

### 7.1 設定項目

| 名前 | 既定 | 内容 |
|---|---|---|
| `KIVRA_SELFDEV_AUTOPILOT_ENABLED` | `false` | `true` と `false` 以外は起動に失敗する。`true` は、自己開発が有効(`KIVRA_SELFDEV_ENABLED=true`)で、`KIVRA_SELFDEV_RUNTIME=cli` のときだけ許す |
| `KIVRA_SELFDEV_AUTOPILOT_APPROVAL` | `required` | `required` は全タスクを運用者が承認する。`auto` は段階 2 の規則に当たるタスクを承認なしで投入する(実際の差分の再検査は未実装。ADR 0011 §6.2)。値は `required` / `auto` のどちらか。**denylist のパスのタスクは、`auto` でも必ず承認が要る**(ADR 0011 §4) |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_PENDING_PRS` | `3` | マージ待ちの自己開発 PR の上限。1〜20 |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_CONSECUTIVE_FAILURES` | `3` | 連続失敗でサーキットブレーカーを開く数。1〜20(この範囲は PR A で決めた値。ADR に範囲の記載は無い) |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY` | `5` | 1 日(UTC)に投入する Run の数。1〜100(この範囲も PR A で決めた値) |
| `KIVRA_SELFDEV_AUTOPILOT_BUDGET_SHARE_PERCENT` | `50` | 月次予算のうち、オートパイロットに割り当てる割合(%)。1〜100 |
| `KIVRA_SELFDEV_AUTOPILOT_PLAN_INTERVAL_SECONDS` | `30` | Planner が計画待ちの Goal を探す間隔(秒)。5〜3600 |
| `KIVRA_AGENT_REQUIRED_ROLE` | (なし) | `<client>:<role>`(例 `kivra-agent:agent`)。**オートパイロットが有効のときは必須**(PR D)。このロールを持つトークンは、運用者の API で 403 になる。オートパイロットが無効のときは読まれない |

数値が範囲外、または `APPROVAL` が `required` / `auto` 以外のときは、起動に失敗する。失敗のメッセージは変数の名前だけを示し、値は出さない。
値は秘密ではない。

### 7.2 無効時の挙動(既定)

- `KIVRA_SELFDEV_AUTOPILOT_ENABLED` が未設定、空、または `false` のとき、ほかのオートパイロットの変数は読まれない。
- 何も配線されず、`selfdev_autopilot_control` の行は読まれない。DB のテーブルは V18 で作られるが、使われない(既存の挙動は変わらない)。
- 運用者の API(`/api/selfdev/goals`、`/api/selfdev/autopilot` など)は、Task API と同じ OIDC 認証(`KIVRA_AUTH_MODE=oidc-jwt`)のときだけ登録される。無効のときは、認証の後で 503 を返す(認証前には状態を漏らさない)。画面の自律運転の欄は、503 のとき隠れる。

### 7.2b Planner(PR B)

- 対象は `GOAL_SUBMITTED` の Goal。claim は DB の時刻で行い、同時に 1 つの Planner だけが取る。claim の期限は 600 秒。切れた claim は次の周期で `GOAL_SUBMITTED` に戻る(`CLAIM_EXPIRED`)。
- モデルは `claude-haiku-5-5` に固定する。起動時に registry にそのモデルが `ACTIVE` で無ければ、起動に失敗する。ツールは渡さない。思考は無効。
- 出力は JSON 1 個だけ。未知のキー、型違い、件数・長さの超過、依存の誤り、allowlist 外のパスは拒否する。拒否は 1 回だけ再試行する。
- denylist のパス(`.github/**`、`**/db/migration/**`、`docs/adr/**` など)に触れるタスクは、`auto` でも承認待ちになる。モデルは `requiresApproval` を出せない。
- 予算が足りない、または UNKNOWN_COST のとき、Goal は `BLOCKED(PLAN_BUDGET_HOLD)`。モデル呼び出しの失敗は `BLOCKED(PLAN_CALL_FAILED)`。検証の失敗は `BLOCKED(PLAN_INVALID)`。
- `BLOCKED` の計画段階の Goal は、自動では再試行しない。運用者が再計画する(`BLOCKED → GOAL_SUBMITTED`、理由 `REPLAN_REQUESTED`)。入口は §7.4 の「再計画」(PR D)。
- ログには Goal の本文、計画、パス、モデルの出力、例外の文を出さない。ID と固定の理由コード、件数、費用、出力のダイジェストの先頭 12 文字だけを出す。

### 7.2c Planner のリポジトリのファイル一覧(Issue #293)

- 目的: Planner は、リポジトリの中身を見られないため、パスを推測して `PATH_NOT_ALLOWED` で失敗していた。Planner のユーザーの
  メッセージに、リポジトリのファイル一覧を `<repo-data>` の区切りで渡し、タスクのパスを一覧の完全なパスに限らせる。大きなファイル
  (20 KB 以上)には、サイズ(KB)を付ける。一覧は、`main` の先頭のツリー(`GitHubPort.listTree`)から作り、5 分キャッシュする。
- 範囲: 許可パス(`kivra-*/src/**`、`docs/**`、`README.md`)のうち、禁止パス(`PlanValidator` の規則と同じ判定)を除いたファイル。
  シンボリックリンクとサブモジュールは含めない。見えない文字(bidi、ゼロ幅など)や制御文字を含むパス、200 文字を超えるパスは一覧から
  外す(除去すると別のパスになるため)。
- 大きさ: 一覧が 48 KB を超えるときは、ディレクトリごとの件数の要約に縮める(一覧の先頭に「要約」と明記)。要約では、要約の下の
  ファイルのパスは出さない。既存のファイルを名指ししないタスクは、要約のディレクトリの下に新しいファイルを作るときだけ、親のディレクトリを使う。
- 環境変数: `KIVRA_SELFDEV_PLANNER_REPO_TREE`。未設定と `true` は有効(既定)、`false` は無効。それ以外の値は起動に失敗する
  (変数名だけを出す)。自律運転(`KIVRA_SELFDEV_AUTOPILOT_ENABLED=true`)が無効のときは、この変数を読まない。
  無効のときは、今までと同じプロンプトになる。
- 縮退: 一覧の取得が失敗した(切り詰め `TREE_TRUNCATED`、その他の GitHub の失敗、不明な結果、例外)ときは、一覧なしで計画を続ける。計画は止めない。
  失敗は、固定の理由コード(`reason=TREE_TRUNCATED` など)だけをログに出す。取得に成功した結果だけをキャッシュし、失敗はキャッシュしない。
- 再試行のフィードバック: 1 回目の出力が `PATH_NOT_ALLOWED` で無効だったときだけ、2 回目のプロンプトに、許可されなかったパス
  (Planner 自身の出力の文字列。見えない文字を除き、200 文字で切る)と許可パスの規則を足す。Goal の題名と本文は足さない。
  この機能が無効のときは、再試行は今までどおり。
- ログ: 件数、要約の有無、バイト数、除外数だけを出す。パスの一覧、Goal の本文は出さない。

### 7.2d 自己開発の記憶(ADR 0016 P1: 保存のみ、影)

- 状態: 段階 P1(保存のみ)。記録は書くだけで、**読まない**。Planner のプロンプト、`PlanValidator`、承認と投入の判断は、記録に依存しない。
  閲覧と削除の API と画面は P2 で入った(7.2e)。運用者のメモの入力 API は、まだ無い。
- 環境変数: `KIVRA_SELFDEV_MEMORY_ENABLED`。既定は `false`。`true` と `false` 以外は起動に失敗する(変数名だけを出す)。
  オートパイロット(`KIVRA_SELFDEV_AUTOPILOT_ENABLED=true`)が無効のときは、この変数を読まない(記録を書く流れが無いため)。
- 記録する事実(固定のコードと件数だけ。文章は下の題名だけ):
  - `PLAN_FACT`: `PLAN_PROPOSED`(件数はタスク数)。計画が提案されたとき。失敗で止まったときは、理由コード(`PLAN_INVALID` など)。
  - `TASK_FACT`: 理由コードが `OPERATOR_APPROVED` / `AUTO_APPROVED` / `OPERATOR_SKIPPED` / `GOAL_STOPPED` / `PR_MERGED` /
    `RUN_FAILED` / `POLICY_REJECTED` / `PR_ABANDONED` / `RUN_RELEASED` / `AUTO_SCOPE_EXCEEDED` のとき。`identity_sha256` は
    タスクのパスの集合の SHA-256(パスそのものは入れない)。
  - `RUN_FACT`: Run の結果(`PR_MERGED`、`FAILED` の理由コードなど)。
  - `GOAL_FACT`: Goal の終了(`ALL_TASKS_COMPLETED` / `TASKS_INCOMPLETE` / `OPERATOR_STOPPED`)。
  - `GOAL_TITLE`: 運用者が Goal を投入したときの**題名だけ**(200 字まで)。本文は記録しない(ADR 0016 U-6)。
- 記録しないもの: Goal の本文、計画の文、タスクの本文、PR の本文、モデルの出力、運用者の生の ID(ハッシュの先頭 12 文字だけ)。
- 書き込みの経路: すべて `SelfDevMemoryRepository.save`(検証を通ったものだけ保存)。状態遷移の後、**別のトランザクション**で書く。
  書けなかった・拒否されたときは、固定コードのログ(`selfdev memory refused: kind=... code=...` / `selfdev memory write failed:
  exception=<例外の型名>`)だけを出し、流れは止めない。例外の文言はログに出ない。
- 止め方: `KIVRA_SELFDEV_MEMORY_ENABLED=false` にして再起動する。記録の書き込みも、保持ジョブも止まる。既に書かれた記録は残る
  (消すのは保持期間の後。手で消す手段は P2 で入った。7.2e)。
- 保持期間(ADR 0016 section 9): 文章(`GOAL_TITLE` を含む)180 日、削除された文章の墓標 30 日、事実 365 日、監査 365 日。
  保持ジョブは 1 時間ごとに動く。期間は `created_at` からの経過で判定する。
- 確認(件数の集計。本文は出ない):
  ```sql
  SELECT kind, code, COUNT(*) FROM selfdev_memory_records WHERE status = 'ACTIVE' GROUP BY kind, code;
  SELECT kind, code, COUNT(*) FROM selfdev_memory_events GROUP BY kind, code;
  ```
- 未実装(P1 の範囲外): `ATTEMPT_FACT`、`COST_FACT`、`PLAN_RATIONALE`、`FAILURE_EXPLANATION`、`CYCLE_REVIEW`、運用者のメモの API。

### 7.2e 自己開発の記憶の閲覧と削除(ADR 0016 P2)

- 状態: P2(閲覧と削除)。既定は無効。環境変数 `KIVRA_SELFDEV_MEMORY_ENABLED` が `true` のときだけ、API と画面が動く
  (オートパイロットが有効なときだけ読む。7.2d の規則のまま)。`false` のとき、API は 503(`memory is disabled`)を返し、何も読まない。
  画面の「記憶」の欄は、一覧の API が 200 を返したときだけ出る(503、403、404 のときは出ない)。
- 権限: 運用者だけ(運用者の役割。エージェントのトークンは 403)。AI の主体(`KIVRA_SELFDEV_AI_PRINCIPAL`)は、この API の全項目で 403 `forbidden`。
- HTTP:
  - `GET /api/selfdev/memory?kind=&source=&tier=&goalId=&createdAfter=&createdBefore=&limit=&offset=`: 一覧。
    `limit` は 1〜100(既定 20)、`offset` は 0〜100000。種類と出所と信頼は、列挙の名前(例: `OPERATOR_NOTE`、`TRUSTED_OPERATOR`)。
    日時は `2026-10-01T00:00:00` の書式(UTC)。応答は `items`、`total`(条件に合う有効な記録の件数)、`limit`、`offset`、
    `tombstones`(削除された文章の墓標の件数)、`deletionAudit`(削除の監査の件数の合計)。
  - `POST /api/selfdev/memory/{id}/delete` と `{"reason": "..."}`: 個別の削除。200 `{"recordId", "deleted": true}`。該当が無ければ 404。
  - `POST /api/selfdev/memory/delete-by-condition` と `{kind?, source?, tier?, goalId?, createdAfter?, createdBefore?, dryRun?, reason?}`:
    条件の削除。`dryRun` の既定は `true`(件数だけを返し、何も消さない)。`dryRun: false` のときは `reason` が必須。
    条件が 1 つも無いと 400。該当が 500 件を超えると 409(`matched` を返し、何も消さない。条件を絞って繰り返す)。
    応答は `{matched, deleted, dryRun}`。
- 返す項目: 記録の ID、種類、出所、信頼の段階、Goal の ID、件数、理由コード(固定の列挙だけ)、作成時刻。
  TRUSTED の文章(`OPERATOR_NOTE`、`GOAL_TITLE`、`REJECTION_REASON`)の本文は返す。
- 返さない項目:
  - UNTRUSTED の文章(モデルや系統が書いた文。`PLAN_RATIONALE` など)の本文。代わりに `bodyWithheld: true` を返す(ADR 0016 U-1)。
  - 削除の理由の文。理由は形を検査した後に捨てる。監査は文章を持たない(ADR 0016 4.2)。したがって、理由は画面の確認にだけ使われ、保存されない。
  - 拒否された文章、生の運用者の ID(記録の作成者はハッシュの先頭 12 文字だけ)。
- 画面(`/ui` の「記憶」の欄): 種類と信頼で絞り込み、20 件ずつ表示する。個別の削除は、理由(1〜500 文字、制御文字なし)を入れた
  確認ダイアログの後にだけ送る。条件の削除は、先に「該当の件数を確認」で件数を出す。同じ条件のまま件数が 1 以上のときだけ
  「この条件で削除」が有効になる。失敗は固定の文だけを出し、サーバーの文は出さない。
- 削除の手順:
  1. 条件を絞り、`dryRun: true` で件数を確認する(画面の「該当の件数を確認」、または API)。
  2. 件数が想定どおりなら、理由を付けて削除する(`dryRun: false`)。
  3. 一覧の下の墓標と監査の件数が、削除した文章の数だけ増えたことを確認する。
- 削除の後の扱い: 削除は次の読み出しから効き、一覧にも Planner の入力にも出ない(P2 の条件。Planner は P4 まで記録を読まない)。
  進行中の Planner の呼び出しには、最大 240 秒の遅れがありうる(ADR 0016 8.4)。バックアップには、保持の期限まで削除前の内容が残る(U-4)。
- 保持: 墓標は削除の後 30 日で物理的に消える。監査は 365 日。削除の監査の件数は、墓標が消えた後も残るため、
  墓標の件数と一致しなくなる(これは正常)。

### 7.4 運用手順(PR D: 画面と HTTP)

画面は `/ui` の「開発目標(Goal)」の欄。オートパイロットが無効のとき、この欄と「Goal の一覧と承認」の欄は出ない。

**Goal の入力**: 目的(1〜200 文字、必須)と、背景・制約・受け入れ条件(0〜8000 文字、任意)を入れて「Goal を送る」。対象リポジトリは設定のもの(`KIVRA_SELFDEV_TARGET_REPO`)で、画面からは変えられない。
同じ内容の再送は同じ Goal として扱われ、二重には作られない。受け付けられた Goal は `計画待ち` になり、Planner が計画を作る。

- HTTP: `POST /api/selfdev/goals`(`{"title", "body"?, "idempotencyKey"}`。ほかの項目は 400)。202 は新規、200 は既に受け付け済み、409 は同じ投入キーで別の内容。
- 本文は、一覧には出ない。詳細(`GET /api/selfdev/goals/{id}`)でだけ見える。
- AI の主体(`KIVRA_SELFDEV_AI_PRINCIPAL` の owner key)は、Goal の書き込み(投入、計画の承認、replan、中止、タスクの承認と見送り)と、オートパイロットの制御(一時停止 `pause`、再開 `resume`、ブレーカーのリセット `reset-breaker`、自動マージの一時停止 `auto-merge/pause`、`auto-merge/resume`)を 403 `forbidden` で拒否される(ADR 0019。拒否の前に本文は読まれず、何も保存されない)。読み取り(`GET`)は AI の主体にも許す。`KIVRA_SELFDEV_AI_PRINCIPAL` は order の有効化(`KIVRA_SELFDEV_ORDERS_ENABLED`)と独立に読まれる(#311)。order を無効にしたままでも、Goal の REST と Goal の MCP ツールの拒否は効く。未設定のときは、この拒否は効かない(従来どおり)。不正な形式は、order が無効でも起動に失敗する。

**計画の承認**: `計画の承認待ち` の Goal の詳細で、Plan(計画)と各タスクを確かめ、「この Plan を承認」を押す(`POST .../approve-plan`、`{"version": n}`)。
承認すると、Goal は `承認済み` になる。計画の承認は、個々のタスクの承認を代わりに行わない(`requiresApproval` のタスクは、タスクごとの承認が要る)。`auto` のときは、段階 2 の条件に当たる低リスクのタスクだけが、計画の承認の後に投入の対象になる。

**タスクの承認と見送り**: 各タスクは、既定では運用者が 1 件ずつ承認する。承認(`POST /api/selfdev/tasks/{planId}-{番号}/approve`)すると `承認済み（投入を待っています）`、見送り(`.../reject`)すると `見送り`。
**変更範囲に承認が必要なパス(denylist。例: `.github/**`、`**/db/migration/**`)のタスクは、この承認でしか投入されない**(計画の承認では投入されない)。
承認は、承認者(運用者)と時刻を保存する(`approved_by`、`approved_at`)。承認は、Plan の承認の後にだけできる。

**中止**: 詳細の「中止…」で理由(1〜500 文字、必須)を入れる(`POST .../stop`、`{"version": n, "reason": "..."}`)。
未投入のタスクは `見送り` になり、Goal は `中止` で終わる。**投入済みの Run と、作成済みの PR には触らない**(PR は閉じず、マージもしない。PR の扱いは人が決める)。理由は、今は保存もログにも出さない(ADR 0011 U-18)。

**タスクの失敗で止まった Goal**: 投入したタスクが失敗(`FAILED`)し、残りが見送り(`SKIPPED`)になって、全タスクが終わると、Dispatcher が Goal を `中止` にする(理由は「タスクが完了せずに終わった」。ADR 0011 §2.8)。
手で止めたのではないので、「中止」の表示を見たら、まず詳細のタスクの状態と、§4.5 の失敗の原因を確かめる。全タスクが `COMPLETED` で終わったときは `完了` になる。
次のタスクは投入されない。やり直すときは、新しい Goal を出す(`FAILED` のタスクを戻す HTTP の操作は無い)。
投入の主体は、タスクを承認した運用者。承認が無い自動承認のタスクは、Goal を作った人の名で投入される(`approved_by` が無いとき)。

**再計画**: Goal が `停止中` で、理由が計画段階(`PLAN_INVALID` / `PLAN_BUDGET_HOLD` / `PLAN_CALL_FAILED`)のときだけ、「再計画する」が出る(`POST .../replan`)。Planner が新しい計画を作る。計画の回数の上限は 3 回。
ディスパッチ段階の停止(予算、日次上限、ブレーカーなど)は、この操作では再開しない。

**全体の一時停止と再開**: 「自律運転を一時停止」(`POST /api/selfdev/autopilot/pause`)で、新しい投入と計画の作成を止める。実行中の Run は止めない。「再開」で戻す。

**自動マージの一時停止と再開**: `POST /api/selfdev/autopilot/auto-merge/pause`、`POST /api/selfdev/autopilot/auto-merge/resume`(空の JSON `{}`)。自動マージの影の判定だけを止める。新しい投入や計画は止めない。次の tick から効き、再起動は要らない。AI の主体は 403 `forbidden`。再開は運用者が行う。`main` の CI が赤になったときの自動停止は、この操作とは別の作業で扱う。

**サーキットブレーカーの再開**: 連続して失敗すると、自律運転は止まり、画面に「サーキットブレーカーを手動で再開」が出る(`POST /api/selfdev/autopilot/reset-breaker`)。
押す前に、失敗の原因(UNKNOWN の Run、PR_ABANDONED、POLICY_REJECTED など)を §4 で確かめる。原因が直っていないまま再開すると、また同じ数の失敗で止まる。

**見るもの**: `GET /api/selfdev/autopilot`(稼働中か一時停止か、自動マージの一時停止 `autoMergePaused`、ブレーカー、今日の Run 数、対象リポジトリ、承認の方式)。
ログは、操作の ID と固定の理由コード、操作した運用者の短いダイジェスト(SHA-256 の先頭 12 文字)だけを出す。本文、理由、運用者の ID は出ない。

**やってはいけないこと**: 作成済みの PR を、この画面の操作で閉じる、またはマージすることを期待しない。マージは人が行う(ADR 0008、ADR 0009)。

### 7.4b 段階 2 の差分の再検査と `AUTO_SCOPE_EXCEEDED`(PR F)

`approval = auto` のとき、承認なしで投入されたタスク(`approved_by` が無いタスク)は、コミットの前に、**実際の差分**で再検査される。
範囲は段階 2 と同じ(`docs/**`、`kivra-*/src/test/**`、`README.md` だけ。denylist に触れない)。加えて、変更パスは 3 件以下、変更行は 200 行以下である。
範囲の外なら、PR は作られず、Run は `POLICY_REJECTED` で終わり、タスクは承認待ち(`PENDING_APPROVAL`、理由 `AUTO_SCOPE_EXCEEDED`)に戻り、Goal は `BLOCKED`(`AUTO_SCOPE_EXCEEDED`)になる。

**運用者の見方**:
- 画面で Goal の理由が「変更範囲が計画を超えました（運用者の確認が必要）」(`AUTO_SCOPE_EXCEEDED`)なら、この再検査で止まっている。
- 失敗としては数えない。サーキットブレーカーは動かない。
- ログには `autopilot scope exceeded: runId=... code=AUTO_SCOPE_EXCEEDED paths=<件数> lines=<行数>` が出る。パスと内容は出ない。差分の中身は、ログからは分からない。
- 範囲外の差分は、kivra に保存されない(作業ブランチも作られない)。内容を確かめられるのは、coding attempt の出力の保持(§6.12。既定は無効)が有効なときだけである。

**再承認の手順**:
1. 原因を確かめる。計画(Plan)の対象パスと、Run の失敗の理由(`POLICY_REJECTED`)を見る。このタスクの変更が正当なら、範囲を広げる別の Goal を出すか、手で PR を作る方が安全である(自動の範囲は広げない)。
2. 承認し直す: 詳細の該当タスクで「承認」(`POST /api/selfdev/tasks/{planId}-{番号}/approve`)。承認すると、タスクは新しい投入キーで `QUEUED` になり、Goal は `BLOCKED` から `RUNNING`(または `TASKS_QUEUED`)へ戻る。次の tick で投入される(新しい Run)。
3. 承認したタスクは、再検査の対象外になる。差分が範囲外でも、承認者の判断でコミットされる。承認の前に、計画の内容を確かめる。
4. 見送るなら「見送り」(`reject`)。タスクは `SKIPPED` になり、Goal の残りは進む。

**注意**:
- 承認し直す前に、同じ原因が直っているか確かめる。再承認で同じ差分が出ると、承認済みのため再検査されず、そのままコミットされうる。
- 範囲外の判定は、PatchPolicy より後に行う。PatchPolicy が直接拒否するパス(`.github/**` など)は、再検査の前に `POLICY_REJECTED` で止まり、失敗として数えられる(安全側の動き)。

### 7.4c 段階 1 → 2 の昇格条件の見方(ADR 0011 §6.3、Issue #233)

- 画面: 「Goal の一覧と承認」の中の「段階 1 → 2 の昇格条件」。読み取り API は `GET /api/selfdev/autopilot/promotion`(運用者だけ。無効時は 503)。DB の SELECT だけで、マイグレーションも書き込みもない。
- 条件と集計:
  - `APPROVED_COMPLETED`: 運用者が承認した(`approved_by` がある)タスクのうち `COMPLETED` の数。必要は 10 件以上(ADR の暫定値、U-8)。
  - `POLICY_REJECTED`: 承認したタスクのうち、タスクの理由、または Run の `state_reason` が `POLICY_REJECTED` のもの。必要はゼロ。
  - `OUT_OF_SCOPE`: `state_reason` が `AUTO_SCOPE_EXCEEDED` のタスクの数。必要はゼロ。注意: 運用者が承認したタスクは、差分の再検査の対象外である(§6.2)。そのため、この数に出るのは段階 2 の自動承認のタスクだけで、段階 1 の範囲外の変更はここからは分からない。段階 1 では PR の差分を人が見て確認する。
  - `BUDGET_CONFIRMED`: 未計測。§6.3 の「#230 が完了し、予算の計上が実機で確認できていること」は、DB の集計では判定できない。運用者が実機の確認の結果を別に記録して判断する。
- 数字の読み方: 「達成」は、その数字が ADR の数値を満たしたという意味だけである。昇格の判断は運用者が行い、段階の設定を変える(コードの変更は伴わない)。この画面は何も変えない。
- 集計は全期間の数である。差し戻し(再投入)でタスクの理由は消えるため、`POLICY_REJECTED` は Run の理由でも数える。Run が消えた場合は、この数に出ない。

### 7.5 dev でオートパイロットを有効化する手順(PR E)

対象は dev だけである。段階 1(`APPROVAL=required`、全タスクを運用者が承認)で始める。段階 2 と 3 は、ADR 0011 §6.3 の昇格条件を満たしてから、別の判断で行う。
**k8s の変更は、この節の手順には含まない。** env の変更は別の PR で行い、ArgoCD で反映する。ここに書く値は秘密ではない。API キー、鍵の内容、トークンは書かず、扱わない。

#### 7.5.1 前提(すべて満たす)

| 項目 | 必要な値 | 欠けたときの結果 |
|---|---|---|
| 自己開発 | `KIVRA_SELFDEV_ENABLED=true`、`KIVRA_SELFDEV_RUNTIME=cli`、`KIVRA_AGENT_API_ENABLED=true`(§1.2.1、§6.9) | オートパイロットの起動が失敗する |
| 認証 | `KIVRA_AUTH_MODE=oidc-jwt`、運用者のロール `admin-panel:admin`(§1.1) | 起動が失敗する |
| エージェントの拒否 | `KIVRA_AGENT_REQUIRED_ROLE=<client>:<role>`(例: `kivra-agent:agent`)。エージェントのクライアントは、運用者のロールを持たせない | 起動が失敗する。ロールが無いと、エージェントのトークンが運用者の API に入れてしまう |
| モデル | `KIVRA_SELFDEV_MODEL_REGISTRY_PATH` の registry に `claude-haiku-5-5` があり、`ACTIVE` であること。Anthropic の認証は §1.1 の設定を使う | registry の読み込みか、モデルの確認で起動が失敗する |
| GitHub | §1.1 の GitHub 認証と `KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID` | 起動が失敗する(既存の自己開発と同じ) |
| 為替 | §1.1 の `KIVRA_FX_USD_JPY`、`KIVRA_FX_AS_OF` | 起動が失敗する(既存の自己開発と同じ) |
| 費用の計上 | CLI 経路の費用が予算 ledger に計上されること(#230、§6.13)。**起動時の検査は無い**(ADR 0011 §5.3 の検査は未実装)。有効化の前に、§6.13 の方法で計上を確かめる | 計上されない費用の上で自動投入が動く |

#### 7.5.2 k8s の env(dev、別 PR で設定する値)

| 名前 | 値 | 備考 |
|---|---|---|
| `KIVRA_SELFDEV_AUTOPILOT_ENABLED` | `true` | 有効化のスイッチ。これだけが既定(無効)を変える |
| `KIVRA_SELFDEV_AUTOPILOT_APPROVAL` | `required` | 段階 1。全タスクに運用者の承認が要る |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_PENDING_PRS` | `3` | 既定。マージ待ちの PR の上限(ただし下の注を読む) |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_CONSECUTIVE_FAILURES` | `3` | 既定。連続失敗でブレーカーが開く |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY` | `5` | 既定。UTC の日付 |
| `KIVRA_SELFDEV_AUTOPILOT_BUDGET_SHARE_PERCENT` | `50` | 既定。月次予算のうち、オートパイロットの割合 |
| `KIVRA_SELFDEV_AUTOPILOT_PLAN_INTERVAL_SECONDS` | `30` | 既定。計画と投入の周期 |
| `KIVRA_SELFDEV_MAX_ACTIVE_RUNS` | `1` | 既定。同時に進行する自己開発 Run の上限 |

**注**: 開いている PR の Run も「進行中」として同時 Run の上限に数えられる(`activeSelfDevRunIds` は PULL_REQUEST を含む)。そのため既定の `MAX_ACTIVE_RUNS=1` では、マージ待ちの PR は常に 1 件までで、`MAX_PENDING_PRS=3` の判定には届かない。**PR の上限は、同時 Run の上限で先に制限される。** PR の上限を効かせるには、同時 Run の上限を上げる必要がある(ADR 0011 §5.2 の「上げない」との関係は未決)。コードは変えていない。`maxPendingPrs` が `maxActiveRuns` より大きいと、起動ログに WARN が 1 行出る。有効な値は起動時に `autopilot limits: maxActiveRuns=... maxPendingPrs=...` の INFO で出る(数値のみ)。

#### 7.5.3 有効化の順序

1. §7.5.1 の前提を確かめる。特に、エージェントのロールと、モデルの registry。
2. §7.5.2 の env を入れた k8s の PR を出し、レビューしてマージする(ArgoCD が反映する)。
3. 反映後、`GET /api/selfdev/autopilot` が 200 で `enabled: true` を返すことを確かめる(無効なら 503)。画面の「開発目標（Goal）」の欄が出ることでも確かめられる。
4. 下の「有効化後の最初の確認」を、Goal 1 件で行う。

#### 7.5.4 有効化後の最初の確認(画面)

1. `/ui` を運用者で開く。「開発目標（Goal）」の欄が出ていること。
2. 目的(1 行)を入れる。小さな docs の変更で足りる(例: `docs/ の用語の表記を揃える`)。背景は任意。「Goal を送る」。Goal は「計画待ち」になる。
3. 30 秒の周期の後、Goal が「計画の承認待ち」になる。Plan の要約と各タスク(対象パス、サイズ、依存、承認が要るもの)を読む。`docs/`、`kivra-*/src/test/`、`README.md` 以外のパスがあれば、承認しない。
4. 「この Plan を承認」を押し、次に、承認するタスクを 1 件ずつ承認する(段階 1 は全件)。
5. 次の周期で、最初のタスクが投入される(Run ができる)。同時に進むのは 1 件だけ。Run は既存の経路で PR まで進む。
6. PR ができたら、人がレビューしてマージする。kivra はマージしない。マージされると、Run は COMPLETED になり、次のタスクが投入される。

**合格の基準**: 最初のタスクの PR ができ、人がマージした後、タスクが COMPLETED になり、次のタスクが 1 件だけ投入される。Goal は、全タスクが COMPLETED になると DONE になる。
Run が FAILED、UNKNOWN、POLICY_REJECTED になったら、§4.1、§4.3、§4.5 で原因を見る。自動では再投入されない(§5.4)。

#### 7.5.5 止め方

| 止め方 | 操作 | 止まるもの | 止まらないもの |
|---|---|---|---|
| 全体の一時停止 | 画面の「自律運転を一時停止」(`POST /api/selfdev/autopilot/pause`) | 新しい計画と、新しい投入 | 進行中の Run(PR まで進む) |
| 有効化の停止 | k8s の `KIVRA_SELFDEV_AUTOPILOT_ENABLED` を `false` にする、または外す(別 PR) | 計画と投入。`/api/selfdev/goals*` は 503 になる | 進行中の Run。自己開発が有効なら、既存のワーカーが進める |
| 自己開発の停止 | `KIVRA_SELFDEV_ENABLED=false`(§4.4) | Run の進行と、すべての投入 | 作成済みの PR(kivra は閉じない) |

#### 7.5.6 ロールバック

- 設定を戻す(`KIVRA_SELFDEV_AUTOPILOT_ENABLED=false`)。コードの変更は要らない。
- DB の行は消さない。V18 と V19 は expand のみで、既存の表は変わらない。Goal の本文は DB に残る(U-9 は未決で、削除の手段は無い)。消す必要がある場合は、別の判断として行う。
- 残った Goal は、状態のまま残る。中止が必要なら、運用者が画面の「中止」で `STOPPED` にする(未投入のタスクは `SKIPPED`)。
- 投入済みの PR は閉じない、マージしない(kivra は PR に触れない)。

#### 7.5.7 有効化の前に知っておく制約

- 起動時に、CLI 経路の費用の計上を検査する(ADR 0011 §5.3)。`KIVRA_AGENT_API_ENABLED=true`、`KIVRA_FX_USD_JPY` と `KIVRA_FX_AS_OF`、`KIVRA_SELFDEV_MODEL_REGISTRY_PATH` のどれかが無ければ、起動を拒否する(拒否の理由には変数名だけが出る)。FX の期限切れは起動を拒否しない。期限切れの間は USD の呼び出しが止まり、既存の失効の警告(`fx rate expired`、ERROR)が出る。
- 既定の同時 Run 上限(1)では、マージ待ちの PR の上限(3)は効かない(§7.5.2 の注)。
- 失敗したタスクは自動では再投入されず、Goal は `RUNNING` のまま残る(ADR に、失敗による Goal の終端は無い)。残りは運用者が Goal を中止して終える。
- サーキットブレーカーが開いて `BLOCKED`(`CIRCUIT_OPEN`)になった Goal は、運用者の再開(`POST /api/selfdev/autopilot/reset-breaker`)の後、次の周期(既定 30 秒)で `RUNNING` に戻り、残りのタスクが投入される。他の理由(計画段階の `PLAN_INVALID` など)で `BLOCKED` の Goal は、この再開では戻らない。

### 7.3 状態遷移

Goal、Plan、タスクの状態と遷移表は、ADR 0011 の §2.5 が正本である。コードの表は `kivra-core/src/main/kotlin/net/kigawa/kivra/core/autopilot/AutopilotStates.kt` にあり、
全組み合わせ(許可と拒否)を `AutopilotStatesTest` が確かめる。表に無い遷移は拒否され、終端の状態(Goal の `DONE` / `STOPPED`、タスクの `COMPLETED` / `SKIPPED`)からは動かない。

ADR から変えた点(PR A での判断、ADR §2.8 に記録):

- 再計画は `PLAN_PROPOSED → GOAL_SUBMITTED` である(ADR の `PLANNING` ではない)。`PLANNING` は claim されている間だけの状態にするため。
- 一時停止からの再開先は、一時停止の直前の状態を `selfdev_goals.resume_state` に保存して決める。
- 計画段階の `BLOCKED` の理由は `PLAN_INVALID` / `PLAN_BUDGET_HOLD` である。ディスパッチ段階の `BUDGET_HOLD` とは分ける。計画段階で止まった Goal は、今の表では `STOPPED` 以外へ進めない(未決の点 U-16)。
- 全タスクが終わって全部は完了しなかった Goal は `STOPPED`(理由 `TASKS_INCOMPLETE`)になる(PR E。ADR §2.8b)。`STOPPED` / `DONE` の終端からは動かない。投入の直前に、Goal が `TASKS_QUEUED` / `RUNNING` でなければ投入しない(`GOAL_NOT_DISPATCHABLE`)。

### 7.6 自己開発の order(ADR 0015 段階 1、Issue #273、既定は無効)

order は、Goal を 1 周ずつ回す長く生きる指示である。段階 1 は**ルートの order 1 件**だけで、子の order、周の自動の開始、複数周はまだ無い(最大 1 周。周は運用者が開始する)。操作は HTTP、MCP(§8.8)、`/ui` の画面(§7.6.7)である。

#### 7.6.1 有効化の前提(起動時に検査される)

| 変数 | 必須 | 内容 |
|---|---|---|
| `KIVRA_SELFDEV_ORDERS_ENABLED` | ○ | `true` で有効。`false` か未設定で無効(既定)。`true` / `false` 以外は起動に失敗する。オートパイロットが無効のときは、この変数も他の order の変数も読まれず、order は無効(ルートも作られず、ルートの API は 404)。 |
| `KIVRA_SELFDEV_AUTOPILOT_ENABLED=true` | ○(有効時) | order の周は、オートパイロットの Planner と Dispatcher が回す。オートパイロットが無効なら起動に失敗する。 |
| `KIVRA_SELFDEV_ROOT_APPROVER` | ○(有効時) | ルートの停止を承認できる唯一の主体の owner key(`<長さ>:<issuer>:<sub>`)。kigawa の値を入れる。コードには固定しない。ログには出さない。 |
| `KIVRA_SELFDEV_AI_PRINCIPAL` | 任意 | AI の主体の owner key(AI 専用の Keycloak ユーザー。ADR 0015 の U-28)。停止の要求と読み取りだけ許す。承認者と同じ値は起動に失敗する。order が無効のときも読まれ、Goal の書き込みと autopilot の制御(pause、resume、reset-breaker)を拒否する。形式が不正なら order の有無にかかわらず起動に失敗する。 |

有効化の順序: dev で有効化し、E2E を確かめる。有効化の後の起動では、既存の `ACTIVE` の order はすべて `PAUSED`(理由 `STARTUP_HOLD`)になる(U-14)。運用者が再開するまで、周は始まらない。

#### 7.6.2 ルートの作成と周の開始(運用者)

- 作成: `POST /api/selfdev/orders` `{"title": "...", "policy": "..."}`。ルートは 1 件だけ(2 件目は 409)。`policy` は方針の文で、作成後は変えられない。AI の主体は作成できない(403)。
- 周の開始: `POST /api/selfdev/orders/{id}/cycles` `{"version": n}`。Goal が 1 件作られ(`order_id` と `cycle_no` = 1)、既存の Planner と Plan の承認に進む。進行中の周があるとき、上限(既定 1 周)に達したとき、期限切れのとき、全体の一時停止中のときは 409。
- 状況: `GET /api/selfdev/orders/{id}`(状態、理由、周の数、保留中の停止の要求。方針と題名は運用者だけに返る)、`GET /api/selfdev/orders/{id}/cycles`。一覧 `GET /api/selfdev/orders` には題名と方針を含めない。

#### 7.6.2b 子の作成(段階 2 の PR 3a。既定では作れない)

- 作成: `POST /api/selfdev/orders/{id}/children` `{"title": "...", "policy": "...", "orderBudgetJpy": n, "idempotencyKey": "..."}`(運用者だけ。AI の主体は、入力を読む前に 403)。題名と方針の検証はルートと同じで、双方向制御文字の拒否を含む(ルートの方針も同じ規則になった)。
  - 201 で作成する。同じキーと同じ内容の再送は 200 で同じ子を返し、割当ては二重にならない。同じキーで内容が違えば 409 `KEY_CONFLICT`。
  - 子の対象リポジトリは親から引き継ぐ。子の期限は作成から 14 日だが、親の期限を超えない。
- 上限の環境変数(オートパイロットと order が有効のときだけ読まれる。範囲外や整数でない値は起動に失敗し、値は表示されない):

| 変数 | 既定 | 範囲 | 内容 |
|---|---|---|---|
| `KIVRA_SELFDEV_ORDER_MAX_DEPTH` | 0 | 0〜2 | 子の深さの上限。0 では子を作れない(段階 2 のロールバック) |
| `KIVRA_SELFDEV_ORDER_MAX_CHILDREN` | 5 | 1〜20 | 1 つの親の子の数 |
| `KIVRA_SELFDEV_ORDER_MAX_TREE` | 15 | 1〜100 | 木 1 本の order の数(ルートを含む) |

- 既定(深さ 0)では、子の作成は 409 `CHILDREN_DISABLED`。上限を上げるのは運用者の承認の後(k8s の PR)。
- 拒否(409。固定コード。何も書かれない): `PARENT_NOT_ACTIVE`(親が無い、親か祖先が `ACTIVE` でない、親の期限切れ)、`DEPTH_LIMIT`、`CHILD_LIMIT`、`TREE_LIMIT`、`BUDGET_EXCEEDS_PARENT`。
- 予算の入れ子: 子の `orderBudgetJpy` は、親の未割当て(`orderBudgetJpy` − `allocatedJpy`)以下でなければならない。作成のたびに、親の `allocatedJpy` と `childCount` が増える。親の行を `FOR UPDATE` でロックしてから検査するので、同時に作っても割当ての合計は親の予算を超えない。木の大きさの検査も直列化する(ルートの行を先に、親の行をその後にロックする)。
- 親は作成時に決まり、変えられない(付け替えの API は無い)。循環は起きない。
- 監査: `selfdev_order_events` に `CHILD_CREATED` を書く(子の ID、親の ID、深さ、割当額、主体の SHA-256 の先頭 12 文字)。題名と方針は入らない。
- 読み取り: 一覧と詳細に `orderBudgetJpy`、`allocatedJpy`、`childCount` が出る(`parentOrderId` と `depth` は従来どおり)。題名と方針は、従来どおり詳細だけ。MCP の `kivra_order_list` / `kivra_order_get` は、従来どおり親の ID と深さを返す。
- **注意(PR 3a の時点の制限。PR 3b の周の開始の検査は §7.6.2c)**: 子の費用は、まだ親の `spent_jpy` に加算されない(費用の自動集計は後続の PR)。そのため、子の予算は**割当ての上限としてだけ**働き、子の周の費用は数えられない。既存の `POST .../cycles` は子の order にも使える。

この PR に含まれないもの(後続の PR): 子の停止、部分木の停止、親の自動の決定(§4.4)、子の費用の自動集計、`/ui` の変更。祖先の状態による周の開始と再開の検査は PR 3c で入った(§7.6.2d)。

#### 7.6.2c 周の開始前の予算の検査(段階 2 の PR 3b。`spent_jpy` はまだ増えない)

- 周を始める(`POST .../cycles`)とき、経路上の全ての order(自分からルートまで)で、`order_budget_jpy − spent_jpy >= cycle_budget_jpy` を確かめる。どれか 1 つでも足りなければ、409 `ORDER_BUDGET` で、何も書かない(`cycles_started` も進まない)。応答の `orderId` は、足りなかった order の ID だけである(題名と方針は含まない)。拒否は監査に記録しない(既存の拒否と同じ)。
- 列(V26): `spent_jpy`(その order と子孫の費用の累計。既定 0)、`cycle_budget_jpy`(1 周の費用の上限。既定 1,000 円)。どちらも `DECIMAL(12,0)`。ADR 0015 §2.1 の表は `cycle_budget_jpy` を `DECIMAL(10,0)` と書くが、他の予算の列と揃えて `DECIMAL(12,0)` にした。
- 子の作成: `orderBudgetJpy` が 1,000 円未満なら 400(1 周の上限を下回る子は、周を一度も始められないため)。子の `cycle_budget_jpy` は既定の 1,000 円で、作成の API からは指定できない。
- 読み取り: 一覧と詳細に `spentJpy`、`cycleBudgetJpy`、`remainingJpy`(= `orderBudgetJpy − spentJpy`)が出る。題名と方針は従来どおり詳細だけ。
- ロック: 周の開始は、経路の行をルートから順に `FOR UPDATE` する(子の作成と同じ順序)。同じ木で並行に周を始めても、デッドロックせず直列になる。
- **限界(必ず読むこと)**: `spent_jpy` を増やす経路は、この PR には無い。費用の自動集計(台帳からの算出)は後続の PR。運用者が `spent_jpy` を手で書き換える API も、費用の計上を手で書き換える穴になるため、作らない。そのため、この PR の時点では `spent_jpy` は 0 のままで、**予算の検査は実質的に働かない**。検査が意味を持つのは、後続の PR で `spent_jpy` が増えるようになってからである。
- dev で子を有効にする前の注意: 子の周の費用は、後続の PR までは数えられない。子を有効にする(`KIVRA_SELFDEV_ORDER_MAX_DEPTH` を 1 以上にする)のは後続の PR の後にする。それまでは、子の周を人が手で始めないこと(`POST .../cycles` は子にも使える)。

#### 7.6.2d 周の開始と再開の有効な状態(段階 2 の PR 3c)

- **有効な状態**(ADR 0015 §2.4): order が周を始めてよいのは、自分と、経路上の全祖先(ルートまで)が `ACTIVE` のときだけである。祖先のどれかが `PAUSED`、`STOP_REQUESTED`、`STOPPED` なら、子孫は周を始められない。子の行は書き換えない。祖先が再開されると、子は自分の状態に戻る。
- **周の開始**(`POST .../cycles`): 自分の状態の検査の後に、全祖先を検査する。どれかが `ACTIVE` でなければ 409 `ANCESTOR_NOT_ACTIVE` で、何も書かない(`cycles_started` も、目標も作らない)。応答の `orderId` は、止めている最も近い祖先の ID だけである(題名、方針、理由の文は含まない)。
- **再開**(`POST .../resume`): 自分を `PAUSED` から `ACTIVE` に戻す前に、同じ祖先の検査をする。止まっていれば 409 `ANCESTOR_NOT_ACTIVE`(`orderId` は同じ)で、子の行は書き換えない。ルートの再開は従来どおりである(祖先が無い)。
- **子の一時停止**(`POST .../pause`)は従来どおり。自分の状態だけを `PAUSED` にし、祖先の状態も version も変えない。祖先が止まっている子に対する一時停止も許す。**子の `PAUSED` は親に伝わらない**(§2.7)。子の一時停止が親の周を止めることは無い。
- **共通の検査**: 周の開始と、子の作成の「親の有効な状態」の検査は、同じ経路の祖先の検査を使う。子の作成の `PARENT_NOT_ACTIVE` は、固定コードも挙動も変えない。
- **ロック**: 周の開始と再開は、経路の行をルートから順に `FOR UPDATE` する(子の作成と同じ順序)。子の作成も、同じ経路を同じ順序でロックする。親の一時停止は、親の行だけをロックする。並行に親を一時停止しても、デッドロックせず、どちらかが先に決まる。
- **安全装置(`OrderWatch`)**: ACTIVE の order を、自分の状態だけで検査する。祖先の状態に依存しないので、この PR では変えない。
- **読み取り**: 一覧と詳細に `effectiveState` と `blockedBy` は足さない(MCP の読み取りも変えない)。祖先が周を止めているかは、祖先の `state` を読んで確かめる。
- **注意**: この PR より前は、祖先が `PAUSED` や `STOP_REQUESTED` でも、子の周が始まった(ADR §2.4 に反していた)。この PR の後は始まらない。ただし、この PR より前に始まった周は、そのまま進む。
- この PR に含まれないもの: 子の停止、部分木の停止(親の停止に伴う子孫の `STOPPED`)、親の自動の決定(§4.4)、子の費用の自動集計(`spent_jpy` への加算)、`/ui` の変更、MCP の読み取りの変更。

#### 7.6.2e 子の停止と部分木の停止(段階 2 の PR 3d)

- **決定者**: 子の停止は、親の決定が承認を兼ねる(ADR 0015 §4.2 (a))。この PR で決定できるのは**人(ルートの承認者。`KIVRA_SELFDEV_ROOT_APPROVER`)だけ**で、対象の直接の親の名で決める。`decided_by` はその owner key、`decider_kind` は `HUMAN`。決めた親の ID は、`STOP_APPROVED` と `STOPPED` の監査の行の `parent_order_id` に残る。親 order の自動の決定(`decider_kind = ORDER`、§4.4)は PR 3e で入る。この PR には含まれない。
- **要求**: `POST /api/selfdev/orders/{id}/stop-requests` は、ルートでも子でも同じ。運用者と AI の主体が要求できる。応答には `targetIsRoot`(ルートなら `true`、子なら `false`)が出る。MCP の `kivra_order_request_stop` も同じ経路を使うが、MCP の応答は変えていない。
- **要求者と決定者**: ルートの停止は、要求者と承認者が別の主体でなければならない(アプリの検査と、DB の CHECK `ck_selfdev_order_stop_separation` の両方)。子の停止は、親の決定が承認を兼ねるため、同じ主体でも決められる。
- **AI の主体**: 子の停止を要求できる。決定(承認、却下)は、入力を読む前に 403。
- **承認の検査**: 対象が `STOP_REQUESTED`、要求が `PENDING` で期限内、`digest` が一致、対象と子孫の部分木の版が要求時と一致する。どれかが違えば、何も書かずに拒否する(版の変化は 409 `ORDER_CHANGED`)。部分木の外の変化(兄弟など)は、承認を拒否しない。
- **部分木の停止**: 承認と同じ transaction で、対象を `STOPPED` にする(子は `PARENT_STOPPED`、ルートは `OPERATOR_STOPPED`)。対象の子孫は全て `STOPPED`(`ANCESTOR_STOPPED`)になる。兄弟と親は変わらない。周の開始の検査は、対象の自分の状態を見るので、孫が `ACTIVE` のまま残ることは無い(孤児の禁止、ADR 0015 §2.7)。これまでルートに子がいるとき、ルートの停止は子を `ACTIVE` のまま残していた。この PR で、子孫も止まるように直した。
- **digest**: `subtree` は、対象と子孫の `order_id` と `version` の組を ID 順に並べたもの。子の無い対象(既存のルートの要求)の正規の JSON は、この PR の前と同じである。子のある木の要求の digest は変わる。この PR の前に出された、子のある木のルートの未決の要求は、承認で 409 `ORDER_CHANGED` になる(安全側。要求し直す)。
- **ロック**: 要求と承認は、経路をルートから順に、続けて子孫を階層ごとに ID 順に `FOR UPDATE` する(周の開始と子の作成と同じ順序)。同じ木で、子の停止の承認と別の子の周の開始が並行しても、デッドロックしない(テストで確認)。
- **監査**: `STOP_APPROVED` と `STOPPED` の行に `parent_order_id` と `depth` を入れる(子の行。ルートの行は従来どおり NULL)。停止の理由コードと、主体の SHA-256 の先頭 12 文字だけで、題名と方針は入れない。
- **DB(V27)**: `selfdev_order_stop_requests.target_is_root TINYINT NOT NULL DEFAULT 1`(既存の行は全てルートの要求)。分離の CHECK は `target_is_root = 0 OR decided_by IS NULL OR decided_by <> requested_by` に置き換えた。ルートの要求では、アプリを迂回した SQL でも同じ主体による承認を拒否する。`ck_selfdev_order_stop_decider` は `HUMAN` のままである。
- **この PR に含まれないもの**: 親の自動の決定(§4.4、PR 3e)、子の費用の自動集計(`spent_jpy` への加算)、`/ui` の変更、MCP の応答の変更(`targetIsRoot` は MCP に出さない)。

#### 7.6.2f 親の自動の停止(段階 2 の PR 3e。既定では無効)

- **範囲**: 親 order の自動のループ(`OrderWatch` の tick)が、直接の子を止められる。理由コードは `CHILD_CYCLE_FAILURES`(子の `consecutive_failed_cycles` が 2 以上)。`CHILD_BUDGET_EXHAUSTED` は §7.6.2j で加わった(ADR 0015 §4.4)。HTTP の経路は無く、MCP にも無い。ルートは対象にならない。
- **条件(すべて満たすときだけ)**: 対象は親の直接の子で、`ACTIVE` か `PAUSED`。親と全祖先が `ACTIVE`(有効な状態)。失敗が 2 回続いている。木に `PENDING` の停止要求が無い(人の要求を横取りしない)。頻度の上限の内。
- **頻度の上限(UTC の日)**: 同じ親が決める子の停止は 1 日 1 件。木全体で 1 日 2 件。超えたら決めず、`selfdev_order_events` に `AUTO_STOP_RATE_LIMITED` を残す(子ごとに 1 日 1 回。tick が毎分回っても増えない)。ログは `order auto-stop: code=AUTO_STOP_RATE_LIMITED orderId=... parentId=...`。数えるのは `decider_kind = ORDER` の行の `decided_at`。
- **決定の行**: `selfdev_order_stop_requests` に `state = CONSUMED`、`decider_kind = ORDER`、`decider_order_id` = 決めた親、`requested_by` と `decided_by` は固定の `system:order-auto-stop`、`requester_kind = SYSTEM`、`target_is_root = 0`。要求の段階は無い(要求なしで決める)。
- **停止の内容**: 子は `STOPPED`(`PARENT_STOPPED`)、子孫は `STOPPED`(`ANCESTOR_STOPPED`。既に `STOPPED` の子孫は変えない)。その後、部分木の各 order の周の Goal を、人の承認の場合と同じ既存の停止で止める(`stopCycleGoalOf`)。進行中の Run と PR は、§4.5 のとおり既存の経路で扱う。
- **監査**: 子の `STOP_APPROVED` と `STOPPED` の行は、`actor_kind = SYSTEM`、`actor_ref` は空、`reason_code = CHILD_CYCLE_FAILURES`、`parent_order_id` は決めた親、`depth` を持つ。題名と方針は入らない。ログは `order auto-stop: code=CHILD_CYCLE_FAILURES orderId=... parentId=...`。
- **ルートの保証(DB)**: V28 の CHECK `ck_selfdev_order_stop_auto_root`(`decider_kind IS NULL OR decider_kind = 'HUMAN' OR target_is_root = 0`)により、ルートの行に `ORDER` の決定は入らない。アプリの検査(`parent_order_id IS NULL` は対象外)と二重になっている。ルートの停止の承認は、従来どおり人だけが行い、要求者と承認者は別の主体でなければならない(V23 と V27 の分離の CHECK)。
- **スイッチ**: `KIVRA_SELFDEV_ORDER_AUTO_STOP_ENABLED`。既定は `false`。`true` と `false` 以外は起動に失敗し、値は表示されない。order が無効のときは読まれない。`false` のときは、tick の step が何もしない。
- **この PR に含まれないもの**: 理由コード `CHILD_BUDGET_EXHAUSTED`、`CHILD_DUPLICATE_PLAN`、`CHILD_SCOPE_VIOLATION`。これらは、事実の仕組み(費用の自動集計、重複の検出、`scope_profile`)がまだ無いため、列挙にも入れていない。人の判断の経路の変更。
- **dev で有効にする前の注意**: 有効化は運用者の承認の後(k8s の PR)。子は `KIVRA_SELFDEV_ORDER_MAX_DEPTH` が 1 以上のときだけ作れるため、既定では自動の停止は起きない。子の周の費用はまだ集計されない(§7.6.2c)。自動の根拠は周の失敗の回数だけである。停止は終端であり、取り消すには人が新しい子を作る。

#### 7.6.2g 周の費用の記録表と台帳からの読み取り(段階 3f。挙動は変えない)

- 費用の自動集計(ADR 0015 追補 2026-10-23)の段階 3f。**この PR は挙動を変えない**。`spent_jpy` は書かず、`startCycle` の検査は変えず、`OrderWatch` に段は足さない。周の費用は、まだ order に加算されない。
- 追補の「V28」は、V28 が PR 3e(`selfdev_order_auto_stop`)で使われたため、**実装は V29** である。ADR 本文は変更していない。
- V29 `selfdev_order_cycle_spend`(expand のみ。表は空)。主キー `(order_id, cycle_no)`、列 `goal_id`、`ledger_micro_yen`、`counted_jpy`、`final`、`updated_at`。書く経路は次の段階(3g)で作る。
- 読み取り `SelfDevCycleSpendReader.read(orderId, cycleNo)`(`SELECT` のみ。ロックなし)。周の Goal に結ばれる台帳の行を、状態ごとに集計する。CLI は `coding:<attemptId>:<gen>` の完全一致、Planner は `planner-<goalId>-`、API の Developer は `developer-<runId>-` の前方一致(追補 C.5、C.6)。返す値は、確定(COMMITTED と ACKNOWLEDGED)、LAPSED、予約中(RESERVED)、UNPRICED の額、未確認の UNPRICED の有無、進行中の attempt 数。
- テスト: キーの 3 形式の契約(`SelfDevCycleSpendContractTest`)。ロック順序(`SelfDevBudgetLockOrderTest`。`DbBudgetStore` は order の表を参照しない。読み取りは書き込みも行ロックもしない)。
- 次の段階: 3g(tick での差分の加算。`spent_jpy` と `counted_jpy` の更新、`final` の判定)、3h(`startCycle` の式の実効化、未確認の UNPRICED の拒否、`CHILD_BUDGET_EXHAUSTED` の事実)。

#### 7.6.2h 周の費用の自動集計(段階 3g。既定では無効)

- **範囲**: 監視(tick)の新しい段 `order spend`(周の評価の後)が、周ごとの費用を台帳から読み、差分を経路上の order の `spent_jpy` に加える。ADR 0015 追補の §F の 3g。`startCycle` の式は変えない(3h)。
- **スイッチ**: `KIVRA_SELFDEV_ORDER_SPEND_ENABLED`。既定は `false`。`true` と `false` 以外は起動に失敗し、値は表示されない。order が無効のときは読まれない。`false` のときは段が何もしない。**dev で有効にするのは運用者の承認の後**(k8s の PR)。
- **対象**: `selfdev_order_cycle_spend.final = FALSE` の周と、表の行がまだ無く `selfdev_goals.order_id` を持つ周(初回の計上)。order の状態では絞らない。停止された order の周も、`final` になるまで計上する。1 回の tick で最大 50 件(`BATCH`)。
- **周ごとの処理(1 transaction)**: 経路を根から下へロック(`lockPath`)→ 表の行を `FOR UPDATE`(無ければ作る)→ 台帳を読み直す → `counted = ceil(合計マイクロ円 / 1,000,000)`(周ごとに 1 回だけ切り上げ)→ `delta = counted - 前の counted`。`delta` が 0 でなければ、経路上の全 order の `spent_jpy` に `delta` を足す(0 を下回らない)。テナントの budget 行は読むだけで、ロックしない。
- **合計に含む状態**: `COMMITTED`、`ACKNOWLEDGED`、`LAPSED`、`RESERVED`、`UNPRICED`(安全側で見積もりの額)。`RELEASED` は含めない。追補 §B と §C.2 は `RESERVED` を含めていないが、本段階は含める(予約の解放で合計が減る経路を持たせるため。ADR 追補 J.1 で、この差を訂正した)。
- **冪等**: 加算は「前に計上した額との差」。同じ台帳の値で何度 tick しても `spent_jpy` は増えない。台帳の合計が減ると(予約の解放など)`spent_jpy` も減る。
- **`final`**: 周の Goal が `DONE` か `STOPPED`、その周の attempt が全て非活性、`RESERVED` と `LAPSED` の**行**が無い、未確認の `UNPRICED` が無い、のすべてを満たすとき。判定は金額でなく行の有無で行う(ADR 0015 追補 K.2。3g' で変更)。金額が 0 円の `RESERVED`/`LAPSED` の行も `final` を妨げる。その行は後で true-up により金額を持ちうるため、早く `final` にすると、以後の再計数の対象から外れて過少計上になるためである。`final` の周は以後の tick で対象にしない(行も書かない)。
- **0 円の行の扱い**: 0 円の `RESERVED`/`LAPSED` の行がある周は、金額が 0 でも `final` にならず、tick で再計数される。行が金額を持ったときは `spent_jpy` に加算される。0 円の行が無い周の挙動は変わらない。
- **`spent_jpy` の基礎(現状。案 A)**: `spent_jpy` は、周の「確定 + `LAPSED` + `UNPRICED` + 予約中(前回の tick の時点の `RESERVED`)」の合計である。予約中の額は、tick の間に生じた予約を含まない(最大 `INTERVAL` の 60 秒のずれ。U-40)。
- **同時実行**: 同じ木の処理は、根の行ロックで直列化される。ロックの順序は order の行(根から下)→ 表の行 で、budget の行には触らない(`SelfDevBudgetLockOrderTest` で固定)。
- **監査**: `delta` が 0 でないとき、周の order に `SPEND_COUNTED` を 1 件(`amount_jpy` = `delta` の円、`reason_code` = `FINAL` は `final` のときだけ)。題名、方針、台帳のキー、パスは入らない。`delta` が 0 のときは書かない。
- **失敗**: 周ごとに隔離される。失敗した周は飛ばし、他の周と tick は続く。ログは `order spend: failed code=SPEND_FAILED orderId=... exception=...`(例外の文言は出さない)。
- **見えるようになるもの**: 周の費用が `spent_jpy` に載る(`GET` の一覧と詳細の `spentJpy` にも出る)。3b の周の開始の検査(`order_budget_jpy - spent_jpy >= cycle_budget_jpy`)が**実効化される**。費用が予算に届いた order は、次の周を `ORDER_BUDGET` で始められない。
- **見えないもの**: CLI の実行中の額は、報告されるまで台帳に無いため、`spent_jpy` にも無い(U-37)。
- **dev で有効にする前の注意**: `spent_jpy` は台帳から導く値である。手で書き換えない(次の tick が台帳の値に合わせ直す)。有効化すると `spent_jpy` が実際に増えるため、既存の order の周の開始が `ORDER_BUDGET` で止まりうる。
- **この PR に含まれないもの(3h)**: `startCycle` の式(進行中の予約を足す)、未確認の UNPRICED の拒否(`ORDER_UNPRICED`)、`CHILD_BUDGET_EXHAUSTED` の事実。3h で予約を足すときは、`RESERVED` が既に `spent_jpy` に入っているため、二重に数えないこと。

#### 7.6.2i 未確認の UNPRICED による周の開始の拒否(段階 3h-A。既定では無効)

- 周を始める(`POST .../cycles`)とき、経路上の order(自分と祖先)のどれかに、**未 final の周**で**未確認の `UNPRICED` 行**(0 円を含む)があれば、409 `ORDER_UNPRICED` で、何も書かない(`cycles_started` も進まない)。応答の `orderId` は付かない。`ORDER_BUDGET` の検査の後に行う。
- 判定の対象は、費用の行(`selfdev_order_cycle_spend`)がある周だけである。行は、スイッチ `KIVRA_SELFDEV_ORDER_SPEND_ENABLED` が有効なときの tick が作る。無効のときは行が無いため、この検査は何も拒否しない(挙動は変わらない)。
- 解除: 運用者が台帳の `UNPRICED` 行を確認する(`POST /api/admin/budget/acknowledge`)。次の tick で周が final になると、開始できる。order の状態は変わらないため、再開の操作は要らない。承認は要らない。
- 拒否の文言は固定で、題名や方針は含まない。読み取りだけで、ロックの順序(order → budget)は変えない。
- 範囲: 自分と祖先の周だけを見る。子孫の未確認の周は、この order の周の開始を止めない(ADR 0015 追補の U-44 は未決)。

#### 7.6.2j 親の自動の停止の理由 CHILD_BUDGET_EXHAUSTED(段階 3e の残り。既定では無効)

- **範囲**: 親の自動のループ(`OrderWatch` の tick)が、直接の子を理由 `CHILD_BUDGET_EXHAUSTED` で止められる。スイッチは §7.6.2f と同じ `KIVRA_SELFDEV_ORDER_AUTO_STOP_ENABLED`(既定 `false`)。同じ経路と同じ上限(親 1 日 1 件、木 1 日 2 件。理由ごとに上限を分けない)。ルートは対象にならない。HTTP にも MCP にも経路は無い。
- **条件**(ADR 0015 §4.4 の 2、追補 K.1、U-41)。子 X について次がすべてそろうとき。(1) X の `spent_jpy` が `order_budget_jpy` 以上。(2) X の部分木(X と子孫)のすべての周の `selfdev_order_cycle_spend.final` が `TRUE`、かつ、部分木に周の行の無い goal が無い。(3) X の部分木に非終端の Run が無い。Run が非終端とは、`development_runs.state` が `COMPLETED`、`FAILED`、`CANCELLED` 以外であること、または、その Run に `coding_attempts.active_flag = 1` の attempt があること。
- **決定の検査**: 決定は木のロック(`lockPath` の後の `lockDescendants`)の下で、上の条件をもう一度確かめる。条件が成り立たないときは何も書かない(理由 `BUDGET_NOT_EXHAUSTED`)。事実を読むだけの関数は `SelfDevOrderRepository.budgetExhaustedFacts(orderId)` にある(書き込みもロックもしない)。
- **spent_jpy の意味**: §7.6.2h の案 A のとおり、予約中(`RESERVED`)の額を含む。予約が確定し、周が `final` になるまで、この理由は成立しない。
- **3g のスイッチとの関係**: `KIVRA_SELFDEV_ORDER_SPEND_ENABLED` が `false` のときは周の行が作られず、(2) が成立しない。したがって、この理由は決して成立しない(安全側)。3g を無効のまま、この理由の自動の停止だけを有効にしても、何も止まらない。
- **監査**: 停止の要求と、子の `STOP_APPROVED` と `STOPPED` の行は、`reason_code = CHILD_BUDGET_EXHAUSTED`、`actor_kind = SYSTEM`、`actor_ref` は空、`parent_order_id` は決めた親、`depth` を持つ。ログは `order auto-stop: code=CHILD_BUDGET_EXHAUSTED orderId=... parentId=...`。題名、方針、台帳のキーは入らない。
- **要求には使えない**: `CHILD_BUDGET_EXHAUSTED` は人や AI の停止の要求の理由に使えない(要求の API と MCP は拒む)。DB の移行は無い(`reason_code` に CHECK が無い)。
- **運用上の注意**:
  - 停止は終端である。取り消すには人が新しい子を作る(§7.6.2e と同じ)。
  - **周を始めていない子**: 子の作成(HTTP)は予算を 1000 円以上に限る(`BUDGET_BELOW_CYCLE`)。周を一度も始めていない子は `spent_jpy` = 0 < 予算なので、(1) を満たさず、止まらない。
  - 止めるのは、周が `final` になってからである。周の終わりから `final` までの遅れ(tick 1 回分、最大 60 秒など)の分だけ、止めるのが遅れる。遅れは安全側である。
  - 誤判定の件数は、停止の監査(`reason_code = CHILD_BUDGET_EXHAUSTED`)で数える。1 件でも誤りと判明したら、有効化を外し、条件を見直す(ADR 0015 §13)。

#### 7.6.3 状態

| 状態 | 意味 | 解除 |
|---|---|---|
| `ACTIVE` | 周を続けてよい | — |
| `PAUSED` | 周を始めない。理由コードを持つ | 運用者の再開(`POST .../resume`)。ただし `LIMIT_REACHED` と `EXPIRED` は再開できない(新しい order が要る) |
| `STOP_REQUESTED` | 停止を要求中。周を始めない。承認を待つ | 承認(`STOPPED`)、却下、取り消し、期限切れ(24 時間。`PAUSED` に戻る) |
| `STOPPED` | 終端。承認でだけ入る | なし |

理由コードの例: `OPERATOR_PAUSED`(運用者の一時停止)、`STARTUP_HOLD`、`BUDGET_HOLD`(月次の割当ての枯渇)、`FX_EXPIRED`(為替の期限切れ)、`UNKNOWN_RUN`(周の Run が UNKNOWN)、`CYCLE_FAILURES`(周が 2 回続けて失敗)、`LIMIT_REACHED`、`EXPIRED`、`STOP_NOT_APPROVED`。一時停止の理由は、この固定コードだけである(自由な文は保存しない)。

#### 7.6.4 止め方

- **一時停止(承認は不要)**: `POST /api/selfdev/orders/{id}/pause` `{"version": n}`(運用者)。周だけが止まり、order は生きている。緊急時はこちら。全体の止め方は §7.4 の `POST /api/selfdev/autopilot/pause` も使える(周の開始と投入を止める。order の状態は変えない)。
- **安全装置の一時停止(承認は不要、自動)**: 監視(約 60 秒ごと)が、`BUDGET_HOLD`、`FX_EXPIRED`、`UNKNOWN_RUN`、`CYCLE_FAILURES`、`LIMIT_REACHED`、`EXPIRED` を検出すると、`ACTIVE` の order を `PAUSED` にする。自動では再開しない。原因を確かめてから、運用者が `resume` する。
- **停止(承認が要る)**: 次の手順で、ルートの承認者が承認する。
  1. 要求: `POST /api/selfdev/orders/{id}/stop-requests` `{"version": n, "reasonCode": "..."}`(MCP の `kivra_order_request_stop` も同じ規則。§8.8)。理由は固定の列挙だけ(`OPERATOR_REQUEST`、`RUNAWAY_SUSPECTED`、`BUDGET_CONCERN`、`SCOPE_CONCERN`)。自由な文は受け付けない。要求の時点で order は `STOP_REQUESTED` になり、周は始まらない。木に未決の要求は 1 件まで。
  2. 承認者が一覧を見る: `GET /api/selfdev/stop-requests?state=PENDING`(承認者だけ。`digest` を含む)。
  3. 承認: `POST /api/selfdev/stop-requests/{id}/approve` `{"digest": "<一覧の digest>"}`。承認者は、ルートの停止では要求した主体と同じ owner key では承認できない(409 ではなく 403)。子の停止は親の決定が承認を兼ねるため、同じ主体でも決められる(§7.6.2e)。`digest` が違えば 409。承認すると order は `STOPPED`、要求は `CONSUMED`。その周の Goal も既存の停止で止まる。投入済みの Run と PR には変更が無い(kivra は PR を閉じず、マージもしない)。
  4. 却下: `POST /api/selfdev/stop-requests/{id}/reject`(承認者)。取り消し: `POST /api/selfdev/stop-requests/{id}/cancel`(要求した運用者か承認者。AI の主体は取り消せない)。どちらも order は `PAUSED`(`STOP_NOT_APPROVED`)に戻り、自動では再開しない。
  5. 期限切れ: 要求から 24 時間で `EXPIRED`、order は `PAUSED`(`STOP_NOT_APPROVED`)。承認は期限切れの後は受け付けない。

AI の主体ができるのは、読み取りと停止の要求だけである。作成、周の開始、一時停止、再開、承認、却下、取り消しは、AI の主体には 403 を返す。

#### 7.6.5 監査とログ

- 監査: `selfdev_order_events`(追記専用)。ID、種類、理由コード、状態、主体の種類(`HUMAN` / `AI` / `SYSTEM`)、owner key の SHA-256 の先頭 12 文字(`actor_ref`)だけを持つ。題名、方針、理由の本文は入らない。
- ログ: 固定のコード、ID、operator の先頭のハッシュだけ(`net.kigawa.kivra.selfdev.order.operator`)。
- 確認: `SELECT kind, from_state, to_state, reason_code, actor_kind, occurred_at FROM selfdev_order_events WHERE order_id = '...' ORDER BY occurred_at;`

#### 7.6.6 段階 1 の範囲外

周の自動の開始、複数周は、後の段階である(ADR 0015 §7)。親の自動の停止は §7.6.2f(理由コードは `CHILD_CYCLE_FAILURES` だけ)。子の停止は段階 2 の PR 3d で入った(§7.6.2e)。子の作成は段階 2 の PR 3a で入った(§7.6.2b。既定では作れない)。MCP の読み取りと停止の要求は PR 2a で入った(§8.8)。子の費用の加算(`spent_jpy`)は段階 3g(tick での差分の加算。§7.6.2g の後続)であり、PR 3b は周の開始の検査だけである(§7.6.2c)。

#### 7.6.7 `/ui` の画面(PR 2b)

- **一覧**: オートパイロットが有効で `GET /api/selfdev/orders` が 200 のときだけ、「order の一覧（ADR 0015）」を出す。状態、周の数、連続して失敗した周、期限を見られる。題名と方針は表示しない。「詳細」で、停止の要求の有無を見られる。
- **一時停止と再開**: 詳細の「一時停止」は `ACTIVE` のときだけ、「再開」は `PAUSED` で理由が `LIMIT_REACHED` と `EXPIRED` 以外のときだけ出す。
- **停止の承認**: `GET /api/selfdev/stop-requests?state=PENDING` が 200 の主体(承認者)だけに、「停止の承認（承認者だけ）」を出す。要求の対象、理由、要求した主体(AI の主体か人)、要求の時刻、期限を表示する。
  - 「承認」は確認のダイアログ(「この order を停止します。元に戻せません。」)を挟んでから送る。送る本文は、一覧で読んだ `digest` だけである。`digest` は画面に表示しない。
  - 「却下」は理由を取らず、空の JSON(`{}`)を送る。どちらも、結果は固定の文で出る。
- **表示の規則**: 理由とコードは、`app.js` の固定の対応表で日本語にする(order の状態と理由、停止の理由 `OPERATOR_REQUEST`、`RUNAWAY_SUSPECTED`、`BUDGET_CONCERN`、`SCOPE_CONCERN`)。対応表にないコードは「不明（コード）」と、コードの文字列だけを出す。API の応答の文字列(題名、方針、要求者の ID)は、どの節にも出さない。
- **実機の確認(運用者)**: 承認者で `/ui` を開き、承認の節が出ること、承認の確認ダイアログが出ること、承認後に対象の order が「停止済み」になることを確かめる。承認を試すのは、停止を実際に行ってよいときだけにする。

### 6.15 周期ワーカーと結果の保持(Issue #231、ADR 0010 追補 6)

エージェント API が有効(`KIVRA_AGENT_API_ENABLED=true`)のときだけ、`CodingReconcileWorker` が起動する。起動時に `coding reconcile worker started` のログが出る。無効のときは起動せず、変数も読まない。

**設定(既定値で動く。k8s の設定は変えていない)**

| 名前 | 既定 | 範囲 | 内容 |
| --- | --- | --- | --- |
| `KIVRA_CODING_RECONCILE_INTERVAL_SECONDS` | `60` | 10〜3600 | 周期ワーカーの間隔(秒) |
| `KIVRA_CODING_RESULT_RETENTION_DAYS` | `30` | 1〜365 | 受理された結果(`coding_results`)を残す日数 |

範囲外や整数でない値は起動に失敗する。エラーには変数名だけが出て、値は出ない。

**一周期で行うこと**

1. 期限切れの attempt に印を付け、予約を計上する(QUEUED で期限切れは EXPIRED、lease が切れた CLAIMED と RUNNING は ORPHANED)。Run は動かさない。Run の UNKNOWN 化は、runner が次に進むときに行われる。
2. 保持期間を過ぎた結果を消す。消すのは、attempt が非活性で、かつ Run が終端であるか、行が attempt の受理された結果ではないもの。一周期で最大 200 行。残りは次の周期で消える。attempt と `coding_attempt_usage` は消えない。

**複数の起動は安全。** attempt の遷移は CAS で一度だけ、予約は `coding:<attemptId>:<世代>` の鍵で一度だけ計上される。

**デプロイ順序(`EMPTY_PATCH`、`BUDGET_EXCEEDED`、`OVERLOADED`、追補 6)**: kivra を先に出し、そのあとでエージェントの新版を出す。新しいコードを送るエージェントは、kivra がそれを受理できるようになってからでなければ、`fail` が 400 になる。逆の順序では、変更なしや予算超過の報告が落ちる。

**確認の手順**: `coding_results` の行数が増え続けないこと、終端の Run の結果が保持期間を過ぎて消えていることを、次の SQL で見る(本文は見ない)。

```sql
SELECT COUNT(*) AS rows_kept, MIN(created_at) AS oldest
  FROM coding_results;
```

`oldest` が保持期間より新しいこと。保持期間を過ぎた行が、DEVELOPING か UNKNOWN の Run の attempt に残っていてよい。

### 6.16 実行の経過の送信(ADR 0012 PR 2、既定は無効)

コード: `kivra-agent/.../claude/MonitorEvent.kt`(型)、`claude/MonitorEventExtractor.kt`(stream-json の 1 行を固定の型に写す純粋な関数)、`MonitorEventSender.kt`(有界の待ち行列、バッチ、seq、再送、`MonitorSession`)、`docker/DockerRunner.kt`(`onStdoutLine`、stdout の逐次読み)、`KivraAgentClient.postEvents`(`POST /api/agent/v1/attempts/{attemptId}/events`)。

**何を送るか**: 固定のコードと数値だけ。ツールの入力・出力、本文、パス、プロンプト、モデルの出力、モデル id と CLI の版は送らない。ツール名は kivra の `TOOL_NAMES` のいずれか(`Read`、`Edit`、`Write`、`Bash`、`Grep`、`Glob`、`Task`、`TodoWrite`、`OTHER`)。それ以外の名前は `OTHER`。

**kind と項目の対応**(kivra の `MonitorEventSchema` の `MonitorKind` と同じ。ゴールデンテスト `MonitorWireGoldenTest` が固定している。各イベントは `seq` と `kind` のほかに、下の項目だけを持つ。256 バイト以下):

| kind | 項目 | 送るとき |
| --- | --- | --- |
| `ATTEMPT_STARTED` | なし | CLI を起動した時 |
| `CLI_INIT` | なし | CLI の init の行(中身は送らない) |
| `PHASE` | `phase`(`STARTED`、`EDITING`、`TESTING`、`ROUND_COMPLETED`、`FINALIZING`) | 結果の送信の直前の `FINALIZING` の 1 件 |
| `TOOL_USE` | `toolName`(上の一覧) | assistant の `tool_use` ブロックごと |
| `TOOL_RESULT` | `count`(常に 1) | `is_error` が真の `tool_result` だけ。成功は送らない |
| `TURN` | `count`(常に 1) | assistant のメッセージごと |
| `PERMISSION_DENIED` | `count`(0 から 10,000) | 最後の結果の `permission_denials` の件数 |
| `API_RETRY` | `code`(`AUTHENTICATION`、`RATE_LIMIT`、`OVERLOADED`、`BILLING`、`OTHER`) | `api_retry` の行 |
| `COST` | `costUsd`(0 から 100、小数 6 桁まで) | 最後の結果の `total_cost_usd` |
| `STOP_REQUESTED` | なし | この PR では送らない(停止の表示は PR 5) |
| `PROCESS_EXIT_CONFIRMED` | `code`(`EXITED`、`TIMEOUT`、`CANCELLED`、`OUTPUT_LIMIT`) | コンテナの終了を確認した時 |
| `WARNING` | `count`(件数) | エージェントが読めなかった行(長すぎる行、ブロックが多すぎる行)の件数。種別は送らない |
| `DROPPED` | `count`(0 から 10,000) | 送れなかったイベントの件数(待ち行列が満杯、上限到達) |
| `TRUNCATED` | (送らない) | kivra が上限に達した時に自分で書く。エージェントは送らない |

**送り方**: 1 リクエストは最大 50 件、本文 8 KiB 以下。`seq` は `(attempt, generation)` ごとに 1 から連番で、送るイベントと `DROPPED` の 1 件ごとに 1 つ使う。上限は attempt あたり 2,000 seq(kivra の上限と同じ)。エージェントは 1,999 件まで送り、残りを最後の `DROPPED` 1 件にまとめる(最後の seq を空けておく)。待ち行列は最大 500 件で、超えた分は捨てて `DROPPED` の件数に数える。送信は 1 秒に 5 リクエストまで(間隔 200 ms)。

- 200: 受理。409 で `lastAcceptedSeq` があれば、その続きから同じ seq で送り直す。`lastAcceptedSeq` が無い 409(世代が古い)は、送信を止める。
- 429: `Retry-After` を守って同じバッチを送り直す。400: そのバッチを捨てて、seq は使い直す(WARNING を 1 件)。401・403・404 は送信を止める。5xx・通信の失敗・時間切れは、同じバッチを 3 回まで送り直し、その後止める。
- 結果(`result`)や失敗(`fail`)の前に、残りを最大 3 秒待って送り切る。間に合わなければ残りは捨てる。終わった後は送らない。

**監視は best effort**: 送信の失敗は attempt の結果を変えない。CLI の実行と kill も止めない(stdout の行の読み取りは別スレッドの有界キューで行い、リスナーの例外や停止は CLI に伝わらない)。ログには ID、件数、固定コードだけを出す。

**有効化**: 既定は無効で、無効のとき従来とまったく同じ動きをし、何も送らない。有効にするのは次の順序で行う。
1. kivra の対応版(`POST .../events` を受理する版)を dev に反映する。
2. エージェントを動かす PC の `/etc/kivra-agent/agent.env` に `KIVRA_AGENT_MONITOR_EVENTS=1` を書く(`install.sh` の雛形には `#KIVRA_AGENT_MONITOR_EVENTS=1` がある)。
3. `systemctl --user restart kivra-agent.service` で再起動する。

kivra が未対応のまま `1` にすると、`events` が 404 になり、送信は止まる(結果と fail は影響を受けない)。この場合も Run の結果は変わらないが、経過は残らない。

**戻し方**: `agent.env` の行を消すか `0` にして、ユニットを再起動する。kivra 側の受理の処理は消さない(古いエージェントが送り続けるかもしれないため)。

**未確認(実機で確かめる)**: stream-json の実行中の `assistant`・`user`・`system`(init)の形式は、この repo の fixture で読んだ仮定であり、実 CLI の出力では未確認。CLI の版を固定した fixture で確かめるまで、形式が違えば何も記録されない(安全側)。

### 6.17 実行の監視イベント(ADR 0012 PR 1、Issue #223)

エージェントが、CLI の実行中の進み(段階、ツールの呼び出し回数、ターン数、費用など)を固定のコードと数値で送る経路。kivra は受けて `coding_monitor_events`(V20)に保存する。保存するのは固定の値だけで、プロンプト、モデルの出力、標準出力、パス、ツールの入力は一つも入らない。エージェント側の送信は PR 2 で入る。この PR では kivra 側だけが入る。

**経路**: `POST /api/agent/v1/attempts/{attemptId}/events`。認証と所有者の扱いは §6.2 と同じ(claim したエージェントの `sub` だけ)。本文は `{"generation": n, "events": [{"seq": 1, "kind": "TURN", "count": 1}, ...]}`。

**種別と、その種別が持てる項目**(項目は種別ごとに決まっていて、余分な項目と不足は 400)

| kind | 項目(JSON 名) | 値の範囲 |
| --- | --- | --- |
| `ATTEMPT_STARTED`、`CLI_INIT`、`STOP_REQUESTED` | なし | `CLI_INIT` はモデル名と版を保存しない(ADR 0012 U-5) |
| `PHASE` | `phase` | `STARTED`、`EDITING`、`TESTING`、`ROUND_COMPLETED`、`FINALIZING` |
| `TOOL_USE` | `toolName` | `Read`、`Edit`、`Write`、`Bash`、`Grep`、`Glob`、`Task`、`TodoWrite`、`OTHER` |
| `TOOL_RESULT`、`TURN`、`PERMISSION_DENIED`、`WARNING`、`DROPPED` | `count` | 0〜10,000 |
| `API_RETRY` | `code` | `AUTHENTICATION`、`RATE_LIMIT`、`OVERLOADED`、`BILLING`、`OTHER` |
| `PROCESS_EXIT_CONFIRMED` | `code` | `EXITED`、`TIMEOUT`、`CANCELLED`、`OUTPUT_LIMIT` |
| `COST` | `costUsd` | 0〜100、小数 6 桁まで(数値。文字列は不可) |

- `TRUNCATED` は kivra だけが書く(上限の印)。エージェントが送ると 400。
- `seq` は (attempt, 世代) の中で 1 から連番。1 リクエストの中でも連続していること。受理済みの最後の次から送る。
- 同じ内容の再送は 200(冪等)。同じ `seq` で内容が違えば 409 `SEQ_CONFLICT`。欠番は 409 `SEQ_GAP`。どちらも `lastAcceptedSeq` を返すので、エージェントはその続きから送る。
- 型違い(文字列の数、小数の回数、指数表記)、範囲外、許可外の kind や値、未知のフィールドは、どれか一つでもあれば**その本文の全体を 400**。何も保存しない。エラーの本文は固定の文言だけ。

**上限**

| 項目 | 値 |
| --- | --- |
| 1 リクエストの件数 | 50 件まで |
| 本文 | 8 KiB まで(超えると 413) |
| 1 件 | 256 バイトまで |
| 1 attempt の件数 | 2,000 件まで(全ての世代の合計)。超えた分は保存せず、上限に達した時に `TRUNCATED` の印を 1 件書く(seq 0)。応答の `truncated` が true になる |
| 頻度 | 1 attempt あたり 1 秒に 5 リクエストまで(超えると 429 と `Retry-After: 1`)。上限はプロセスごと(複数の Pod では合計が倍数になる) |

**世代の確認(fencing)**: 本文の `generation` が現在の lease の世代と一致し、attempt が CLAIMED か RUNNING で、claim した holder からだけ受理する。古い世代は 409 `STALE_GENERATION`、終了した attempt は 409 `NOT_ACTIVE`。

**監視は best effort**: この経路が失敗しても(503)、attempt の結果と heartbeat は変わらない。heartbeat、結果、失敗の経路とは独立に動く。監視の書き込みは attempt の行を一時的にロックする(`FOR UPDATE`)ので、同時の heartbeat は、その一瞬だけ待つことがある。

**保持期間**

| 名前 | 既定 | 範囲 | 内容 |
| --- | --- | --- | --- |
| `KIVRA_CODING_MONITOR_RETENTION_DAYS` | `30` | 1〜365 | 終了した attempt のイベントを残す日数。エージェント API が有効のときだけ読む |

周期ワーカー(§6.15)が一周期ごとに掃除する。消すのは、attempt が非活性(QUEUED、CLAIMED、RUNNING でない)で、Run が終端(COMPLETED、FAILED、CANCELLED)で、attempt の最終更新が保持期間より古いもの。一周期で最大 200 attempt。attempt の行は消えない。活性の attempt のイベントは、保持期間に関わらず消えない。

**ログ**: 出すのは attempt の id、固定のコード、件数だけ。イベントの値、項目の名前、`sub` は出さない(テストで確かめている)。

**確認の手順**(値は見ない。件数だけ)

```sql
SELECT kind, COUNT(*) AS n
  FROM coding_monitor_events
 WHERE attempt_id = ?
 GROUP BY kind;
```

**デプロイ順序**: kivra を先に出す。エージェントの送信(PR 2)は、この経路が受理できるようになってから出す。逆の順序では、送信が 400 か 404 で落ちる(監視だけが失われ、attempt は影響を受けない)。

**未実装**: 読み取りの API と画面(PR 3、PR 4)。停止(PR 5)。SSE。

### 6.19 実行の経過の読み取り(ADR 0012 PR 3、Issue #223 / #224)

運用者が、Run の実行の経過(attempt の状態、ツール呼び出しの件数、ターン数、費用の確定と推定、停止の段階、鮮度)を読むための API。読み取りだけで、Run も attempt も変えない。画面は PR 4(§4.7 の「実行の経過」)、停止は PR 5。値は §6.17 の監視イベントから作る。画面は SSE が使えなくても、REST の定期取得だけで全部の表示が動く。

**API**

| メソッド・パス | 内容 |
| --- | --- |
| `GET /api/admin/runs/{runId}/execution` | スナップショット。`snapshotAt`(サーバの時刻)、`revision`(attempt の version の合計と保存済みイベント数)、`freshness`、`attempts`、`stop`、`tools`、`turns`、`permissionDenied`、`warnings`、`dropped`、`truncated`、`lastPhase`、`testingReported`、`apiRetries`、`cost` |
| `GET /api/admin/runs/{runId}/execution/events?attemptId=&afterGeneration=&afterSeq=&limit=` | 差分。`limit` は 1〜200(既定 100)。カーソルは `(afterGeneration, afterSeq)` の組で、初回は省略(0 と -1)。応答の `nextAfterGeneration`、`nextAfterSeq` を次の要求にそのまま渡す。`hasMore` が true なら続きがある |
| `GET /api/admin/runs/{runId}/execution/stream` | SSE(§6.19 の SSE の節) |

**認可**(`/api/admin/runs` の既存の一覧は変えていない。新しい経路だけに下の規則を使う)

| 呼び手 | 結果 |
| --- | --- |
| 認証なし | 401 |
| エージェントのトークン(`kivra-agent:agent`。運用者のロールを併せ持っても) | 403。Run を読む前に返す |
| 運用者(`admin-panel:admin`) | どの Run も読める |
| 作成者本人(運用者のロールは無い) | 自分の Run だけ読める |
| それ以外 | 404(Run が無いときと同じ。存在を漏らさない) |

- `KIVRA_AUTH_MODE=oidc-jwt` の本番では、トークンは `Authorization: Bearer` か、oauth2-proxy の `X-Forwarded-Access-Token` から取る(§README の `oidc-jwt` の節と同じ)。開発の header モード(`oauth2-proxy` / `none`)では運用者の区別が無いので、作成者本人だけが読める。
- 作成者本人の規則は、運用者のロールを持たない有効なトークンを 401 でなく 404 として扱うためにある。本番では、運用者以外の有効なトークンは Task API の他の経路でも 401 になる。

**鮮度(`freshness`)**: 最後に受信したイベントからの経過が閾値未満なら `LIVE`、閾値以上か、受信が一度も無ければ `STALE`。閾値は lease TTL(既定 2 分)の 2 倍で、コードの定数(`DEFAULT_MONITOR_STALE_AFTER`)。設定の項目は無い。`STALE` は「監視が途切れている」の意味で、Run が止まったという意味ではない。

**費用**: `confirmedUsd` は、使用量の台帳(`coding_attempt_usage`)の `REPORTED` 行の合計。CLI が結果で報告した値で、結果が届いた後にだけ入る。`estimatedUsd` は、台帳の `ESTIMATED_RESERVE` 行の合計に、まだ使用行の無い活性の attempt の予約額(`KIVRA_AGENT_ATTEMPT_RESERVE_USD`、既定 0.50 USD)を足したもの。推定は確定ではない。

**停止の段階(`stop.stage`)**: `NONE` → `REQUESTED`(`cancel_requested` が立った)→ `STOPPING`(エージェントが停止要求を受けたと報告した)→ `EXIT_CONFIRMED`(エージェントがコンテナの終了を報告した)→ `CANCELLED`(Run が取消し済み)。段階は到達した最も先のもの。エージェントの報告は観測であり、検証された事実ではない。停止の要求の時刻は今のスキーマに無いので、「確認待ちが長い」(閾値 5 分、ADR 0012 U-12)の判定は PR 5 で入れる。

**SSE**(`/execution/stream`)

- 形式: 行ごとに `id: <attemptId>:<generation>:<seq>`、`event: monitor`、`data:` に 1 件の JSON。`: heartbeat` のコメント行を 15 秒ごとに送る(何も送らなかったときだけ)。
- `Last-Event-ID` を受けたら、その位置の次から DB を読み直して送る(サーバのメモリには依存しない。再起動と複数の Pod でも同じ結果)。
- 保持期間で消えた位置、または Run の中に無い位置を指定されたら、`event: resync`(`data: {"action":"refetch_snapshot"}`)を 1 件送って切断する。クライアントはスナップショットを取り直す。
- `Last-Event-ID` の形式が違えば 400(接続は始まらない)。
- DB は接続ごとに 2 秒間隔で読む(1 回に最大 200 件)。通知の仕組みは使わない。
- 認可は接続の時と、各回の読みの前に検査する。権限が無くなれば切断する(ログの `reason=ACCESS_ENDED`)。注意: 検査は接続時のトークンを同じまま再検証する。ロールの剥奪は、そのトークンが期限切れになるまで接続に反映されない。
- 同時接続の上限: 利用者あたり 5、プロセス全体で 50。超えると 429。上限はプロセスごとなので、Pod が増えると全体の上限も増える(ADR 0012 U-13)。

**上限と既定値**

| 名前・項目 | 値 |
| --- | --- |
| 読みの `limit` | 1〜200(既定 100) |
| SSE の読みの間隔 | 2 秒(接続ごと) |
| SSE の heartbeat | 15 秒(コメント行) |
| SSE の同時接続 | 利用者あたり 5、全体 50(プロセスごと) |
| 鮮度の閾値 | lease TTL の 2 倍(既定 4 分、コードの定数) |

**ログ**: 出すのは、SSE の終了時の Run の id、送った件数、終了の固定コード(`CLIENT_CLOSED`、`ACCESS_ENDED`、`RESYNC`)だけ。利用者の `sub`、イベントの値、Run の題名や本文、パスは出さない(テストで確かめている)。読み取りは監査イベントを書かない(既存の `GET /api/admin/runs` と同じ)。

**未確認(実環境で確かめる項目)**

- oauth2-proxy が、SSE の応答を溜めずに流すか(バッファリングの無効化が要るか)。SSE の要求にも `X-Forwarded-Access-Token` が付くか、長い接続の途中でトークンが更新されるか。コードは、Task API と同じくこのヘッダーを読む。
- ingress とプロキシの読み取りのタイムアウトと、長い接続の扱い。
- ブラウザの `EventSource` が、同一オリジンの Cookie でプロキシを通るか(`EventSource` はヘッダーを付けられない)。
- 実 CLI の出力を使った 1 Run の end-to-end(§6.17 の未確認と同じ)。

これらが動かない場合の代替: 画面は REST のポーリング(スナップショットを数秒ごと、差分は `events` のカーソル)で足りる。SSE は必要にならない限り使わなくてよい。

## 8. MCP エンドポイント(`/mcp`、ADR 0013、Issue #181、PR 1: 読み取り、PR 4: 書き込み `kivra_selfdev_start`)

既定は無効。`KIVRA_MCP_ENABLED` が未設定、`false`、空のとき、MCP の変数は他に一つも読まれず、`/mcp` と `/.well-known/oauth-protected-resource` は登録されない(404)。

**未実装の前提**: MCP クライアントが OAuth でログインするための Keycloak の public client は PR 2(`kigawa01/k8s-system`)、`/mcp` を oauth2-proxy の外へ出す ingress は PR 3(`kigawa-net/kigawa-net-k8s`)。この PR だけでは、クライアントから接続できない。動作確認は、テストか、トークンを手で付けた直接の HTTP 呼び出しに限られる。

### 8.1 有効化の前提(起動時に検査される)

- `KIVRA_MCP_ENABLED=true` には `KIVRA_AUTH_MODE=oidc-jwt` が要る。`KIVRA_OIDC_*`(issuer、JWKS、`KIVRA_OIDC_AUDIENCE`、`KIVRA_OIDC_REQUIRED_ROLE`)は、REST と同じものを使う。
- `KIVRA_MCP_AUDIENCE`(必須)。`https://<host>/mcp` の形(例 `https://kivra-dev.kigawa.net/mcp`)。MCP のアクセストークンの `aud` に、この値が含まれること。
- `KIVRA_MCP_ALLOWED_HOSTS`(必須)。カンマ区切りの `Host`(例 `kivra-dev.kigawa.net`)。一致しなければ 403。
- `KIVRA_MCP_ALLOWED_ORIGINS`(任意)。カンマ区切りの `Origin`(例 `https://kivra-dev.kigawa.net`)。`Origin` ヘッダーが付いたリクエストは、一覧に一致する必要がある。`Origin` が無いリクエスト(非ブラウザのクライアント)は通す。
- `KIVRA_MCP_RATE_PER_MINUTE`(任意、既定 60、1〜6000)。主体(所有者のキー)ごとに、1 分あたりの上限。超えると 429。
- `KIVRA_AGENT_REQUIRED_ROLE`(任意)。設定されていれば、そのロールを持つトークンは 403(エージェントは MCP の呼び出し元ではない)。`aud` の検査で、エージェントのトークンは既に 401 になる。これは多層防御。
- `KIVRA_MCP_WRITE_ENABLED`(任意、既定 `false`)。`true` で書き込みのツール `kivra_selfdev_start` を登録する(§8.7)。order が有効なときは、停止の要求のツール `kivra_order_request_stop` も登録する(§8.8)。`KIVRA_MCP_ENABLED=true` のときだけ有効にできる。MCP が off のまま `true` にすると起動を止める。
- `KIVRA_MCP_WRITE_RATE_PER_MINUTE`(任意、既定 5、1〜6000)。書き込みの呼び出しの、主体ごとの 1 分あたりの上限。読み取りの上限(`KIVRA_MCP_RATE_PER_MINUTE`)とは別。超えると 429。書き込みが無効のときは読まれない。
- `KIVRA_MCP_GOAL_READ_ENABLED`、`KIVRA_MCP_GOAL_STOP_ENABLED`、`KIVRA_MCP_GOAL_SUBMIT_ENABLED`、`KIVRA_MCP_GOAL_TEXT_ENABLED`(任意、既定 `false`)。`KIVRA_MCP_GOAL_TEXT_ENABLED` は題名の読み取り(§8.9 の末尾)。Goal の読み取り、中止、投入のツール(§8.9)。どれも `KIVRA_MCP_ENABLED=true` と オートパイロットが要る。中止と投入は `KIVRA_MCP_WRITE_ENABLED=true` も要る。MCP が off のまま `true` にすると起動を止める。
- `KIVRA_MCP_GOAL_SUBMIT_DAILY_LIMIT`(任意、既定 10、1〜100)。`kivra_goal_submit` で、運用者 1 人が UTC の 1 日に投入できる Goal の件数。冪等の再送は数えない。

不正な値は起動を止める。エラーには変数の名前だけが出て、値は出ない。

### 8.2 エンドポイント

| パス | メソッド | 内容 |
|---|---|---|
| `/mcp` | POST | Streamable HTTP(ステートレス、JSON-RPC 2.0)。`initialize`、`ping`、`tools/list`、`tools/call` |
| `/mcp` | GET | 405(サーバーからの push は持たない) |
| `/.well-known/oauth-protected-resource` | GET | RFC 9728 の protected resource metadata。`resource` は `KIVRA_MCP_AUDIENCE`、`authorization_servers` は issuer |

- 対応する protocolVersion: `2025-06-18`(最新)と `2025-03-26`。それ以外の版を要求されたときは、`2025-06-18` を返す(クライアントが判断する)。
- 通知(`notifications/*`、`id` の無いメッセージ)は 202 で本文なし。バッチ(JSON 配列)は 400(2025-06-18 で削除された)。
- 本文の上限は 64 KiB(超えると 413)。`Content-Type` は `application/json`(違えば 415)。`Accept` に `application/json` も `text/event-stream` も無ければ 406。`MCP-Protocol-Version` ヘッダーが対応外なら 400。

### 8.3 認証と認可

- Bearer トークンは `Authorization` ヘッダーだけから読む(`X-Forwarded-Access-Token` は使わない)。検証は、RS256、JWKS の署名、`iss`、`aud`(MCP の値)、`exp`、`nbf`、必要なクライアントロール(REST と同じ)。
- 未認証は 401 と `WWW-Authenticate: Bearer resource_metadata="https://<host>/.well-known/oauth-protected-resource"`。本文は JSON-RPC のエラー。
- エージェントのトークン(`kivra-agent` の audience)は 401。ロールを持つ場合は 403。
- スコープは `scope` クレーム(空白区切り)。ツールごとに必要なスコープが違い、足りなければ 403 と `WWW-Authenticate: Bearer error="insufficient_scope", scope="..."`。
- 所有者は `iss` と `sub` から作るキー(`email` は使わない)。他人の Run は、存在しない Run と同じく `NOT_FOUND`。

### 8.4 ツール(PR 1 の範囲は読み取り 2 つだけ)

| ツール | 必要なスコープ | 入力 | 出力(`structuredContent`) |
|---|---|---|---|
| `kivra_run_list` | `kivra.runs.read` | `limit`(1〜50、既定 20)、`cursor`(任意、前ページの `next_cursor`) | `runs`(`run_id`、`kind`、`state`、`stage`、`reason_code`、`pr_url`、`created_at`、`updated_at`)、`next_cursor` |
| `kivra_run_get` | `kivra.runs.read` | `run_id`(26 文字) | 1 件の同じ項目。他人の Run や無い Run は `reason_code: NOT_FOUND`(`isError: true`) |

- 入力は厳格(未知の項目、型違い、範囲外は JSON-RPC の -32602)。
- 題名(title)、本文、パッチ、トークン、ログは返さない。
- 書き込みのツール `kivra_selfdev_start` は §8.7 のとおり。`KIVRA_MCP_WRITE_ENABLED` が `false` のときは `tools/list` に出ず、呼べば -32602(未知のツール)。`kivra_run_stop` は、まだ存在しない(PR 5)。

### 8.5 ログと監査

1 リクエストにつき 1 行。`via=mcp`、メソッド名、ツール名、主体のハッシュ(SHA-256 の先頭 12 文字)、結果コード、書き込みの場合は `run_id`。引数の値(題名、本文、`idempotency_key`)、トークン、主体の生の値は出さない。書き込みが投入されたときは、別に `mcp.selfdev_start via=mcp kind=SELF_DEV run_id=... replayed=...` の 1 行が出る。

### 8.7 書き込み(`kivra_selfdev_start`、PR 4、既定は無効)

#### 有効化の前提と順序

書き込みは、次の順で有効にする。途中で読み取りの確認が取れなければ、先へ進まない。

1. **読み取りだけを dev で確かめる**: `KIVRA_MCP_ENABLED=true` の状態で、`tools/list` に `kivra_run_list` と `kivra_run_get` が出て、`kivra_selfdev_start` が出ないことを確かめる(`KIVRA_MCP_WRITE_ENABLED` が未設定か `false`)。
2. **キルスイッチが有効であること**: `KIVRA_SELFDEV_ENABLED=true`。off のとき、書き込みは `tools/list` に出ず、呼べば固定の理由 `KILL_SWITCH_OFF` で拒否される(§8.7 の応答)。
3. **書き込みを有効にする**: `KIVRA_MCP_WRITE_ENABLED=true`(dev だけ。本番の既定は `false` のまま)。`KIVRA_MCP_WRITE_RATE_PER_MINUTE` は既定の 5 のまま始める。
4. **確認**: `tools/list` に `kivra_selfdev_start` が出る(書き込みのスコープ `kivra.selfdev.start` を持つトークンだけ)。小さな依頼を一件だけ投入し、同じ `idempotency_key` で再送して `replayed: true` の同じ `run_id` が返ることを確かめる。

`KIVRA_MCP_WRITE_ENABLED=true` は `KIVRA_MCP_ENABLED=true` なしでは起動を止める。

#### スキーマ

| 項目 | 必須 | 規則 |
|---|---|---|
| `title` | はい | 1〜200 文字、制御文字なし。Issue の参照(`https://github.com/<owner>/<repo>/issues/<n>`、`#<n>`、`<owner>/<repo>#<n>`)も可。参照は既存の経路で解決される(§5.1) |
| `body` | いいえ | 0〜8000 文字。改行とタブは可、ほかの制御文字は不可。空白だけは body 無しと同じ |
| `idempotency_key` | はい | クライアントが生成する。1〜128 文字、制御文字なし |

- 余分な項目(`repo`、`command`、`env`、`token`、`branch` など)は -32602。認証情報、環境変数、任意のコマンド、リポジトリは受け取らない。
- 型違い、範囲外、制御文字は -32602。Run は作られない。
- 本文は UNTRUSTED のデータとして扱われる。題名に「指示を無視して」と書いても、通常の依頼として投入されるだけである。

#### スコープ

- 必要なスコープは `kivra.selfdev.start`。無い場合は HTTP 403 と `WWW-Authenticate: Bearer error="insufficient_scope", scope="kivra.selfdev.start"`。読み取りのスコープ(`kivra.runs.read`)だけでは呼べない。
- 所有者は `iss` と `sub` から作るキー。同じ `idempotency_key` でも、主体が違えば別の Run になる。他人の Run を再送で取ることはできない。

#### 応答(`structuredContent`)

- 成功: `{"run_id": "...", "state": "CREATED", "stage": "...(あれば)", "replayed": false, "reason_code": "...(あれば)"}`。`replayed: true` は同じ key と同じ内容の再送。
- 拒否(`isError: true`、`structuredContent.error.code` に固定の値):
  - `KILL_SWITCH_OFF`: `KIVRA_SELFDEV_ENABLED=false`。
  - `CONCURRENCY_LIMIT`: 同時に進む自己開発 Run が上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)に達した。
  - `IDEMPOTENCY_CONFLICT`: 同じ key で内容が違う。既存の Run の ID は返さない。
- 書き込みの呼び出しが上限を超えると、HTTP 429 と JSON-RPC の `-32029`(`RATE_LIMITED`)。
- 状態は実際の値をそのまま返す(`UNKNOWN`、`WAITING` など)。「PR ができた」とは言わない。PR は `kivra_run_get` で確かめる。

#### 止め方

- 書き込みだけを止める: `KIVRA_MCP_WRITE_ENABLED=false`(または未設定)にして再起動。ツールは `tools/list` から消え、呼べば未知のツールになる。読み取りは続く。
- 自己開発を全体で止める: `KIVRA_SELFDEV_ENABLED=false`(キルスイッチ、§4.4)。書き込みは `KILL_SWITCH_OFF` で拒否される。
- MCP を全体で止める: `KIVRA_MCP_ENABLED=false`。`/mcp` は 404 になる。
- クライアントの切断は Run をキャンセルしない。投入された Run は、切断と無関係に続く。応答が失われたら、同じ `idempotency_key` で再送すれば同じ Run が返る。

### 8.8 order のツール(ADR 0015 段階 1、PR 2a、既定は無効)

order が有効(`KIVRA_SELFDEV_ORDERS_ENABLED=true` かつ `KIVRA_SELFDEV_AUTOPILOT_ENABLED=true`)のときだけ、次のツールを登録する。無効なら一つも出ず、呼べば未知のツールと同じ -32602 になる。

| ツール | 必要なスコープ | 入力 | 出力(`structuredContent`) |
|---|---|---|---|
| `kivra_order_list` | `kivra.runs.read` | なし | `orders`(古い順、最大 50 件)。各件は REST の一覧と同じ項目(`order_id`、`state`、`state_reason`、周の数、`expires_at`、`version` など) |
| `kivra_order_get` | `kivra.runs.read` | `order_id`(26 文字) | 1 件の同じ項目、`cycles`(`cycle_no`、`goal_id`、`goal_state`、`state_reason`)、`pending_stop_request`(保留中の要求。`request_id`、`requester_kind`、`reason_code`、`state`、`order_version`、`expires_at`。`digest` は含まない)。無い order は `reason_code: NOT_FOUND`(`isError: true`) |
| `kivra_order_request_stop` | `kivra.selfdev.stop` | `order_id`、`version`(0 以上の整数)、`reason`(`OPERATOR_REQUEST`、`RUNAWAY_SUSPECTED`、`BUDGET_CONCERN`、`SCOPE_CONCERN` のどれか) | 要求の `request_id`、`state`、`reason_code`、`requester_kind`、`order_version`。**`KIVRA_MCP_WRITE_ENABLED=true` のときだけ登録される** |

- 入力は厳格。未知の項目(`policy`、`reason` 以外の自由な文を含む)、列挙外の理由、型違い、負の版は -32602 で、何も書かれない。
- **停止の要求だけ**。承認、却下、取り消し、作成、周の開始、一時停止、再開は、MCP には無い(ADR 0015 U-9)。承認は REST の `approve` だけで、要求した主体は承認できない(`SAME_PRINCIPAL`。REST は 403)。
- 認可は REST と同じ。MCP の主体(所有者のキー)が `KIVRA_SELFDEV_AI_PRINCIPAL` と一致すれば AI の主体、それ以外は人の運用者。停止の要求は REST と同じく両方ができ、遷移の可否はリポジトリの規則で決まる。
- 拒否は REST と同じ固定コードを `structuredContent.error.code` に返す(例: `STOP_ALREADY_PENDING`、`STALE_VERSION`、`ORDER_NOT_FOUND`)。理由の文は固定の文だけ。
- 題名、方針、目標の本文、`digest` は返さない。ログと監査には、固定のコード、ID、主体のハッシュだけが出る(`net.kigawa.kivra.selfdev.order.operator`、`via=mcp`)。
- 有効化: order と MCP を有効にし、停止の要求も使うなら `KIVRA_MCP_WRITE_ENABLED=true` を加える(dev で先に確かめる)。戻すには該当の変数を `false` にして再起動する。order の状態は DB に残り、起動時に既存の `ACTIVE` は `PAUSED`(`STARTUP_HOLD`)になる。

### 8.9 Goal のツール(ADR 0019 S1、既定は無効)

Goal の読み取りは、`KIVRA_MCP_GOAL_READ_ENABLED=true` かつ `KIVRA_MCP_ENABLED=true` かつ オートパイロットが有効のときだけ、`kivra_goal_list` と `kivra_goal_get` を登録する。中止は `KIVRA_MCP_GOAL_STOP_ENABLED=true` のとき、さらに `KIVRA_MCP_WRITE_ENABLED=true` と オートパイロットが要る。投入(`kivra_goal_submit`、S2)は `KIVRA_MCP_GOAL_SUBMIT_ENABLED=true` のとき、さらに `KIVRA_MCP_WRITE_ENABLED=true` と オートパイロットが要る。どれかが欠けると、そのフラグは起動を止める。すべて既定は `false`。

| ツール(S2) | 必要なスコープ | 入力 | 出力(`structuredContent`) |
|---|---|---|---|
| `kivra_goal_submit` | `kivra.selfdev.start` | `title`(1〜200 字)、`body`(任意、8000 字まで)、`idempotency_key`(必須、1〜128 字) | `goal_id`、`state`(`GOAL_SUBMITTED`)、`replayed`(同じ鍵と同じ内容の再送は `true` で既存の Goal を返す)。 |

- **投入の規則**: `POST /api/selfdev/goals` と同じ。同じ関数で検証する(題名と鍵は制御文字を含めない、本文は改行とタブだけ許す。長さの上限も同じ)。`(運用者, idempotency_key)` で同一性を判定し、同じ内容は既存の Goal を返し、違う内容は `IDEMPOTENCY_CONFLICT`(既存の Goal の ID は返さない)。検証の失敗は `INVALID_INPUT`、AI の主体は `forbidden`。どちらも Goal は作られず、入力の文は返らない。
- **1 日の上限**: `DAILY_LIMIT`。主体が作成した Goal のうち、UTC の今日(0 時から)の `created_at` の件数を数えて判定する(REST で作った分も数える。マイグレーションは無い)。上限に達したあとの冪等の再送は、既存の Goal を `replayed: true` で返し、上限に数えず拒否もしない。翌日の 0 時(UTC)に数えが 0 に戻る。
- **返さない項目と記録**: 題名、本文、冪等キーの値は、応答にもログにも出さない。ログは `net.kigawa.kivra.selfdev.autopilot.operator` に、`via=mcp`、操作名、Goal の ID の先頭 12 文字、`idempotencyKey=present` と結果のコードだけ。題名は、REST と同じく Goal の題名の記憶(ADR 0016)に記録される(本文は記録されない)。
- **レート制限**: 書き込みの共有の上限(`KIVRA_MCP_WRITE_RATE_PER_MINUTE`、既定 1 分 5 件)に乗る。
- **有効化**: 投入は計画の作成を始めるため、dev での有効化は運用者の承認を経る。既定は off。

| ツール | 必要なスコープ | 入力 | 出力(`structuredContent`) |
|---|---|---|---|
| `kivra_goal_list` | `kivra.runs.read` | なし | `goals`(更新の新しい順、最大 50 件)。各件は `goal_id`、`state`、`state_reason`、`version`、`created_at`、`updated_at`、`task_counts`(タスクの状態別の件数。最新の Plan)。 |
| `kivra_goal_get` | `kivra.runs.read` | `goal_id`(26 文字) | 1 件の同じ項目と、`tasks`(最新の Plan のタスクごとの `ordinal`、`state`、`state_reason`、`run_id`)。無い Goal は `GOAL_NOT_FOUND`(`isError`)。 |
| `kivra_goal_stop` | `kivra.selfdev.stop` | `goal_id`、`version`(0 以上の整数)、`reason`(`OPERATOR_REQUEST`、`RUNAWAY_SUSPECTED`、`BUDGET_CONCERN`、`SCOPE_CONCERN` のどれか) | `state`(`STOPPED`)、`reason_code`(`OPERATOR_STOPPED`)、`version`(新しい版)、`run_still_active`。 |

- **返さない項目**: Goal の題名、本文、タスクの題名と本文。これは UNTRUSTED の入力であり(ADR 0019 §3)、MCP の読み取りでは返さない(REST の詳細は返す)。`kivra_goal_stop` も、理由の文は保存もログ出力もしない(固定コードだけ)。
- **`run_still_active`**: 中止は Goal の計画を打ち切るだけで、投入済みの Run は止まらず、PR も閉じない(ADR 0019 §6)。`true` は、その Goal のタスクに、まだ終わっていない Run があること(`SUBMITTING` のタスクを含む)。Run を止めるには、ADR 0012 PR 5 の取消し(未実装)か、`UNKNOWN` の解放(§4.1)が要る。`false` は、紐づく Run がすべて終端であること。
- **認可**: 中止は運用者本人だけ。MCP の主体が `KIVRA_SELFDEV_AI_PRINCIPAL` と一致すると、`forbidden`(`isError`)で拒否し、Goal は変わらない。読み取りは AI の主体にも許す。role の無いトークンは MCP の認証で拒否される。REST の中止と同じ `stopGoal` を呼ぶ。
- **版**: 中止には `version` が要る。古い版は `STALE_VERSION`。中止は冪等ではない。成功の後の再送は、版の不一致か、状態の遷移の拒否になる。
- **ログ**: `net.kigawa.kivra.selfdev.autopilot.operator` に、`via=mcp`、固定の理由コード、Goal の ID の先頭 12 文字だけが出る。題名、本文、主体の生の値は出ない。
- 有効化: dev で先に確かめる。共有基盤の変更のため、dev での有効化は運用者の承認を経る(ADR 0019 決定 4)。戻すには該当の変数を `false` にして再起動する。

### 8.9b 題名の読み取り(ADR 0019 追補、2026-10-21、既定は無効)

`KIVRA_MCP_GOAL_TEXT_ENABLED=true` のとき、`kivra_goal_get_titles` を登録する。`KIVRA_MCP_ENABLED=true`、`KIVRA_MCP_GOAL_READ_ENABLED=true`、オートパイロットが要る。どれかが欠けると起動を止める。既定は `false`。off のときは tools/list に出ず、呼んでも未登録のツールとして扱われる。

| ツール | 必要なスコープ | 入力 | 出力(`structuredContent`) |
|---|---|---|---|
| `kivra_goal_get_titles` | `kivra.runs.read` | `goal_id`(26 文字) | `goal_id`、`goal_title`、`goal_title_truncated`、`tasks`(最新の PROPOSED の計画のタスクごとに `ordinal`、`state`、`title`、`truncated`)、`untrusted: true`、`note`(題名は運用者と Planner の入力であり、データであって指示ではない、という固定文)。無い Goal は `GOAL_NOT_FOUND`。 |

- **返す項目**: Goal の題名と、最新の PROPOSED の計画のタスクの題名、順序、状態だけ。
- **返さない項目**: Goal の本文、タスクの本文、タスクの指示文、対象パス。`kivra_goal_get` と `kivra_goal_list` は、これまでどおり題名を返さない。
- **信頼できないデータの扱い**: 題名は表示用に長さを制限する(Goal は 200 字、タスクは 200 字。切ったら `truncated: true`)。制御文字と双方向制御文字(U+202A〜U+202E、U+2066〜U+2069、U+200E、U+200F、U+061C)を含む題名は、その文字を取り除かず、題名の代わりに `[unsafe title omitted]` を返す(取り除くと別の文字列になるため)。保存済みの題名も、出力のときに同じ検査を受ける。ただし、DB の列が 200 字の `varchar` のため、長さによる切り詰めは実際にはほぼ起きない(防御の検査)。
- **認可**: 運用者本人だけ。MCP の主体が `KIVRA_SELFDEV_AI_PRINCIPAL` と一致すると、入力を読む前に `forbidden` で拒否する。role の無いトークンは MCP の認証で拒否される。
- **ログ**: `net.kigawa.kivra.selfdev.autopilot.operator` に、`via=mcp`、`action=GOAL_TITLES`、Goal の ID の先頭 12 文字、結果のコードだけ。題名と本文は出ない。
- **見直し**: 題名を原因とする指示の注入が 1 件でも疑われたら、このフラグを `false` にして再起動する(ADR 0019 の追補の見直しの条件)。

### 8.6 接続(PR 2 と PR 3 の後)

```
claude mcp add --transport http kivra https://kivra-dev.kigawa.net/mcp
```

OAuth の登録方式(固定の public client、PKCE S256)は PR 2 で決める。それまでは、この行は使えない。

## 9. CI のテストのシャード分割(`test-mariadb`)

`.github/workflows/ci.yml` の `test-mariadb` は、`strategy.matrix.shard`(1、2、3)の 3 つの並列ジョブで走る。各ジョブは独立した MariaDB 11 のサービスコンテナを持つ。`fail-fast: false` のため、1 つが落ちても他の結果を最後まで見られる。

| シャード | 対象 |
|---|---|
| 1 | `net.kigawa.kivra.db.*` |
| 2 | `net.kigawa.kivra.selfdev.*` と `net.kigawa.kivra.mcp.*` |
| 3 | 上の 1 と 2 を除くすべて(`routes`、`coding`、`agent`、`acceptance`、`reaper`、`auth` など)|

- 対象は `kivra-app/build.gradle.kts` の `kivraTestShardPackages` と、シャード 3 の除外(全部から 1 と 2 を除く)で決まる。新しいパッケージは自動でシャード 3 に入る。どのシャードにも属さないテストは存在しない。
- `KIVRA_TEST_SHARD` が未設定なら全テストを実行する(ローカルの `./gradlew test` と `test-h2` は従来どおり)。`1`、`2`、`3` 以外の値は Gradle の設定段階で失敗する。
- レポートのアーティファクト名は `test-reports-mariadb-shard-<n>`。
- 必須チェックの `test` は、`needs: [test-h2, test-mariadb]` のまま、`needs.test-mariadb.result` で判定する。マトリクスのどれか 1 つでも失敗すると、`test-mariadb` の結果は `success` にならず、`test` は失敗する。

ローカルで 1 シャードだけ実行する(H2 でよい):

```
KIVRA_TEST_SHARD=1 ./gradlew :kivra-app:test --rerun
```
