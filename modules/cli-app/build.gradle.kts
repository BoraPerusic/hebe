plugins {
    id("hebe.application")
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("com.hebe.cli.MainKt")
    applicationName = "hebe"
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
    implementation(libs.ktor.server.core)
}
