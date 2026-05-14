plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":modules:plugin-api"))
    implementation(project(":modules:api"))
    implementation(project(":modules:observability"))
    implementation(project(":modules:config"))
    implementation(project(":modules:security"))
    implementation(libs.pf4j)
    implementation(libs.tomlj)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.bouncycastle)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
