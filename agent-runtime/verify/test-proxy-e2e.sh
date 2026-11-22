#!/usr/bin/env bash
# PR 0 (b) and PR 10 proxy checks against the mock upstream (Keycloak + Anthropic token + API).
# Fake secrets and tokens only. Requires the proxy started with the mock env (run-all.sh does this).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib.sh"
PROXY="http://${PREFIX}-proxy:8080"
SECRET_MARKER="${VERIFY_SECRET_MARKER:?VERIFY_SECRET_MARKER must be set by run-all.sh}"
CONTAINER_KEY="container-fake-KEY-MARKER-7f3a"
CONTAINER_AUTH="container-fake-AUTH-MARKER-9c1e"

client() { # client <curl args...>
  docker run --rm --network "${PREFIX}-internal" --read-only --tmpfs /tmp --cap-drop=ALL "$CLIENT_IMAGE" curl -sS -m 30 "$@"
}
mock_state() {
  docker run --rm --network "${PREFIX}-upstream" --read-only --tmpfs /tmp --cap-drop=ALL "$CLIENT_IMAGE" curl -sS -m 10 "http://${MOCK_NAME}:9000/mock/state"
}
mock_revoke() {
  docker run --rm --network "${PREFIX}-upstream" --read-only --tmpfs /tmp --cap-drop=ALL "$CLIENT_IMAGE" curl -sS -m 10 -X POST "http://${MOCK_NAME}:9000/mock/revoke" >/dev/null
}
json_field() { # json_field <json> <field>  (prints the value, no secrets involved)
  printf '%s' "$1" | sed -n "s/.*\"$2\": *\([0-9]*\).*/\1/p"
}
msg_status() { # msg_status [path]  (POST with container-supplied auth headers)
  client -o /dev/null -w '%{http_code}' -X POST "$PROXY${1:-/anthropic/v1/messages}" \
    -H "x-api-key: $CONTAINER_KEY" -H "Authorization: Bearer $CONTAINER_AUTH" \
    -H 'content-type: application/json' -H 'anthropic-version: 2023-06-01' \
    -d '{"model":"mock","max_tokens":8,"messages":[{"role":"user","content":"ok"}]}'
}

echo "== (b) proxy with mock upstream"
st=$(mock_state); [ -n "$st" ] || { ko "mock upstream not reachable"; summary; exit 1; }
check "first /v1/messages through the proxy returns 200" sh -c "[ \"$(msg_status)\" = 200 ]"
st=$(mock_state)
check "exactly one Keycloak JWT issued and one token exchange" sh -c "echo '$st' | grep -q '\"keycloak_jwts_issued\": *1' && echo '$st' | grep -q '\"exchanges\": *1'"
check "container x-api-key never reaches upstream" sh -c "echo '$st' | grep -q '\"x_api_key_seen\": *0'"
code2=$(msg_status); st2=$(mock_state)
check "second request reuses the cached token (no new JWT, no new exchange)" sh -c "[ '$code2' = 200 ] && echo '$st2' | grep -q '\"keycloak_jwts_issued\": *1' && echo '$st2' | grep -q '\"exchanges\": *1'"
code_files=$(client -o /dev/null -w '%{http_code}' -X POST "$PROXY/anthropic/v1/files" -d '{}')
check "path outside the allowlist is 403 and does not reach upstream" [ "$code_files" = 403 ]
code_get=$(client -o /dev/null -w '%{http_code}' "$PROXY/anthropic/v1/messages")
check "GET to the messages path is 403" [ "$code_get" = 403 ]
mock_revoke
check "upstream 401 is returned to the client (no in-place retry)" sh -c "[ \"$(msg_status)\" = 401 ]"
check "after 401 the next request exchanges again (new JWT) and succeeds" sh -c "[ \"$(msg_status)\" = 200 ]"
st=$(mock_state)
check "after revoke + 401: two JWTs and two exchanges in total" sh -c "echo '$st' | grep -q '\"keycloak_jwts_issued\": *2' && echo '$st' | grep -q '\"exchanges\": *2'"
check "container x-api-key still never reached upstream" sh -c "echo '$st' | grep -q '\"x_api_key_seen\": *0'"

echo "== named Claude accounts"
code_a=$(msg_status /anthropic/repo-a/v1/messages)
st=$(mock_state)
check "repo-a has its own token exchange" [ "$code_a:$(json_field "$st" exchanges)" = "200:3" ]
code_b=$(msg_status /anthropic/repo-b/v1/messages/count_tokens)
st=$(mock_state)
check "repo-b count_tokens has its own token exchange" [ "$code_b:$(json_field "$st" exchanges)" = "200:4" ]
code_a=$(msg_status /anthropic/repo-a/v1/messages/count_tokens)
code_b=$(msg_status /anthropic/repo-b/v1/messages)
code_default=$(msg_status)
before_denied=$(mock_state)
check "each route reuses its cached token" [ "$code_a:$code_b:$code_default:$(json_field "$before_denied" exchanges)" = "200:200:200:4" ]
unknown=$(msg_status /anthropic/unknown/v1/messages)
malformed=$(msg_status /anthropic/%72epo-a/v1/messages)
after_denied=$(mock_state)
check "unknown and malformed account IDs return 403" [ "$unknown:$malformed" = "403:403" ]
check "denied routes cause no upstream requests or exchanges" [ "$before_denied" = "$after_denied" ]
check "named routes discard container x-api-key" [ "$(json_field "$after_denied" x_api_key_seen)" = "0" ]

echo "== (b) logs must not contain secret material"
logs=$(docker logs "${PREFIX}-proxy" 2>&1)
for marker in "$SECRET_MARKER" "$CONTAINER_KEY" "$CONTAINER_AUTH" "sk-ant-oat01" "eyJmock" "access_token" "svac_VERIFY" "wrkspc_VERIFY"; do
  if printf '%s' "$logs" | grep -q -- "$marker"; then ko "proxy log contains a secret marker ($marker)"; else ok "proxy log is free of marker: $marker"; fi
done
summary
