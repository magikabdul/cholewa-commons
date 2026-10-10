# cholewa-commons

Shared library (`cloud.cholewa:cholewa-commons`) hosted on the **personal `magikabdul`
account**, not the `smart-home-automation-system` org — it is also used by services
outside that project, so treat every public-API change as affecting unknown external
consumers, not just the org ones. Published to GitHub Packages
(`maven.pkg.github.com/magikabdul/cholewa-commons`, pom server id `github-prv`).
Java 21, Spring Boot 4.1.1 (`spring-boot-starter-parent`), Maven.

Org consumers, with the version each is on today (2026-10-11, read from `origin/main` of
each): `ai-service`, `amx-service`, `boiler-service`, `database-service`, `heating-service`,
`notification-service`, `shelly-cloud-service` and `water-service` on **1.7.0**;
`api-gateway-service` and `presence-service` on 1.5.1. Every org consumer is on the 1.x line.
Four of them have RabbitMQ on the classpath - `amx-service`, `heating-service`,
`notification-service`, `boiler-service` - and only those are reached by the `rabbit/` and
`notification/` packages.
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
  `ErrorMessage`, `UniqueError`, `ErrorId`, plus `NotImplementedException`; and
  `DownstreamError`, what `DownstreamErrors.read` returns for an error answered by another
  service (HAS-174).
- `database/` — `R2dbcConnectionFactoryAutoConfiguration` and `DatabaseProperties`: a
  pooled PostgreSQL `ConnectionFactory` built from the `database.*` property group, so a
  database-backed service carries no `DbConfig` of its own (HAS-146).
- `validation/` — `ValidationMessagesAutoConfiguration`: Bean Validation messages always
  from the root bundle — English for the built-in constraints — whatever the locale of the
  JVM or of the request (HAS-180).
- `rabbit/` — `RabbitConnectionNameAutoConfiguration`: the `ConnectionNameStrategy` that
  names a RabbitMQ connection after its pod (HAS-205).
- `notification/` — `NotificationPublisher`, the publisher of household notifications to a
  headers exchange; `NotificationPublisherAutoConfiguration` and `NotificationProperties` for
  an application that publishes over its only connection, `NotificationConnection` for one
  that needs a second connection; `NotificationPublishException` (HAS-205).
- `info/` — `InfoController`: `GET /info` with app name, version and git commit
  (requires `application.title`/`application.version` properties and `GitProperties`
  in the consumer).

There are four auto-configurations, all registered in
`META-INF/spring/…AutoConfiguration.imports`: `R2dbcConnectionFactoryAutoConfiguration`
(guarded by `@ConditionalOnClass` on the R2DBC types and `@ConditionalOnProperty` on
`database.host`), `ValidationMessagesAutoConfiguration` (on by default, see below),
`RabbitConnectionNameAutoConfiguration` (on by default where Spring AMQP is on the
classpath) and `NotificationPublisherAutoConfiguration` (opt-in,
`cholewa.notification.enabled`).
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
  and `WebClientResponseExceptionProcessor` propagates the downstream HTTP status and, since
  1.7.0 (HAS-174), the **causes the downstream service named**: its own message stays first
  and unchanged, then every downstream message **that carries a code**, as `message` + `code`
  and **without `details`**. Without this a code died at the first hop — the reason the
  review of HAS-174 asked for it. The filter is the point, do not loosen it: `details` is by
  convention the raw exception text (`DefaultExceptionProcessor` puts the driver message
  there), and relaying it would carry a downstream 500's SQL to a caller two hops away, from
  services the gateway deliberately does not route; and the processor cannot tell an own
  service from an external system that happens to answer with an `errors` array, while a
  code is something a service chose to publish. A body that is not the contract (a Shelly
  device, Spring's default error) adds nothing and, on this path, logs nothing:
  `DownstreamErrors.messagesOf(String)` is silent by design, because the processor already
  logs the downstream status and a WARN per device error would be noise. **No org consumer
  is affected today** (checked 2026-10-06): every client in boiler-, water-, heating-,
  presence- and amx-service maps its errors to an exception of its own, so no
  `WebClientResponseException` reaches the global handler; the relay starts working for the
  first service that uses a plain `retrieve()`.
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
- **`ErrorMessage.code` is additive and must stay so** (HAS-174, 1.7.0): `NON_EMPTY`, so no
  existing body gains a key (bar the 501 of `NotImplementedException`, which now names its
  `UniqueError`); the two-argument constructor of the earlier releases is kept
  by hand next to Lombok's all-args one, because a consumer compiled against it would
  otherwise fail with `NoSuchMethodError`. The code is the **name** of an `ErrorId` constant
  (`ErrorId.codeOf(...)`), never the description — a description gets reworded, and callers
  branch on the code. `codeOf` is a **static** interface method on purpose: a default
  `getCode()` would be inherited and clash with any implementation outside the org that
  already has a `getCode()` of another type (an `int` code is the most common thing an error
  enum carries); a static one is not inherited. The name of a constant becomes wire contract
  the moment a caller branches on it — nothing in this library can pin that, the service
  owning the enum has to.
  The field also joins Lombok's `equals`/`hashCode`/`toString`: `toString()` gains
  `code=null`, and a relayed message with a code no longer de-duplicates against the same
  message built locally without one. Of the built-in processors only `NotImplementedExceptionProcessor`
  sets one; the others have no `ErrorId` behind them.
- **`DownstreamErrors.read` never loses the status and never fails**, whatever the body
  does: none, HTML, JSON of another shape, `"errors":[null]`, a body that breaks off, a body
  that never ends. It logs the kind of failure only — a parser can quote the body. What the
  review of the first version added, each with a test:
  - the body is read **as text and parsed with the helper's own `JsonMapper`**, not through
    the application's codecs: `bodyToMono(Errors.class)` refuses a JSON body under another
    `Content-Type`, and a proxy that rewrites the header would cost exactly the code the
    helper exists to deliver. Parsing by hand also tells the contract with no messages
    (`{"errors":[]}`, nothing to warn about) from a foreign body (no `errors` key, one WARN);
  - the wait for the body is **bounded** (`DEFAULT_BODY_TIMEOUT`, 2 s): headers followed by a
    stalled body is the shape of the 2026-09-26 outage, and without the bound the status
    would never reach the caller. It has to stay **below the caller's own call timeout**
    (`amx-service` gives its `database-service` call 5 s, counted from the start of the
    request) — the same relation as `max-validation-time` to `max-acquire-time` in the pool;
  - the messages are bound **one by one into a `LinkedHashSet`**: bound as a whole,
    `Errors.errors` comes back from Jackson as a `HashSet` and the order of the body is gone
    before anything can preserve it. The order test goes through `read` with six messages —
    a test feeding the record constructor a `LinkedHashSet` proves nothing about the wire;
  - `DownstreamError` drops null elements and wraps the messages unmodifiable in a
    `LinkedHashSet` — `Set.copyOf` throws on a null and iterates in an order that changes
    with every JVM start;
  - `httpStatus(fallback)` exists because every call exception in the services takes an
    `HttpStatus`, and `HttpStatus.valueOf` throws for a status without a constant;
  - an element of `errors` counts only when it **has a `message`**, and one unreadable
    element does not cost the others: `errors` is a common name, and Spring's body for a
    failed binding has an array under it whose objects carry a `code` (`NotNull`) and no
    `message` — read as they are, `hasCode("NotNull")` would match a code no `ErrorId` issued.
    For the same reason a `code` counts only as **text**: the mapper coerces scalars, so an
    external API's `"code": 32` would become `"32"` and pass the relay filter of the built-in
    processor. Skipped elements are counted and reported in **one WARN on the `read` path**
    (the number, never the content) — otherwise a downstream service that changes the shape
    of its messages loses every code here without a trace.
- **`Errors.addError` copies the set every time** (fixed here): the set an `Errors` is built
  with is usually one that cannot be added to — `Collections.singleton` in every processor —
  and the old code called `add` on it. It is the generalised `DeviceDatabaseClient.mapErrorToException` of
  `amx-service`, which decoded the body by hand since HAS-150. The point of `hasCode`: a
  routing 404 and "no such record" are both a 404, and only a code tells them apart.
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
  both fail on `Locale.ENGLISH`. Two edges of the choice, both documented in the README and
  both outside the org (no org consumer has a `MessageSource` or `ValidationMessages` of its
  own): a key that lives only in an `_en` file is not found any more, and a
  `ReloadableResourceBundleMessageSource` with `fallbackToSystemLocale` left on looks the JVM
  locale up before the root file whatever it is asked for.
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

## RabbitMQ — the connection name and the notification publisher (HAS-205)

Both pieces were copies in the services before they moved here (the strategy in four, the
publisher in `heating-service` and `boiler-service`); the adoption is HAS-206. The header
names, the levels, the content type and the three error texts are **wire contract** with
`notification-service` and with what the services log — `NotificationPublisherTest` pins them
as the copies had them.

- **`spring-boot-amqp` is `provided`**, like the r2dbc dependencies and for the same reason:
  six of the ten org consumers have no broker. Every class that touches Spring AMQP is either
  an auto-configuration behind `@ConditionalOnClass` or one a consumer has to reach for
  itself. Do not make it a compile dependency: `RabbitAutoConfiguration` would start trying
  to configure a connection in `database-service`.
- **Never add a `ConnectionFactory` or a `RabbitOperations` bean here.**
  `RabbitAutoConfiguration` backs off from either, and the consumer loses its auto-configured
  connection with everything `spring.rabbitmq.*` sets on it (manual ack, prefetch,
  observation, the retry of the template). That is why the single-connection publisher takes
  the `RabbitOperations` of the context and why `NotificationConnection` keeps its factory and
  template as fields. Three tests assert `hasSingleBean(ConnectionFactory.class)` next to the
  real `RabbitAutoConfiguration` — they are the guard, keep them.
- **The bean names are `cholewaPodConnectionNameStrategy` and `cholewaNotificationPublisher`
  on purpose** (the lesson of HAS-180): the services call theirs `connectionNameStrategy` and
  `notificationPublisher`. On the day a service takes 1.8.0 and has deleted nothing, its own
  strategy makes the library's back off (`@ConditionalOnMissingBean`), and the publisher is
  off because nobody set `cholewa.notification.enabled`. Checked by building copies of
  `boiler-service`, `notification-service`, `amx-service` and `database-service` against the
  branch, unchanged.
- **`HOSTNAME` is read from the `Environment`, not through `${HOSTNAME:}`**: an empty value
  bypasses a placeholder default, and "missing" and "empty" have to end the same way. It
  counts only when it starts with `spring.application.name` and a dash — outside Kubernetes
  the variable is set to a workstation name (Git Bash, Linux shells) or a container id
  (Docker). The test for "not set" removes the system environment from the context, because
  every machine that runs the build has one.
- **No application name, no strategy.** The services wrote `${spring.application.name}` and
  could afford to fail without it; a library cannot. `@ConditionalOnProperty` takes an empty
  value for a set one, hence the small `ApplicationNameCondition`.
- **The connection name is on by default — for consumers outside the org too.** An
  application with Spring AMQP and a name gets its connection renamed by the bump. It is a
  label, nothing routes by it; the README says so and names the switch.
- **The publisher is opt-in, and `env` has no default.** Most consumers with a broker publish
  no notifications (`amx-service`, `notification-service`), and a default `env` would let a
  run on a workstation publish into the production queues. `exchange` has one
  (`notification`): a wrong exchange fails every publish loudly, a wrong `env` does not.
- **The startup check (`requireDeliveryFeedback`) is new** — neither copy had it;
  `boiler-service` pinned the same three settings with a context test of its own. Without
  correlated confirms the future of a `CorrelationData` never completes (simple confirms do
  not count), and without returns or the mandatory flag an unroutable message is confirmed
  and counted as sent. The mandatory flag can only be asked of a `RabbitTemplate`; for another
  `RabbitOperations` that part is skipped.
- **A returned message reaches the publisher without a returns callback on the template.**
  `PublisherCallbackChannelImpl` sets `CorrelationData.returned` before it looks for a
  listener, and `RabbitTemplate.send` applies the mandatory flag when the `CorrelationData`
  has an id (a `new CorrelationData()` always has one) — read in the sources of Spring AMQP
  4.1.1. So the auto-configuration does not install a callback: a template takes exactly one
  (`setReturnsCallback` throws on a second), and `boiler-service` already installs its own
  through a `RabbitTemplateCustomizer`. `NotificationConnection` owns its template and does
  install one.
- **The publisher swallows nothing and retries nothing.** "A notification must neither fail
  nor stall what it reports on" is the caller's rule to keep, because the two callers differ:
  `boiler-service` swallows the error and cuts the publish off after 30 s, `heating-service`
  lets it fail the pass so that the row is not written.
- **The timeout is a `NotificationPublishException` now**, with the `TimeoutException` as its
  cause; the copies let Reactor's `TimeoutException` through, whose message names an operator
  instead of what happened. A broker that cannot be reached still arrives as the exception of
  Spring AMQP.
- **Open, the owner's call: a confirm that arrives after the timeout counts as not sent**, so
  a caller that repeats the publish sends it twice — unbounded while the broker is slow.
  Accepted "for now" in the reviews of HAS-109. This class is the one place to bound it, and
  `should_stay_failed_when_the_confirmation_comes_after_the_timeout` pins today's behaviour.
- **`confirm-timeout` has a unit and two bounds** (`@DurationUnit(SECONDS)`, 1 s to 60 s): a
  bare `10000` meant as milliseconds would otherwise bind as close to three hours.
- **`NotificationConnection.Settings` overrides `toString()`**: a record prints its
  components, and one of them is the password.
- **Not auto-configured on purpose: the second connection.** The task asked for a factory
  there, and `heating-service` keeps a `NotificationRabbitConfig` of a few lines. Folding it
  into the auto-configuration (a `cholewa.notification.connection.*` group) would be the next
  step if a second consumer of that shape appears.
- Nothing here was run against a real broker: no test opens a connection, and the confirm /
  return path is tested on a mocked `RabbitOperations`. The proof against a broker is the one
  HAS-206 names — a notification published into the dev queue from a run outside the cluster.

## Tests

`RabbitConnectionNameAutoConfigurationTest`, `NotificationPublisherAutoConfigurationTest` and
`NotificationConnectionTest` use `ApplicationContextRunner` with the real
`RabbitAutoConfiguration`: Spring AMQP connects with the first use of a template, so a context
starts without a broker. `NotificationPublisherTest` is the test of the two service copies
(Mockito, `StepVerifier` with virtual time) plus the configurable timeout and the late confirm.

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
