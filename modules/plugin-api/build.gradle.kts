plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
