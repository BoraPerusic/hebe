plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:observability"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.junit.platform.runner)
    testRuntimeOnly(libs.junit.platform.launcher)
}
