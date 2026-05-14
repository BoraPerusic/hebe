# Tasks — Review 012 (M6 Plugin System Fixes)

Work through these tasks in order. Each task tells you exactly which file to edit, what to change, and what the result must be. Do not skip tasks or reorder them — later tasks depend on earlier ones.

---

## T1 — Fix `AbiChecker.isCaretMatch()` so it works for `"0.1.x"`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/abi/AbiChecker.kt`

**Problem:** `isCaretMatch()` uses `prefix.endsWith(".")` where `prefix = manifestVersion.substringBeforeLast(".")`. For input `"0.1.x"`, `prefix` is `"0.1"` which does NOT end with `"."`, so the method always returns `false`.

**What to do:**

Replace the entire `isCaretMatch()` method with logic that:
1. Strips the `x`/wildcard suffix: split `manifestVersion` on `"."`, drop the last component if it is `"x"` or `"*"`, rejoin with `"."`. The result is the prefix like `"0.1"`.
2. Check that `hostVersion` starts with that prefix followed by `"."` OR equals that prefix exactly.
3. Also verify the major version matches (for `"0.x"` style, only major must match; for `"0.1.x"` style, major AND minor must match).

Concrete correct implementation for the caret/wildcard semantics used in this project (`"0.1.x"` means `"starts with 0.1."`):

```kotlin
private fun isCaretMatch(manifestVersion: String, hostVersion: String): Boolean {
    val parts = manifestVersion.split(".")
    val prefix = parts
        .takeWhile { it != "x" && it != "*" }
        .joinToString(".")
    if (prefix.isEmpty()) return true
    return hostVersion == prefix || hostVersion.startsWith("$prefix.")
}
```

**Verification:** Add or update the `AbiChecker` unit tests in T12 to cover this case.

---

## T2 — Fix `Lifecycle.stopPlugin()` broken method reference

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt`

**Problem:** `stopPlugin()` calls `HebePluginManager.stopPlugin(id)` as if it were a static call. `HebePluginManager` has no companion object with this method. This will either fail to compile or throw at runtime.

**What to do:**

`Lifecycle` must hold a reference to the `DefaultPluginManager` instance (or `HebePluginManager` instance). If it does not already have one, add a constructor parameter:

```kotlin
class Lifecycle(
    private val pluginManager: org.pf4j.PluginManager,
    private val store: PluginRegistrationStore,
)
```

Then in `stopPlugin(id: String)`:

```kotlin
fun stopPlugin(id: String) {
    store.unregister(id)
    pluginManager.stopPlugin(id)
}
```

Do not use reflection. Do not call a static method. Call the instance method on the injected `pluginManager`.

Update all call sites of `Lifecycle(...)` to pass the `pluginManager` instance.

---

## T3 — Wire `HostClassLoader` into `HebePluginManager.createPluginClassLoader()`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/HebePluginManager.kt`

**Problem:** `HebePluginManager` does not override `createPluginClassLoader()`. PF4J defaults to unrestricted parent-first loading. `HostClassLoader` is defined but never used.

**What to do:**

Override `createPluginClassLoader()` in `HebePluginManager`:

```kotlin
override fun createPluginClassLoader(pluginPath: Path, pluginDescriptor: PluginDescriptor): PluginClassLoader {
    return HostClassLoader.getInstance(pluginPath.toUri().toURL(), this::class.java.classLoader)
}
```

Also fix `HostClassLoader.findHebrewApiJars()` → rename to `findHebeApiJars()` everywhere it appears (method definition and all call sites within the file).

**Verification:** After this change, a plugin that tries to access a host class outside `com.hebe.api.*` or `com.hebe.plugin.api.*` must receive `ClassNotFoundException`. Add a test for this in T12.

---

## T4 — Replace `TestPluginHost` with `RealPluginHost` in `PluginManager.tools()`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginManager.kt`

**Problem:** `tools()` instantiates an inner `TestPluginHost` which has no capability gates, no allowlist enforcement, and no permission checks. This is the production code path.

**What to do:**

Remove the `TestPluginHost` inner class entirely.

In `tools()`, for each loaded plugin, construct a `RealPluginHost` using the plugin's manifest (loaded from `plugin.properties` and `plugin.toml`):

```kotlin
val manifest = loadManifestForPlugin(pluginWrapper)   // read plugin.toml from plugin dir
val host = RealPluginHost(manifest, secretStore, ssrfGuard)
val plugin = pluginWrapper.plugin as HebePlugin
val rawTools = plugin.tools(host)
rawTools.map { NamespacedTool(pluginWrapper.pluginId, it) }
```

You must implement `loadManifestForPlugin(wrapper: PluginWrapper): PluginManifest` — read `plugin.toml` from `wrapper.pluginPath` using `ManifestParser.parse()`.

If `ManifestParser` returns `ManifestResult.Error`, log a warning and skip the plugin (do not crash).

---

## T5 — Register `PluginStateListener` in `HebePluginManager` to wire `Lifecycle`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/HebePluginManager.kt`

**Problem:** `Lifecycle.afterStart()` and `beforeStop()` are defined but never called. PF4J lifecycle events are never dispatched to them.

**What to do:**

In `HebePluginManager`, accept a `Lifecycle` in the constructor (or as a setter). After initializing the manager, register a `PluginStateListener`:

```kotlin
addPluginStateListener(object : PluginStateListener {
    override fun pluginStateChanged(event: PluginStateEvent) {
        when (event.pluginState) {
            PluginState.STARTED -> lifecycle.afterStart(event.plugin.pluginId)
            PluginState.STOPPED -> lifecycle.beforeStop(event.plugin.pluginId)
            else -> {}
        }
    }
})
```

`addPluginStateListener()` is a method on `DefaultPluginManager` — call it directly, no reflection needed.

---

## T6 — Fix `NamespacedToolWrapper.invoke()` — do not filter/drop args

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt`

**Problem:** `invoke()` filters the args map to only keys that appear in the tool's declared `parameters`. This silently drops keys the tool didn't declare, changing behavior.

**What to do:**

Remove the args filtering. Pass the args map through unchanged:

```kotlin
override suspend fun invoke(args: Map<String, Any?>): ToolResult {
    return delegate.invoke(args)
}
```

---

## T7 — Fix plugin name/identity in `InstallFlow` and `SideloadFlow`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/install/InstallFlow.kt`

**Problem:** Both `InstallFlow.install()` and `SideloadFlow.sideload()` use:
```kotlin
val name = manifest.hebeApiVersion  // WRONG: this is "0.1.x", not the plugin ID
```

The plugin ID comes from `plugin.properties` inside the archive, under the key `plugin.id`.

**What to do:**

Add a private method to both classes (or extract to a shared utility — see T8):

```kotlin
private fun readPluginId(archivePath: Path): String {
    ZipFile(archivePath.toFile()).use { zip ->
        val entry = zip.getEntry("plugin.properties")
            ?: throw IllegalStateException("plugin.properties not found in archive")
        val props = java.util.Properties()
        props.load(zip.getInputStream(entry))
        return props.getProperty("plugin.id")
            ?: throw IllegalStateException("plugin.id missing from plugin.properties")
    }
}
```

Then replace:
```kotlin
val name = manifest.hebeApiVersion
val version = extractVersion(manifest.hebeApiVersion)
```
with:
```kotlin
val name = readPluginId(archivePath)   // in InstallFlow, use pulled.archivePath
val version = extractVersion(manifest.hebeApiVersion)
```

The extract directory becomes `pluginsDir.resolve("$name-$version")` — same as before but now `name` is the actual plugin ID like `"hello"` not `"0.1.x"`.

Apply this fix identically in both `InstallFlow.install()` and `SideloadFlow.sideload()`.

---

## T8 — Eliminate copy-paste between `InstallFlow` and `SideloadFlow`

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/install/InstallFlow.kt`

**Problem:** `extractAndParseManifest()`, `extractVersion()`, `extractArchive()`, `computeHash()`, and the new `readPluginId()` from T7 are identically copy-pasted in both `InstallFlow` and `SideloadFlow`.

**What to do:**

Extract these five methods into a top-level internal object (or a file-level set of `internal fun` functions) in the same file, e.g.:

```kotlin
internal object ArchiveUtils {
    fun computeHash(path: Path): ByteArray { ... }
    fun extractAndParseManifest(archivePath: Path): ManifestResult<PluginManifest> { ... }
    fun extractVersion(apiVersion: String): String { ... }
    fun extractArchive(archivePath: Path, destDir: Path, log: Logger) { ... }
    fun readPluginId(archivePath: Path): String { ... }
}
```

Both `InstallFlow` and `SideloadFlow` call `ArchiveUtils.*` instead of having their own copies.

---

## T9 — Persist install records to settings after successful install

**Files:**  
- `modules/plugins/src/main/kotlin/com/hebe/plugins/install/InstallFlow.kt`  
- `modules/plugins/src/main/kotlin/com/hebe/plugins/install/SideloadFlow.kt`

**Problem:** Plan M6.T9 requires `settings.plugins.installed` to be updated with each installed plugin's ID, version, and path. Neither flow writes anything to settings.

**What to do:**

Add a `settingsStore: com.hebe.config.SettingsStore` parameter to both `InstallFlow` and `SideloadFlow` constructors.

After successful extraction (before returning `InstallResult.Ok`), write the record:

```kotlin
settingsStore.appendToList(
    "plugins.installed",
    mapOf("id" to name, "version" to version, "path" to extractDir.toString())
)
```

Use whatever `SettingsStore` API already exists for appending to a list. If only `set` exists, read the current list, append, and write back.

Update all call sites (`AppComponents.runInstall()`, `PluginInstallCommand`) to pass the `settingsStore`.

---

## T10 — Fix `OciClient.loadDockerConfigAuth()` to use Basic auth, not Bearer

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/oci/OciClient.kt`

**Problem:** Docker's `config.json` `auth` field is `base64(username:password)`. Current code decodes it to `"user:pass"` and uses it as a Bearer token value. Registries expect either:
- `Authorization: Basic <base64>` (the raw base64 directly), or  
- Username/password extracted and used in a token request.

**What to do:**

`loadDockerConfigAuth()` should return the raw base64 string (without decoding), and callers should use `Basic` auth:

```kotlin
private fun loadDockerConfigAuth(registryHost: String): String? {
    return try {
        val dockerConfig = Path.of(System.getProperty("user.home"), ".docker", "config.json")
        if (!Files.exists(dockerConfig)) return null
        val content = Files.readString(dockerConfig)
        val json = content.parseJson().jsonObject
        val auths = json["auths"]?.jsonObject ?: return null
        val registryAuth = auths[registryHost]?.jsonObject ?: return null
        registryAuth["auth"]?.jsonPrimitive?.content  // raw base64, NOT decoded
    } catch (e: Exception) {
        log.debug("Failed to load docker config auth: {}", e.message)
        null
    }
}
```

In `fetchManifest()` and `fetchLayer()`, change the auth header from:
```kotlin
append(HttpHeaders.Authorization, "Bearer $authToken")
```
to:
```kotlin
// Use Basic for docker config auth, Bearer only for ACR tokens
append(HttpHeaders.Authorization, if (isAcrToken) "Bearer $authToken" else "Basic $authToken")
```

The cleanest approach: add an `AuthToken` sealed class (`Bearer(val token: String)`, `Basic(val token: String)`) and have `resolveAuthToken()` return `AuthToken?`. Apply the correct prefix in the header.

---

## T11 — Fix `GatedHttpClientImpl`: shared `HttpClient`, add SSRF guard

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/host/GatedHttpClientImpl.kt`

**Two problems to fix:**

**Problem A — New `HttpClient` per call:**

The class creates `HttpClient(CIO) { ... }` inside each method (`doGet`, `doPost`, etc.), then never closes it. This leaks thread pools.

Fix: Create one `HttpClient` instance as a class-level property and close it in a `close()` method or via `AutoCloseable`:

```kotlin
class GatedHttpClientImpl(
    private val allowlistDomains: List<String>,
    private val ssrfGuard: SsrfGuard,
) : GatedHttpClient, AutoCloseable {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
        }
    }

    override fun close() = httpClient.close()
    ...
}
```

**Problem B — No SSRF guard:**

Before making any outbound request, call the `SsrfGuard` (already in `modules/security`, M3.T4). Add `ssrfGuard: SsrfGuard` to the constructor. In the URL validation block (before the allowlist check), call:

```kotlin
ssrfGuard.validate(url)  // throws or returns an error result if private/loopback/metadata IP
```

If `ssrfGuard.validate()` returns a failure, throw `SecurityException("SSRF guard blocked: $url")` or return the appropriate error — do not proceed with the request.

---

## T12 — Fix `ManifestParser` capability parsing to be case-insensitive

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/manifest/ManifestParser.kt`

**Problem:** `Capability.valueOf(item)` is case-sensitive. TOML values like `"tool"` cause `IllegalArgumentException` because the enum value is `Tool` (PascalCase).

**What to do:**

Replace `Capability.valueOf(item)` with:

```kotlin
Capability.entries.firstOrNull { it.name.equals(item, ignoreCase = true) }
    ?: return ManifestResult.Error(listOf(ManifestError(
        message = "Unknown capability: $item",
        source = path.toString(),
    )))
```

Apply this same pattern for `Permission` parsing if it also uses `valueOf()` anywhere in the file.

---

## T13 — Make `PluginRegistrationStore` thread-safe

**File:** `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginRegistration.kt`

**Problem:** Uses `mutableMapOf()` (a `LinkedHashMap`). Concurrent plugin lifecycle events can corrupt it.

**What to do:**

Replace `mutableMapOf()` with `ConcurrentHashMap()`:

```kotlin
private val store = ConcurrentHashMap<String, List<NamespacedTool>>()
```

Also use `store.compute()` or `store.merge()` for atomic register/unregister if multiple tools per plugin can be added concurrently.

---

## T14 — Fix `plugin list` command to output a formatted table

**File:** `modules/cli-app/src/main/kotlin/com/hebe/cli/Main.kt` — `PluginListCommand`

**Problem:** Current implementation lists directory names only. Plan requires: `ID | VERSION | STATUS | CAPABILITIES | PERMISSIONS`.

**What to do:**

Read the installed plugins from `settings.plugins.installed` (the list written in T9). For each entry, read the plugin's `plugin.toml` to get capabilities and permissions (use `ManifestParser`). Get the plugin status from `pluginManager.getPlugin(id)?.pluginState?.name ?: "NOT_LOADED"`.

Print as a table. Minimum required columns: `ID`, `VERSION`, `STATUS`, `CAPABILITIES`. Example output:
```
ID        VERSION  STATUS   CAPABILITIES
hello     1.0.0    STARTED  tool
my-plugin 2.1.0    STOPPED  tool,http
```

Use left-aligned fixed-width columns (pad with spaces). You do not need a third-party table library — `String.padEnd(n)` is sufficient.

---

## T15 — Fix `PluginRemoveCommand` to update settings

**File:** `modules/cli-app/src/main/kotlin/com/hebe/cli/Main.kt` — `PluginRemoveCommand`

**Problem:** Deletes the plugin directory but does not remove the entry from `settings.plugins.installed`.

**What to do:**

After deleting the directory:
1. Read `settings.plugins.installed` list.
2. Remove the entry where `id == <the removed plugin id>`.
3. Write the updated list back to settings.
4. Call `pluginManager.deletePlugin(id)` (PF4J method) to stop and unload the plugin if it is currently loaded.

---

## T16 — Fix `PluginInstallCommand` to load real `HebeConfig`

**File:** `modules/cli-app/src/main/kotlin/com/hebe/cli/Main.kt` — `PluginInstallCommand`

**Problem:** Uses `HebeConfig()` with all defaults (no config file loaded). The signature mode, registry, and publisher keys are all wrong.

**What to do:**

`PluginInstallCommand` must receive the real `HebeConfig` from the parent command (the same way other commands receive it). Pass it via constructor/context injection — follow the existing pattern in `Main.kt` for how other subcommands access config.

Do not construct `HebeConfig()` directly inside the command.

---

## T17 — Rename `plugin-api/TalosPlugin.kt` → `HebePlugin.kt`

**File:** `modules/plugin-api/src/main/kotlin/com/hebe/plugin/api/TalosPlugin.kt`

**Problem:** File contains class `HebePlugin` but is named `TalosPlugin.kt`. Kotlin convention requires the filename to match the top-level class name.

**What to do:**

Rename the file:
1. Move/rename `TalosPlugin.kt` to `HebePlugin.kt` in the same directory.
2. Do not change the class name — `HebePlugin` is correct.
3. Update any import or reference to `TalosPlugin` that may exist elsewhere in the codebase (run a project-wide grep for `TalosPlugin`).

---

## T18 — Remove undocumented extension points from `HebePlugin`

**File:** `modules/plugin-api/src/main/kotlin/com/hebe/plugin/api/HebePlugin.kt` (after T17 rename)

**Problem:** `HebePlugin` declares `channels()`, `memoryStores()`, and `observers()` methods that are not in the M6 plan, have no wiring in `PluginManager`, and no tests.

**What to do:**

Remove these three methods from `HebePlugin`. The only methods `HebePlugin` should expose are the ones in the M6 plan: `tools(host: PluginHost): List<Tool>` and `init(host: PluginHost)`.

If removing them causes compile errors elsewhere, fix those call sites (they should not be called anywhere in production code if they are not wired — remove those call sites too).

---

## T19 — Add `plugin-template/README.md`

**File:** `plugin-template/README.md` (new file)

**Problem:** Plan M6.T13 requires a README explaining the plugin author workflow.

**What to do:**

Create `plugin-template/README.md` with the following sections:

1. **Prerequisites** — JDK 21, Gradle, an OCI registry to publish to.
2. **Writing a plugin** — Structure of `plugin.toml` (each field explained), structure of `plugin.properties`, how to implement `HebePlugin`, how to implement a `Tool`, how to use the `PluginHost` for HTTP/env/secret access.
3. **Building the plugin JAR** — `./gradlew jar`, where the output goes.
4. **Publishing to an OCI registry** — `./gradlew publishPlugin -Pregistry=<host> -Pref=<image>:<tag>`, what the task does (packages JAR + `plugin.toml` into a ZIP, signs it, pushes as OCI artifact).
5. **Sideloading for local dev** — `hebe plugin sideload ./build/libs/hello-plugin.jar --unsigned`.

---

## T20 — Add `plugin-template/buildSrc/oras-publish.gradle.kts`

**File:** `plugin-template/buildSrc/oras-publish.gradle.kts` (new file, and `plugin-template/buildSrc/build.gradle.kts` if not present)

**Problem:** Plan M6.T13 requires a Gradle task that signs and publishes the plugin JAR as an OCI artifact.

**What to do:**

Create `plugin-template/buildSrc/` directory with a `build.gradle.kts` that applies the Kotlin DSL plugin. Then create `oras-publish.gradle.kts` that defines a `publishPlugin` task:

```kotlin
tasks.register("publishPlugin") {
    group = "publishing"
    description = "Package, sign, and push plugin to an OCI registry"
    dependsOn(tasks.jar)

    doLast {
        val registry = project.property("registry") as String
        val ref = project.property("ref") as String
        val jarFile = tasks.jar.get().archiveFile.get().asFile

        // 1. Create a ZIP containing the JAR and plugin.toml
        val zipFile = File(buildDir, "plugin-bundle.zip")
        ant.withGroovyBuilder {
            "zip"("destfile" to zipFile) {
                "fileset"("file" to jarFile)
                "fileset"("file" to file("plugin.toml"))
            }
        }

        // 2. Sign the ZIP using ED25519 key from HEBE_PLUGIN_SIGNING_KEY env var
        //    (output: plugin-bundle.zip.sig)

        // 3. Push to OCI registry using ORAS CLI or oras-java-sdk
        //    Layer 1: plugin-bundle.zip (media type: application/vnd.hebe.plugin.bundle.v1)
        //    Layer 2: plugin-bundle.zip.sig (media type: application/vnd.hebe.plugin.signature.v1)

        println("Published $ref")
    }
}
```

The actual signing and ORAS push logic should use whatever tooling is already available (oras CLI via `exec {}`, or the oras-java-sdk dependency). Document the expected environment variables in a comment at the top of the file: `HEBE_PLUGIN_SIGNING_KEY`, `HEBE_REGISTRY_TOKEN` (optional, falls back to docker config).

---

## T21 — Add unit tests for all new plugin components

**Directory:** `modules/plugins/src/test/kotlin/com/hebe/plugins/`

**Problem:** Near-zero test coverage for all M6 components.

Create the following test files. Use Kotest `StringSpec`. Use `mockk` for dependencies. Do NOT use `TestPluginHost` — test `RealPluginHost` directly.

### T21a — `ManifestParserTest.kt`

Test cases (all must pass):
- `happy path` — valid TOML with all required fields parses to `ManifestResult.Ok`
- `missing hebe_api_version` — returns `ManifestResult.Error` with a message mentioning the field
- `lowercase capabilities` — `capabilities = ["tool"]` parses correctly (uses `Tool` enum value)
- `unknown capability` — returns `ManifestResult.Error`
- `missing plugin.toml file` — returns `ManifestResult.Error` (file not found)

### T21b — `AbiCheckerTest.kt`

Test cases (all must pass):
- `exact match` — manifest `"0.1.0"`, host `"0.1.0"` → `AbiResult.Compatible`
- `caret match "0.1.x"` — manifest `"0.1.x"`, host `"0.1.3"` → `AbiResult.Compatible`
- `caret mismatch minor` — manifest `"0.1.x"`, host `"0.2.0"` → `AbiResult.Incompatible`
- `caret mismatch major` — manifest `"0.1.x"`, host `"1.0.0"` → `AbiResult.Incompatible`
- `range match ">=0.1.0 <0.2.0"` — host `"0.1.5"` → `AbiResult.Compatible`
- `range miss` — manifest `">=0.1.0 <0.2.0"`, host `"0.2.0"` → `AbiResult.Incompatible`
- `host version not matching` — manifest `"0.1.0"`, host `"0.1.1"` → `AbiResult.Incompatible` (exact match)

### T21c — `SignatureVerifierTest.kt`

Test cases (all must pass):
- `DISABLED mode` — any manifest → `SignatureResult.Verified` (or `Unsigned` with a note, per plan) without any key check
- `OPTIONAL mode, no signature` → `SignatureResult.Unsigned`
- `OPTIONAL mode, valid signature` → `SignatureResult.Verified`
- `REQUIRED mode, no signature` → `SignatureResult.BadSignature`
- `REQUIRED mode, bad signature` → `SignatureResult.BadSignature`
- `REQUIRED mode, valid signature, known key` → `SignatureResult.Verified`
- `REQUIRED mode, valid signature, unknown key` → `SignatureResult.BadSignature`

Use a test Ed25519 keypair generated in the test (Bouncy Castle). Do not use real keys.

### T21d — `RealPluginHostTest.kt`

Test cases (all must pass):
- `http() allowed domain` — `allowlistDomains = ["api.example.com"]`, request to `https://api.example.com/foo` succeeds (mock `GatedHttpClientImpl`)
- `http() blocked domain` — request to `https://evil.com/foo` throws `SecurityException`
- `env() allowed key` — key not in denylist → returns value
- `env() denied key` — key matching denylist pattern (e.g. `AWS_SECRET_ACCESS_KEY`) → throws `SecurityException`
- `secret() with Permission.Secret("db_password")` declared → returns value from secret store
- `secret() without permission declared` → throws `SecurityException`

### T21e — `LifecycleTest.kt`

Test cases:
- `startPlugin registers namespaced tools` — after `startPlugin("hello")`, `store.toolsForPlugin("hello")` returns tools named `"hello:say_hello"` etc.
- `stopPlugin unregisters tools` — after `stopPlugin("hello")`, `store.toolsForPlugin("hello")` is empty
- `invoke() passes args unchanged` — calling the namespaced tool with `{"name": "world"}` passes exactly `{"name": "world"}` to the delegate tool

---

## T22 — Fix spike test to check namespaced tool name

**File:** `modules/plugins/src/test/kotlin/com/hebe/plugins/PluginSpikeTest.kt`

**Problem:** Test asserts `"say_hello"` but the plan requires `"hello:say_hello"`.

**What to do:**

Find the assertion that checks the tool name and update it:

```kotlin
// OLD:
tools.any { it.name == "say_hello" }
// NEW:
tools.any { it.name == "hello:say_hello" }
```

This test will only pass after T4 (using `RealPluginHost` + `NamespacedTool` in `PluginManager.tools()`).

---

## T23 — Remove unused `oras-sdk` dependency

**File:** `modules/plugins/build.gradle.kts`

**Problem:** `oras-sdk` is declared as a dependency but `OciClient` uses manual Ktor HTTP calls exclusively. The SDK is not imported anywhere.

**What to do:**

Remove the `oras-sdk` dependency line from `modules/plugins/build.gradle.kts`. Run `./gradlew :modules:plugins:build` to confirm nothing breaks.

If T20 (`publishPlugin` Gradle task) ends up using the ORAS SDK, add it back there — but only in `plugin-template/buildSrc/build.gradle.kts`, not in the main `modules/plugins` module.

---

## Completion Checklist

Before marking M6 as done, verify all of the following:

- [ ] T1: `AbiCheckerTest` caret match `"0.1.x"` passes
- [ ] T2: `stopPlugin()` compiles and calls instance method without reflection
- [ ] T3: `HebePluginManager.createPluginClassLoader()` is overridden and uses `HostClassLoader`
- [ ] T3: `findHebrewApiJars` renamed to `findHebeApiJars`
- [ ] T4: `TestPluginHost` inner class deleted from `PluginManager.kt`
- [ ] T4: `PluginManager.tools()` uses `RealPluginHost`
- [ ] T5: `PluginStateListener` registered in `HebePluginManager`
- [ ] T6: `invoke()` does not filter args
- [ ] T7: `InstallFlow` and `SideloadFlow` use `plugin.id` for plugin name (not `hebeApiVersion`)
- [ ] T8: Shared `ArchiveUtils` used by both flows (no duplicate methods)
- [ ] T9: Successful install writes to `settings.plugins.installed`
- [ ] T10: `loadDockerConfigAuth()` uses Basic auth (raw base64), not Bearer decoded string
- [ ] T11: `GatedHttpClientImpl` has one shared `HttpClient` instance
- [ ] T11: SSRF guard called before every outbound request
- [ ] T12: `ManifestParser` capability parsing is case-insensitive
- [ ] T13: `PluginRegistrationStore` uses `ConcurrentHashMap`
- [ ] T14: `plugin list` outputs ID, VERSION, STATUS, CAPABILITIES columns
- [ ] T15: `plugin remove` updates `settings.plugins.installed`
- [ ] T16: `PluginInstallCommand` uses real `HebeConfig` from parent command
- [ ] T17: `TalosPlugin.kt` renamed to `HebePlugin.kt`; no references to `TalosPlugin` remain
- [ ] T18: `channels()`, `memoryStores()`, `observers()` removed from `HebePlugin`
- [ ] T19: `plugin-template/README.md` exists with all 5 sections
- [ ] T20: `plugin-template/buildSrc/oras-publish.gradle.kts` exists with `publishPlugin` task
- [ ] T21a: `ManifestParserTest` — all 5 cases pass
- [ ] T21b: `AbiCheckerTest` — all 7 cases pass
- [ ] T21c: `SignatureVerifierTest` — all 7 cases pass
- [ ] T21d: `RealPluginHostTest` — all 6 cases pass
- [ ] T21e: `LifecycleTest` — all 3 cases pass
- [ ] T22: Spike test checks `"hello:say_hello"` and passes
- [ ] T23: `oras-sdk` dependency removed from `modules/plugins/build.gradle.kts`
- [ ] `./gradlew build` passes with no errors
- [ ] `./gradlew test` passes with no failures
