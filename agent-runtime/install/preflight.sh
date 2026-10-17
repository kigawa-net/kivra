#!/usr/bin/env bash
# kivra-agent.service の ExecStartPre。起動前の検査だけを行う。秘密の中身は読まず、値も出さない(パスとモードだけ)。
# 検査: client secret のモード(グループ・その他に読ませない)、real のときだけ docker、proxy、内部ネットワーク、
#   デーモンから secret がファイルとして見えること。
set -uo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
. "$here/lib.sh"
fail() { echo "kivra-agent preflight: $*" >&2; exit 1; }

SECRET="${KIVRA_AGENT_CLIENT_SECRET_FILE:-/etc/kivra-agent/client-secret}"
[ -f "$SECRET" ] || fail "client secret のファイルがありません: $SECRET"
mode="$(stat -c %a "$SECRET" 2>/dev/null)" || fail "client secret のモードを読めません"
[ $((8#$mode & 077)) -eq 0 ] || fail "client secret のモードが $mode です(0600 にしてください。JVM はグループ・その他が読めるファイルを拒否します)"

if [ "${KIVRA_AGENT_EXECUTOR:-stub}" = real ]; then
  command -v docker >/dev/null 2>&1 || fail "docker CLI がありません"
  timeout 15 docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || fail "docker デーモンに接続できません(DOCKER_HOST を確認)"
  [ "$(docker inspect -f '{{.State.Running}}' kivra-agent-proxy 2>/dev/null)" = true ] \
    || fail "egress proxy が動いていません(kivra-agent-proxy.service を確認)"
  docker network inspect "${KIVRA_AGENT_DOCKER_NETWORK:-kivra-agent-internal}" >/dev/null 2>&1 \
    || fail "内部ネットワークがありません(kivra-agent-network.service を確認)"
  # デーモンから secret がファイルとして見えるか(中身は読まない)。/etc/kivra-agent より後に起動したデーモンでは空のディレクトリに見える。
  case "$(secret_view "$SECRET")" in
    file) ;;
    dir) fail "デーモンから client secret がディレクトリに見えます(デーモンは /etc/kivra-agent より前に起動した)。rootless docker を再起動してください: systemctl --user restart docker.service" ;;
    *) fail "デーモンから client secret を確認できません(イメージ ${KIVRA_AGENT_DOCKER_IMAGE:-kivra-agent-runtime:local} の有無と docker を確認)" ;;
  esac
fi
echo "kivra-agent preflight: OK (executor=${KIVRA_AGENT_EXECUTOR:-stub})"
