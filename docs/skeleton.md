# Maluca — Skeleton Guide

This document explains every single file and folder in the current skeleton.
Nothing here is implementation. It is all plumbing — build system, dependencies,
infrastructure, and configuration. You will write all the actual code yourself.

---

## Top-level layout

```
maluca/
  settings.gradle
  build.gradle
  gradle/wrapper/gradle-wrapper.properties
  docker-compose.yml
  .env.example
  maluca-proxy/
  demo-backend/
  scripts/traffic/
  docs/
  infra/
```

---

## Gradle — the build system

Gradle is the tool that compiles your Java code, manages dependencies (libraries),
and packages everything into a runnable JAR.

This project uses a **multi-module** Gradle setup. That means one root build that
contains two separate sub-projects (modules): `maluca-proxy` and `demo-backend`.
They share a common build configuration at the root level but each has its own
dependencies and produces its own JAR.

### `settings.gradle`

```groovy
rootProject.name = 'maluca'
include 'maluca-proxy'
include 'demo-backend'
```

This is the entry point for Gradle. It:
- Names the root project "maluca"
- Tells Gradle that `maluca-proxy/` and `demo-backend/` are sub-modules

Without this file Gradle doesn't know about the sub-modules at all.

### `build.gradle` (root)

This applies shared configuration to **both** sub-modules so you don't repeat yourself.

```groovy
plugins {
    id 'org.springframework.boot' version '3.3.5' apply false
    id 'io.spring.dependency-management' version '1.1.6' apply false
}
```

- **spring-boot plugin** — knows how to package a Spring Boot app into a single runnable JAR.
  `apply false` means it's declared here (for version control) but not actually applied at the root.
  Each sub-module applies it themselves.
- **dependency-management plugin** — lets you import a Spring Boot "BOM" (Bill of Materials).
  A BOM is a curated list of library versions that are tested to work together.
  This means you don't need to manually specify versions for every Spring library —
  the BOM picks compatible versions for you.

```groovy
subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'
    group = 'com.maluca'
    version = '0.1.0-SNAPSHOT'
    java.sourceCompatibility = JavaVersion.VERSION_21
    ...
    dependencyManagement {
        imports { mavenBom "org.springframework.boot:spring-boot-dependencies:3.3.5" }
    }
}
```

- `apply plugin: 'java'` — tells Gradle this is a Java project (compile, test, package)
- `group = 'com.maluca'` — the Maven group ID, like a namespace for your JARs
- `java.sourceCompatibility = VERSION_21` — compile with Java 21 features enabled
- `mavenBom` — imports the Spring Boot BOM so all Spring library versions are managed

### `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionUrl=https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
```

The Gradle wrapper means anyone who clones this repo can build it without
installing Gradle manually. When you run `./gradlew build`, this file tells
the wrapper which Gradle version to download. The wrapper JAR (`gradlew` script)
is what you need to generate — run `gradle wrapper` once locally to produce
`gradlew` and `gradlew.bat`.

---

## `maluca-proxy/build.gradle` — the proxy dependencies

This is where you declare the libraries Maluca will actually use.

```groovy
plugins {
    id 'org.springframework.boot'  // apply the Spring Boot plugin here
}
```

### Dependencies, one by one

```groovy
implementation 'org.springframework.boot:spring-boot-starter-webflux'
```
**WebFlux** — the reactive (non-blocking) web framework for Spring Boot.
This is the core of the entire proxy. Instead of one thread per HTTP request
(the old way), WebFlux uses an event loop: a small number of threads handle
thousands of concurrent requests by never blocking while waiting for I/O.

For a proxy this matters enormously — you're always waiting on two things:
Redis (to read/write counters) and the upstream backend (to forward the request).
WebFlux lets you wait on both without parking a thread.

This gives you: HTTP server, HTTP client (WebClient for proxying), routing.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```
**Actuator** — adds built-in management endpoints to your app.
Gives you `/actuator/health` (is the app alive?), `/actuator/metrics`, etc.
You will expose `/actuator/prometheus` for metric scraping.
Zero code required — it's all automatic when the dependency is present.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'
```
**Reactive Redis** — connects to Redis using the Lettuce client (non-blocking).
"Reactive" means Redis operations return `Mono<T>` (a future value), not `T` directly.
This keeps Redis I/O consistent with the WebFlux event loop — no thread blocking.

You will use this to store and read per-client counters, track distinct paths,
and record block entries, all with automatic TTL-based expiry.

```groovy
implementation 'io.micrometer:micrometer-registry-prometheus'
```
**Micrometer + Prometheus** — metrics library.
Micrometer is the abstraction layer (you write `counter.increment()`).
This dependency adds the Prometheus backend, which formats metrics in a way
Prometheus can scrape. This is how you'll track total requests, rate-limited
requests, blocked requests, and upstream latency.

```groovy
implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```
**Logstash encoder** — makes your logs emit as JSON instead of plain text.
Example output:
```json
{"timestamp":"...","level":"INFO","clientKey":"1.2.3.4","action":"BLOCK","score":95}
```
Structured logs are machine-readable, which matters when you're trying to debug
why a specific client was blocked or grep logs for all RATE_LIMIT decisions.

```groovy
compileOnly 'org.projectlombok:lombok'
annotationProcessor 'org.projectlombok:lombok'
```
**Lombok** — generates boilerplate Java code (getters, setters, builders, constructors)
via annotations at compile time. `@Value` gives you an immutable class with getters.
`@Builder` gives you a builder pattern. `@Slf4j` injects a logger.
`compileOnly` means it's used during compilation but not shipped in the final JAR.

```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'io.projectreactor:reactor-test'
```
Test dependencies. `spring-boot-starter-test` pulls in JUnit 5, Mockito, AssertJ.
`reactor-test` gives you `StepVerifier` — a tool for testing reactive Mono/Flux
streams, which you'll need when testing Redis operations.

---

## `demo-backend/build.gradle`

Much simpler — just standard Spring MVC (blocking web). The demo backend is
not the thing you're learning to build. It's just a simple HTTP server that
sits behind Maluca so you have something real to proxy to.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
```
Standard blocking web framework. Fine for the backend since it's not in the
critical path of the proxy's event loop.

---

## `docker-compose.yml` — the local environment

Docker Compose defines all the services that need to run together locally.
You run `docker compose up` and everything starts.

### Services

**`redis`**
```yaml
image: redis:7.2-alpine
ports: ["6379:6379"]
```
Redis is the shared state store for Maluca. Every counter (request rate,
distinct paths, sensitive hits), every block entry — all of it lives here.

Why Redis instead of in-memory (a Java HashMap)?
If you run two Maluca instances, in-memory state is per-instance and they
can't see each other's counts. Redis is shared, so counters are correct
across all instances. This is what makes horizontal scaling work.

Alpine variant = smaller image, faster to pull.

**`demo-backend`**
```yaml
expose: ["8081"]   # internal only, not published to host
```
The backend service Maluca proxies to. Not published to host — you can only
reach it through Maluca. This simulates a real deployment where the backend
is protected and only the proxy is exposed.

**`maluca-proxy`**
```yaml
ports: ["8080:8080"]   # this is the public entry point
environment:
  UPSTREAM_URL: http://demo-backend:8081
  REDIS_HOST: redis
```
The proxy is the only thing exposed to the outside world. `UPSTREAM_URL` is
set to the Docker internal hostname of the backend — `demo-backend` resolves
to the backend container's IP automatically within the Docker network.

`REDIS_HOST: redis` — same thing, "redis" resolves to the Redis container.

**`prometheus`** (behind `--profile observability`)
```yaml
profiles: [observability]
```
Only starts when you explicitly run `docker compose --profile observability up`.
Not needed for basic development — only when you want to see metrics graphs.

### `maluca-net` network

All services are on the same Docker bridge network so they can reach each
other by container name (`demo-backend`, `redis`, etc.).

---

## `maluca-proxy/src/main/resources/application.yml`

This is the configuration file for the Maluca proxy app.
Spring Boot reads this on startup and makes values available throughout the code.

### Key sections

**`server.port`**
```yaml
server:
  port: ${PORT:8080}
```
`${PORT:8080}` means: read the `PORT` environment variable, fall back to 8080.
This is how the same config works both locally (port 8080) and in Docker (set via env).

**`spring.data.redis`**
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```
Tells Spring where Redis is. Locally: `localhost`. In Docker: `redis` (container name).
Both handled by the same env-variable pattern.

**`management.endpoints`**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```
Tells Actuator which endpoints to expose publicly. Without this, most endpoints
are hidden by default. `prometheus` is the metrics scrape endpoint.

**`maluca.upstream`**
```yaml
maluca:
  upstream:
    url: ${UPSTREAM_URL:http://localhost:8081}
    connect-timeout-ms: 5000
    response-timeout-ms: 30000
```
Where to forward allowed traffic. You will read this in your config class and
use it when building the HTTP client that proxies requests upstream.

**`maluca.identity`**
```yaml
maluca:
  identity:
    trust-x-forwarded-for: false
```
`X-Forwarded-For` is an HTTP header that a load balancer sets to tell you
the real client IP when it proxies a request. If Maluca is directly exposed
(no LB in front), you must NOT trust this header — an attacker can send a
fake `X-Forwarded-For: 1.2.3.4` and bypass per-IP tracking.
Off by default. Only enable when there is a trusted LB in front.

**`maluca.policy`**
```yaml
maluca:
  policy:
    default:
      thresholds:
        requests-per-10s: 30
        requests-per-60s: 120
        distinct-paths-per-30s: 15
        sensitive-hits-per-60s: 20
      scoring:
        high-qps-10s-weight: 40
        ...
      bands:
        allow-max: 39
        observe-max: 69
        rate-limit-max: 89
    sensitive-paths:
      - /login
      - /admin
```
All the tunable thresholds and score weights — no code changes needed to adjust
sensitivity. You will bind this to a config class and use those values in your
scorer and signal extractor.

---

## `maluca-proxy/src/main/resources/logback-spring.xml`

Logback is the logging library Spring Boot uses by default.
This config gives you two modes:

- **Default (local dev):** plain human-readable log lines
- **`json-logging` profile:** structured JSON logs (set via `SPRING_PROFILES_ACTIVE=json-logging`)

Docker Compose sets `SPRING_PROFILES_ACTIVE: json-logging` so containers
emit JSON logs. You locally get readable output. Same code, different format.

---

## Directory structure — what each package will hold

```
maluca-proxy/src/main/java/com/maluca/
  config/       Your config classes — bind application.yml values to Java objects
  model/        Plain data classes — RequestContext, ClientState, RiskScore, etc.
  proxy/        The WebFlux filter that intercepts all requests + the upstream forwarder
  state/        Everything that touches Redis — reading/writing counters and blocks
  scoring/      Signal extraction (counters → booleans) + risk scoring (booleans → score)
  mitigation/   Maps score to action (allow/rate-limit/block), writes blocks to Redis
  metrics/      Micrometer counter increments — called after every decision

demo-backend/src/main/java/com/maluca/demo/
  (just the app entry point + a few HTTP endpoints to proxy to)

scripts/traffic/
  (Python scripts you will write to simulate normal and attack traffic)
```

---

## `infra/prometheus.yml`

Tells Prometheus where to scrape metrics from.

```yaml
scrape_configs:
  - job_name: 'maluca-proxy'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['maluca-proxy:8080']
```

Prometheus will hit `http://maluca-proxy:8080/actuator/prometheus` every 5 seconds
and store the metrics. You don't need this until you want to look at graphs.

---

## `Dockerfile` (both modules)

Multi-stage build:

1. **Build stage** — uses a Gradle+JDK image, compiles and packages your code into a JAR
2. **Runtime stage** — copies just the JAR into a slim JRE-only image (no build tools)

Why multi-stage? The final image is much smaller — no Gradle, no JDK, just the JRE.
`eclipse-temurin:21-jre-alpine` is roughly 200MB vs 500MB+ for a full JDK image.

The `eclipse-temurin` image is the community distribution of OpenJDK maintained
by the Eclipse foundation — the de-facto standard for containerized Java.

---

## `.env.example`

Documents the environment variables the app expects.
Copy to `.env` when running locally. Never commit `.env` (it's in `.gitignore`).

---

## What you need to build (all the actual code)

In rough implementation order:

1. **`MalucaApplication.java`** — Spring Boot entry point (5 lines of boilerplate)
2. **`config/MalucaConfig.java`** — bind application.yml `maluca.*` to a Java class
3. **`model/`** — plain data classes: `RequestContext`, `ClientIdentity`, `ClientState`,
   `RiskSignals`, `RiskScore`, `MitigationAction` (enum), `MitigationDecision`
4. **`config/RedisConfig.java`** — configure how Spring connects and serializes to Redis
5. **`config/WebClientConfig.java`** — build the WebClient that forwards requests upstream
6. **`proxy/ClientIdentityExtractor.java`** — extract client IP (or XFF) from the request
7. **`state/RedisStateService.java`** — INCR counters with TTL, read state, write blocks
8. **`scoring/SignalExtractor.java`** — turn raw counter values into boolean signals
9. **`scoring/RiskScorer.java`** — weighted sum of signals → score + band
10. **`mitigation/MitigationEngine.java`** — band → action, write block to Redis if needed
11. **`metrics/MalucaMetrics.java`** — increment Micrometer counters per decision
12. **`proxy/UpstreamProxyHandler.java`** — forward request to backend, stream response back
13. **`proxy/MalucaProxyFilter.java`** — WebFlux filter that orchestrates steps 6–12
14. **`demo-backend/`** — simple Spring MVC controller with a few endpoints
15. **`scripts/traffic/`** — Python scripts to generate normal and attack traffic
16. **Tests** — at minimum, unit tests for the scorer (pure function, easy to test)
