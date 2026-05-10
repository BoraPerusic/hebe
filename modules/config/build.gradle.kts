plugins {
    id("hebe.library")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
}

dependencies {
    api(project(":modules:api"))
    api(libs.tomlj)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
}
