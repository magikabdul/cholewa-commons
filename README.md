# cholewa-commons

[![CI](https://github.com/magikabdul/cholewa-commons/actions/workflows/CI.yml/badge.svg)](https://github.com/magikabdul/cholewa-commons/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)

[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=coverage)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=magikabdul_cholewa-commons&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=magikabdul_cholewa-commons)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.0-blue?style=plastic)

![GitHub issues](https://img.shields.io/github/issues/magikabdul/cholewa-commons?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/magikabdul/cholewa-commons?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/magikabdul/cholewa-commons?style=plastic)

![GitHub release](https://img.shields.io/github/v/release/magikabdul/cholewa-commons?style=plastic)
![GitHub release date](https://img.shields.io/github/release-date/magikabdul/cholewa-commons?style=plastic)
![GitHub last commit](https://img.shields.io/github/last-commit/magikabdul/cholewa-commons?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/magikabdul/cholewa-commons?style=plastic)

Common building blocks for reactive (WebFlux) Spring Boot services: a global error
handler with a pluggable exception-processor mechanism, a consistent JSON error model,
an auto-configured pooled R2DBC connection factory and a simple `/info` endpoint exposing
application name, version and git commit.

Used by the [smart-home-automation-system](https://github.com/smart-home-automation-system)
services (`amx-service`, `api-gateway-service`, `boiler-service`, `database-service`,
`heating-service`, `shelly-cloud-service`, `water-service`) and other applications
outside that organization.

## Installation

The artifact is published to GitHub Packages:

```xml
<dependency>
    <groupId>cloud.cholewa</groupId>
    <artifactId>cholewa-commons</artifactId>
    <version>1.3.0</version>
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

Processor selection is hierarchy-aware: an exception matches the processor registered
for its exact class or, failing that, for its most specific registered supertype (e.g.
`MissingRequestValueException` is handled by the `ServerWebInputException` processor).
Framework `ResponseStatusException`s without a more specific registration (unmatched
route → 404, unsupported method → 405, …) keep their own status; exceptions with no
matching registration at all fall back to the default processor (HTTP 500).

Database integrity errors have their own tier: `DuplicateKeyException` renders as
`400` with the message `Duplicate Key`, and every other
`DataIntegrityViolationException` (a `NOT NULL` or check-constraint violation, …) as
`400` with `Data integrity violation`. Both deliberately omit `details` — the raw
driver text names tables, columns and constraints, which must not reach a client; it
is logged instead. These processors are why the library depends on `spring-tx`.

Every built-in processor logs the exception it handles with a uniform
`Handled [<exception class>]: …` line — at `WARN` level for 4xx responses and `ERROR`
for 5xx (processors with a dynamic status pick the level from the resolved status).
Only the default processor logs the stack trace. Custom processors registered via
`withCustomErrorProcessor` are responsible for their own logging.

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
| `database.pool.initial-size` | `2` | Connections opened when the pool warms up — must not exceed `max-size` |
| `database.pool.max-size` | `4` | Maximum connections; see the warning below |
| `database.pool.max-acquire-time` | `PT10S` | How long a caller waits for a free connection |
| `database.pool.max-idle-time` | `PT5M` | Idle connection lifetime |

The five connection properties are mandatory and validated at bind time, so a missing one
fails the startup with a message naming it rather than a bare `value must not be null`.

> **Replacing an existing `DbConfig`?** Set `database.pool.max-size` explicitly in the same
> change. The default of `4` is sized for a new service, and a service whose own pool was
> larger silently shrinks to it — under load the callers then time out on
> `max-acquire-time` instead of queueing on the connections they used to have.

Repositories are deliberately **not** enabled here — keep `@EnableR2dbcRepositories` on a
class in the service (or rely on Boot's own auto-configuration), so the scan starts from the
service's package and not from this library's.

The bean is named `connectionFactory`, which is also the `name` tag of the `r2dbc_pool_*`
metrics when Actuator and a Micrometer registry are on the classpath.

### Info endpoint

`InfoController` (active in web applications only) exposes `GET /info` with the
application name, version and git commit id. It requires the `application.title` and
`application.version` properties and `GitProperties` (the `git-commit-id` Maven plugin)
in the consuming service.
