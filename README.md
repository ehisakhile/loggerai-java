# LoggerAI for Java

Performance monitoring, errors, logs, health checks and server metrics for
Spring Boot (Spring MVC and WebFlux) and any servlet app, with AI that explains what went wrong.

- **Every endpoint** timed under its route pattern (`/orders/{id}`):
  p50/p95/p99, Apdex, status codes, slow and failed requests.
- **What each request waits on**: every JDBC query (so JPA and Hibernate too),
  grouped as `mysql SELECT`, `postgres UPDATE`; calls made with `RestTemplate`,
  `RestClient` and `WebClient`.
- **Exceptions**, grouped by cause, with stack traces that show your code
  first, including ones an `@ExceptionHandler` turned into a 5xx.
- **Logs** at warning and above from SLF4J/Logback.
- **Health checks** you define (database, cache, a queue).
- **The server**: CPU, memory and disk every 30 seconds (inside Docker, against
  the container's own limits).
- **Background work** (`@Scheduled` tasks, queue listeners) timed like requests.

Secrets (passwords, tokens, cookies, API keys, JWTs) are masked before
anything leaves your server, request and response bodies are never sent, and
users are counted by a one-way hash of their id. No dependencies; Java 17+.

## Install

Maven:

```xml
<dependency>
  <groupId>uk.loggerai</groupId>
  <artifactId>loggerai-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("uk.loggerai:loggerai-java:0.1.0")
```

Get your project's **secret key** (`sk_live_...`) from LoggerAI → your
project → **Connect**, and set it in the environment:

```bash
LOGGERAI_KEY=sk_live_...
LOGGERAI_RELEASE=$GIT_SHA      # optional: "since release X"
```

Without a key nothing is recorded or sent, so local development stays quiet.

Check it can reach LoggerAI from your server (or container):

```bash
java -jar loggerai-java-0.1.0.jar test
```

## Spring Boot 3

Nothing else to do: with the dependency and the key, it starts on its own.
Requests are named by their `@GetMapping` pattern, the app's `DataSource` is
timed, `RestTemplate` and `RestClient` calls are timed, and warnings and
errors logged through SLF4J are sent. The service is named after
`spring.application.name`.

Settings go in `application.properties` or the environment (`LOGGERAI_*`):

```properties
loggerai.key=${LOGGERAI_KEY}
loggerai.release=${GIT_SHA:}
loggerai.environment=production
# loggerai.enabled=false            # off, e.g. in tests
# loggerai.host-metrics=false       # no CPU, memory and disk
# loggerai.logs-level=ERROR         # or NONE
# loggerai.app-packages=com.acme    # your code, for stack traces (default: your @SpringBootApplication's package)
```

## Spring WebFlux (reactive)

Also automatic: every request is timed under its route pattern, `WebClient`
calls are timed, and errors (including ones an `@ExceptionHandler` turns into
a 5xx) are reported. A reactive request hops between threads, so it travels
in the Reactor context; use the reactive helpers from your handlers:

```java
import uk.loggerai.reactive.ReactiveLoggerAI;

return ReactiveLoggerAI.measure("api.stripe.com", "POST", stripe.confirm(id))   // a call LoggerAI can't see (R2DBC, reactive Mongo, an SDK)
        .flatMap(pi -> ReactiveLoggerAI.setUser(user.getId()).thenReturn(pi));
```

## Other servlet apps (Jakarta EE, Quarkus on servlets, plain Tomcat or Jetty)

```java
LoggerAI.init(Options.fromEnvironment().service("api").appPackages(List.of("com.acme.")));
```

Register `uk.loggerai.servlet.LoggerAIFilter` for every path, first in the
chain, and wrap your DataSource: `dataSource = LoggerAIDataSource.wrap(dataSource)`.
For Logback, add `uk.loggerai.logback.LoggerAIAppender` to `logback.xml`.
Without a framework that names routes, ids in the path become `{id}`.

## Microservices

Give each service its own name and use the same project key everywhere
(`LOGGERAI_SERVICE=payments`). Calls between services then link up on their own:
a request is one trace from the first service to the last, and LoggerAI's
Service map shows which service calls which. The servlet and WebFlux filters
continue the trace each request is called with (`traceparent`), and
RestTemplate, RestClient and WebClient calls carry it on, with this service's
name (`x-loggerai-caller`). Only the project's own services get them: hosts without a dot (Docker and Kubernetes service names), private addresses, and `.internal`, `.local` and `.svc.cluster.local` names. Outside APIs (Stripe, OpenAI) never do. If your services call each other by a public name, list it (`Options.propagate("api.acme.io,*.acme.io")`, or
`LOGGERAI_PROPAGATE`; `all` or `none` also work).

Queues: put the trace on the message, and pass it back when you handle it.

```java
var out = new ProducerRecord<>("orders", key, value);
LoggerAI.traceHeaders("orders").forEach((k, v) -> out.headers().add(k, v.getBytes(UTF_8)));

Map<String, String> h = new HashMap<>();
record.headers().forEach(x -> h.put(x.key(), new String(x.value(), UTF_8)));
LoggerAI.track("job", "order.created", h, () -> handle(record));
```

## More

```java
// Count requests against a user (only a one-way hash of the id is sent)
LoggerAI.setUser(user.getId());

// An exception you handled but still want to see
LoggerAI.captureException(e, Map.of("orderId", id));

// A call LoggerAI can't see on its own (an SDK with its own HTTP client)
PaymentIntent pi = LoggerAI.measure("api.stripe.com", "POST", () -> stripe.confirm(id));

// A scheduled task or queue listener, timed like a request
@Scheduled(cron = "0 0 * * * *")
void sendInvoices() {
    LoggerAI.track("job", "SendInvoices", () -> invoices.sendAll());
}

// Health checks, every minute: return true/false, or throw
LoggerAI.healthCheck("database", () -> jdbc.queryForObject("SELECT 1", Integer.class) == 1);
```

Requests are followed on the thread that serves them (and on to async
servlet completion). Work you hand to another thread pool isn't attributed
to the request; time it with `measure`.

## How it behaves

- Batches go out every 5 seconds from one daemon thread, gzipped, with a
  2-second timeout. Nothing makes a request wait, and nothing throws into
  your app.
- When LoggerAI can't be reached, data is dropped rather than piling up in
  memory. A refused key (401) stops sending until the app restarts.
- When the JVM stops (deploys, containers stopping), it sends what's left first.

## Development

```bash
mvn verify
```

A tag like `v0.1.0` runs the tests and publishes to Maven Central.
