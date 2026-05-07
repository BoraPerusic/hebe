pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "talos"

include(
    ":modules:api",
    ":modules:plugin-api",
    ":modules:observability",
    ":modules:config",
    ":modules:memory",
    ":modules:security",
    ":modules:providers:openai-compat",
    ":modules:tools:dispatch",
    ":modules:tools:builtin",
    ":modules:tools:mcp-client",
    ":modules:core",
    ":modules:plugins",
    ":modules:channels:api",
    ":modules:channels:cli",
    ":modules:channels:web",
    ":modules:channels:telegram",
    ":modules:mcp-server",
    ":modules:gateway",
    ":modules:scheduler",
    ":modules:detekt-rules",
    ":modules:cli-app",
)
