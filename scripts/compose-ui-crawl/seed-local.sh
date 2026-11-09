#!/bin/bash
# run-local-server.sh で起動したローカルの kivra-app に、画面確認用のサンプルデータを API(POST)で入れる。
# 実行するたびにプロジェクトなどが増える(同じ内容の再送の扱いは確認していない)。空の DB から一度だけ流すこと。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${KIVRA_LOCAL_DIR:-$HOME/.cache/kivra-local}"
BASE="${BASE:-http://127.0.0.1:${PORT:-18080}}"
export OIDC_DIR="$WORK/oidc"
TOK="$(node "$HERE/oidc-local.mjs" token local-root)"
AUTH=(-H "Authorization: Bearer $TOK" -H "Content-Type: application/json")

post() { curl -s "${AUTH[@]}" -X POST "$BASE$1" -d "$2"; echo; }
id_of() { sed -n 's/.*"projectId":"\([^"]*\)".*/\1/p' | head -1; }

echo "--- プロジェクト"
PA="$(post /api/projects '{"name":"サンプルA(開発)","description":"ローカル検証用のプロジェクト"}' | id_of)"
PB="$(post /api/projects '{"name":"サンプルB(運用)","description":"二つ目"}' | id_of)"
echo "A=$PA B=$PB"

echo "--- 開発目標"
post "/api/projects/$PA/tasks" "{\"title\":\"ログイン画面を直す\",\"body\":\"本文のサンプル\",\"idempotencyKey\":\"seed-goal-a-1\",\"projectId\":\"$PA\"}" >/dev/null
post "/api/projects/$PA/tasks" "{\"title\":\"README を整える\",\"body\":null,\"idempotencyKey\":\"seed-goal-a-2\",\"projectId\":\"$PA\"}" >/dev/null
post "/api/projects/$PB/tasks" "{\"title\":\"監視を追加\",\"body\":\"\",\"idempotencyKey\":\"seed-goal-b-1\",\"projectId\":\"$PB\"}" >/dev/null

echo "--- order(maxCycles は KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP=5 以下にする)"
post "/api/projects/$PA/orders" "{\"title\":\"週次の改善 order\",\"policy\":\"小さく進める\",\"maxCycles\":3,\"projectId\":\"$PA\"}" >/dev/null

echo "--- Run(シミュレーション)"
post /api/tasks '{"title":"サンプルのタスク実行","idempotencyKey":"seed-run-1"}' >/dev/null
echo "done"
