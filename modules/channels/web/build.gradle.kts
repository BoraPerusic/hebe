plugins {
    id("hebe.library")
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:channels:channel-manager"))
    implementation(project(":modules:core"))
    implementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
