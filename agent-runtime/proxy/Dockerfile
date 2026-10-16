# kivra-agent egress proxy (see README.md). Stdlib-only Go, static binary.
# Stage `mock` is the verification stand-in for Keycloak / Anthropic and is never used in production.

FROM golang:1.25-bookworm AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
COPY cmd/ cmd/
COPY allowlist.txt ./
RUN CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags="-s -w" -o /out/kivra-agent-proxy . \
 && CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags="-s -w" -o /out/mockupstream ./cmd/mockupstream

FROM gcr.io/distroless/static-debian12 AS mock
COPY --from=build /out/mockupstream /usr/local/bin/mockupstream
ENTRYPOINT ["/usr/local/bin/mockupstream"]

# Runs as root inside the container on purpose: under rootless docker, root in the container is the
# host kivra-agent user, which is the owner of the secret file. Run with --cap-drop=ALL.
FROM gcr.io/distroless/static-debian12
COPY --from=build /out/kivra-agent-proxy /usr/local/bin/kivra-agent-proxy
COPY allowlist.txt /etc/kivra-agent-proxy/allowlist.txt
ENV KIVRA_PROXY_LISTEN=:8080 \
    KIVRA_PROXY_ALLOWLIST_FILE=/etc/kivra-agent-proxy/allowlist.txt
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/kivra-agent-proxy"]
