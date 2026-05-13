pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "hebe"

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
    ":modules:channels:channel-manager",
    ":modules:channels:cli",
    ":modules:channels:web",
    ":modules:channels:telegram",
    ":modules:mcp-server",
    ":modules:gateway",
    ":modules:scheduler",
    ":modules:detekt-rules",
    ":modules:cli-app",
)
