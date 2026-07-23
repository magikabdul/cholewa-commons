# cholewa-commons

Shared library (`cloud.cholewa:cholewa-commons`) hosted on the **personal `magikabdul`
account**, not the `smart-home-automation-system` org — it is also used by services
outside that project, so treat every public-API change as affecting unknown external
consumers, not just the org ones. Published to GitHub Packages
(`maven.pkg.github.com/magikabdul/cholewa-commons`, pom server id `github-prv`).
Java 21, Spring Boot 4.1.0 (`spring-boot-starter-parent`), Maven.

Org consumers: `amx-service`, `api-gateway-service`, `boiler-service`,
`database-service`, `heating-service`, `shelly-cloud-service`, `water-service` — all
still on 0.2.x until their own Java 21 migrations; 1.0.x is a breaking line
(Java 21 bytecode, Jackson 3).

Org-wide conventions and working rules (PR flow, branch naming `feature/HAS-<n>`,
"user writes library code, Claude reviews", public-repo hygiene) live in the workspace
`organization.md` — this file only covers what is specific to this repo. When opened as
part of the workspace, those rules apply here too.

## What this library is

Common building blocks for **reactive (WebFlux)** Spring Boot services:

- `error/` — global error handling: `GlobalErrorExceptionHandler`
  (an `AbstractErrorWebExceptionHandler`, `@Order(-2)`) renders every unhandled
  exception as an `Errors` JSON body via a pluggable `ExceptionProcessor` mechanism.
- `error/model/` — the JSON error contract shared by all services: `Errors`,
  `ErrorMessage`, `UniqueError`, `ErrorId`, plus `NotImplementedException`.
- `info/` — `InfoController`: `GET /info` with app name, version and git commit
  (requires `application.title`/`application.version` properties and `GitProperties`
  in the consumer).

There is no Spring auto-configuration: consumers register
`GlobalErrorExceptionHandler` as a bean themselves (see README for the snippet).

## Error handling — the parts worth knowing before changing anything

- **Processor selection is hierarchy-aware** (HAS-131): exact class first, then the
  most specific registered supertype (`key.isInstance(throwable)`), then
  `DefaultExceptionProcessor` (500). A registration on a subclass always beats one on
  a supertype; `withCustomErrorProcessor` may override built-in registrations
  (custom wins on duplicate key).
- Built-in registrations include a `ResponseStatusException` tier — unmatched routes
  (404), unsupported methods (405) etc. keep their own status instead of becoming 500 —
  and `WebClientResponseExceptionProcessor` propagates the downstream HTTP status.
- `ServerWebInputExceptionProcessor` distinguishes missing vs malformed request body by
  walking the cause chain for `DecodingException` (bounded depth); response `details`
  deliberately carry only cause messages, never stack traces or method signatures.
- Error responses are client-facing in **public repos' services** — keep messages free
  of internals when touching processors.
- `logError` in the handler is intentionally suppressed; the only logging for unhandled
  exceptions is `DefaultExceptionProcessor` (message + stack trace).

## Tests

`GlobalErrorExceptionHandlerTest` covers selection logic (exact / subclass /
most-specific / override / fallback); `GlobalErrorExceptionHandlerIntegrationTest` is a
`@WebFluxTest` asserting real end-to-end responses (missing `@RequestParam`, missing and
malformed body, path-variable type mismatch, WebClient error propagation, 404/405,
default 500). Some assertions pin Spring's reason phrases (e.g. `"Type mismatch."`) —
a major Spring bump may legitimately break them; update the expected text, not the logic.

## Build & release

- Build: `mvn verify` (JDK 21; on WSL set
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`).
- The pom keeps a dev version (`0.0.1-SNAPSHOT`) — the released version comes from the
  git tag: `gh release create <X.Y.Z>` triggers `package.yml`, which runs
  `versions:set` from the tag and deploys to GitHub Packages. Tags have no `v` prefix.
- CI/CD: `CI.yml` (build + tests), `sonar.yml` (SonarCloud), `package.yml` (publish on
  GitHub release). Release flow: the `release` skill from the `smart-home` plugin.
- After a release, bump the pinned version in the README installation snippet.
