# agent-runtime: ローカルエージェントの実行イメージと egress プロキシ(試作)

ADR 0010 の PR 0(隔離・egress の検証)と PR 10(実行イメージ・プロキシ)の先行試作です。
Gradle モジュールではありません(`settings.gradle.kts` の include には入れていません)。
本物の Anthropic の資格情報は使っていません。live-smoke はユーザーが実行します。

関連: ADR 0010(決定 3・5・6)、設計案 `design-coding-runtime.md` の §3.3・§3.4・§7・§9。

## 構成

```
 ┌─────────────── ホスト (kivra-agent ユーザー, ルートレス docker) ───────────────┐
 │                                                                            │
 │  kivra-agent-run-<id> (Run ごとに 1 個, --rm)                               │
 │    claude -p ... ── ANTHROPIC_BASE_URL=http://kivra-agent-proxy:8080/anthropic │
 │    HTTPS_PROXY=http://kivra-agent-proxy:8080                                │
 │    /work (rw, その Run の作業ディレクトリだけ)                              │
 │         │ 接続先は内部ネットワークだけ                                       │
 │         ▼                                                                  │
 │  [kivra-agent-internal]  docker network create --internal (外への経路なし)  │
 │         │                                                                  │
 │  kivra-agent-proxy (egress proxy, 単一バイナリ)                             │
 │    /anthropic/v1/messages … → api.anthropic.com  (WIF の Bearer を付与)     │
 │    CONNECT host:443       → 許可リストのホストだけトンネル                  │
 │         │                                                                  │
 │  [kivra-agent-egress]    通常のブリッジ(プロキシだけが接続)                 │
 │         │                                                                  │
 └─────────┼──────────────────────────────────────────────────────────────────┘
           ▼
   user.kigawa.net (Keycloak: JWT)  /  api.anthropic.com (/v1/oauth/token, /v1/messages)
   repo.maven.apache.org, services.gradle.org, plugins.gradle.org, ... (許可リストのみ)
```

ファイル:

| パス | 内容 |
| --- | --- |
| `image/Dockerfile` | 実行イメージ。Ubuntu 24.04、uid 10001、JDK 21、git、Claude Code を検証付きで導入 |
| `proxy/` | egress proxy(Go 標準ライブラリのみ)。`wif.go`(WIF 認証)、`anthropic.go`(API 転送)、`egress.go`(CONNECT)、`allowlist.txt` |
| `proxy/cmd/mockupstream/` | 検証用の模擬サーバー(Keycloak・Anthropic トークン・API)。本番では使わない |
| `proxy/Dockerfile` | proxy イメージ(distroless)と、検証用の `mock` ターゲット |
| `network/create-networks.sh` | 内部ネットワークと外向きネットワークを作る(冪等) |
| `network/up-proxy.sh` | proxy を起動し、両方のネットワークに接続する |
| `network/run-agent.sh` | Run ごとの使い捨てコンテナを、隔離フラグ付きで起動する |
| `network/down.sh` | proxy と実行中のコンテナを止める |
| `verify/` | PR 0 (a)〜(c) の検証。`run-all.sh` が全部を回す。`live-smoke.sh` は実 API 用(未実行) |

## 認証(WIF。API キーから変更)

ADR 0010 の決定 6 は API キーを想定していましたが、ユーザーの決定により **WIF(Workload Identity Federation)** に変わりました。
コンテナには認証情報を一切置きません。認証は proxy だけが持ちます。

proxy の交換手順(`proxy/wif.go`。参考: `kivra-model-gateway/.../AnthropicWifAuth.kt`):

1. Keycloak(`https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/token`)に `client_credentials` で JWT を取る。client secret はファイルから読む。
2. JWT を `POST https://api.anthropic.com/v1/oauth/token` に `jwt-bearer` で渡し、アクセストークンを得る。`federation_rule_id`、`organization_id`、`service_account_id`、`workspace_id`(任意)を添える。
3. JWT の jti は 1 回限り。交換のたびに Keycloak から**新しい JWT を取る**(再利用しない)。
4. コンテナのリクエストに `Authorization: Bearer <アクセストークン>` を付ける。コンテナが送った `Authorization` と `x-api-key` は捨てる。
5. キャッシュ: 期限の 120 秒前から推奨更新(失敗してもキャッシュを使う)。30 秒前からは必須更新(失敗はエラー)。同時呼び出しは 1 回の交換に束ねる。
6. 上流が 401 を返したらキャッシュを捨てる。同じリクエストは再送しない。次の呼び出しで交換し直す。
7. 失敗は固定メッセージだけを返す。JWT、トークン、レスポンス本文、client secret はログにもエラーにも出さない。

**実装言語の選択**: Go の標準ライブラリだけで書いた単一の静的バイナリにしました。WIF の交換は HTTP 2 回と JSON で書け、
別の sidecar を足す必要がありません。単体試験(`go test`)も標準のまま書けます。nginx や squid では WIF の交換を書けないため除外しました。

**差し替え口**: `Authorizer` インターフェース(`proxy/auth.go`)。本番は `KIVRA_PROXY_AUTH=wif`(既定)。
テスト用に `KIVRA_PROXY_AUTH=static-test` と `KIVRA_PROXY_STATIC_TEST_KEY` で固定の偽キーを `x-api-key` として付けられます。本番では使いません(起動時に WARNING を出します)。

### 設定(非秘密)と秘密の置き場所

`/etc/kivra-agent/proxy.env`(非秘密。0640 で可):

```
KIVRA_PROXY_WIF_FEDERATION_RULE_ID=fdrl_...
KIVRA_PROXY_WIF_ORGANIZATION_ID=<uuid>
KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID=svac_...
KIVRA_PROXY_WIF_WORKSPACE_ID=wrkspc_...      # 任意
```

client secret: `/etc/kivra-agent/client-secret`。
- 所有者 root、グループ `kivra-agent`、モード 0640。ディレクトリ `/etc/kivra-agent` は root:kivra-agent の 0750。
- 読めるのは、ホストの kivra-agent ユーザーで動く proxy コンテナとエージェント本体だけです。作業用コンテナには一切マウントしません。
- proxy には read-only の bind mount で `/run/secrets/keycloak_client_secret` として見せます。
- 正本は Bitwarden(infra の `kivra-agent-keycloak-client-secret`)です。配置はユーザーが配置スクリプトで行います。手順の概要は、Bitwarden から値を取り出し、root で所有者・グループ・モードを設定することです。
- **チャット・git・ログに貼らないでください。**

> **決定(2026-10-17、ユーザー)**: client secret は root 所有・グループ kivra-agent・0640 にする。
> ルートレス docker では、コンテナ内の root がホストの kivra-agent に対応し、グループ kivra-agent の読み取り権限で secret を読めます。
> proxy コンテナは root(コンテナ内)で動かし、`--cap-drop=ALL` を付けます。
> **この前提は kivra-agent での実機確認が必要です(未確認)。** 読めない場合は下の代案を検討します。
>
> 採らなかった代案: (a) rootful docker で proxy を動かす(kivra-agent に docker を root 相当で使わせることになる)。
> (b) systemd の `LoadCredential=` で、権限のある経路から secret を渡す(system unit が要る。user unit で使えるかは未確認)。

## 起動手順(kivra-agent ユーザーで)

前提: kivra-agent ユーザー(uid 1001)、ルートレス docker(`DOCKER_HOST=unix:///run/user/1001/docker.sock`)。
docker.sock をコンテナに渡すことはありません。

```bash
export DOCKER_HOST=unix:///run/user/1001/docker.sock
cd /path/to/agent-runtime

# 1. イメージ(CLAUDE_CODE_VERSION は固定値。署名検証はイメージのビルド時に行う)
docker build -t kivra-agent-runtime:local --build-arg CLAUDE_CODE_VERSION=2.1.301 image
docker build -t kivra-agent-proxy:local proxy

# 2. ネットワーク(冪等)と proxy の起動
network/create-networks.sh
network/up-proxy.sh          # /etc/kivra-agent/proxy.env と client-secret を読む

# 3. Run(作業ディレクトリは Run ごとに作る。ホストの他のパスは mount しない)
network/run-agent.sh /path/to/run-workdir claude -p "..."
```

作業ディレクトリは、コンテナの uid 10001 が書ける必要があります(ルートレスでは、サブ uid への対応が要ります)。

停止: `network/down.sh`。

## 検証

### 1. テスト用の検証(この PC の rootful docker で実行済みの項目)

```bash
verify/run-all.sh            # build、Go 単体試験、egress、proxy e2e(模擬上流)、隔離検査、後片付け
verify/run-all.sh --no-build # 既存イメージを使う
```

テストのリソースは `kivra-agent-verify-` の接頭辞です。secret は実機のものを使わず、`run-all.sh` が偽の値の一時ファイルを作ります。終了時に、コンテナ・ネットワーク・テスト用イメージを削除します。
`DOCKER_HOST` を尊重するので、ルートレス docker でも同じスクリプトが動きます。

| 検証 | 内容 |
| --- | --- |
| Go 単体試験 | WIF の交換本文、毎回新しい JWT、更新しきい値(120 s と 30 s)、401 でのキャッシュ破棄、並行呼び出しの 1 回化、リダイレクト拒否、エラーに秘密が出ないこと、パス・メソッドの 403、コンテナの認証ヘッダー破棄、CONNECT の拒否、非公開 IP への接続拒否 |
| egress | 内部ネットワークから直接は外に出られない。proxy 経由で許可ホストだけ通る。未許可と 443 以外は 403 |
| proxy e2e | 模擬 Keycloak・トークン・API で、交換が 1 回であること、コンテナの `x-api-key` が上流に届かないこと、401 後の再交換、許可外パスの 403、proxy のログに秘密の印が無いこと |
| 隔離 | `docker inspect`: read-only、privileged 無し、cap-drop ALL、no-new-privileges、bind は作業ディレクトリだけ、uid 10001。コンテナ内: docker.sock 無し、`~/.ssh` 無し、`~/.claude` の認証無し、PID 1 の環境に秘密の値無し |

### 2. ルートレス docker での再実行(kivra-agent で行う)

`verify/run-all.sh` を `DOCKER_HOST=unix:///run/user/1001/docker.sock` で実行してください。確認する点:
- 内部ネットワーク(`--internal`)が、ルートレスの slirp4netns/pasta の下でも外への経路を持たないこと。
- proxy コンテナが両方のネットワークに入れること。
- 作業ディレクトリの所有者(サブ uid)と、コンテナ内の uid 10001 の対応。`--tmpfs /home/agent` の `uid=10001` が効くこと。
- `verify/test-isolation.sh` の結果。

### 3. live-smoke(ユーザーが実行。エージェントは実行していません)

前提: 本物の `proxy.env` と secret を配置し、`network/up-proxy.sh` で proxy を起動してあること。

```bash
verify/live-smoke.sh
```

内容: `claude -p "Reply with the single word: ok"` をプロキシ経由で実行し、`ok` が返ること、コンテナ内に認証ファイルが無いこと、
`ANTHROPIC_API_KEY` がダミーであること、認証用の環境変数が無いことを確認します。秘密は表示しません。

## 既知の限界と未確認

- **確認済み(公式ドキュメント)**: Claude Code がゲートウェイに呼ぶ必須エンドポイントは `POST /v1/messages`(`?beta=true` 付き)。`/v1/messages/count_tokens` は任意。`anthropic-beta` と `anthropic-version` は無改変で転送が必要(`code.claude.com/docs/en/llm-gateway-protocol`)。両方を許可している。
- **確認済み(公式ドキュメント)**: 起動時の `HEAD /api/hello`(接続の事前確認)は、拒否されても動作に影響しない。本 proxy は 403 を返す。既定では `GET /v1/models` も呼ばない(`CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1` のときだけ)。
- **確認済み(公式ドキュメント)**: 導入は `downloads.claude.ai/claude-code-releases/<version>/manifest.json`(+ `.sig`)と `<version>/<platform>/claude`、署名鍵の指紋 `31DD DE24 DDFA B679 F42D 7BD2 BAA9 29FF 1A7E CACE`。Dockerfile はこれに従い、manifest の署名と SHA256 を検査する。
- **確認済み(2026-10-17、実機)**: OAuth の beta ヘッダーは不要。`authorization: Bearer <WIF のアクセストークン>` と `anthropic-version: 2023-06-01` だけで Messages API が HTTP 200 を返した。プロキシは `anthropic-beta` / `anthropic-version` を無改変で転送する(Claude Code が自分で付けるヘッダーのため)。
- **未確認**: 上の一覧にないパスを Claude Code が呼ぶか。許可外は 403 になるので、live-smoke で 403 が出たらパスを `proxy/anthropic.go` に追加します。
- 許可ドメインへの送信は防げません。Anthropic・Gradle・Maven の先に秘密を送ることは、ドメイン単位の許可では止められません(設計案 §3.3 の残存リスク)。
- Gradle は JVM の設定でプロキシを使うため、`JAVA_TOOL_OPTIONS` で `https.proxyHost` を渡しています(環境変数の `HTTPS_PROXY` だけでは効きません)。
- 管理者設定(`/etc/claude-code/managed-settings.json` の `Read` の deny)は未実装です(設計案 §3.3 の第三層。PR 0 で確認する項目)。
- Gradle の依存取得は、許可リストの 3 ホストだけです(`services.gradle.org`、`plugins.gradle.org`、`repo.maven.apache.org`。kivra の `settings.gradle.kts` と wrapper の設定に一致)。`github.com` は含めていません。kivra のビルドで必要になれば追加します。
- 名前 `kivra-agent-proxy` は内部ネットワーク上の DNS 名に依存します。別の名前空間では `KIVRA_AGENT_PREFIX` を揃えてください。
- CI(`.github/workflows/ci.yml`)は、このディレクトリの Go 試験を実行しません。Go 試験と検証スクリプトは手元の docker で実行します。

## 実運用の注意

- **未確認: Gradle のプラグイン解決。** `plugins.gradle.org` から別のホスト(例: `plugins-artifacts.gradle.org`)へリダイレクトされる場合があります。許可リストに無いと、そのホストへの接続が拒否されます。コンテナ内で kivra の実ビルドを動かして確認してください。本試作では `./gradlew` は走らせていません。
- **不足の見つけ方**: proxy のログで `deny method=CONNECT dest=<host>:443 reason=not_allowlisted` の `dest` を見ます。そのホストを `proxy/allowlist.txt` に追加し、proxy イメージを作り直して `network/up-proxy.sh` で起動し直します。
- **503 `upstream authorization unavailable` の見分け方**: proxy のログ `deny method=POST path=/v1/messages reason=authorization_unavailable stage=<段階>` の `stage` で、失敗した段階が分かります。`upstream_status=<数値>` は、Keycloak または Anthropic の応答の HTTP ステータスです(本文は記録しません)。

| stage | 意味 | 対処 |
|---|---|---|
| `secret_unreadable` | client secret のファイルを読めない | パス(`KIVRA_PROXY_KEYCLOAK_CLIENT_SECRET_FILE`)、所有者、権限(0600 か 0640 root:kivra-agent)を確認する |
| `secret_empty` | secret のファイルが空(空白だけを含む) | ファイルの中身を入れ直す。中身は表示しない |
| `keycloak` | Keycloak が 200 以外を返した(`upstream_status` を見る) | 401/403: client secret・client ID・Keycloak 側の client 設定を確認する。5xx: Keycloak の状態を確認する |
| `keycloak_network` | Keycloak に接続できない(DNS、接続拒否、タイムアウト) | proxy 内の名前解決と経路、`KIVRA_PROXY_KEYCLOAK_TOKEN_URL` を確認する |
| `exchange` | Anthropic のトークン交換が 200 以外を返した(`upstream_status` を見る) | 401/403: federation rule、organization、service account、workspace の ID を確認する。4xx/5xx: Anthropic 側の状態を確認する |
| `exchange_network` | Anthropic のトークン交換先に接続できない | `api.anthropic.com` の名前解決と経路を確認する |
| `malformed` | 200 だが応答の JSON が想定と違う(トークンが空を含む) | Keycloak か Anthropic の応答形式が変わっていないかを確認する。応答本文は記録しない |
| `other` | 上のどれにも当たらない(リクエストの組み立てなど) | コードの経路を確認する。ログからは原因が分からないので、ソースを見る |

## ローカル PC へのインストール(install/)

専用ユーザー `kivra-agent` の PC に、kivra-agent 本体と上記の資材を配置し、`systemd --user` で常駐させる。手順、有効化の順序、ロールバックは `docs/runbook-selfdev.md` の §6.11 を参照する。

- `install/build-dist.sh`: origin/main の archive から `kivra-agent` を一般ユーザーでビルドする。
- `install/install.sh --dist <dir>`: sudo で `/opt/kivra-agent` に配置する(冪等。常駐は開始しない)。
- `install/kivra-agentctl.sh`: `status` / `start` / `disable` / `stop`(キルスイッチ、STOP ファイル)/ `resume`。
- `install/uninstall.sh`: 取り除く(`--purge` でデータと設定も消す)。
- `install/units/`: `kivra-agent-network`、`kivra-agent-proxy`、`kivra-agent` の user unit。
- `install/preflight.sh`: `kivra-agent.service` の `ExecStartPre`。client secret のモードと、real のときの docker・proxy・ネットワークを確かめる。
- ルートレス docker は起動後に作られた `/etc/kivra-agent` を見えない(secret が空のディレクトリになる)。`install.sh` は必要なとき docker.service を再起動し、preflight と `status` は見え方を検査する。詳細は runbook §6.11 の c の補足。

実行器は既定で stub(`KIVRA_AGENT_EXECUTOR=stub`)のまま。`real` への切り替えは、runbook §6.11 の順序 ⑤ で行う。
