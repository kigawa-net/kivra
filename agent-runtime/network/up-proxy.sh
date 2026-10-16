#!/usr/bin/env bash
# Starts the egress proxy (replaces any previous one with the same name).
# The proxy joins <prefix>-egress (outbound) and then <prefix>-internal (agent containers reach it).
# Optional: KIVRA_AGENT_EXTRA_NETWORKS="net1 net2" to attach more networks (used by verify/ only).
#
# Inputs (none of them is a secret value, except the secret file, which is mounted and never printed):
#   KIVRA_AGENT_PROXY_ENV_FILE         env file with KIVRA_PROXY_* settings (WIF IDs, URLs). Default /etc/kivra-agent/proxy.env
#   KIVRA_AGENT_KEYCLOAK_SECRET_FILE   host file with the Keycloak client secret. Default /etc/kivra-agent/keycloak_client_secret
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
PREFIX="${KIVRA_AGENT_PREFIX:-kivra-agent}"
PROXY_IMAGE="${KIVRA_AGENT_PROXY_IMAGE:-kivra-agent-proxy:local}"
ENV_FILE="${KIVRA_AGENT_PROXY_ENV_FILE:-/etc/kivra-agent/proxy.env}"
SECRET_HOST_FILE="${KIVRA_AGENT_KEYCLOAK_SECRET_FILE:-/etc/kivra-agent/keycloak_client_secret}"
NAME="${PREFIX}-proxy"

[ -r "$ENV_FILE" ] || { echo "env file is not readable: $ENV_FILE" >&2; exit 1; }
[ -r "$SECRET_HOST_FILE" ] || { echo "secret file is not readable: $SECRET_HOST_FILE" >&2; exit 1; }

"$here/create-networks.sh"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --restart unless-stopped \
  --network "${PREFIX}-egress" \
  --read-only --tmpfs /tmp:rw,size=16m \
  --cap-drop=ALL --security-opt=no-new-privileges \
  --pids-limit 128 --memory 256m \
  --env-file "$ENV_FILE" \
  -v "$SECRET_HOST_FILE:/run/secrets/keycloak_client_secret:ro" \
  "$PROXY_IMAGE" >/dev/null
docker network connect "${PREFIX}-internal" "$NAME"
for n in ${KIVRA_AGENT_EXTRA_NETWORKS:-}; do
  docker network connect "$n" "$NAME"
done
echo "started $NAME"
