plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:observability"))
    implementation(project(":modules:config"))
    implementation(project(":modules:tools:dispatch"))
    implementation(libs.bouncycastle)
    testImplementation(libs.mockk)
}
