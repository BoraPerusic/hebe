plugins {
    id("talos.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.tomlj)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle)
}
