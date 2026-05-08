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
    implementation(project(":modules:config"))
    implementation(project(":modules:observability"))
}
