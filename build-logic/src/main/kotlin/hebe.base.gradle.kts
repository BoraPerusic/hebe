plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

dependencies {
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.0")
    "testImplementation"("io.kotest:kotest-assertions-core:6.1.2")
    "testImplementation"("io.mockk:mockk:1.14.9")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.0")
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    autoCorrect = false
}

ktlint {
    filter { exclude("**/generated/**") }
}

tasks.named("check") {
    dependsOn("detekt", "ktlintCheck")
}
