#!/usr/bin/env bash
# Shared settings for agent-runtime/install/*.sh. Sourced, not executed.
# Every path can be overridden. DESTDIR (for install/uninstall) prefixes the install-time paths only, so the
# scripts can be dry-run in a temporary directory: with DESTDIR set, root checks, chown, systemctl and docker are skipped.
PREFIX="${PREFIX:-/opt/kivra-agent}"
ETC_DIR="${ETC_DIR:-/etc/kivra-agent}"
STATE_DIR="${STATE_DIR:-/var/lib/kivra-agent}"
UNIT_DIR="${UNIT_DIR:-/etc/systemd/user}"
BIN_DIR="${BIN_DIR:-/usr/local/bin}"
AGENT_USER="${AGENT_USER:-kivra-agent}"
DESTDIR="${DESTDIR:-}"
UNITS="kivra-agent-network.service kivra-agent-proxy.service kivra-agent.service"

die() { echo "error: $*" >&2; exit 1; }

# True when DESTDIR is set (dry run in a temporary directory).
is_dry() { [ -n "$DESTDIR" ]; }

# Runs a command as the agent user with its own systemd --user bus and docker context (no privilege for the caller).
run_as_agent() {
  local uid
  uid="$(agent_uid)"
  (cd / && runuser -u "$AGENT_USER" -- env \
    XDG_RUNTIME_DIR="/run/user/$uid" \
    DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" \
    HOME="/home/$AGENT_USER" PATH="/usr/local/bin:/usr/bin:/bin" "$@")
}

agent_uid() {
  if is_dry; then echo "${AGENT_UID:-1001}"; else id -u "$AGENT_USER"; fi
}

# Owner change, skipped in a dry run.
own() { if ! is_dry; then chown "$@"; fi; }

# Finds a JDK of at least major version $1 and prints its java binary (resolved). Dies with a clear message otherwise.
find_jdk() {
  local min="$1" java_bin major
  java_bin="${KIVRA_AGENT_JAVA:-$(command -v java || true)}"
  [ -n "$java_bin" ] || die "java が見つかりません。JDK ${min} 以上を導入してください(例: apt install openjdk-${min}-jdk-headless)"
  java_bin="$(readlink -f "$java_bin")"
  [ -x "$(dirname "$java_bin")/javac" ] || die "java は JRE のようです。JDK(javac を含む)${min} 以上を導入してください"
  major="$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [ -n "$major" ] && [ "$major" -ge "$min" ] || die "JDK ${min} 以上が必要です(検出: ${major:-不明})"
  printf '%s\n' "$java_bin"
}
