plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:security"))
    implementation(project(":modules:observability"))
    implementation(libs.jgit)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}