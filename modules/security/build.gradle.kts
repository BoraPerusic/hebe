plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:memory"))
    implementation(project(":modules:observability"))
    testImplementation(libs.mockk)
}
