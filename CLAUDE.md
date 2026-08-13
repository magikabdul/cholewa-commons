# cholewa-commons

Shared library (`cloud.cholewa:cholewa-commons`) hosted on the **personal `magikabdul`
account**, not the `smart-home-automation-system` org — it is also used by services
outside that project, so treat every public-API change as affecting unknown external
consumers, not just the org ones. Published to GitHub Packages
(`maven.pkg.github.com/magikabdul/cholewa-commons`, pom server id `github-prv`).
Java 21, Spring Boot 4.1.0 (`spring-boot-starter-parent`), Maven.

Org consumers, with the version each is on today (2026-08-13): `boiler-service`,
`database-service`, `heating-service` and `water-service` on **1.2.0**; `ai-service` and
`notification-service` on **1.1.0**; `amx-service` and `shelly-cloud-service` on 0.2.1 and
`api-gateway-service` on 0.1.2 — those three stay on the old line until their own Java 21
migrations, because 1.0.x is a breaking one (Java 21 bytecode, Jackson 3). This list drifts:
the authoritative answer is the `cholewa-commons.version` property in each consumer's pom.

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
- `database/` — `R2dbcConnectionFactoryAutoConfiguration` and `DatabaseProperties`: a
  pooled PostgreSQL `ConnectionFactory` built from the `database.*` property group, so a
  database-backed service carries no `DbConfig` of its own (HAS-146).
- `info/` — `InfoController`: `GET /info` with app name, version and git commit
  (requires `application.title`/`application.version` properties and `GitProperties`
  in the consumer).

The only auto-configuration is `R2dbcConnectionFactoryAutoConfiguration` (registered in
`META-INF/spring/…AutoConfiguration.imports`, guarded by `@ConditionalOnClass` on the R2DBC
types and `@ConditionalOnProperty` on `database.host`). Everything else is opt-in:
consumers register `GlobalErrorExceptionHandler` as a bean themselves (see README for the
snippet).

## Error handling — the parts worth knowing before changing anything

- **Processor selection is hierarchy-aware** (HAS-131): exact class first, then the
  most specific registered supertype (`key.isInstance(throwable)`), then
  `DefaultExceptionProcessor` (500). A registration on a subclass always beats one on
  a supertype; `withCustomErrorProcessor` may override built-in registrations
  (custom wins on duplicate key).
- Built-in registrations include a `ResponseStatusException` tier — unmatched routes
  (404), unsupported methods (405) etc. keep their own status instead of becoming 500 —
  and `WebClientResponseExceptionProcessor` propagates the downstream HTTP status.
- **Database integrity tier** (HAS-137): `org.springframework.dao.DuplicateKeyException`
  → 400 `Duplicate Key`, the parent `DataIntegrityViolationException` → 400
  `Data integrity violation`; hierarchy-aware selection keeps duplicates on the more
  specific processor. Neither sets `details` — raw driver text names tables, columns and
  constraints, so it stays in the log only.
- `spring-tx` is a **compile-scope** dependency and must stay one: the handler holds class
  literals for the `org.springframework.dao` types, so a `provided` scope (not transitive)
  would break every consumer without its own spring-tx — `ai-service` has none, and would
  fail at startup with `NoClassDefFoundError`. In Boot 4 this pulls no autoconfiguration:
  `TransactionAutoConfiguration` lives in the separate `spring-boot-transaction` module.
- `ServerWebInputExceptionProcessor` distinguishes missing vs malformed request body by
  walking the cause chain for `DecodingException` (bounded depth); response `details`
  deliberately carry only cause messages, never stack traces or method signatures.
- Error responses are client-facing in **public repos' services** — keep messages free
  of internals when touching processors.
- `logError` in the handler is intentionally suppressed; logging happens in the
  processors instead (HAS-132): every processor logs the handled exception
  (`Handled [<class>]: <message>`) — `warn` for 4xx responses, `error` for 5xx
  (processors with a dynamic status pick the level at runtime) — and only
  `DefaultExceptionProcessor` logs the stack trace.

## R2DBC connection factory — what the guards are for

- Ordered `@AutoConfiguration(before = R2dbcAutoConfiguration.class)`: Boot's own factory is
  `@ConditionalOnMissingBean` as well, so without the explicit ordering the winner is
  undefined.
- Two guards keep the library inert for consumers that do not want it.
  `@ConditionalOnClass({ConnectionFactory.class, ConnectionPool.class})` names **both** types
  — `r2dbc-pool` does not come with `spring-boot-r2dbc`; in the services it arrives through
  `spring-boot-data-r2dbc`, so a consumer can have the SPI without the pool. And
  `@ConditionalOnProperty(prefix = "database", name = "host")` keeps it off for anyone
  configuring the database the Boot way, through `spring.r2dbc.*` — without it, upgrading to
  1.3.0 would break such a consumer's startup on a `ConnectionFactoryOptions` null.
- The r2dbc dependencies are `provided` — safe here, unlike `spring-tx` above, exactly
  because `@ConditionalOnClass` is read via ASM and the class never loads without them.
- The five connection properties carry bean-validation constraints and the record is
  `@Validated`: `ConnectionFactoryOptions` rejects a null with a message that does not name
  the property, so a half-configured consumer would otherwise see only `value must not be
  null`. `sslMode` is a property (`database.ssl-mode`, default `REQUIRE`) rather than a
  constant, so a consumer on a database without TLS is not forced to declare its own
  `ConnectionFactory` bean just to change one option.
- `@EnableR2dbcRepositories` must **not** move into this package: with no `basePackages` the
  scan base is the annotated class's package, so no consumer repository would be found, and
  its mere presence makes `R2dbcRepositoriesAutoConfiguration` back off. It stays in the
  service.
- The nested `Pool` record needs a bare `@DefaultValue` on the `pool` component itself, not
  only on its fields — otherwise a missing `database.pool` group binds to `null` and the pool
  build NPEs.
- Pool size stays per-service configuration: the managed database allows 22 backend
  connections in total (heating 8 / database 6 / water 4), and a re-split must not require a
  library release. Note that the `r2dbc_pool_*` metrics are tagged with the **Spring bean
  name** (`connectionFactory`) by `ConnectionPoolMetricsAutoConfiguration`, not with
  `ConnectionPoolConfiguration.name(...)`, which only feeds the JMX object name.

## Tests

`R2dbcConnectionFactoryAutoConfigurationTest` drives the auto-configuration with
`ApplicationContextRunner`: every guard above, the pool defaults and overrides (asserted on
the built pool via `getMetrics().getMaxAllocatedSize()`, not just on the bound properties),
and — through `ImportCandidates` — that the class is really listed in the `.imports` file.
Run it with `clean`: `mvn test` alone keeps a stale copy of that resource in `target/classes`
and the check passes even when the file is gone.

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
- The README installation snippet pins the version **being released** and is bumped in the
  same PR as the change, not in a follow-up commit after the release — `main` should always
  advertise the version a consumer is meant to depend on. The trade-off is accepted: between
  the merge and `gh release create` the README names a version that is not on GitHub Packages
  yet, so do not let a merged release PR sit unreleased.
