#!/usr/bin/env bash
# PR 0 (a): from a container on the internal network, direct egress is impossible and only
# allowlisted HTTPS hosts succeed through the proxy. Needs the proxy running (run-all.sh starts it).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib.sh"
PROXY="http://${PREFIX}-proxy:8080"
echo "== (a) egress"
check "direct https://example.com fails from the internal network" \
  sh -c "! docker run --rm --network ${PREFIX}-internal --read-only --tmpfs /tmp --cap-drop=ALL $CLIENT_IMAGE curl -sS -m 10 -o /dev/null https://example.com"
check "direct https://repo.maven.apache.org fails (no route without the proxy)" \
  sh -c "! docker run --rm --network ${PREFIX}-internal --read-only --tmpfs /tmp --cap-drop=ALL $CLIENT_IMAGE curl -sS -m 10 -o /dev/null https://repo.maven.apache.org/maven2/"
check "via proxy: allowlisted repo.maven.apache.org is reachable" \
  sh -c "code=\$(docker run --rm --network ${PREFIX}-internal --read-only --tmpfs /tmp --cap-drop=ALL $CLIENT_IMAGE curl -sS -m 30 -x $PROXY -o /dev/null -w '%{http_code}' https://repo.maven.apache.org/maven2/); case \$code in 2*|3*) exit 0;; *) exit 1;; esac"
check "via proxy: allowlisted services.gradle.org is reachable" \
  sh -c "code=\$(docker run --rm --network ${PREFIX}-internal --read-only --tmpfs /tmp --cap-drop=ALL $CLIENT_IMAGE curl -sS -m 30 -x $PROXY -o /dev/null -w '%{http_code}' https://services.gradle.org/distributions/); case \$code in 2*|3*) exit 0;; *) exit 1;; esac"
check "via proxy: example.com is refused with 403 at CONNECT" \
  sh -c "docker run --rm --network ${PREFIX}-internal --read-only --tmpfs /tmp --cap-drop=ALL $CLIENT_IMAGE curl -sS -m 15 -x $PROXY -o /dev/null https://example.com 2>&1 | grep -q '403'"
code_http=$(docker run --rm --network "${PREFIX}-internal" --read-only --tmpfs /tmp --cap-drop=ALL "$CLIENT_IMAGE" curl -sS -m 15 -x "$PROXY" -o /dev/null -w '%{http_code}' http://example.com 2>/dev/null)
check "via proxy: plain http://example.com is refused with 403" [ "$code_http" = 403 ]
check "via proxy: non-443 port on an allowlisted host is refused" \
  sh -c "! docker run --rm --network ${PREFIX}-internal --read-only --tmpfs /tmp --cap-drop=ALL $CLIENT_IMAGE curl -sS -m 15 -x $PROXY -o /dev/null https://repo.maven.apache.org:8443/"
summary
