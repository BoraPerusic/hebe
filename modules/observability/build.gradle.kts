plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":modules:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlin.logging)
    api(libs.logback.classic)
    api(libs.logstash.logback.encoder)
    implementation(libs.otel.sdk)
    implementation(libs.otel.exporter.otlp)
    implementation(libs.otel.autoconfigure)
}
