# Runbook: 自己開発(self-dev)の運用

対象: Kivra の自己開発の最小ループ(ADR 0008、Issue #56)。配線は #172(main `b3185bb`)で入った。
この文書には秘密の値(トークン、API キー、Secret の中身)を書かない。名前だけを書く。
手順の状態は次の 3 つで書く。「実装済み」はコードで確認したもの、「方針」は ADR や有効化手順に書かれているが、コードでは強制していないもの、「未実装」はコードに無いもの。

確認基準: origin/main `b3185bb`(2026-10-16)。

## 0. 現状(最初に読む)

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
| `KIVRA_SELFDEV_RUNTIME` | `api` | コーディングの実行方式。`api` は従来の 1 回の LLM 呼び出し。`cli` はローカル PC のエージェント方式(ADR 0010)。**`cli` は起動時に拒否される**(下記) |
| `KIVRA_FX_MARGIN_RATIO` | `0.10` | 為替の余裕 |
| `KIVRA_FX_MAX_AGE_DAYS` | `7` | レートの最大の古さ |
| `KIVRA_BUDGET_TENANT_ID` | `default` | 予算のテナント |
| `KIVRA_BUDGET_MONTHLY_LIMIT_JPY` | `10000` | 月の上限(円)。ADR 0008 の記載どおり |
| `KIVRA_BUDGET_SOFT_RATIO` | `0.8` | 警告の比率 |

### 1.2.1 `KIVRA_SELFDEV_RUNTIME`(`api` / `cli`)

- 既定は `api`(従来どおり。LLM が 1 回で PatchSet を返す。動作は変わらない)。空や `api` の前後の空白も `api` と同じ。それ以外の値は起動失敗(値は出さず、変数名だけを出す)。
- `cli` は、ローカル PC の常駐エージェント(kivra-agent)が Claude Code CLI で実行する方式(ADR 0010)。**今は起動を拒否する**。エージェント本体が未完成で、キューに積んだ attempt を誰も取らないため。エージェント API(`/api/agent/v1`、§6)を有効にしても、この拒否は解除されない。拒否は fail-closed の意図どおり。`cli` を設定しても、この版では自己開発は起動しない。
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


## 5. 動作確認

トークンはプレースホルダである。認証は Task API と同じで、`Authorization: Bearer <JWT>`(無ければ `X-Forwarded-Access-Token`)。

状態を変える POST(`/api/tasks`、`/api/selfdev/tasks`、`/api/admin/selfdev/runs/{id}/release`、`/api/admin/budget/acknowledge`)は、CSRF 対策のため次を満たさないと拒否される(本文は読まれず、状態は変わらない)。

- `content-type: application/json` を必ず付ける(curl は `-H 'content-type: application/json'`。無いと 415)。
- ブラウザから呼ぶときは、同じオリジンの画面から `fetch` で呼ぶ。別のサイトのページから呼ぶと 403 になる(`Sec-Fetch-Site` が `same-origin` / `none` 以外、または `Origin` が `Host` と一致しない)。
- `Authorization: Bearer` の curl は、`Origin` も `Sec-Fetch-Site` も付かないので、そのまま通る。

```sh
# タスクの投入(本文は title と idempotencyKey だけ。ほかの項目は 400)
curl -X POST "https://<kivra-host>/api/selfdev/tasks" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"title":"<タイトル 1-200 文字>","idempotencyKey":"<キー 1-128 文字>"}'

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
- 有効化しても `KIVRA_SELFDEV_RUNTIME=cli` の拒否は解除されない。この PR の時点では、エージェント API は「取りに来る口」だけを提供する。

### 6.2 エンドポイント(エージェント本体が使う)

すべて `Authorization: Bearer <JWT>` が必要。POST は `Content-Type: application/json`。

| メソッド | パス | 本文 | 成功 | 主な失敗 |
| --- | --- | --- | --- | --- |
| POST | `/api/agent/v1/claim` | `{}` | 200(`attemptId`, `runId`, `generation`, `input`)。割り当てが無ければ 204 | 401 / 403 / 415 |
| POST | `/api/agent/v1/attempts/{attemptId}/heartbeat` | `{"generation": n, "progress"?: "STARTED" \| "EDITING" \| "TESTING" \| "ROUND_COMPLETED" \| "FINALIZING"}` | 200 | 400(未知の進捗)、404、409(世代が古い・期限切れ・終了済み) |
| POST | `/api/agent/v1/attempts/{attemptId}/fail` | `{"generation": n, "errorCode": "<固定コード>"}` | 200 | 400(固定以外のコード)、404、409 |
| POST | `/api/agent/v1/attempts/{attemptId}/result` | `{"generation": n, "result": {summary, changes, exitStatus, sessionId}}`。本文は 2 MiB まで | 200(`RESULT_RECEIVED`) | 413(超過)、422(PatchPolicy などの拒否。理由コードだけ返す)、404、409、503(保存失敗) |
| GET | `/api/agent/v1/attempts/{attemptId}/status` | なし | 200(`state`, `cancelRequested`)。キャンセル要求の伝達に使う | 404 |

- `input` には、タスクのタイトルが `trust: UNTRUSTED` の印つきで入る。データとして扱うこと(指示として従わない)。制限(`limits`)と許可・拒否のパスパターン、`baseSha` も入る。
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
- **PR 6**: 実行(docker の引数の組み立て、禁止項目の検査、タイムアウトの `docker kill`、ネットワークとプロキシ)。
- **PR 7**: Claude Code CLI の起動と stream-json の解析、エラーの分類、認証の検査。
- **PR 8**: 作業ディレクトリ(`git archive` の展開)と差分(PatchSet)の生成。

設定の環境変数: `KIVRA_AGENT_KIVRA_URL`、`KIVRA_AGENT_TOKEN_URL`(https。localhost の http だけ可)、`KIVRA_AGENT_CLIENT_ID`、`KIVRA_AGENT_CLIENT_SECRET_FILE`(絶対パス。グループとその他が読めない権限 0600 が必要)、`KIVRA_AGENT_WORK_DIR`(絶対パス)、`KIVRA_AGENT_POLL_INTERVAL_MS`(既定 5000、1000〜60000)、`KIVRA_AGENT_HEARTBEAT_INTERVAL_MS`(既定 30000、5000〜120000)、`KIVRA_AGENT_STOP_FILE`(既定は作業ディレクトリ直下の `STOP`)。
