plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.hebe"
            artifactId = "api"
            version = "0.1.0"
            artifact(tasks.named("jar"))
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
}
