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
handler with a pluggable exception-processor mechanism, a consistent JSON error model
and a simple `/info` endpoint exposing application name, version and git commit.

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
    <version>1.0.1</version>
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
errors, `NoSuchElementException`, …) are built in; service-specific exceptions plug in
via `withCustomErrorProcessor`:

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

### Info endpoint

`InfoController` (active in web applications only) exposes `GET /info` with the
application name, version and git commit id. It requires the `application.title` and
`application.version` properties and `GitProperties` (the `git-commit-id` Maven plugin)
in the consuming service.
