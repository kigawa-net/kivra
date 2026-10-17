#!/usr/bin/env bash
# Runs ONE disposable agent container with the hardening flags. Usage:
#   run-agent.sh <host-workdir> <command> [args...]
# Env:
#   DETACH=1   start detached (for isolation checks) and print the container name
#   KIVRA_AGENT_IMAGE  default kivra-agent-runtime:local
# The workdir is the only host path mounted (rw). No docker socket, no ~/.claude, no ~/.ssh.
set -euo pipefail
if [ "$#" -lt 2 ]; then
  echo "usage: $0 <host-workdir> <command> [args...]" >&2
  exit 2
fi
WORKDIR="$1"; shift
PREFIX="${KIVRA_AGENT_PREFIX:-kivra-agent}"
IMAGE="${KIVRA_AGENT_IMAGE:-kivra-agent-runtime:local}"
NAME="${PREFIX}-run-$(date +%s)-$$"
[ -d "$WORKDIR" ] || { echo "workdir does not exist: $WORKDIR" >&2; exit 1; }

mode=(--rm -i)
if [ "${DETACH:-0}" = "1" ]; then
  mode=(-d)
fi
exec docker run "${mode[@]}" --name "$NAME" \
  --user 10001:10001 \
  --read-only \
  --tmpfs /tmp:rw,nosuid,size=512m \
  --tmpfs /home/agent:rw,nosuid,mode=0700,uid=10001,gid=10001,size=2g \
  --cap-drop=ALL --security-opt=no-new-privileges \
  --pids-limit 512 --memory 6g --cpus 4 \
  --network "${PREFIX}-internal" \
  -v "$WORKDIR:/work:rw" \
  -e ANTHROPIC_BASE_URL="http://${PREFIX}-proxy:8080/anthropic" \
  -e ANTHROPIC_API_KEY=kivra-agent-dummy-not-a-credential \
  -e HTTPS_PROXY="http://${PREFIX}-proxy:8080" \
  -e NO_PROXY=localhost,127.0.0.1 \
  -e JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=${PREFIX}-proxy -Dhttps.proxyPort=8080 -Dhttp.nonProxyHosts=localhost|127.0.0.1" \
  "$IMAGE" "$@"
