# kivra-agent runtime image: one disposable container per Run, running `claude -p` against the egress proxy.
# Contains no credentials, no SSH keys, no ~/.claude. Build: see agent-runtime/README.md.
#
# Build args:
#   CLAUDE_CODE_VERSION  pinned Claude Code release (checked against the signed manifest)

# ---- Stage 1: fetch and verify the Claude Code native binary (curl and gpg stay in this stage) ----
FROM ubuntu:24.04 AS fetch
ARG CLAUDE_CODE_VERSION=2.1.301
# Anthropic release signing key (security@anthropic.com). Fingerprint and manifest layout as documented at
# code.claude.com/docs/en/setup ("Binary integrity and code signing"). Checked again by gpg below.
ARG CLAUDE_SIGNING_FPR=31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE
ENV REPO=https://downloads.claude.ai/claude-code-releases
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg jq \
 && rm -rf /var/lib/apt/lists/*
# The architecture comes from dpkg, not from TARGETARCH: the legacy builder does not set TARGETARCH.
RUN set -eux; \
    case "$(dpkg --print-architecture)" in amd64) P=linux-x64 ;; arm64) P=linux-arm64 ;; *) echo "unsupported arch" >&2; exit 1 ;; esac; \
    echo "$P" > /platform; \
    export GNUPGHOME=/tmp/gnupg; mkdir -m 700 "$GNUPGHOME"; \
    curl -fsSL https://downloads.claude.ai/keys/claude-code.asc -o /tmp/key.asc; \
    gpg --batch --import /tmp/key.asc; \
    gpg --batch --list-keys --with-colons | grep -q "^fpr:::::::::${CLAUDE_SIGNING_FPR}:" \
      || { echo "signing key fingerprint mismatch" >&2; exit 1; }; \
    cd /tmp; \
    curl -fsSLO "$REPO/$CLAUDE_CODE_VERSION/manifest.json"; \
    curl -fsSLO "$REPO/$CLAUDE_CODE_VERSION/manifest.json.sig"; \
    gpg --batch --verify manifest.json.sig manifest.json; \
    EXPECTED=$(jq -r --arg p "$P" '.platforms[$p].checksum' manifest.json); \
    echo "$EXPECTED" | grep -Eq '^[0-9a-f]{64}$' || { echo "checksum not found in manifest" >&2; exit 1; }; \
    curl -fsSL "$REPO/$CLAUDE_CODE_VERSION/$P/claude" -o /tmp/claude; \
    echo "$EXPECTED  /tmp/claude" | sha256sum -c -; \
    install -m 0755 /tmp/claude /claude-bin; \
    rm -rf "$GNUPGHOME" /tmp/key.asc

# ---- Stage 2: runtime ----
FROM ubuntu:24.04
ARG CLAUDE_CODE_VERSION=2.1.301
LABEL org.opencontainers.image.title="kivra-agent-runtime" \
      kivra.claude-code.version="${CLAUDE_CODE_VERSION}"

# git: the Gradle build may call it. openjdk-21: kivra uses jvmToolchain(21) (see build.gradle.kts, ci.yml).
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates git openjdk-21-jdk-headless \
 && ln -s "/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)" /opt/java \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY --from=fetch /claude-bin /usr/local/bin/claude

# Fixed non-root identity. Ubuntu 24.04's default user is uid 1000, so 10001 is free.
RUN groupadd --gid 10001 agent \
 && useradd --uid 10001 --gid 10001 --home-dir /home/agent --no-create-home --shell /usr/sbin/nologin agent \
 && install -d -o 10001 -g 10001 -m 0700 /home/agent /work

ENV HOME=/home/agent \
    JAVA_HOME=/opt/java \
    GRADLE_USER_HOME=/home/agent/.gradle \
    PATH=/usr/local/bin:/usr/bin:/bin \
    LANG=C.UTF-8 \
    DISABLE_AUTOUPDATER=1 \
    DISABLE_TELEMETRY=1 \
    DISABLE_ERROR_REPORTING=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY=1 \
    ANTHROPIC_BASE_URL=http://kivra-agent-proxy:8080/anthropic

# No credential in the image. The dummy API key and the proxy URL are set per Run by network/run-agent.sh.
# No ENTRYPOINT: the agent assembles the `claude -p ...` arguments per Run.
USER 10001:10001
WORKDIR /work
CMD ["claude", "--version"]
