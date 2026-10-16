#!/usr/bin/env bash
# Orchestrates the PR 0 / PR 10 verification with test-only resources (kivra-agent-verify-*).
# Usage: verify/run-all.sh [--no-build]
# Requires: docker (rootful or rootless via DOCKER_HOST), network access for test-egress.sh.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$(cd "$here/.." && pwd)"
. "$here/lib.sh"
NO_BUILD=0; [ "${1:-}" = "--no-build" ] && NO_BUILD=1
tmp=$(mktemp -d)
SECRET_VALUE="TEST-CLIENT-SECRET-DO-NOT-LOG-$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
export VERIFY_SECRET_MARKER="$SECRET_VALUE"

cleanup() {
  for c in $(docker ps -aq --filter "name=^${PREFIX}-"); do docker rm -f "$c" >/dev/null 2>&1; done
  for n in "${PREFIX}-internal" "${PREFIX}-egress" "${PREFIX}-upstream"; do docker network rm "$n" >/dev/null 2>&1; done
  for i in "$KIVRA_AGENT_IMAGE" "$KIVRA_AGENT_PROXY_IMAGE" "$MOCK_IMAGE" "$CLIENT_IMAGE"; do docker rmi "$i" >/dev/null 2>&1; done
  rm -rf "$tmp"
}
trap cleanup EXIT

if [ "$NO_BUILD" = 0 ]; then
  echo "== build"
  docker build -q -t "$KIVRA_AGENT_IMAGE" -f "$root/image/Dockerfile" "$root/image" >/dev/null || { echo "runtime build failed" >&2; exit 1; }
  docker build -q -t "$KIVRA_AGENT_PROXY_IMAGE" -f "$root/proxy/Dockerfile" "$root/proxy" >/dev/null || { echo "proxy build failed" >&2; exit 1; }
  docker build -q --target mock -t "$MOCK_IMAGE" -f "$root/proxy/Dockerfile" "$root/proxy" >/dev/null || { echo "mock build failed" >&2; exit 1; }
  docker build -q -t "$CLIENT_IMAGE" -f - "$here" >/dev/null <<EOD || { echo "client build failed" >&2; exit 1; }
FROM $KIVRA_AGENT_IMAGE
USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
USER 10001:10001
EOD
fi

echo "== unit tests (Go, stdlib only)"
docker run --rm --name "${PREFIX}-unit" -v "$root/proxy:/src:ro" -e GOFLAGS=-buildvcs=false -e GOCACHE=/tmp/gocache \
  -w /src golang:1.25-bookworm sh -c 'cp -r /src /tmp/src && cd /tmp/src && go test -count=1 ./...' \
  && echo "  PASS: go test ./..." || { echo "  FAIL: go test" >&2; FAILED=1; }

echo "== network and proxy"
# Test secret: fake, mode 0444 so the container's root (dropped caps) can read it.
printf '%s\n' "$SECRET_VALUE" > "$tmp/keycloak_client_secret" && chmod 0444 "$tmp/keycloak_client_secret"
cat > "$tmp/proxy.env" <<EOF2
KIVRA_PROXY_KEYCLOAK_TOKEN_URL=http://${MOCK_NAME}:9000/realms/kigawa-net/protocol/openid-connect/token
KIVRA_PROXY_ANTHROPIC_TOKEN_URL=http://${MOCK_NAME}:9000/v1/oauth/token
KIVRA_PROXY_ANTHROPIC_UPSTREAM=http://${MOCK_NAME}:9000
KIVRA_PROXY_WIF_FEDERATION_RULE_ID=fdrl_VERIFY
KIVRA_PROXY_WIF_ORGANIZATION_ID=00000000-0000-4000-8000-0000000000aa
KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID=svac_VERIFY
KIVRA_PROXY_WIF_WORKSPACE_ID=wrkspc_VERIFY
EOF2
docker network inspect "${PREFIX}-upstream" >/dev/null 2>&1 || docker network create --driver bridge --subnet "$UPSTREAM_SUBNET" "${PREFIX}-upstream" >/dev/null || { echo "upstream network failed" >&2; exit 1; }
docker run -d --name "$MOCK_NAME" --network "${PREFIX}-upstream" -e MOCK_CLIENT_SECRET="$SECRET_VALUE" "$MOCK_IMAGE" >/dev/null \
  || { echo "mock start failed" >&2; exit 1; }
sleep 2
KIVRA_AGENT_PROXY_ENV_FILE="$tmp/proxy.env" \
KIVRA_AGENT_KEYCLOAK_SECRET_FILE="$tmp/keycloak_client_secret" \
KIVRA_AGENT_EXTRA_NETWORKS="${PREFIX}-upstream" \
  "$root/network/up-proxy.sh" || { echo "proxy start failed" >&2; exit 1; }
sleep 2

"$here/test-egress.sh" || FAILED=1
"$here/test-proxy-e2e.sh" || FAILED=1
"$here/test-isolation.sh" || FAILED=1

if [ "${FAILED:-0}" = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED" >&2; exit 1; fi
