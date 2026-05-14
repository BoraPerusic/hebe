plugins {
    id("hebe.library")
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.hebe"
            artifactId = "plugin-api"
            version = "0.1.0"
            artifact(tasks.named("jar"))
        }
    }
}

dependencies {
    api(project(":modules:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.pf4j)
    api(libs.kotlinx.serialization.json)
}
