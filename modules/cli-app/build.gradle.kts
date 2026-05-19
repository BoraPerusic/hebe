plugins {
    id("hebe.application")
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("com.hebe.cli.MainKt")
    applicationName = "hebe"
}

tasks.shadowJar {
    archiveBaseName.set("hebe")
    archiveClassifier.set("")
    archiveVersion.set(project.version.takeUnless { it.toString() == "unspecified" }?.toString() ?: "")
    manifest {
        attributes["Main-Class"] = "com.hebe.cli.MainKt"
    }
    mergeServiceFiles()
    isZip64 = true
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":modules:api"))
    implementation(project(":modules:config"))
    implementation(project(":modules:gateway"))
    implementation(project(":modules:channels:channel-manager"))
    implementation(project(":modules:channels:cli"))
    implementation(project(":modules:channels:web"))
    implementation(project(":modules:channels:telegram"))
    implementation(project(":modules:observability"))
    implementation(project(":modules:security"))
    implementation(project(":modules:tools:dispatch"))
    implementation(project(":modules:plugins"))
    implementation(project(":modules:mcp-server"))
    implementation(project(":modules:core"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:providers:openai-compat"))
    implementation(project(":modules:tools:builtin"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:scheduler"))
    implementation(project(":modules:tools:mcp-client"))
    implementation(project(":modules:providers:openai-compat"))
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client.jetty)
    implementation(libs.ktor.server.core)
}
