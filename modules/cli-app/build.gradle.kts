plugins {
    id("talos.application")
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("com.talos.cli.MainKt")
    applicationName = "talos"
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":modules:config"))
    implementation(project(":modules:observability"))
}
