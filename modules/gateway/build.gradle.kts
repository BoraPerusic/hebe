plugins {
    id("hebe.library")
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:config"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:security"))
    implementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}
