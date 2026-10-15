# Runbook: 自己開発(self-dev)の運用

対象: Kivra の自己開発の最小ループ(ADR 0008、Issue #56)。
この文書には秘密の値(トークン、API キー、Secret の中身)を書かない。名前だけを書く。
手順の状態は次の 3 つで書く。「実装済み」はコードで確認したもの、「方針」は ADR に書かれているが未配線のもの、「未実装」はコードに無いもの。

## 0. 現状(最初に読む)

確認基準: origin/main `f18aa84`(2026-10-16)。

- `kivra-app/src/main/kotlin/net/kigawa/kivra/Application.kt` は、自己開発の部品を構築していない。`SelfDevRunner`、`configureBudgetAdminRoutes`、`DbBudgetStore`、`CostConverter`、`DbRunAdmission` のいずれも本番の配線から呼ばれていない。
- `KIVRA_SELFDEV_ENABLED` を読む箇所は本リポジトリに無い。`SelfDevConfig.enabled` の既定は `false` だが、環境変数からは設定されない。
- `POST /api/selfdev/tasks` と `GET /api/selfdev/runs/{id}` は存在しない。
- このコミットの Kivra では、自己開発の経路は動かない。以下の手順のうち「実装済み」と書いていないものは、すべて未実装である。

## 1. 有効化の前提

| 項目 | 名前 | 状態 |
|---|---|---|
| 自己開発のキルスイッチ | 環境変数 `KIVRA_SELFDEV_ENABLED`(既定 `false`) | 未実装(環境変数の読み込みが無い) |
| Task API の認証モード | `KIVRA_AUTH_MODE=oidc-jwt`(本番用)。`oauth2-proxy` と `none` は `KIVRA_RUNTIME_ENV=development` のときだけ使える。未設定では Task API のルートを登録しない | 実装済み(`auth/TaskApiAuth.kt`)。自己開発の起動経路は未実装 |
| OIDC の検証条件 | `KIVRA_OIDC_ISSUER`、`KIVRA_OIDC_AUDIENCE`、`KIVRA_OIDC_REQUIRED_ROLE`(`oidc-jwt` で必須)。`KIVRA_OIDC_JWKS_URL` は任意 | 実装済み(`auth/OidcJwt.kt`) |
| GitHub のトークン | 細粒度 PAT。対象は `kigawa-net/kivra` のみ。権限は `contents:write` と `pull_requests:write` のみ。`administration` と `workflows` は付けない。管理者の権限を持つトークンは使わない | 方針(ADR 0008 §3(c))。Secret の名前は未決定。コードに読み込み経路は無い |
| Anthropic の API キー | k8s の Secret に置く(ADR 0008)。モデル registry の `credentialRef` は論理名で、環境変数名は信頼できる管理者の `CredentialBinding` で決まる。example の論理名は `ANTHROPIC_API_KEY` | 方針。`CredentialBinding` の本番配線は未実装 |
| モデル registry | provider の `dataHandling.status` が `CONFIRMED` であること。`CONFIRMED` には `confirmedBy`、`confirmedAt`、`sourceUrl` が必要。`UNKNOWN` のままでは INTERNAL 以上のデータに使えない | 型と検証は実装済み(`modelgateway/Registry.kt`)。本番での読み込みは未実装。`model-registry.example.yaml` は `UNKNOWN` のまま |
| 為替 | `KIVRA_FX_USD_JPY`(1 以上 1000 以下)と `KIVRA_FX_AS_OF`(ISO-8601 の瞬間、または日付。日付は UTC 0 時)。任意で `KIVRA_FX_MARGIN_RATIO`(既定 0.10)と `KIVRA_FX_MAX_AGE_DAYS`(既定 7) | 変換は実装済み(`modelgateway/CostConverter.kt`)。本番での構築は未実装。未設定なら換算しない |
| 予算 | ADR 0008 では月 1 万円、Run ごとの上限も持つ。上限の値は未確定 | 方針。本番での強制は未実装 |
| 対象リポジトリの保護 | `kigawa-net/kivra` の `main` にブランチ保護を付ける(kigawa-net/infra#296 の Terraform)。ADR 0008 の記載時点では未適用 | 方針。現在の適用状態は未確認 |

## 2. 有効化の順序

有効化はこの順で行う。ただし、本書の時点では手順 3 以降を実行できない(§0)。

1. §1 の前提をすべて満たす。特に、ブランチ保護が適用済みであることを確認する(未確認なら有効化しない)。
2. 全レプリカを新しいイメージにする。新旧が混在した状態で有効にしない。
3. レプリカを 1 にする。これは「maxActive の判定がプロセス内排他のため」という前提による。`maxActive` という名前の設定は本リポジトリに見つからなかったため、この前提は未確認である。
4. `KIVRA_SELFDEV_ENABLED=true` にして起動する。
5. ロールバックの準備として、新しい状態の Run(`PATCH_READY` と `UNKNOWN`)が残っていないことを確認する。読み取りの例は次のとおり。

   ```sql
   SELECT state, COUNT(*) FROM development_runs WHERE state IN ('PATCH_READY', 'UNKNOWN') GROUP BY state;
   ```

   ロールバックするときは、新しい状態の Run が残らない状態にしてから旧イメージに戻す。旧イメージが新しい状態を読めるかは未確認である。残っている `UNKNOWN` を解放する手順は未実装(§4.1)なので、解放できないときはロールバックの判断をエスカレーションする。

## 3. やってはいけないこと

- `org.mariadb` のログを TRACE または DEBUG にしない。INSERT の値(根拠・理由)がログに出る(運用メモによる)。
- レプリカを増やさない(§2 の 3)。
- GitHub のトークンに admin 権限を付けない。ブランチ保護の `enforce_admins = false` のため、管理者の権限を持つトークンは保護を迂回できる。
- kivra から PR をマージしない、PR を閉じない。マージの API は `GitHubPort` に無く、テストで固定されている(ADR 0008 §3(d))。
- Secret の値を、ログ、Issue、PR、本書に書かない。

## 4. 運用手順

### 4.1 UNKNOWN の Run を解放する

**未実装。** ADR 0008 では「運用者の監査つきの明示操作」として設計されている。現在のコードでは、`SelfDevRunner` が `UNKNOWN` の Run に対して `AWAITING_OPERATOR` を返すだけで、解放の API、CLI、手順は無い。DB を直接書き換える手順は本書に書かない。

関連する既存の挙動(コード上の事実):

- PR が見つからない場合は `UNKNOWN`(理由 `PR_NOT_FOUND`)。同じ head の PR が複数ある場合は `UNKNOWN`(理由 `PR_AMBIGUOUS`)。
- 再開時にブランチが現在の base の head に無い場合も `UNKNOWN` になる。base が動いただけでも起きる。

### 4.2 UNKNOWN_COST の acknowledge

**実装済み(ルートの定義)。ただし本番では未配線。** `configureBudgetAdminRoutes` は `Application.kt` から呼ばれていないため、現在のビルドではこのエンドポイントは登録されない。

- エンドポイント: `POST /api/admin/budget/acknowledge`
- 認証: Task API と同じ(`KIVRA_AUTH_MODE`)。オペレーターは認証された主体になり、リクエストの `operatorId` は無視される。
- リクエストの項目: `scopeId`、`period`、`reservationKey`、`actualAmountJpy`(任意。省略は「実費不明」、`"0"` は「検証済みの請求なし」)、`evidence`、`reason`
- 応答: 200 `ACKNOWLEDGED` または `ALREADY_ACKNOWLEDGED`、400 不正な入力、401 未認証、404 該当なし、409 未価格の予約ではない、503 ストアが判断できない

```sh
curl -X POST "https://<kivra-host>/api/admin/budget/acknowledge" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"scopeId":"<scopeId>","period":"<period>","reservationKey":"<reservationKey>","actualAmountJpy":"0","evidence":"<根拠の参照>","reason":"<理由>"}'
```

### 4.3 PR_ABANDONED(PR が閉じられた、またはタイムアウト)

**実装済み(自動の経路)。ただし Runner が配線されていないため、現在は動かない。**

- PR が閉じられた(マージされずに)場合、または最終更新から `prReviewTimeout`(既定 7 日)を過ぎた場合、Run は `PULL_REQUEST` から `CANCELLED` になり、監査の理由コードは `PR_ABANDONED` になる。
- kivra は PR を閉じない。閉じるのは人間である。
- マージ済みの PR は Run を `PULL_REQUEST` に残し、待機理由 `AWAITING_MERGE_VERIFICATION` になる。完了へ進める経路は無い(`completePrOnlyRun` の前提が揃わない)。
- オペレーターが手動で `PR_ABANDONED` を記録する手順は未実装。

### 4.4 キルスイッチで止める

**未実装。** ADR 0008 では `KIVRA_SELFDEV_ENABLED=false` で無効化するとあるが、この環境変数は読まれていない。現在のコードでは Runner が構築されないため、止める対象も無い。Runner を配線した後の停止手順は、そのときに決めて本書に追記する。

## 5. 動作確認

**未実装。** `POST /api/selfdev/tasks` と `GET /api/selfdev/runs/{id}` は存在しない。以下は ADR 0008 の設計(§ 実装の分割 ⑦)に基づく予定の例で、動作しない。トークンはプレースホルダである。

```sh
# 予定(未実装): タスクの投入
curl -X POST "https://<kivra-host>/api/selfdev/tasks" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"title":"<タイトル>","description":"<説明>","target":"kigawa-net/kivra"}'

# 予定(未実装): Run の状態の確認
curl -H "Authorization: Bearer <JWT>" "https://<kivra-host>/api/selfdev/runs/<runId>"
```

注意: `POST /api/dev/tasks` は開発用のプレビューであり、インメモリで、`SIMULATED` の結果を返す。自己開発の Run は作らないため、動作確認には使わない。
