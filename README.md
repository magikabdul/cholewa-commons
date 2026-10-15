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
English, a RabbitMQ connection named after its pod, a publisher of household notifications
and a simple `/info` endpoint exposing application name, version and git commit.

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
    <version>1.9.0</version>
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
- Only elements with a textual `message` count as messages, and only a textual `code` is a
  code. `errors` is a common name — Spring's own body for a failed binding has an array under
  it too, and many external APIs answer with numeric codes — so an element of another shape
  is skipped, and a code that is not text is dropped, rather than passed on as if a service
  of yours had issued it. One element that cannot be read does not cost the others; skipped
  elements leave one `WARN` line with their number.
- `hasCode` is what tells "the thing you asked for does not exist" from "this path does not
  exist": both are a 404, only the first carries the code. A service on a release before
  1.7.0 sends no codes, so `hasCode` is `false` for all of its errors.

A service that does none of this — a plain `retrieve()` whose `WebClientResponseException`
reaches the global handler — still passes the named causes on. From 1.7.0 the built-in
processor answers with its own message first (`404 Not Found from GET …`, as before) followed
by the downstream messages **that carry a code**, as `message` + `code` and without their
`details`. So a code survives every hop without any code in between, while raw exception
text — which is what `details` usually holds — and messages of anything that merely answers
with an `errors` array stay where they were. A downstream that sends no codes is answered
exactly as before. `DownstreamErrors.messagesOf(String)` is the same parsing for a body you
already hold.

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

### RabbitMQ connection named after the pod

From 1.8.0 an application with Spring AMQP on the classpath gets a `ConnectionNameStrategy`
from auto-configuration, and Spring Boot hands it to the connection factory it configures.
The management UI of the broker then shows who is connected:

| `HOSTNAME` | Connection name |
|---|---|
| starts with `spring.application.name` and a dash — a pod: `heating-service-69bccdf7f9-xzfxt` | the value as it is, which tells the old pod from the new one during a rollout |
| anything else — a workstation, a container id, a pod of another application, empty, not set | `<spring.application.name>-local` |

Nothing to declare. Outside Kubernetes `HOSTNAME` is not simply missing — Git Bash and Linux
shells set it to the name of the machine, plain Docker to a container id — which is why only a
value that starts with the application name counts.

- It needs `spring.application.name`; without one (or with a blank one) the default name of
  Spring AMQP is left alone.
- A `ConnectionNameStrategy` bean of your own wins; the one from this library is then not
  created.
- `cholewa.rabbit.pod-connection-name: false` switches it off.
- **Taking 1.8.0 renames the connection** of an application that has Spring AMQP and an
  application name. It is only the label the broker shows; nothing routes by it.
- An application without Spring AMQP is not affected: the dependency is `provided`, so it
  does not arrive through this library.

### Notification publisher

`NotificationPublisher` (from 1.8.0) publishes a notification for the household as plain text
to a RabbitMQ **headers exchange**. The routing key is empty; the exchange routes by two
headers and the consumer reads the third:

| Method | `category` | `level` | Meaning |
|---|---|---|---|
| `publishAlert(text)` | `alert` | `error` | something went wrong |
| `publishReminder(text)` | `alert` | `warn` | it is still wrong — the same queue as the alert |
| `publishInfo(text)` | `info` | `info` | it is fine again |

Every message also carries `env` (for example `prod` or `dev`), content type `text/plain` and
encoding `UTF-8`. These names and values are the wire contract with the consumer of the
queues; the exchange, the queues and the bindings are expected to exist — the publisher
declares nothing.

```java
notificationPublisher.publishAlert("The sensor in the office is not reporting")
    .then(alertRepository.save(alert));   // runs only when the message is in a queue
```

**The returned `Mono` completes only when the broker has taken the message into a queue**: it
confirmed the message *and* did not hand it back. A send that merely returns proves nothing —
the broker confirms a message that matches no binding too, after returning it. So state that
depends on a notification can be written after the `Mono` completes. Every other outcome is an
error of the `Mono`:

| Outcome | Error |
|---|---|
| the broker refused the message (nack) | `NotificationPublishException` — `Notification refused by the broker: <reason>` |
| the message matched no queue | `NotificationPublishException` — `Notification was not routed to any queue: <reply text>` |
| no confirmation within the timeout (10 s by default) | `NotificationPublishException` — `Notification not confirmed by the broker within PT10S` |
| the broker cannot be reached | the exception of Spring AMQP, as it comes (`AmqpConnectException`, …) |

- **Nothing is retried and nothing is swallowed.** A notification must neither fail nor stall
  what it reports on, and only the caller knows what that takes: put an `onErrorResume` and a
  timeout of your own around a publish that sits in a control loop.
- **A confirmation that arrives after the timeout does not count.** The publish has failed by
  then; a caller that repeats it sends the notification a second time. With a slow broker that
  means duplicates, for as long as it stays slow.
- **What you chain after a publish runs on `Schedulers.boundedElastic()`** — after a success, a
  refusal and a timeout alike — never on a thread of the broker connection. Spring AMQP
  delivers a confirmation on the thread that reads the frames of the connection; a slow step
  there would hold up its heartbeats and every other confirmation, so the outcome is handed
  over first. The send itself blocks on the connection and runs on the same scheduler.
- The connection is opened by the first publish, so a wrong password shows only then — force
  it after a deployment (the RabbitMQ health indicator of Actuator does, for the connection
  Spring Boot configures).

There are two ways to get a publisher, for the two shapes of application.

#### One connection, already on the virtual host of the notifications

Point `spring.rabbitmq.*` at that virtual host and switch the publisher on:

```yaml
spring:
  rabbitmq:
    virtual-host: /notification
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
      observation-enabled: true   # the published message carries the trace of what raised it

cholewa:
  notification:
    enabled: true
    env: prod
```

| Property | Default | Description |
|---|---|---|
| `cholewa.notification.enabled` | `false` | The switch; the publisher exists only when it is `true` |
| `cholewa.notification.env` | — | Value of the `env` header. Mandatory, and without a default on purpose: with one, a run on a workstation would publish into the queues of production |
| `cholewa.notification.exchange` | `notification` | The headers exchange |
| `cholewa.notification.confirm-timeout` | `10s` | How long the confirmation of the broker is waited for; a bare number is seconds, 1 s to 60 s |

The publisher uses the `RabbitOperations` bean of the application — the template Spring Boot
configures — and adds no connection factory and no template of its own. **The application
does not start** unless that connection has correlated confirms and publisher returns and the
template is mandatory: without the confirms every publish would end in the timeout, and
without the other two a notification that reached no queue would be reported as sent. It
does not start either when there is no `RabbitOperations` bean at all, or when there are
several and none is `@Primary` — which one leads to the notifications is not guessed. In a
context test that mocks the `RabbitTemplate`, switch the publisher off or mock the publisher:
a mocked template has no connection factory to check. A `NotificationPublisher` bean of your
own wins.

The template is not given a returns callback (a `RabbitTemplate` takes only one, and it is
yours). Without one Spring AMQP logs `Returned message but no callback available` at `WARN`
for a message that matched no queue; add a `RabbitTemplateCustomizer` if you want a line of
your own.

#### A second connection, to another virtual host

A connection belongs to exactly one virtual host. An application whose own connection leads
elsewhere — a listener on another virtual host — needs a second one for the notifications,
and leaves `cholewa.notification.enabled` off:

```java
@Configuration
class NotificationRabbitConfig {

    @Bean
    NotificationConnection notificationConnection(
        RabbitProperties rabbit,                       // the same broker, another virtual host
        MyNotificationProperties notification,
        ConnectionNameStrategy connectionNameStrategy,
        ApplicationContext applicationContext
    ) {
        return NotificationConnection.create(
            new NotificationConnection.Settings(
                rabbit.determineHost(), rabbit.determinePort(),
                notification.virtualHost(), notification.username(), notification.password(),
                rabbit.getConnectionTimeout(), rabbit.getRequestedHeartbeat()),
            connectionNameStrategy,
            applicationContext);
    }

    @Bean
    NotificationPublisher notificationPublisher(
        NotificationConnection connection, MyNotificationProperties notification
    ) {
        return connection.publisher(notification.exchange(), notification.env());
    }
}
```

`NotificationConnection` builds the connection factory and the template by hand, with
correlated confirms, returns, the mandatory flag, a log line for a returned message and
observation switched on. The connection is named like the first one of the pod with
`/notification` added (`heating-service-69bccdf7f9-xzfxt/notification`). Declared as a bean it
is closed with the application context, and closed is final: a publish after that fails with
`AmqpApplicationContextClosedException` instead of opening a connection again.

`Settings` is one host and port, a virtual host, a user and a password, and optionally the
connection timeout and the requested heartbeat. It does **not** cover TLS or a list of broker
addresses (`rabbit.determineHost()` gives the first address of such a list). The port has to
be 1 to 65535, the timeout at least a millisecond, the heartbeat zero — switched off — or at
least a second: the client takes whole milliseconds and seconds, and a smaller value would
reach it as zero, which means "no limit" and "no heartbeat".

> **Never expose its connection factory or `rabbitOperations()` as a bean.** Spring Boot's
> `RabbitAutoConfiguration` backs off from any `ConnectionFactory` or `RabbitOperations` bean,
> and the listeners of the application would lose the auto-configured connection together
> with everything `spring.rabbitmq.*` sets on it — acknowledge mode, prefetch, observation.
> The `NotificationConnection` and the publisher are safe to declare; that is what the class
> is for.

The second connection is not covered by the RabbitMQ health indicator, which looks at the
`RabbitTemplate` beans.

### Required properties checked at the start

A property bound from a placeholder Spring cannot resolve keeps the placeholder as its text:
`spring.rabbitmq.password: ${rabbitmq-password}` without the variable becomes the password
`${rabbitmq-password}`, the application starts and turns Ready, and the broker refuses it later.
List the properties the application must not start without, and the start is refused **before
the first bean is created** — no connection opened, no migration run:

```yaml
cholewa:
  required-properties:
    - property: spring.rabbitmq.password
      variable: rabbitmq-password            # optional, named in the refusal
    - property: notification.password
      variable: notification-rabbitmq-password
```

```
A required property has no value: spring.rabbitmq.password (set rabbitmq-password)
```

- A property counts as missing when it is not set, is blank, or holds a placeholder that does
  not resolve. Every missing one is named in one message.
- The value is never quoted — not even the part of a password with `${` in it that did resolve.
- **Nothing is checked without the list** (since 1.9.0): an application that does not list
  anything is not touched by the upgrade.
- A profile for a workstation or for tests gives the properties a default (`${rabbitmq-password:dummyPassword}`)
  and passes the check; the list stays in the shared document.

### Info endpoint

`InfoController` (active in web applications only) exposes `GET /info` with the
application name, version and git commit id. It requires the `application.title` and
`application.version` properties and `GitProperties` (the `git-commit-id` Maven plugin)
in the consuming service.
