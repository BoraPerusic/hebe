plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    api(project(":modules:config"))
    api(project(":modules:tools:dispatch"))
    api(project(":modules:memory"))
    implementation(project(":modules:tools:builtin"))
    api(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.io)
    implementation(libs.koog.utils)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
