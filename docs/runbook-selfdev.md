# Runbook: 自己開発(self-dev)の運用

対象: Kivra の自己開発の最小ループ(ADR 0008、Issue #56)。配線は #172(main `b3185bb`)で入った。
この文書には秘密の値(トークン、API キー、Secret の中身)を書かない。名前だけを書く。
手順の状態は次の 3 つで書く。「実装済み」はコードで確認したもの、「方針」は ADR や有効化手順に書かれているが、コードでは強制していないもの、「未実装」はコードに無いもの。

確認基準: origin/main `b3185bb`(2026-10-16)。

## 0. 現状(最初に読む)

- 計画の自動作成と投入(オートパイロット)の設計は [ADR 0011](adr/0011-selfdev-autopilot.md)。未実装。運用手順は実装 PR で足す。
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

### 4.7 Web 画面(`/ui`)で Run を見る

`KIVRA_AUTH_MODE` が設定されているときに `https://<kivra-host>/ui` で開ける。認証は API と同じで、画面自体には秘密を含まない。この画面からできるのは、Run の一覧と詳細の確認、Task の投入(種別の選択を含む)、自己開発の Run の解放である。予算の操作は、画面からはまだできない(API を使う。PR5 は別 PR)。

- **投入(種別の選択)**: 投入フォームの「種別」で、シミュレーション(`POST /api/tasks`)か自己開発(`POST /api/selfdev/tasks`、GitHub に PR を作る)を選ぶ。自己開発は、`GET /api/selfdev/status` の `enabled` が `true` のときだけ選べる。無効のとき、または状態を取れないときは選べない。
  - 冪等キーは画面が作る。種別を切り替えたとき、またはタイトルを変えて送るときは、キーを作り直す。送信が成功するまでは、同じ内容の再送は同じキーになる(ネットワーク失敗のあとに押し直しても二重にはならない)。
  - 送信中はボタンを無効化する。結果: 202「受け付けました」(Run ID つき)、200「既に受け付け済み」、409「同じキーで別の内容」(キーを作り直す。内容を確かめてから投入し直す)、429「自己開発の Run が上限に達している」、400・401・415・その他は固定の文言。応答の本文は画面に出さない。
- **解放(詳細の「運用者の解放…」「解放（束縛なし）…」)**: 対象は、自己開発の Run で状態が `UNKNOWN` のもの(§4.1)、または `PULL_REQUEST` で束縛が無いもの(§4.1b)。ボタンは理由(1〜500 文字、必須、空白だけは不可)を入力する確認の画面(ダイアログ)を開く。理由を入れて「解放する」を押すと、`/release` か `/release-unbound-pr` を呼ぶ。成功すると詳細を読み直す。PR は閉じず、マージもしない(GitHub には書き込まない)。
  - 応答: 200「解放しました（CANCELLED）」、409「解放できる状態ではない（状態が既に変わっている）」、404「見つからない」、401 はセッション切れの案内、400・415・その他は固定の文言。
- **上部の注意書き**: `GET /api/selfdev/status` の `enabled` で出し分ける。有効なら「自己開発の Run は GitHub に PR を作成します(実際に実行されます)」、無効なら「自己開発は無効です」。状態を取れなければ「設定を取得できていません」と出す(有効・無効は推測しない)。
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

**手動の更新(自動が失敗した、または PR が作れないとき)**:
1. GitHub Actions の `fx-refresh` を `workflow_dispatch` で再実行する。ログの `Refresh FX rate` に `CHANGED`、`NEW_RATE`、`AS_OF` などが出る。
2. 手元で更新する場合: `python3 -I scripts/fx-refresh.py <kigawa-net-k8s の kivra/dev/kivra.yaml のパス>`(取得は実 API。ネットワークが要る)。終了コード 0 は書き換えた、3 は変更なし、1 は失敗(何も書き換えない)。
3. 最後の手段として、`KIVRA_FX_USD_JPY` と `KIVRA_FX_AS_OF` の value を手で直す。レートは 1 以上 1000 以下の整数、AS_OF は `YYYY-MM-DD`(UTC)。

**実行の確認**: `gh run list --repo kigawa-net/kivra --workflow fx-refresh.yml`。PR は `gh pr list --repo kigawa-net/kigawa-net-k8s --state open --json headRefName,title,url` で、接頭辞 `chore/kivra-dev-fx-` のものを見る。

**方針を変える**: 式は `scripts/fx-refresh.py` の `compute_rate`。52 週の窓は `WINDOW_DAYS`、警告の閾値は `BIG_MOVE_RATIO`、検査の範囲は `MIN_RATE` / `MAX_RATE` / `MAX_STALE_DAYS` / `MIN_RANGE_POINTS`。変更は kivra リポジトリの PR で行い、`scripts/test_fx_refresh.py` を更新する。頻度は `fx-refresh.yml` の `cron`(7 日の失効より十分短く保つ)。余裕と税の比率は k8s 側の環境変数で、kivra の設定を変える。

**k8s の他の PR との関係**: cd.yml の deploy PR も同じファイルを更新するが、触る行が違うので通常は競合しない。競合したら、この節の式で値を直し直す。

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

- 既定の `--max-turns` は 40(`CliCommand.DEFAULT_MAX_TURNS`)。値は整数だけ(`05`、`+5`、空は拒否)。
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
| `OUTPUT_LIMIT` | 実行の出力上限、または解析の総量上限、または 1 MiB を超える行 | `OUTPUT_LIMIT` |
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
- `stream-json` の `usage` の全フィールド。Write のツール入力が stdout に入るため、1 MiB の出力上限が小さすぎないか(ADR 0010 の出力量の上限は未決)。
- `.kivra/` の書き込み権限(uid 10001)と、task ファイルを PR 8 の差分から外す処理。

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
3. CLI のコンテナを 1 つ起動する(§6.6、§6.7 の引数)。時間制限は `limits.maxRuntimeSeconds`。出力上限は `min(limits.maxOutputBytes, 1 MiB)`。
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
- 出力上限は、claim の `maxOutputBytes` を 1 MiB の上限まで縮めるだけに使う。
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
- `stream.jsonl`: CLI の stdout(stream-json)全体。8 MiB で先頭から切る。ただし実行の出力上限(claim の `maxOutputBytes` と 1 MiB の小さいほう)を超えた実行では、その上限で既に切れている。
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

**Console 側の上限と kivra の上限の関係**: 共有ワークスペースの支出上限(Console)は、kivra の月次の上限より大きくする。kivra の上限は計上の範囲で止める。Console は、kivra の計上が漏れた場合の最終防壁になる。

**既知の制限(この PR)**

- 入場の確認と claim は別の操作である。同時に複数のエージェントが claim すると、予約額の数件分だけ超えうる(1 台の PC を前提とする)。
- ORPHANED / EXPIRED の遷移は済んでいるが、その計上が失敗した場合は、警告ログ(`coding reserve not booked`)が出るだけで、再試行しない。周期ワーカー(§6.15)は遷移を一度しか行わないので、この欠損は再試行されない。このログが出たら、該当の attempt を §4.2 の手順で確認する。
- 為替が失効していた間に報告された費用は金額 0 で計上される。この予約には実額を入れられない(上記の確認手順 3)。
- 運用画面で API 経路と CLI 経路を区別して表示する作業(UI PR5)は、この PR に含まれない。


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

状態: **基盤(PR A)と Planner(PR B)を実装済み**。Dispatcher、UI、HTTP は後続の PR で入る。
Planner は `KIVRA_SELFDEV_AUTOPILOT_ENABLED=true` のときだけ動く。既定(無効)では何も起きない。Goal の受付(HTTP)は PR D まで無い。
Planner は、`GOAL_SUBMITTED` の Goal を claim して計画を作り、`PLAN_PROPOSED` に進める。タスクは投入されない(投入は PR C)。

### 7.1 設定項目

| 名前 | 既定 | 内容 |
|---|---|---|
| `KIVRA_SELFDEV_AUTOPILOT_ENABLED` | `false` | `true` と `false` 以外は起動に失敗する。`true` は、自己開発が有効(`KIVRA_SELFDEV_ENABLED=true`)で、`KIVRA_SELFDEV_RUNTIME=cli` のときだけ許す |
| `KIVRA_SELFDEV_AUTOPILOT_APPROVAL` | `required` | `required` は全タスクを運用者が承認する。`auto` は段階 2 の規則に当たるタスクを承認なしで投入する(後続の PR)。値は `required` / `auto` のどちらか。**denylist のパスのタスクは、`auto` でも必ず承認が要る**(ADR 0011 §4) |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_PENDING_PRS` | `3` | マージ待ちの自己開発 PR の上限。1〜20 |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_CONSECUTIVE_FAILURES` | `3` | 連続失敗でサーキットブレーカーを開く数。1〜20(この範囲は PR A で決めた値。ADR に範囲の記載は無い) |
| `KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY` | `5` | 1 日(UTC)に投入する Run の数。1〜100(この範囲も PR A で決めた値) |
| `KIVRA_SELFDEV_AUTOPILOT_BUDGET_SHARE_PERCENT` | `50` | 月次予算のうち、オートパイロットに割り当てる割合(%)。1〜100 |
| `KIVRA_SELFDEV_AUTOPILOT_PLAN_INTERVAL_SECONDS` | `30` | Planner が計画待ちの Goal を探す間隔(秒)。5〜3600 |

数値が範囲外、または `APPROVAL` が `required` / `auto` 以外のときは、起動に失敗する。失敗のメッセージは変数の名前だけを示し、値は出さない。
値は秘密ではない。

### 7.2 無効時の挙動(既定)

- `KIVRA_SELFDEV_AUTOPILOT_ENABLED` が未設定、空、または `false` のとき、ほかのオートパイロットの変数は読まれない。
- 何も配線されず、`selfdev_autopilot_control` の行は読まれない。DB のテーブルは V18 で作られるが、使われない(既存の挙動は変わらない)。

### 7.2b Planner(PR B)

- 対象は `GOAL_SUBMITTED` の Goal。claim は DB の時刻で行い、同時に 1 つの Planner だけが取る。claim の期限は 600 秒。切れた claim は次の周期で `GOAL_SUBMITTED` に戻る(`CLAIM_EXPIRED`)。
- モデルは `claude-haiku-5-5` に固定する。起動時に registry にそのモデルが `ACTIVE` で無ければ、起動に失敗する。ツールは渡さない。思考は無効。
- 出力は JSON 1 個だけ。未知のキー、型違い、件数・長さの超過、依存の誤り、allowlist 外のパスは拒否する。拒否は 1 回だけ再試行する。
- denylist のパス(`.github/**`、`**/db/migration/**`、`docs/adr/**` など)に触れるタスクは、`auto` でも承認待ちになる。モデルは `requiresApproval` を出せない。
- 予算が足りない、または UNKNOWN_COST のとき、Goal は `BLOCKED(PLAN_BUDGET_HOLD)`。モデル呼び出しの失敗は `BLOCKED(PLAN_CALL_FAILED)`。検証の失敗は `BLOCKED(PLAN_INVALID)`。
- `BLOCKED` の計画段階の Goal は、自動では再試行しない。運用者が再計画する(`BLOCKED → GOAL_SUBMITTED`、理由 `REPLAN_REQUESTED`)。この操作の入口は PR D まで無い。
- ログには Goal の本文、計画、パス、モデルの出力、例外の文を出さない。ID と固定の理由コード、件数、費用、出力のダイジェストの先頭 12 文字だけを出す。

### 7.3 状態遷移

Goal、Plan、タスクの状態と遷移表は、ADR 0011 の §2.5 が正本である。コードの表は `kivra-core/src/main/kotlin/net/kigawa/kivra/core/autopilot/AutopilotStates.kt` にあり、
全組み合わせ(許可と拒否)を `AutopilotStatesTest` が確かめる。表に無い遷移は拒否され、終端の状態(Goal の `DONE` / `STOPPED`、タスクの `COMPLETED` / `SKIPPED`)からは動かない。

ADR から変えた点(PR A での判断、ADR §2.8 に記録):

- 再計画は `PLAN_PROPOSED → GOAL_SUBMITTED` である(ADR の `PLANNING` ではない)。`PLANNING` は claim されている間だけの状態にするため。
- 一時停止からの再開先は、一時停止の直前の状態を `selfdev_goals.resume_state` に保存して決める。
- 計画段階の `BLOCKED` の理由は `PLAN_INVALID` / `PLAN_BUDGET_HOLD` である。ディスパッチ段階の `BUDGET_HOLD` とは分ける。計画段階で止まった Goal は、今の表では `STOPPED` 以外へ進めない(未決の点 U-16)。

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

