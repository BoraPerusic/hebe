plugins {
    id("hebe.library")
}

dependencies {
    api(project(":modules:api"))
    implementation(project(":modules:providers:openai-compat"))
    api(libs.koog.agents)
    api(libs.koog.utils)
    api(libs.koog.utils.common)
}
