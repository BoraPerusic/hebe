plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:observability"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
