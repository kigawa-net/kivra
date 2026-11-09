#!/bin/bash
# 未分類(project_id が NULL)のデータを作る。seed-local.sh の後、サーバを止めた状態で一度だけ実行する。
#
# 作るもの: 開発目標「監視を追加」(プロジェクト B の目標)と order「未分類に回る order(検証用)」の project_id を NULL にする。
# Run は seed-local.sh の「シミュレーション」の Run が最初から NULL(プロジェクトを持たない)。
# 作成の API は project_id の無い作成を拒否する(ADR 0022 PR-C)ので、H2 の UPDATE で NULL にする。
# 記憶(selfdev_memory_records)は API で作れないため、ここでは扱わない。
#
# 使い方: scripts/compose-ui-crawl/seed-unassigned-local.sh   (サーバが止まっていること。H2 のファイルロックのため)
# 環境変数: KIVRA_LOCAL_DIR(既定 $HOME/.cache/kivra-local)、H2_JAR(H2 の jar。既定は Gradle キャッシュから探す)
set -euo pipefail

WORK="${KIVRA_LOCAL_DIR:-$HOME/.cache/kivra-local}"
PORT="${PORT:-18080}"
H2_JAR="${H2_JAR:-$(find "$HOME/.gradle/caches" -name 'h2-2.3.232.jar' 2>/dev/null | head -1)}"

if [ -z "$H2_JAR" ]; then
  echo "H2 の jar が見つかりません。H2_JAR=<path> で指定してください。" >&2
  exit 1
fi
if curl -s -o /dev/null -m 2 "http://127.0.0.1:${PORT}/health"; then
  echo "サーバが動いています。止めてから実行してください(H2 のファイルはサーバが開いている間は使えません)。" >&2
  exit 1
fi

URL="jdbc:h2:file:${WORK}/h2/kivra;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
run_sql() {
  java -cp "$H2_JAR" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$1"
}

run_sql "UPDATE selfdev_tasks SET project_id = NULL WHERE title = '監視を追加'"
run_sql "UPDATE selfdev_orders SET project_id = NULL WHERE title = '未分類に回る order(検証用)'"
echo "done"
