#!/bin/bash
# ローカル検証用に kivra-app を実サーバとして起動する。本番の設定ではない。
#
# 構成: 認証は oidc-jwt(ローカルの JWKS と、このディレクトリの oidc-local.mjs が発行するトークン)。
# DB は H2 のファイル(WORK 配下)。selfdev・自律運転・order・記憶・ロールは有効、外部(GitHub・Anthropic)の資格情報は
# ダミーなので、外部への呼び出しは失敗する(画面の確認には影響しない)。
#
# 前提: 先に `./gradlew :kivra-app:installDist` を実行しておく(H2 は testImplementation のみなので、
# このスクリプトが H2 の jar を classpath に足す。本体のビルドは変えない)。
#
# 使い方: scripts/compose-ui-crawl/run-local-server.sh   (フォアグラウンドで起動。止めるときは Ctrl-C)
# 環境変数: KIVRA_LOCAL_DIR(作業ディレクトリ。既定 $HOME/.cache/kivra-local)、PORT(既定 18080)、
#           H2_JAR(H2 の jar。既定は Gradle キャッシュから探す)、OIDC_PORT(JWKS の待ち受け。既定 18090)
#           ※ OIDC_PORT を変えたときは、トークンを作る側にも OIDC_ISSUER=http://127.0.0.1:<OIDC_PORT>/realms/local を渡す。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
APP="$REPO/kivra-app/build/install/kivra-app"
WORK="${KIVRA_LOCAL_DIR:-$HOME/.cache/kivra-local}"
PORT="${PORT:-18080}"
OIDC_PORT="${OIDC_PORT:-18090}"   # JWKS の待ち受け。同じマシンで別の起動が使っているときは OIDC_PORT で変える(ISSUER も変わる)
ISS="http://127.0.0.1:${OIDC_PORT}/realms/local"

if [ ! -d "$APP/lib" ]; then
  echo "kivra-app が未ビルドです。先に ./gradlew :kivra-app:installDist を実行してください。" >&2
  exit 1
fi

H2_JAR="${H2_JAR:-$(find "$HOME/.gradle/caches" -name 'h2-2.3.232.jar' 2>/dev/null | head -1)}"
if [ -z "$H2_JAR" ]; then
  echo "H2 の jar が見つかりません。H2_JAR=<path> で指定してください。" >&2
  exit 1
fi

mkdir -p "$WORK/h2"
export OIDC_DIR="$WORK/oidc"
if [ ! -f "$OIDC_DIR/jwks.json" ]; then
  node "$HERE/oidc-local.mjs" gen
fi
# JWKS の配信は別プロセス。既に動いていれば起動しない。
if ! curl -s -o /dev/null "http://127.0.0.1:${OIDC_PORT}/certs"; then
  nohup node "$HERE/oidc-local.mjs" serve > "$WORK/oidc.log" 2>&1 &
  sleep 1
fi

# 自律運転の planner が使うモデル(claude-haiku-5-5)を active にしたレジストリ。価格はプレースホルダ。
cat > "$WORK/registry.yaml" <<'YAML'
registryVersion: "local-verification"
providers:
  - id: anthropic
    adapter: anthropic
    trustZone: EXTERNAL
    dataHandling:
      status: UNKNOWN
    enabled: true
    endpoint: https://api.anthropic.com
    credentialRef: ANTHROPIC_API_KEY
models:
  - id: claude-haiku-5-5
    providerId: anthropic
    providerModelName: claude-haiku-5-5
    lifecycle: ACTIVE
    capabilities: [TEXT, TOOL_CALLING, STRUCTURED_OUTPUT_NATIVE, STREAMING, SYSTEM_PROMPT]
    contextWindowTokens: 200000
    maxOutputTokens: 64000
    qualityTier: B
    price:
      inputPerMtok: "1.00"
      outputPerMtok: "5.00"
      currency: USD
      sourceRef: "local placeholder"
  - id: claude-sonnet
    providerId: anthropic
    providerModelName: claude-sonnet-5-5
    lifecycle: ACTIVE
    capabilities: [TEXT, TOOL_CALLING, STRUCTURED_OUTPUT_NATIVE, STREAMING, SYSTEM_PROMPT]
    contextWindowTokens: 200000
    maxOutputTokens: 64000
    qualityTier: A
    price:
      inputPerMtok: "3.00"
      outputPerMtok: "15.00"
      currency: USD
      sourceRef: "local placeholder"
YAML

# owner key = <iss の UTF-8 バイト長>:<iss>:<sub>
ISS_LEN=$(printf '%s' "$ISS" | wc -c)
export KIVRA_RUNTIME_ENV=local
export KIVRA_AUTH_MODE=oidc-jwt
export KIVRA_OIDC_ISSUER="$ISS"
export KIVRA_OIDC_JWKS_URL="http://127.0.0.1:${OIDC_PORT}/certs"
export KIVRA_OIDC_AUDIENCE=kivra-local
export KIVRA_OIDC_REQUIRED_ROLE=admin-panel:admin
export KIVRA_DB_JDBC_URL="jdbc:h2:file:${WORK}/h2/kivra;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
export KIVRA_DB_USER=sa
export KIVRA_DB_PASSWORD=
export PORT
export KIVRA_SELFDEV_ENABLED=true
export KIVRA_SELFDEV_GITHUB_TOKEN=local-dummy-not-used
export KIVRA_SELFDEV_ANTHROPIC_API_KEY=local-dummy-not-used
export KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID=1
export KIVRA_SELFDEV_MODEL_REGISTRY_PATH="$WORK/registry.yaml"
export KIVRA_SELFDEV_RUNTIME=cli
export KIVRA_SELFDEV_ADVANCE_INTERVAL_SECONDS=3600
export KIVRA_FX_USD_JPY=150
export KIVRA_FX_AS_OF="$(date +%Y-%m-%d)"
export KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP=5
export KIVRA_AGENT_API_ENABLED=true
export KIVRA_AGENT_AZP=local-agent
export KIVRA_AGENT_REQUIRED_ROLE=agent:agent
export KIVRA_SELFDEV_AUTOPILOT_ENABLED=true
export KIVRA_SELFDEV_ORDERS_ENABLED=true
export KIVRA_SELFDEV_ROOT_APPROVER="${ISS_LEN}:${ISS}:local-root"
export KIVRA_SELFDEV_AI_PRINCIPAL="${ISS_LEN}:${ISS}:local-ai"
export KIVRA_SELFDEV_MEMORY_ENABLED=true
export KIVRA_SELFDEV_ROLES_MODE=enforce
export KIVRA_SELFDEV_PROJECT_MODE=enforce

echo "kivra-app を http://127.0.0.1:${PORT} で起動します(作業: $WORK)"
exec java -cp "$APP/lib/*:$H2_JAR" io.ktor.server.netty.EngineMain
