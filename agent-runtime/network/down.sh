#!/usr/bin/env bash
# Stops the proxy. Agent containers still running (name prefix <prefix>-run-) are killed too.
set -euo pipefail
PREFIX="${KIVRA_AGENT_PREFIX:-kivra-agent}"
for c in $(docker ps -aq --filter "name=^${PREFIX}-run-"); do
  docker rm -f "$c" >/dev/null
done
docker rm -f "${PREFIX}-proxy" >/dev/null 2>&1 || true
echo "stopped ${PREFIX}-proxy and any ${PREFIX}-run-* containers (networks are kept)"
