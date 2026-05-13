plugins {
    id("hebe.library")
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:channels:channel-manager"))
    implementation(project(":modules:core"))
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client.jetty)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
