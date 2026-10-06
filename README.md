# cholewa-commons

[![CI](https://github.com/magikabdul/cholewa-commons/actions/workflows/CI.yml/badge.svg)](https://github.com/magikabdul/cholewa-commons/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)

[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=coverage)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)

![GitHub issues](https://img.shields.io/github/issues/magikabdul/cholewa-commons?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/magikabdul/cholewa-commons?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/magikabdul/cholewa-commons?style=plastic)

![GitHub release](https://img.shields.io/github/v/release/magikabdul/cholewa-commons?style=plastic)
![GitHub release date](https://img.shields.io/github/release-date/magikabdul/cholewa-commons?style=plastic)
![GitHub last commit](https://img.shields.io/github/last-commit/magikabdul/cholewa-commons?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/magikabdul/cholewa-commons?style=plastic)

Common building blocks for reactive (WebFlux) Spring Boot services: a global error
handler with a pluggable exception-processor mechanism, a consistent JSON error model,
an auto-configured pooled R2DBC connection factory, Bean Validation messages pinned to
English and a simple `/info` endpoint exposing application name, version and git commit.

Used by the [smart-home-automation-system](https://github.com/smart-home-automation-system)
services (`ai-service`, `amx-service`, `api-gateway-service`, `boiler-service`,
`database-service`, `heating-service`, `notification-service`, `presence-service`,
`shelly-cloud-service`, `water-service`) and other applications outside that
organization.

## Installation

The artifact is published to GitHub Packages:

```xml
<dependency>
    <groupId>cloud.cholewa</groupId>
    <artifactId>cholewa-commons</artifactId>
    <version>1.7.0</version>
</dependency>
```

```xml
<repositories>
    <repository>
        <id>github-prv</id>
        <url>https://maven.pkg.github.com/magikabdul/*</url>
    </repository>
</repositories>
```

GitHub Packages requires authentication even for public artifacts — configure a
`github-prv` server with a token (`read:packages`) in your Maven `settings.xml`.

## Usage

### Global error handling

Register `GlobalErrorExceptionHandler` as a bean; it renders every unhandled exception
as an `Errors` JSON body. Processors for common exceptions (validation, `WebClient`
errors, `NoSuchElementException`, database integrity violations, …) are built in;
service-specific exceptions plug in via `withCustomErrorProcessor`:

```java
@Bean
GlobalErrorExceptionHandler globalErrorExceptionHandler(
    ErrorAttributes errorAttributes,
    WebProperties webProperties,
    ApplicationContext applicationContext,
    ServerCodecConfigurer serverCodecConfigurer
) {
    return new GlobalErrorExceptionHandler(
        errorAttributes, webProperties.getResources(), applicationContext, serverCodecConfigurer
    ).withCustomErrorProcessor(Map.ofEntries(
        Map.entry(WaterException.class, new WaterExceptionProcessor())
    ));
}
```

A custom processor implements `ExceptionProcessor` and maps an exception to an
`Errors` object (HTTP status + list of `ErrorMessage`).

An `ErrorMessage` has a `message`, optional `details` and — from 1.7.0 — an optional
`code`: a stable, machine-readable name of the cause, so that a caller can tell errors apart
without parsing the message. It is left out of the JSON unless a processor sets it, so existing
error bodies do not change — with one exception: the `501` answered for a
`NotImplementedException` now carries `"code": "NOT_IMPLEMENTED"`. The usual value is the name of an `ErrorId`
constant:

```java
public enum WaterError implements ErrorId {
    NOT_FOUND_SENSOR("No such sensor");
    // getDescription() ...
}

ErrorMessage.builder()
    .message(NOT_FOUND_SENSOR.getDescription())
    .details(throwable.getMessage())
    .code(ErrorId.codeOf(NOT_FOUND_SENSOR))  // "NOT_FOUND_SENSOR"
    .build();
```

Once a caller branches on a code, the name of that constant is part of the wire contract:
renaming it compiles and passes every test of its own service, and silently changes what the
caller does. Pin the names callers rely on with a test.

Two side effects of the new field for code that never sets it: `toString()` of an
`ErrorMessage` now ends with `code=null`, and two messages that differ only in their code are
not equal, so a `Set` keeps both.

Processor selection is hierarchy-aware: an exception matches the processor registered
for its exact class or, failing that, for its most specific registered supertype (e.g.
`MissingRequestValueException` is handled by the `ServerWebInputException` processor).
Framework `ResponseStatusException`s without a more specific registration (unmatched
route → 404, unsupported method → 405, …) keep their own status; exceptions with no
matching registration at all fall back to the default processor (HTTP 500).

Database integrity errors have their own tier: `DuplicateKeyException` renders as
`409` with the message `Duplicate Key` — a broken unique constraint is a conflict with
the current state of the resource, not a malformed request — and every other
`DataIntegrityViolationException` (a `NOT NULL` or check-constraint violation, …) as
`400` with `Data integrity violation`. Both deliberately omit `details` — the raw
driver text names tables, columns and constraints, which must not reach a client; it
is logged instead. These processors are why the library depends on `spring-tx`.

Every built-in processor logs the exception it handles with a uniform
`Handled [<exception class>]: …` line — at `WARN` level for 4xx responses and `ERROR`
for 5xx (processors with a dynamic status pick the level from the resolved status).
Only the default processor logs the stack trace. Custom processors registered via
`withCustomErrorProcessor` are responsible for their own logging.

### Reading an error of another service

`Errors.httpStatus` is not part of the JSON, so a decoded error body never knows its own
status. `DownstreamErrors.read` (from 1.7.0) takes it from the response and returns it
together with the messages:

```java
webClient.get().uri(...)
    .retrieve()
    .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
        .map(error -> error.status().value() == 404 && error.hasCode("NOT_FOUND_SENSOR")
            ? new SensorNotFoundException()
            : new SensorCallException(error.httpStatus(HttpStatus.BAD_GATEWAY), error.errors())))
    .bodyToMono(Sensor.class);
```

- The status always survives, and the returned `Mono` never fails. A missing body, a proxy's
  HTML page, JSON of another shape, a connection lost half way through the body — each gives
  the status with an empty set of messages. A body that is not the contract leaves one `WARN`
  line naming only the kind of failure; a missing body, or the contract with no messages,
  leaves none.
- The wait for the body is bounded: 2 s by default, or `read(response, timeout)`. The headers
  have arrived by then, and a body that stalls must not keep the status from the caller. Keep
  it below the timeout you put on the whole call: otherwise that one fires first and you see
  your own timeout instead of the status you were already sent.
- The body is read as text and parsed here, so the `Errors` contract is understood under any
  `Content-Type` — a proxy that rewrites the header does not cost the codes.
- `error.status()` is an `HttpStatusCode`; `error.httpStatus(fallback)` gives an `HttpStatus`
  for the signatures that take one, with the fallback for a status Spring has no constant
  for (a proxy's 520) instead of an exception.
- `error.errors()` is unmodifiable and keeps the order of the body. Copy it before adding to
  it.
- `hasCode` is what tells "the thing you asked for does not exist" from "this path does not
  exist": both are a 404, only the first carries the code. A service on a release before
  1.7.0 sends no codes, so `hasCode` is `false` for all of its errors.

### R2DBC connection factory

Services persisting to PostgreSQL over R2DBC get a pooled `ConnectionFactory` from
auto-configuration — no `DbConfig` class of their own. It activates when
`io.r2dbc.spi.ConnectionFactory` and `io.r2dbc.pool.ConnectionPool` are on the classpath
(both arrive with `spring-boot-starter-data-r2dbc`) and `database.host` is set, and it backs
off when the service declares a `ConnectionFactory` bean itself. Services configuring their
database the Boot way, through `spring.r2dbc.*`, are unaffected.

The connection uses `sslMode=REQUIRE` and is wrapped in an `io.r2dbc.pool.ConnectionPool`
disposed on shutdown:

```yaml
database:
  host: ${database-host:localhost}
  port: ${database-port:5432}
  name: ${database-name:dummyName}
  username: ${database-user:dummyUser}
  password: ${database-password:dummyPassword}
  pool:
    max-size: 8
```

| Property | Default | Description |
|---|---|---|
| `database.host` | — | Host; **also the switch** that activates the auto-configuration |
| `database.port` | — | Port |
| `database.name` | — | Database name |
| `database.username` | — | User |
| `database.password` | — | Password (may be empty, but must be present) |
| `database.ssl-mode` | `REQUIRE` | Driver `sslMode`; lower it only for a database without TLS |
| `database.connect-timeout` | `PT10S` | How long opening a physical connection may take |
| `database.pool.initial-size` | `2` | Connections opened when the pool warms up — must not exceed `max-size` |
| `database.pool.max-size` | `4` | Maximum connections; see the warning below |
| `database.pool.max-acquire-time` | `PT10S` | Time limit of one acquire attempt (waiting for a free connection, opening one, validating it); r2dbc-pool retries a failed attempt once, so a caller can wait up to twice this long |
| `database.pool.max-idle-time` | `PT5M` | Idle connection lifetime |
| `database.pool.max-life-time` | `PT30M` | Total connection lifetime, however busy the connection is |
| `database.pool.max-validation-time` | `PT2S` | How long the validation query on acquire may take before the connection is discarded; keep it well below `max-acquire-time` — at or above it, the acquire limit always cuts the validation off and a broken connection is never discarded |

Every connection handed out by the pool is validated first with `SELECT 1`, bounded by
`max-validation-time`. This is what lets a service recover on its own from a connection the
database stopped answering — without it a caller that times out cancels its query, the
connection goes back to the pool with the query still queued on it, every later caller
queues behind it, and once the driver's request queue is full every query fails with
`RequestQueueException` until the pod is restarted (`database-service`, 2026-09-26). The
check costs one round-trip per acquire.

**Recovery is gradual, not within one request.** A connection that fails the check is
discarded and the acquire is retried once (r2dbc-pool's default); the retry takes the next
idle connection, which after an outage is likely broken too. So one acquire discards up to
two broken connections and its caller may still get an error — a pool of `max-size`
broken connections is clean after about `max-size / 2` requests. A connection whose request
queue is already full fails the check at once; a silent one costs its caller up to
`max-validation-time`, which is why the default is short. A larger retry count is
deliberately not used: r2dbc-pool retries *every* failure, so with the pool exhausted or the
database unreachable each caller would wait `max-acquire-time` once per retry.

Known limits: the validation runs inside the acquire's `max-acquire-time`, together with any
wait for a free connection and the opening of a new one — a validation cut off by that limit
puts its connection back into the pool unchecked, to be caught by the next acquire. Discarding
a connection waits for its `close()`, which has no time limit of its own.

`max-life-time` is the backstop: a connection used every few seconds never reaches
`max-idle-time`. The driver also enables TCP keepalive.

The five connection properties are mandatory and validated at bind time, so a missing one
fails the startup with a message naming it rather than a bare `value must not be null`.
The jar ships the configuration metadata for the whole group, so an IDE completes and
type-checks these keys without the consumer declaring anything.

> **Replacing an existing `DbConfig`?** Set `database.pool.max-size` explicitly in the same
> change. The default of `4` is sized for a new service, and a service whose own pool was
> larger silently shrinks to it — under load the callers then time out on
> `max-acquire-time` instead of queueing on the connections they used to have.

Repositories are deliberately **not** enabled here, so the scan starts from the service's
package and not from this library's. The simplest option in the service is to declare
nothing and let Boot's `R2dbcRepositoriesAutoConfiguration` scan from the application class's
package. If you do want `@EnableR2dbcRepositories`, put it on a dedicated `@Configuration` —
on the application class it leaks into `@WebFluxTest` slices, which then fail on the missing
`r2dbcEntityTemplate`.

The bean is named `connectionFactory`, which is also the `name` tag of the `r2dbc_pool_*`
metrics when Actuator and a Micrometer registry are on the classpath.

### Validation messages in English

Bean Validation words a violated constraint in the locale of the JVM or of the request, so
the same request is answered with `nie może być odstępem` on one machine and `must not be
blank` on another. From 1.6.0 an auto-configuration replaces the message interpolator of the
validator Spring Boot configures with one that ignores the locale it is handed and always
asks for the **root** message bundle — English for the built-in constraints. Nothing to
declare — it is active whenever `spring-boot-validation` is on the classpath, which it is for
every consumer of this library.

- A constraint with its own `message = "…"` is rendered as written, in whatever language.
- `{keys}` are still resolved the way Spring Boot does it — first from the application's
  `MessageSource`, then from `ValidationMessages` — but always from the root file
  (`messages.properties`, `ValidationMessages.properties`), not from `_pl` or any other
  locale variant. Two consequences for an application with message files of its own:
  - **The wording everyone should see has to be in the root file.** A key that exists only
    in `messages_en.properties` or `ValidationMessages_en.properties` is no longer found: the
    response carries the literal `{key}`, or — with no root file at all — the text of the JVM
    locale.
  - **A `ReloadableResourceBundleMessageSource` still prefers the JVM locale** unless its
    `fallbackToSystemLocale` is `false`: it looks the system locale up before the root file
    whatever locale it is asked for. Spring Boot's default `ResourceBundleMessageSource` and
    Hibernate's `ValidationMessages` do not.
- A `ValidationConfigurationCustomizer` of your own that installs an interpolator wins: the
  one from this library runs first.
- It covers the validator behind `@Valid` request bodies and constrained request
  parameters. It does **not** cover the validation of `@ConfigurationProperties` at startup:
  Spring Boot builds a validator of its own for that.
- To get localized messages back, set `cholewa.validation.english-messages: false`.
- A consumer that already carries a customizer doing the same can delete it; until it does,
  the two coexist.

### Info endpoint

`InfoController` (active in web applications only) exposes `GET /info` with the
application name, version and git commit id. It requires the `application.title` and
`application.version` properties and `GitProperties` (the `git-commit-id` Maven plugin)
in the consuming service.
