plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:core"))
    implementation(project(":modules:config"))
    implementation(project(":modules:tools:dispatch"))
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotest.junit.platform.runner)
    testRuntimeOnly(libs.junit.platform.launcher)
}
