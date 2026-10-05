# cholewa-commons

Shared library (`cloud.cholewa:cholewa-commons`) hosted on the **personal `magikabdul`
account**, not the `smart-home-automation-system` org — it is also used by services
outside that project, so treat every public-API change as affecting unknown external
consumers, not just the org ones. Published to GitHub Packages
(`maven.pkg.github.com/magikabdul/cholewa-commons`, pom server id `github-prv`).
Java 21, Spring Boot 4.1.1 (`spring-boot-starter-parent`), Maven.

Org consumers, with the version each is on today (2026-10-05): `amx-service`,
`api-gateway-service`, `database-service`, `heating-service`, `notification-service`,
`presence-service` and `water-service` on **1.5.1**; `shelly-cloud-service` on 1.3.1,
`boiler-service` on 1.2.0 and `ai-service` on 1.1.0. Every org consumer is on the 1.x line.
This list drifts: the authoritative answer is the `cholewa-commons.version` property in each
consumer's pom.

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
- `validation/` — `ValidationMessagesAutoConfiguration`: Bean Validation messages always
  from the root bundle — English for the built-in constraints — whatever the locale of the
  JVM or of the request (HAS-180).
- `info/` — `InfoController`: `GET /info` with app name, version and git commit
  (requires `application.title`/`application.version` properties and `GitProperties`
  in the consumer).

There are two auto-configurations, both registered in
`META-INF/spring/…AutoConfiguration.imports`: `R2dbcConnectionFactoryAutoConfiguration`
(guarded by `@ConditionalOnClass` on the R2DBC types and `@ConditionalOnProperty` on
`database.host`) and `ValidationMessagesAutoConfiguration` (on by default, see below).
Everything else is opt-in:
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
- **Database integrity tier** (HAS-137, HAS-150): `org.springframework.dao.DuplicateKeyException`
  → **409** `Duplicate Key` since 1.4.0 (400 before — a broken unique is a conflict with the
  current state, not a malformed request), the parent `DataIntegrityViolationException` → 400
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
- `spring-boot-configuration-processor` is on the classpath in `optional` scope so the jar
  ships `META-INF/spring-configuration-metadata.json` for the `database.*` group. Without it
  a consumer that has the processor itself loses the completion and validation it had for
  its own local properties class and starts hand-maintaining
  `additional-spring-configuration-metadata.json` — `heating-service` did exactly that.
- `@EnableR2dbcRepositories` must **not** move into this package: with no `basePackages` the
  scan base is the annotated class's package, so no consumer repository would be found, and
  its mere presence makes `R2dbcRepositoriesAutoConfiguration` back off. In the consumer the
  better move is to drop the annotation altogether and let that auto-configuration scan from
  the application class's package: put on the application class it leaks into `@WebFluxTest`
  slices, which then fail on the missing `r2dbcEntityTemplate` (hit in `heating-service`).
- The nested `Pool` record needs a bare `@DefaultValue` on the `pool` component itself, not
  only on its fields — otherwise a missing `database.pool` group binds to `null` and the pool
  build NPEs.
- **Validation on acquire is what recovers from a hung connection** (HAS-150, after the
  2026-09-26 `database-service` outage): `validationQuery("SELECT 1")` bounded by
  `maxValidationTime`. Do not drop the bound — r2dbc-pool's default validation time is
  unlimited, so a validation query on a connection the database stopped answering would hang
  the acquire instead of discarding the connection. On failure the pool invalidates the ref
  and retries the acquire once (r2dbc-pool's default `acquireRetry(1)`) — the retry takes the
  **most recently used idle connection if there is one** (a fresh one only when none is idle),
  and after an outage that idle one is likely broken too.
  So recovery is gradual: one acquire discards up to two broken connections and its caller may
  still fail; the pool is clean after a few requests. **Do not raise `acquireRetry`** — tried
  and rejected in HAS-150: r2dbc-pool retries *every* failure, including the per-attempt
  `maxAcquireTime` timeout on an exhausted pool and connect failures, so a caller would wait
  `(retries + 1) × maxAcquireTime` and an unreachable database would get `retries + 1` connect
  attempts per request. Retrying only validation failures would need telling them apart by
  exception message — not worth it. The per-attempt `maxAcquireTime` wraps the queue wait,
  the connect and the validation; a validation cut off by it is *cancelled*, which releases the
  connection back unchecked (`Operators.discardOnCancel` in `ConnectionPool`) — hence the
  short 2 s `maxValidationTime` default. Invalidating a connection also waits for its
  `close()`, which has no time limit.
  `maxLifeTime` is the backstop: a connection used every 30 s never reaches `maxIdleTime`.
  When testing the pool with a mocked `ConnectionFactory`, note that the pool calls
  `create()` **once** and resubscribes to that `Publisher` for every allocation — stub it
  with a cold `Mono.fromSupplier`, not with consecutive `doReturn` values.
- Pool size stays per-service configuration: the managed database allows 22 backend
  connections in total (heating 8 / database 6 / water 4), and a re-split must not require a
  library release. Note that the `r2dbc_pool_*` metrics are tagged with the **Spring bean
  name** (`connectionFactory`) by `ConnectionPoolMetricsAutoConfiguration`, not with
  `ConnectionPoolConfiguration.name(...)`, which only feeds the JMX object name.

## Validation messages — why it is built the way it is

- **Active by default, for every consumer.** `spring-boot-starter-validation` is a compile
  dependency of this library, so every consumer has Bean Validation whether it declares the
  starter or not. For a consumer outside the org on a non-English locale, 1.6.0 changes the
  wording of its validation errors — that is the point, and
  `cholewa.validation.english-messages: false` is the way back. The key carries the library's
  prefix (unlike the older `database.*` group) so that it cannot be mistaken for a Spring
  Boot key or collide with a consumer's own `validation.*` group. The property has no `@ConfigurationProperties` class behind it, so
  its metadata is hand-written in `META-INF/additional-spring-configuration-metadata.json`.
- **It has to be a `ValidationConfigurationCustomizer`**, not a `MessageInterpolator` bean:
  Spring installs its own locale-aware interpolator and runs the customizers after it.
- **The interpolator it wraps comes from Boot's `MessageInterpolatorFactory`**, built with the
  application context — not from `configuration.getDefaultMessageInterpolator()`. The first
  version used the latter and the review caught what that drops: Boot's
  `MessageSourceMessageInterpolator` (a `{key}` defined in `messages.properties` would have
  come out as the literal key) and the fallback to parameter-only interpolation for a
  consumer without an Expression Language implementation (its context would not start).
- **`Locale.ROOT`, not `Locale.ENGLISH`.** A bundle lookup for `en` that finds no `_en` file
  falls back to the JVM default locale *before* the root bundle, so a consumer with
  `ValidationMessages.properties` and `ValidationMessages_pl.properties` would still get
  Polish on a Polish machine. Hibernate's own messages escape that only because it ships an
  empty `ValidationMessages_en.properties`. Asking for the root has no detour. Two tests pin
  it with bundles in `src/test/resources` that have a `_pl` file and deliberately no `_en`;
  both fail on `Locale.ENGLISH`.
- **`@Order(HIGHEST_PRECEDENCE)`**: the customizers are applied in order and the last one to
  set an interpolator wins, so running first leaves a consumer's own interpolator in place.
- **The bean is named `cholewaEnglishValidationMessages` on purpose.** `database-service`
  carried the same customizer as `englishValidationMessages` before it moved here; a second
  definition of that name would fail its startup with a bean-definition override on the day
  it upgrades. With different names the two coexist (the test pins it) until the consumer
  deletes its own — which it should do in the same bump: its copy runs after this one and
  puts back the version without the `MessageSource` and with `Locale.ENGLISH`.
- **Not covered: `@ConfigurationProperties` validation.** Boot validates those with a
  validator it builds itself (`ConfigurationPropertiesJsr303Validator`), which no customizer
  reaches — a startup binding error still follows the JVM locale.
- The tests make the JVM Polish and expect English, and one test asserts the opposite
  without the auto-configuration — if that one ever fails, the JVM was English anyway and the
  others prove nothing.

## Tests

`R2dbcConnectionFactoryAutoConfigurationTest` drives the auto-configuration with
`ApplicationContextRunner`: every guard above, the pool defaults and overrides (asserted on
the built pool via `getMetrics().getMaxAllocatedSize()`, not just on the bound properties),
and — through `ImportCandidates` — that the class is really listed in the `.imports` file.
`should_replace_a_connection_that_does_not_answer_the_validation_query` builds the pool
directly (`connectionPool(...)`) on a mocked factory whose first connection never answers
`SELECT 1`, and asserts the acquire still succeeds on a second connection — it fails
(acquire timeout) as soon as the validation query or its time bound is removed.
`should_recover_from_broken_idle_connections_within_a_few_acquires` pins the documented
recovery: with two broken idle connections the first acquire fails but discards both, the next
one succeeds.
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
