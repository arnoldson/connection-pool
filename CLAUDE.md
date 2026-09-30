# connection-pool

Portfolio project: reproduce and fix ephemeral port exhaustion between two
Jetty services over localhost. Read docs/BACKGROUND.md before changing
anything network-related; docs/OVERVIEW.md is the plain-language version.

## Build & run
- Java 25, Maven wrapper: `./mvnw verify` (unit + integration tests)
- Scenario: `scripts/run-scenario.sh <name> <fresh|pool|jetty> <tw_reuse 0|1>`
- Kernel-only repro (no Java): `scripts/kernel-smoke.sh`

## Conventions
- Modules: http-mini, pool (no deps), service-a, service-b. Keep pool free of
  HTTP knowledge.
- The "before" client must never reuse connections, and A must always close
  first — these are what make the bug reproduce. Don't "fix" them.
- A↔B traffic must stay on loopback in B's network namespace; sysctls live on
  service b in docker/compose.yaml. tcp_tw_reuse must be set explicitly.
- Count A-side TIME_WAIT with `dport = :8080` (loopback shows both ends).
- Prefer java.util.concurrent primitives over wait/notify; inject time via
  InstantSource instead of sleeping in tests.
- Explain non-obvious Java decisions briefly in PR/commit messages — the
  author is using this project to learn Java.
- Lean scope: flag gold-plating instead of adding it.
