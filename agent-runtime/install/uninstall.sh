#!/usr/bin/env bash
# kivra-agent を取り除く。sudo で実行する。既定ではデータ(作業ディレクトリ、設定、client secret)を残す。
#   sudo bash uninstall.sh [--purge]
#   --purge: /var/lib/kivra-agent(作業ディレクトリ)と /etc/kivra-agent(client secret を含む)も消す
# 消さないもの: 専用ユーザー kivra-agent、ルートレス docker、linger、docker イメージ。必要なら手で消す
#   (userdel -r kivra-agent / loginctl disable-linger kivra-agent / docker rmi kivra-agent-proxy:local kivra-agent-runtime:local)
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
. "$here/lib.sh"

PURGE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --purge) PURGE=1; shift ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
if ! is_dry; then
  [ "$(id -u)" -eq 0 ] || die "sudo で実行してください"
fi

echo "== 停止と無効化"
if ! is_dry; then
  run_as_agent systemctl --user disable --now $UNITS >/dev/null 2>&1 || true
  uid="$(agent_uid)"
  run_as_agent env DOCKER_HOST="unix:///run/user/${uid}/docker.sock" sh -c \
    'for c in $(docker ps -aq --filter "name=^kivra-agent-"); do docker rm -f "$c"; done' >/dev/null 2>&1 || true
fi

echo "== ファイルを削除"
for u in $UNITS; do rm -f "$DESTDIR$UNIT_DIR/$u"; done
rm -f "$DESTDIR$BIN_DIR/kivra-agentctl"
rm -rf "$DESTDIR$PREFIX"
if ! is_dry; then run_as_agent systemctl --user daemon-reload >/dev/null 2>&1 || true; fi

if [ "$PURGE" = 1 ]; then
  echo "== --purge: データと設定を削除"
  rm -rf "$DESTDIR$STATE_DIR" "$DESTDIR$ETC_DIR"
else
  echo "データは残しました: $STATE_DIR, $ETC_DIR(--purge で削除)"
fi
echo "uninstall 完了"
