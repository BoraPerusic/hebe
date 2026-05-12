plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:security"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:observability"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
