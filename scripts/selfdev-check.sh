#!/usr/bin/env bash
set -e

# 自己開発機能の動作確認スクリプト

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="${APP_DIR}/kivra"

echo "=== 自己開発機能の動作確認 ==="
echo "作業ディレクトリ：${APP_DIR}"
echo ""

# 1. Docker コンテナが起動しているか確認
echo "1. Docker コンテナの確認"
if docker compose -f "${APP_DIR}/compose.dev.yml" ps 2>/dev/null | grep -q "kivra-app"; then
  echo "   ✓ kivra-app コンテナが稼働中"
else
  echo "   × kivra-app コンテナが稼働していない"
  echo "   起動します..."
  docker compose -f "${APP_DIR}/compose.dev.yml" up --build -d --wait
fi
echo ""

# 2. ヘルスチェック
echo "2. ヘルスチェック"
HEALTH_RESPONSE=$(curl -fsS http://localhost:8080/health || true)
if echo "${HEALTH_RESPONSE}" | grep -q '"status":"ok"'; then
  echo "   ✓ ヘルスチェック OK: ${HEALTH_RESPONSE}"
else
  echo "   × ヘルスチェック失敗"
  echo "   応答: ${HEALTH_RESPONSE}"
  exit 1
fi
echo ""

# 3. selfdev/orders API の確認
echo "3. selfdev/orders API の確認"
ORDERS=$(curl -fsS http://localhost:8080/api/selfdev/orders || true)
if echo "${ORDERS}" | grep -q '"orders"'; then
  echo "   ✓ selfdev/orders API 正常"
  echo "   応答: ${ORDERS}"
else
  echo "   × selfdev/orders API 失敗"
  echo "   応答: ${ORDERS}"
  exit 1
fi
echo ""

# 4. サンプルの自己開発タスク実行
echo "4. サンプルの自己開発タスク実行"
SAMPLE_ORDER_ID=$(echo "${ORDERS}" | jq -r '.orders[0].id' || echo "test-order-1")
echo "   Order ID: ${SAMPLE_ORDER_ID}"
echo "   タイトル：$(echo "${ORDERS}" | jq -r '.orders[0].title')"
echo ""

# 5. 実行結果の確認
echo "5. 実行結果の確認"
ORDER_STATUS=$(curl -fsS http://localhost:8080/api/selfdev/orders/${SAMPLE_ORDER_ID} || true)
if echo "${ORDER_STATUS}" | grep -q '"state"'; then
  echo "   ✓ Order ステータス確認可能"
  echo "   応答: ${ORDER_STATUS}"
else
  echo "   × Order ステータス確認失敗"
  echo "   応答: ${ORDER_STATUS}"
fi
echo ""

echo "=== 動作確認完了 ==="
echo ""
echo "後片付け:"
echo "  docker compose -f ${APP_DIR}/compose.dev.yml down -v"
