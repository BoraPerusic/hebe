plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    api(project(":modules:config"))
    api(project(":modules:memory"))
    api(project(":modules:security"))
    api(project(":modules:mcp-server"))
    implementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}
