#!/usr/bin/env bash
# PR 10 (c): isolation of the agent container, checked with docker inspect and from inside.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib.sh"
echo "== (c) container isolation"
work=$(mktemp -d); chmod 0777 "$work"
cname=$(DETACH=1 "$here/../network/run-agent.sh" "$work" sleep 120) || { ko "could not start agent container"; summary; exit 1; }
cname=$(docker ps -q --filter "name=^${PREFIX}-run-" | head -1)
insp() { docker inspect --format "$1" "$cname"; }
# Values are read once here, so the checks below are plain bash tests (no functions inside sh -c).
readonly_rootfs=$(insp '{{.HostConfig.ReadonlyRootfs}}')
privileged=$(insp '{{.HostConfig.Privileged}}')
capdrop=$(insp '{{json .HostConfig.CapDrop}}')
secopt=$(insp '{{json .HostConfig.SecurityOpt}}')
pidmode=$(insp '{{.HostConfig.PidMode}}')
ipcmode=$(insp '{{.HostConfig.IpcMode}}')
netmode=$(insp '{{.HostConfig.NetworkMode}}')
mounts_n=$(insp '{{len .Mounts}}')
mounts_dst=$(insp '{{range .Mounts}}{{.Destination}} {{end}}')
user=$(insp '{{.Config.User}}')
envs=$(insp '{{range .Config.Env}}{{println .}}{{end}}')

check "rootfs is read-only" [ "$readonly_rootfs" = true ]
check "not privileged" [ "$privileged" = false ]
check "all capabilities dropped" sh -c "printf '%s' '$capdrop' | grep -q ALL"
check "no-new-privileges set" sh -c "printf '%s' '$secopt' | grep -q no-new-privileges"
check "no host PID/IPC/network namespace" sh -c "[ '$pidmode' != host ] && [ '$ipcmode' != host ] && [ '$netmode' = ${PREFIX}-internal ]"
check "only the workdir is bind-mounted (no docker.sock, no home)" sh -c "[ '$mounts_n' = 1 ] && [ '$mounts_dst' = '/work ' ]"
check "runs as uid 10001" [ "$user" = 10001:10001 ]
if printf '%s\n' "$envs" | grep -Eq '^(ANTHROPIC_AUTH_TOKEN|CLAUDE_CODE_OAUTH_TOKEN|AWS_|GITHUB_|KEYCLOAK)'; then
  ko "no secret-like env var names"
else
  ok "no secret-like env var names"
fi
check "ANTHROPIC_API_KEY value is the dummy" grep -qx 'ANTHROPIC_API_KEY=kivra-agent-dummy-not-a-credential' <<<"$envs"

check "no docker socket inside" sh -c "! docker exec '$cname' test -e /var/run/docker.sock"
check "no ~/.ssh inside" sh -c "! docker exec '$cname' test -e /home/agent/.ssh"
check "no ~/.claude credentials inside" sh -c "! docker exec '$cname' test -e /home/agent/.claude/.credentials.json"
# PID 1's environment: only the dummy key may look like a key/token/secret. Values are never printed.
leak=$(docker exec "$cname" sh -c 'tr "\000" "\n" < /proc/1/environ' 2>/dev/null \
  | grep -E '^[A-Z_]*(KEY|TOKEN|SECRET)=' | grep -v '^ANTHROPIC_API_KEY=kivra-agent-dummy-not-a-credential$' || true)
if [ -z "$leak" ]; then ok "PID 1 environ has no non-dummy key values"; else ko "PID 1 environ has a non-dummy key-like value"; fi
check "rootfs is not writable outside the tmpfs (touch fails)" sh -c "! docker exec '$cname' sh -c 'touch /usr/local/bin/x 2>/dev/null'"
check "claude --version works inside" sh -c "docker exec '$cname' claude --version | grep -q 'Claude Code'"
check "git and java 21 are present" sh -c "docker exec '$cname' git --version >/dev/null && docker exec '$cname' java -version 2>&1 | grep -q 'version \"21'"
docker rm -f "$cname" >/dev/null 2>&1
rm -rf "$work"
summary
