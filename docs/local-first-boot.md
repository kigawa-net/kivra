# Kivra local first boot (development only)

This demo boots the actual Ktor `kivra-app` and MariaDB 11. It is **not** yet an interactive AI assistant or autonomous developer. Currently available HTTP routes include `/health` and an opt-in bearer-protected `/metrics`.

## Prerequisites

- Docker Engine + Docker Compose plugin (the multi-module Gradle build runs inside Docker)
- Access to Maven/Gradle dependency repositories during the initial image build
- Host port 8080 available

## Start

```sh
docker compose -f compose.dev.yml up --build -d
docker compose -f compose.dev.yml ps
curl -fsS http://127.0.0.1:8080/health
```

The app runs Flyway database migrations when starting. MariaDB storage persists in the named volume `kivra-dev-db`.

## Troubleshooting

```sh
docker compose -f compose.dev.yml logs -f app
docker compose -f compose.dev.yml logs -f db
docker compose -f compose.dev.yml restart app
docker compose -f compose.dev.yml down
```

For **destructive local reset only**, run `docker compose -f compose.dev.yml down -v`. This deletes all MariaDB data for this Compose project.

If app startup fails, inspect the app logs for Flyway or MariaDB errors; a healthy database is a prerequisite. Use `docker compose -f compose.dev.yml ps` to verify container status. The Compose health check for MariaDB does not guarantee that all app migrations succeeded.

## Isolation and limits

- App published only on the host's `127.0.0.1:8080`; MariaDB is not exposed on the host.
- Passwords are intentionally development-only and stored in this tracked Compose file. Never deploy this Compose stack to a shared/public host.
- Metrics disabled, fusha-task status writes disabled. No exkes/recl/Fortis/scheduler service is started.
- No production provider mutation, memory mutation, automatic external replay, GitHub merge, or self-deployment is enabled by this demo.
- `/health` proves app startup/HTTP response, **not** assistant execution or end-to-end provider compatibility.

Next milestone: Issue #106 Phase B read-only task listing plus a manually invoked FakeExecutionPort run preview, with explicit DEV_ONLY gating and no side effects.
