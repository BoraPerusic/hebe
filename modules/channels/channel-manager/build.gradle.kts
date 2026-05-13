plugins {
    id("hebe.library")
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
