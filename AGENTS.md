# Agent Instructions & Repository Context

# 1. Repository Overview
This is a polyglot monorepo containing microservices and frontends.
**Do not infer build steps.** Follow the strict conventions below.

- **Root Build Tool:** Gradle 9 (Kotlin DSL) + `just` (Command Runner)
- **Primary Languages:** Kotlin (JVM 21), Python (3.13+), TypeScript (Vue 3)
- **Infrastructure:** Kubernetes (K3s Local, Azure AKS Prod), ArgoCD

## 2. Directory Structure
- `gradle/libs.versions.toml`: Central Version Catalog. **All dependency versions must be defined here.**
- `generated`: folder for generated code.

## 3. Technology Stack & Rules

### A. Kotlin Services (Ktor)
- **Build Tool:** Gradle
- **Containerization:** **Jib** (Google Cloud Tools).
    - ❌ **NO Dockerfiles** for Kotlin apps.
    - ✅ Use `just deploy-kt <service>` to build and load into K3s.
- **Testing:** Kotest + Testcontainers (Wiremock).

### B. Python Services (FastAPI)
- **Build Tool:** `uv` (by Astral).
- **Containerization:** Standard `Dockerfile`.
- **Dependency Mgmt:** `pyproject.toml` + `uv.lock`.
- **Proto Consumption:** **Strict Rule.** Python services consume protos as a local dependency from `libs/shared-proto/build/python-package`.
    - ✅ Use `just proto-py` to regenerate before syncing.

### C. Frontend (Vue + Vite)
- **Build Tool:** npm + vite.
- **Containerization:** Standard `Dockerfile` (Nginx).
- **Proto Consumption:** Consumes protos as a local file dependency from `libs/shared-proto/build/js-package`.

## 4. Development Workflow (The "Just" Commander)
Always suggest `just` commands for interactions.

| Action | Command | Context |
| :--- | :--- | :--- |
| **Initialize Repo** | `just init` | Installs Gradle, uv, npm deps & compiles protos. |
| **Build Kotlin** | `just build-kt <service>` | Compiles JAR. |
| **Build Python** | `just sync-py` | Syncs `uv` environments. |
| **Deploy Local** | `just deploy-<type> <service>` | Builds image & loads directly into K3s (`docker build` or `docker load`). |
| **Debug** | `just debug-tunnel` | Ports forwards K3s services (DB, Wiremock) to localhost. |
| **Regenerate Protos**| `just proto-all` | Recompiles `.proto` files for KT, PY, and JS. |

## 5. Protocol Buffers Strategy
- **Versioning:** Folder based: `src/main/proto/com/example/payment/v1/payment.proto`.
- **Modification:** 1. Edit `.proto` file.
    2. Run `just proto-all`.
    3. Kotlin: Imports are immediately available.
    4. Python: `uv sync` is triggered automatically by `just`.
    5. JS: `npm install` is triggered automatically.

## 6. CI/CD & Versioning
- **Versioning:** Strict Semantic Versioning via Git Tags.
- **Tag Format:** `<service-directory-name>/v<major>.<minor>.<patch>`
    - Example: `payment-service-spring/v1.0.2`
- **CI Logic:** The pipeline automatically detects the project type (Jib vs Docker) based on Gradle plugins. Do not hardcode service lists in GitHub Actions.

## 7. Dependency Management
- **Never** hardcode versions in `build.gradle.kts`.
- **Always** add version to `[versions]` and library to `[libraries]` in `gradle/libs.versions.toml`.
- **Usage:** `implementation(libs.my.library)`

## 8. Common Pitfalls to Avoid
- **Python Imports:** Do not try to import protos from `src/`. They live in the generated `libs/shared-proto` package.
- **Local Images:** When writing K8s manifests for local dev, always use `imagePullPolicy: Never`.
- **Gradle:** Do not use `subprojects {}` or `allprojects {}` in the root build file. Use Convention Plugins in `build-logic`.
- **Ktor Responses:** Never use `mapOf` for JSON responses in Ktor routes/handlers. Use `buildJsonObject` with `JsonPrimitive` instead to avoid type erasure issues.

# Technology Stack Instructions

## Frontend

### ✅ Do
- use Typescript for logic, use plain JS for embedded scripts
- wherever possible, use Kotlin/JS for frontend
- use Vue for frontend
- keep components small
- keep diffs small and focused
- always split into CSS and HTML files, never inline CSS or HTML into code
- unless trivial, separate JS script from HTML template

### ❌ Don't
- don't hardcode colors
- don't use `<div>` if a component already exists
- don't bring in new heavy dependencies without approval

### Kotlin + Ktor Tech Stack
- Kotlin
- Ktor, both server and client
- kotlinx.serialization or Ktor JSON serialization
- JetBrains Exposed for SQL DSL; use the DSL, not the ORM
- for the JetBrains Exposed DSL, pay attention to the latest version of the library. Always check the current documentation at "https://jetbrains.github.io/Exposed/api/index.html" for the latest version.
- HOCON (Human-Optimized Config Object Notation) (com.typesafe.config) for configuration (see `application.conf`)
- Clikt for command line parsing and CLI applications

### Kotlin + Ktor Response Serialization
**❌ DON'T use `mapOf` for `call.respond()`** - Kotlin's type erasure causes issues when `mapOf` contains mixed types (e.g., `mapOf("found" to false, "error" to "string")`). The compiler erases the generic `Map<String, T>` to `Map<String, Any>`.

**✅ ALWAYS use `buildJsonObject` with explicit `JsonPrimitive` values:**
```kotlin
// Wrong - causes serialization issues
call.respond(HttpStatusCode.OK, mapOf("found" to false, "error" to "Entity not found"))

// Correct
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

call.respond(HttpStatusCode.OK, buildJsonObject {
    put("found", JsonPrimitive(false))
    put("error", JsonPrimitive("Entity not found"))
})
```

For nested arrays, use `JsonArray`:
```kotlin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

call.respond(buildJsonObject {
    put("items", JsonArray(someList.map { JsonPrimitive(it) }))
})
```


### Kotlin + Spring Boot Tech Stack
- Kotlin
- Spring Boot 4.0
- Jackson serialization
- Spring Data JDBC Templates for SQL access
- Spring Boot CLI for CLI applications
- Spring Cloud to communicate with Azure (and other clouds)
- Spring MVC for REST API
- Spring Security for authentication and authorization

### ✅ Kotlin: Do
- use Kotlin wherever possible for all backend logic
- use Kotlin coroutines (suspend fun) for async logic
- create class-level loggers and log often with DEBUG level
- when asked to serialize / deserialize data with multi-type fields, use sealed Interface and internal classes as specified below in the "Serialization Example" section
- use builders with fluent logic wherever appropriate
- use "companion object" factories for constructors and DSLs
- strictly prefer smaller classes and short methods
- detach logic from presentation / communication (e.g. always separate "handlers" from "routes")
- create both unit and component tests; for multiple components always test one component at a time, mocking (with Wiremock) the other ones
- for larger features use TDD: first design the tests, get them approved, then implement the feature
- use Iterable and iterators when possible, try to avoid specific implementations unless necessary
- use mutable collections when needed; try to avoid copying immutable ones
- comment the code extensively
- use JSON logging; we are using Grafana Alloy/OTEL for logs (see Telemetry section)
- use OAuth2 for authentication and authorization; we use Keycloak internally and the on-behalf-of flow
- use gRPC for internal communication
- use Kotest for testing, StringSpec variant
- use Wiremock for mocking external services in integration tests; use mockk for unit tests
- use ANTLR4 for parsing + grammars
- use Gradle 9 for build automation
- use slf4j for logging
- use Kover for code coverage
- use Flyway for database migrations
- use OpenTelemetry collection, Prometheus for metrics, Grafana Alloy (OTEL) for logs, Tempo for traces. Grafana Alloy as the collector
- use SQLite for dev databases; PostgreSQL for production; H2 for tests
- use OpenAPI for REST API documentation
- always annotate the request / response objects with `@JsonIgnoreUnknowKeys` (requires OptIn annotation)
- when working oin MCP servers, always use the predefined `McpJson` json config for Ktor configuration, and for "manual" seriallization and deserialization,

### ❌ Kotlin: Don't
- don't install dependencies without approval, apart from the pre-approved ones in the "Tech Stack" section
- don't create large files; multiple classes in a single file are fine, but keep the files below 300 lines, unless necessary
- don't use any ORM unless specifically instructed for a given project
- don't use `mapOf` for Ktor JSON responses; use `buildJsonObject` with `JsonPrimitive` instead
- don't use `mapOf` for calls (making requests); always try to use common objects; if not possible, use `buildJsonObject` with `JsonPrimitive` instead

### Python
- use uv for project and version management
- use Python 3.13+
- use FastAPI for REST API
- use SQLAlchemy for SQL access
- use Pydantic
- use pytest for unit tests
- use LangChain / LangGraph for AI agents development

## CI/CD
- use GitHub Actions for CI/CD
- prepare deployments for Kubernetes using helm charts
- prepare deployments for using with ArgoCD app-od-app approach
- use 'kustomize' with 'base' and 'overlays' directories for deployments
- run linting before committing (use pre-commit hook)
- run all tests before merging to main; block merging if any test fails
- build only changed components in CI

## Telemetry

### Overview
All services use **OpenTelemetry (OTEL)** for logging, tracing, and metrics with **Grafana Alloy** as the collector (push mode). The shared `otel-config` library (`shared/libs/kotlin/otel-config`) provides the `createOpenTelemetrySdk()` function for consistent setup.

### Components
- **Traces**: OTLP gRPC → Tempo
- **Metrics**: OTLP gRPC → Prometheus
- **Logs**: OTLP gRPC → Grafana Alloy (forwards to Loki-compatible storage)
- **Collector**: Grafana Alloy (receives OTLP, forwards to Tempo/Prometheus/Loki)

### Setting Up a New Service with OTEL

#### 1. Add Dependencies to `build.gradle.kts`
```kotlin
dependencies {
    // ... existing dependencies
    implementation(project(":shared:libs:kotlin:otel-config"))
    api(libs.otel.logback.appender)
    implementation(libs.otel.exporter.otlp)
}
```

#### 2. Add OTEL Appender to `logback.xml`
```xml
<appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
    <captureExperimentalAttributes>true</captureExperimentalAttributes>
    <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
    <captureLoggerContext>true</captureLoggerContext>
    <captureMdcAttributes>*</captureMdcAttributes>
</appender>

<root level="INFO">
    <appender-ref ref="CONSOLE"/> <!-- or JSON_CONSOLE -->
    <appender-ref ref="OTEL"/>
</root>
```

#### 3. Initialize SDK in `Application.kt`
```kotlin
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "my-service",
            protocol = System.getenv("MY_SERVICE_OTEL_PROTOCOL") ?: "grpc",
        ),
    )
    // ... rest of main
}
```

### Environment Variables

| Variable | Default | Description |
| :--- | :--- | :--- |
| `OTEL_EXPORTER_OTLP_HOST` | `localhost` | Alloy/collector host |
| `OTEL_EXPORTER_OTLP_GRPC_PORT` | `4317` | gRPC port for OTLP |
| `OTEL_EXPORTER_OTLP_HTTP_PORT` | `4318` | HTTP port for OTLP |
| `<SERVICE>_OTEL_PROTOCOL` | `grpc` | Protocol override per service |

### For Services Using Auto-Configured OpenTelemetry
If a service uses `AutoConfiguredOpenTelemetrySdk`, you **must** also call `OpenTelemetryAppender.install()` to enable log forwarding:
```kotlin
val openTelemetry = io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
    .builder()
    .build()
    .openTelemetrySdk

io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender.install(openTelemetry)
```

### Accessing Telemetry in K8s
- **Grafana**: `http://grafana.local` (port-forward with `just debug-tunnel`)
- **Tempo**: Traces at `http://tempo.local:3200`
- **Prometheus**: Metrics at `http://prometheus.local:9090`
- **Alloy**: Runs as DaemonSet, receives OTLP on port 4317 (gRPC) and 4318 (HTTP)

## MCP Server Communication

Rules for the agent ↔ MCP server channel (Python clients in `agents/*` ↔ Kotlin MCP servers in `tools/*`, streamable-HTTP / SSE transport). The cardinal sin is **no time budget and no exception boundary** — every layer must enforce its own.

### ✅ Do — Client side (Python)
- **Layer the timeouts**: `connect = 5s`, `session.initialize = 10s` (`asyncio.wait_for`), `load_mcp_tools = 15s`, `call_tool` default = `60s` with a **per-tool override map** (e.g. `fuzzy_match=15s`, `free_sql=180s`). Wrap every JSON-RPC exchange in `asyncio.wait_for` — a single global httpx timeout is not enough.
- Keep the SSE **read** timeout long (~300s) but operation-level timeouts short. Streamable-HTTP keeps the channel open by design; the budget belongs on each call, not on the transport.
- Run a periodic **health probe** on long-lived sessions (every ~30s, ping or `list_tools` with a short timeout). On failure, close the persistent session so the next call rebuilds it. Never trust an idle SSE stream to still be alive.
- Add a **circuit breaker**: after N consecutive connect failures, fail fast for a cooldown period instead of paying the retry latency on every request.
- **Parallelise startup** across multiple MCP servers with `asyncio.gather(...)` + per-server `wait_for`. A slow or down server must not block the others.
- Mirror the per-tool timeout map between client and server, so failures surface from whichever side hits the budget first.

### ✅ Do — Server side (Kotlin / Ktor)
- Wrap **every** tool callback in a safe wrapper that combines `withTimeout(...)` + `try/catch` for any `Throwable`, returning `CallToolResult(isError = true, content = listOf(TextContent(message)))`. An uncaught throw in the SDK handler kills the SSE stream silently and the client hangs forever.
- Install Ktor `StatusPages` in the shared MCP base as a backstop for any uncaught throw that escapes a handler.
- Set `connectionIdleTimeoutSeconds = 120` (or similar low value), **never 3600**. Zombie sessions should die in minutes, not hours.
- Always install `HttpTimeout` on every Ktor `HttpClient` used by a tool handler (`connectTimeoutMillis = 5_000`, `requestTimeoutMillis = 20–30_000`, `socketTimeoutMillis = 20–30_000`).
- For gRPC clients in tool handlers: use `stub.withDeadlineAfter(...)` on every call, and add channel keepalive (`keepAliveTime = 30s`, `keepAliveTimeout = 10s`, `keepAliveWithoutCalls = true`). TCP half-closes are otherwise undetected for minutes.
- Keep server **startup non-blocking**: catalog/index/schema loads belong in `CoroutineScope(Dispatchers.IO).launch { ... }` after `embeddedServer.start()`. The MCP endpoint must accept connections within ~2s.
- Expose `/health` (always 200 if the process is up) **and** `/ready` (reflects actual readiness — e.g. catalog loaded). Don't conflate them.
- Defensive arg parsing: a missing required field returns `CallToolResult(isError = true, "missing required argument: X")`, never `throw IllegalArgumentException`.
- For SSE/streamable-HTTP, expose `mcp-session-id` and `mcp-protocol-version` in CORS `exposeHeader` (already handled by `installMcpKtorBase`).
- **Log every tool callback return** with structured INFO (outcome summary with key params trimmed to 100 chars) and DEBUG (full `CallToolResult` object) levels. Example pattern:
  ```kotlin
  val result = CallToolResult(...)
  logger.info(
      "{tool_name} completed | {outcome} | keyParam={} | isError={}",
      keyValue?.take(100),
      result.isError,
  )
  logger.debug("{tool_name} {outcome} result: {}", result)
  return result
  ```
  This ensures agents receive consistent, observable responses via structured logs.

### ❌ Don't
- Don't rely on a single global httpx/transport timeout as your time budget. A 1-hour SSE read timeout means a hung tool call hangs the agent for 1 hour.
- Don't throw uncaught exceptions out of an MCP tool callback — they don't reliably serialise to JSON-RPC errors.
- Don't use `runBlocking { ... }` inside MCP tool callbacks — the callback signature is already `suspend`. `runBlocking` pins a Ktor worker thread and starves the engine under concurrent load.
- Don't use `println` or `e.printStackTrace()` for tool-handler errors — they bypass JSON/OTEL and won't show up in Loki. Use class-level SLF4J with structured fields (`toolName`, `durationMs`, `outcome`).
- Don't load slow downstream state synchronously during MCP server startup — clients (and K8s readiness probes) will time out.
- Don't catch `BaseException` in async Python MCP code — it swallows `CancelledError` / `KeyboardInterrupt` and breaks shutdown. Catch `Exception`.
- Don't keep a single persistent client session as the only connection without a health probe — a silent half-close means the next request waits the full read timeout before failing.

## General Instructions

### Operational Rules
1. **Ask before changing code not in the original workplan or tasklist.** If a task requires modifying or refactoring code that was not explicitly listed in the approved task list or workplan, pause and ask the user for approval first.
2. **Never simplify the solution to avoid tasks you cannot solve.** If you encounter a task you cannot complete with your current knowledge or that requires decisions outside the scope of the workplan, the correct behavior is to pause, report the issue clearly, and ask the user for help. Do not simplify the solution to "make it work."
3. **Always use `just` recipes for linting.** When linting, always use `just lint`, `just lint-kt`, `just lint-py`, or other appropriate `just` recipes. If you cannot resolve linting issues within two (2) passes, pause and ask the user for help — do not keep iterating indefinitely.

### Libraries
- use the versions defined in `gradle/libs.versions.toml` and locked versions for Python and TS/JS
- NEVER change a version without prior approval
- for new libraries or new use case, ALWAYS consult the latest documentation:
    - use the `find_doc` tool
    - try to find a code wiki entry for that library at https://codewiki.google/

### Planning
- unless explicitly asked to implement immediately, always prepare a detailed working plan in advance
- Always read through the instructions, either in the prompt or in a specific requirements file.
- If the requirements are written in Stages, always work only on one Stage at a time.
- Always prepare a detailed task list for the given Stage in a file `tasks-stage-xx.md` in the project root directory so that I can review the plan. Use checkboxes so that we can follow up the progress later on.
- While planning, do NOT implement anything yet, focus on analysis and planning. You can use a specific section Open Questiions to ask questions I need to answer before the implementation starts. Please, ask also to confirm any assumptions and defaults you have made in the planning. You will help me a lot with explicitly asking questions, as my requirements might be unclear or incomplete.
- While planning, do NOT touch any code or files. Do NOT refactor any files or pieces of code that seem unused at this time, we will need them later on. Prepare the task list and get back to me for review before changing anything.

### Implementation
- do ONLY what asked for
- don't refactor code outside what you have been asked for
- don't delete any "unused" code without approval
- NEVER merge anything
- if a detailed task list is available, mark the checkboxes as you go

### Safety and permissions
Allowed: read/list files, lint/test single files, git push to a new branch, PR creation
Ask first: installs, deletes, full builds

### PR checklist
- format and type check pass
- unit tests green
- diff small with a short summary

### When stuck or working for a long time
- take a break and let me review the progress
- ask a question
- break, summarize the progress and propose a plan going forward
- store the progress in the project root directory as `progress-stage-xx.md` and the current plan in `fwd-stage-xx.md`




## Examples

### Serialization Example
This is the example of a multi-type field in a data class.
Please, note that the required behavior is to accept the primitives or arrays WITHOUT specifying the "value" or "values" field.
Preferred implementation would be to use the inner classes in the interface, like this:
```kotlin
@Serializable
sealed interface MetadataValue {
    @Serializable
    data class MetadataSingle(val value: String) : MetadataValue
    @Serializable
    data class MetadataList(val values: List<String>) : MetadataValue
}
```

This is the example interface, classes and custom serializer:
```kotlin
@Serializable
sealed interface MetadataValue

@Serializable
data class MetadataSingle(val value: String) : MetadataValue

@Serializable
data class MetadataList(val values: List<String>) : MetadataValue

/**
 * Parse a JSON string representing metadata into a Map<String, MetadataValue>.
 * Values can be a string or an array of strings. Other types will be stringified.
 */
fun parseMetadataJson(jsonText: String): Map<String, MetadataValue> {
    val root = Json.parseToJsonElement(jsonText)
    if (root !is kotlinx.serialization.json.JsonObject) return emptyMap()
    val out = mutableMapOf<String, MetadataValue>()
    for ((k, v) in root) {
        out[k] = when (v) {
            is JsonPrimitive -> MetadataSingle(v.content)
            is JsonArray -> MetadataList(v.mapNotNull { (it as? JsonPrimitive)?.content })
            else -> MetadataSingle(v.toString())
        }
    }
    return out
}

/**
 * Custom serializer for a single MetadataValue that supports the nested forms:
 * {"value":"A"} and {"values":["B","C"]}.
 * Also leniently accepts primitives and arrays.
 */
object MetadataValueSerializer : KSerializer<MetadataValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MetadataValue")

    override fun deserialize(decoder: Decoder): MetadataValue {
        val jd = decoder as? JsonDecoder ?: error("MetadataValueSerializer requires Json")
        val elem = jd.decodeJsonElement()
        return when (elem) {
            is JsonObject -> {
                val v = elem["value"]
                val vs = elem["values"]
                when {
                    v is JsonPrimitive -> MetadataSingle(v.content)
                    vs is JsonArray -> MetadataList(vs.mapNotNull { (it as? JsonPrimitive)?.content })
                    // Fallbacks
                    elem.size == 1 && elem.values.firstOrNull() is JsonPrimitive ->
                        MetadataSingle((elem.values.first() as JsonPrimitive).content)
                    elem.size == 1 && elem.values.firstOrNull() is JsonArray ->
                        MetadataList(((elem.values.first() as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }))
                    else -> MetadataSingle(elem.toString())
                }
            }
            is JsonPrimitive -> MetadataSingle(elem.content)
            is JsonArray -> MetadataList(elem.mapNotNull { (it as? JsonPrimitive)?.content })
            else -> MetadataSingle(elem.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: MetadataValue) {
        val je = encoder as? JsonEncoder ?: error("MetadataValueSerializer requires Json")
        val obj = when (value) {
            is MetadataSingle -> buildJsonObject { put("value", JsonPrimitive(value.value)) }
            is MetadataList -> buildJsonObject {
                put("values", JsonArray(value.values.map { JsonPrimitive(it) }))
            }
        }
        je.encodeJsonElement(obj)
    }
}
```
