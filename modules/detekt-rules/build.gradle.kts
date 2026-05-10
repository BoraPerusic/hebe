plugins {
    id("hebe.library")
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.kotest.junit.platform.runner)
}
