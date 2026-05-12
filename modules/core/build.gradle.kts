plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:providers:openai-compat"))
    implementation(project(":modules:config"))
    implementation(project(":modules:observability"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:tools:dispatch"))
    implementation(project(":modules:security"))
    api(libs.koog.agents)
    api(libs.koog.utils)
    api(libs.koog.utils.common)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
