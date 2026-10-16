# Runbook: 自己開発(self-dev)の運用

対象: Kivra の自己開発の最小ループ(ADR 0008、Issue #56)。配線は #172(main `b3185bb`)で入った。
この文書には秘密の値(トークン、API キー、Secret の中身)を書かない。名前だけを書く。
手順の状態は次の 3 つで書く。「実装済み」はコードで確認したもの、「方針」は ADR や有効化手順に書かれているが、コードでは強制していないもの、「未実装」はコードに無いもの。

確認基準: origin/main `b3185bb`(2026-10-16)。

## 0. 現状(最初に読む)

- 自己開発は既定で無効。`KIVRA_SELFDEV_ENABLED` が正確に `true` のときだけ有効になる。無効のときは、自己開発の他の環境変数を一切読まず、ワーカーも `/api/selfdev/*` も登録しない(`selfdev/SelfDevSettings.kt`、`Application.kt`)。
- 有効化すると、`SelfDevWorker` が起動し、`POST /api/selfdev/tasks`、`GET /api/selfdev/runs`、`GET /api/selfdev/runs/{id}` が登録される(`routes/SelfDevRoutes.kt`)。
- 本番で動かすには、ブランチ保護、GitHub 認証(App 推奨。PAT は代替)、Anthropic 認証(WIF 推奨。静的キーは代替)、モデル registry、為替の設定が必要。§1 を参照する。
- **未配線**: マージ検証(ADR 0008 の ⑩)の判定と完了の経路はコードにあるが、本番の認証器は未配線(`NoPrMergeAuthority`)のため、マージ済みの PR は完了しない(`PULL_REQUEST` のまま待機)(§4.3)。

## 1. 有効化の前提

### 1.1 起動時に検査されるもの(満たさないと起動に失敗する)

| 項目 | 名前 | 内容 |
|---|---|---|
| キルスイッチ | `KIVRA_SELFDEV_ENABLED` | `true` のときだけ有効。`false` または空は無効。それ以外の値は起動失敗。実装済み |
| 認証 | `KIVRA_AUTH_MODE` | `oidc-jwt` が必須。 |
| 実行環境 | `KIVRA_RUNTIME_ENV` | `development` と同時の有効化は拒否される |
| GitHub 認証 | 推奨: GitHub App の `KIVRA_SELFDEV_GITHUB_APP_ID`、`KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID`、`KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY` の 3 つ。代替: PAT の `KIVRA_SELFDEV_GITHUB_TOKEN` | どちらか一方だけ。両方、またはどちらも無いと起動に失敗する。App の秘密鍵は、値が既存のファイルのパスならそのファイルを読み、そうでなければ PEM 本文として扱う。読めない鍵は起動に失敗する。PAT を使う場合は、管理者の権限を持たない専用の細粒度 PAT(`contents:write` と `pull_requests:write` のみ) |
| Anthropic 認証 | 推奨: Workload Identity Federation の `KIVRA_SELFDEV_ANTHROPIC_FEDERATION_RULE_ID`、`_ORGANIZATION_ID`、`_SERVICE_ACCOUNT_ID`、`_IDENTITY_TOKEN_FILE` の 4 つ(任意で `_WORKSPACE_ID`)。代替: 静的キーの `KIVRA_SELFDEV_ANTHROPIC_API_KEY` | どちらか一方だけ。WIF の一部だけ、両方、どちらも無いと起動に失敗する。ID の書式は、rule が `fdrl_`、service account が `svac_`、workspace が `wrkspc_`、organization が UUID。トークンファイルは起動時には読まず、交換のたびに読み直す。値は読み取るだけで、ログや設定の表示には出ない |
| モデル registry | `KIVRA_SELFDEV_MODEL_REGISTRY_PATH` | 必須。読み込めなければ起動に失敗する。エラーに path や内容は出ない |
| 為替 | `KIVRA_FX_USD_JPY`、`KIVRA_FX_AS_OF` | 両方必須。`KIVRA_FX_USD_JPY` は 1 以上 1000 以下。`KIVRA_FX_AS_OF` は ISO-8601 の瞬間か日付(日付は UTC 0 時) |

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
| `KIVRA_FX_MARGIN_RATIO` | `0.10` | 為替の余裕 |
| `KIVRA_FX_MAX_AGE_DAYS` | `7` | レートの最大の古さ |
| `KIVRA_BUDGET_TENANT_ID` | `default` | 予算のテナント |
| `KIVRA_BUDGET_MONTHLY_LIMIT_JPY` | `10000` | 月の上限(円)。ADR 0008 の記載どおり |
| `KIVRA_BUDGET_SOFT_RATIO` | `0.8` | 警告の比率 |

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

`UNKNOWN` になる主な理由(コード上の事実):

- PR が見つからない(`PR_NOT_FOUND`)。同じ head の PR が複数ある(`PR_AMBIGUOUS`)。
- 再開時に、ブランチが現在の base の head に無い(base が動いただけでも起きる)。
- LLM の呼び出し結果が不明。GitHub の書き込みの結果が不明。

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

この PR の時点では、本番の認証器は `NoPrMergeAuthority` のまま(配線は後続の PR で行う)。そのため、マージ済みの PR は完了せず、`AWAITING_MERGE_VERIFICATION` で待機する。認証器が GitHub を読めない場合も同じ待機になり、次の tick で再試行する。

待機理由と意味:

- `AWAITING_MERGE_VERIFICATION`: 認証器が答えない(未配線、または GitHub の一時的な障害)。自動で再試行する。
- `MERGE_NOT_VERIFIED`: 認証器が検証を拒否した。ログの `code` に固定の理由が出る(例: `HEAD_SHA_MISMATCH` は、マージ後に head が変わった、`AUTHOR_MISMATCH` は PR の作者が kivra の App ではない、`MERGE_COMMIT_SHA_MISSING` はマージの SHA が無い)。自動では解決しない。運用者が PR と Run を確認する。
- `BINDING_MISSING`: PR は開いているが binding が無い。この PR の前に開かれた Run(binding の記録より前に作られたもの)がこれに当たる。kivra は binding を自分で組み立てない(GitHub の値から推測しない)ため、完了させない。運用者が確認して解消する。

ログは `self-development run waiting: runId=... reason=... code=...` の形で出る(`code` は固定の理由コードだけ)。

### 4.4 キルスイッチで止める

**実装済み。** `KIVRA_SELFDEV_ENABLED` を `false` にして再起動する。無効のときは、ワーカーが止まり、`/api/selfdev/*` は登録されず、自己開発の秘密の環境変数は読まれない。

- 止まるのは Run の進行と新規の投入だけである。作成済みの PR は残る。kivra は PR を閉じない。
- `UNKNOWN` の Run は、無効化しても自動では解放されない。解放は §4.1 の API で行う。

## 5. 動作確認

トークンはプレースホルダである。認証は Task API と同じで、`Authorization: Bearer <JWT>`(無ければ `X-Forwarded-Access-Token`)。

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
