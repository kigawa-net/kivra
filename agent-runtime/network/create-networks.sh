#!/usr/bin/env bash
# Creates the two networks for the agent runtime. Idempotent.
#   <prefix>-internal : --internal (no route out). Agent containers attach only here.
#   <prefix>-egress   : normal bridge. Only the egress proxy attaches here.
# Works with rootless docker (DOCKER_HOST is honoured by the docker CLI).
set -euo pipefail
PREFIX="${KIVRA_AGENT_PREFIX:-kivra-agent}"
# Optional explicit subnets. Needed when docker's default address pools are exhausted
# ("all predefined address pools have been fully subnetted"), which happens on hosts with many networks.
INTERNAL_SUBNET="${KIVRA_AGENT_INTERNAL_SUBNET:-}"
EGRESS_SUBNET="${KIVRA_AGENT_EGRESS_SUBNET:-}"
subnet_arg() { [ -n "$1" ] && printf -- '--subnet %s' "$1" || true; }

if ! docker network inspect "${PREFIX}-internal" >/dev/null 2>&1; then
  # shellcheck disable=SC2046
  docker network create --internal --driver bridge $(subnet_arg "$INTERNAL_SUBNET") "${PREFIX}-internal" >/dev/null
  echo "created network ${PREFIX}-internal (internal)"
fi
if ! docker network inspect "${PREFIX}-egress" >/dev/null 2>&1; then
  # shellcheck disable=SC2046
  docker network create --driver bridge $(subnet_arg "$EGRESS_SUBNET") "${PREFIX}-egress" >/dev/null
  echo "created network ${PREFIX}-egress"
fi
