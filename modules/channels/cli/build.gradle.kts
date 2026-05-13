plugins {
    id("hebe.library")
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:channels:channel-manager"))
    implementation(project(":modules:core"))
    implementation(libs.jline)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
