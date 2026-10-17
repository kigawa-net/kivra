# Shared helpers for verify/*.sh. Sourced, not executed.
# All test resources use the prefix kivra-agent-verify- (containers, networks, images). The docker CLI
# honours DOCKER_HOST, so the same scripts run with rootful or rootless docker.
PREFIX="${KIVRA_AGENT_PREFIX:-kivra-agent-verify}"
export KIVRA_AGENT_PREFIX="$PREFIX"
export KIVRA_AGENT_IMAGE="${KIVRA_AGENT_IMAGE:-${PREFIX}-runtime:dev}"
export KIVRA_AGENT_PROXY_IMAGE="${KIVRA_AGENT_PROXY_IMAGE:-${PREFIX}-proxy:dev}"
MOCK_IMAGE="${PREFIX}-mock:dev"
CLIENT_IMAGE="${PREFIX}-client:dev"
MOCK_NAME="${PREFIX}-mock"
PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); echo "  PASS: $*"; }
ko()   { FAIL=$((FAIL + 1)); echo "  FAIL: $*" >&2; }
check() { # check <description> <command...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$desc"; else ko "$desc"; fi
}
summary() {
  echo "----"
  echo "pass=$PASS fail=$FAIL"
  [ "$FAIL" -eq 0 ]
}
# Test subnets (10.250.x.0/24 was unused on the development host). Override if they clash.
export KIVRA_AGENT_INTERNAL_SUBNET="${KIVRA_AGENT_INTERNAL_SUBNET:-10.250.10.0/24}"
export KIVRA_AGENT_EGRESS_SUBNET="${KIVRA_AGENT_EGRESS_SUBNET:-10.250.11.0/24}"
UPSTREAM_SUBNET="${KIVRA_AGENT_UPSTREAM_SUBNET:-10.250.12.0/24}"
