#!/usr/bin/env bash
# LIVE smoke test against the real Anthropic API through the WIF proxy. NOT run by the agent that wrote it.
# The operator runs this after: network/create-networks.sh, the real /etc/kivra-agent/proxy.env and the secret file
# are in place, and network/up-proxy.sh has started the proxy.
#
# It never prints credentials. It checks:
#   1. claude -p answers "ok" through the proxy with the dummy key,
#   2. no credential file was written inside the container,
#   3. no real credential-like value is in the container environment (the key must be the dummy).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$(cd "$here/.." && pwd)"
PREFIX="${KIVRA_AGENT_PREFIX:-kivra-agent}"
work=$(mktemp -d); chmod 0777 "$work"; trap 'rm -rf "$work"' EXIT

out=$("$root/network/run-agent.sh" "$work" claude -p "Reply with the single word: ok" 2>/dev/null) || { echo "FAIL: claude -p exited non-zero" >&2; exit 1; }
norm=$(printf '%s' "$out" | tr -d '[:space:].' | tr 'A-Z' 'a-z')
[ "$norm" = "ok" ] && echo "PASS: claude -p returned ok" || { echo "FAIL: unexpected answer (length ${#out})" >&2; exit 1; }

"$root/network/run-agent.sh" "$work" sh -c '
  test ! -e "$HOME/.claude/.credentials.json" && test ! -e "$HOME/.claude.json.credentials" \
    && echo "PASS: no credential file written" || { echo "FAIL: credential file present"; exit 1; }
  [ "$ANTHROPIC_API_KEY" = kivra-agent-dummy-not-a-credential ] && echo "PASS: API key env is the dummy" \
    || { echo "FAIL: API key env is not the dummy"; exit 1; }
  env | grep -Eq "^(ANTHROPIC_AUTH_TOKEN|CLAUDE_CODE_OAUTH_TOKEN)=" && { echo "FAIL: auth token env present"; exit 1; } || echo "PASS: no auth token env"
' || { echo "FAIL: container checks" >&2; exit 1; }
echo "live smoke finished"
