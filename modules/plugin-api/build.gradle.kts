plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":modules:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.pf4j)
    api(libs.kotlinx.serialization.json)
}
