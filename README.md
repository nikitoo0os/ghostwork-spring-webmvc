# GhostWork Spring Web MVC

[![Build](https://github.com/nikitoo0os/ghostwork-spring-webmvc/actions/workflows/ci.yml/badge.svg)](https://github.com/nikitoo0os/ghostwork-spring-webmvc/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nikitoo0os/ghostwork-spring-webmvc)](https://central.sonatype.com/artifact/io.github.nikitoo0os/ghostwork-spring-webmvc)
[![Java 21](https://img.shields.io/badge/Java-21%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Automatic HTTP request lifecycle tracking for GhostWork and Spring MVC.

## Compatibility

| Web MVC integration | GhostWork | GhostWork Spring | Spring Boot | Java |
| --- | --- | --- | --- | --- |
| `0.8.x` | `0.8.x` | `0.8.x` | `3.4.x` | `21+` |

## Installation

```xml
<dependency>
    <groupId>io.github.nikitoo0os</groupId>
    <artifactId>ghostwork-spring-webmvc</artifactId>
    <version>0.8.0</version>
</dependency>
```

Provide the normal `GhostWork` bean. The Web MVC module transitively includes
`ghostwork-spring`, so executor and `@Async` instrumentation are available too.

```java
@Bean
GhostWork ghostWork() {
    return GhostWork.create(Executors.newFixedThreadPool(8));
}
```

No controller annotation, filter, or manual operation wrapper is required.

## Automatic HTTP Request Tracking

```java
@RestController
public class OrderController {

    @GetMapping("/orders/{id}")
    public DeferredResult<Order> order(@PathVariable long id) {
        DeferredResult<Order> result = new DeferredResult<>();
        orderService.load(id).thenAccept(result::setResult);
        return result;
    }
}
```

GhostWork produces:

```text
Operation: GET /orders/{id}
|- Task: OrderService.load
|- HTTP response completed
`- Tasks outlived HTTP request (when applicable)
```

The URI template is used instead of the concrete URI to avoid high-cardinality
operation names.

```mermaid
sequenceDiagram
    participant Client
    participant MVC as Spring MVC
    participant GW as GhostWork
    participant Exec as Spring Executor

    Client->>MVC: GET /orders/42
    MVC->>GW: start GET /orders/{id}
    MVC->>Exec: submit OrderService.load
    Exec->>GW: task submitted / started
    MVC-->>Client: async response
    MVC->>GW: AsyncListener.onComplete
    GW->>GW: operation completed
    Exec->>GW: task completed
```

![GhostWork dashboard execution tree](https://raw.githubusercontent.com/nikitoo0os/ghostwork-dashboard-spring/main/docs/images/execution-tree.png)

## Supported Lifecycles

The integration uses `HandlerInterceptor` and Servlet `AsyncListener`, without
polling. It supports synchronous controllers, `DeferredResult`, `Callable`,
`WebAsyncTask`, `StreamingResponseBody`, and `SseEmitter`. Repeated
`startAsync()` registers the same lifecycle listener on the new async context.

Terminal request states are:

* `COMPLETED`
* `FAILED`
* `TIMED_OUT`
* `ABORTED` for servlet errors caused by an `IOException` or client disconnect

Request completion and child cancellation are separate dimensions:

* timeout keeps the operation `TIMED_OUT` and applies `on-timeout`;
* an observed client disconnect keeps it `ABORTED` and applies
  `on-client-abort`;
* controller failure keeps it `FAILED` and applies `on-operation-failure`;
* normal completion applies `on-operation-complete`, which defaults to `NONE`.

The safe defaults request cooperative cancellation after timeout or client
abort without interrupting running tasks. A custom Spring
`CancellationPolicy` can protect payment work, cancel only queued work, or
interrupt selected operations. Client disconnect detection is best effort:
Servlet containers generally expose it only when a response write or lifecycle
callback reports an I/O failure.

## Request Metadata

Each operation exposes immutable `RequestMetadata` containing the HTTP method,
URI template, remote address, optional query string, request/session/principal
identifiers, timestamps, duration, response status, and async flag.

## Configuration

```yaml
ghostwork:
  web:
    enabled: true
    include-query-string: false
    request-id-header: X-Request-ID
    include: []
    exclude:
      - /actuator/**
      - /swagger-ui/**
      - /v3/api-docs/**
      - /favicon.ico
```

Provide a custom `OperationNameResolver` bean to replace the default
`HTTP_METHOD + URI_TEMPLATE` naming.

## Scope

Version `0.8.x` is Servlet/Spring MVC only. It does not add WebFlux, Reactor,
messaging integrations, metrics, tracing, persistence, or distributed storage.
Scheduling support is provided transitively by `ghostwork-spring`.

## Migration From 0.6

Request ownership, metadata, operation naming, include/exclude rules, async
controller support, and cancellation behavior are unchanged. Version 0.8
aligns the module with core and Spring scheduling support.

## License

Apache License, Version 2.0.
