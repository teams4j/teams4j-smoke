import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("io.github.teams4j:teams4j-webhook-kotlin")
    implementation("io.github.teams4j:teams4j-cards-kotlin")
    // The kotlinx binding, so this consumer has no Jackson anywhere.
    runtimeOnly("io.github.teams4j:teams4j-cards-kotlinx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // No jspecify on purpose; the nullness contract must still reach this module.
}

kotlin {
    jvmToolchain(21)
    // 17 is the floor teams4j claims.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("smoke.KotlinSmokeKt")
}

tasks.named<JavaExec>("run") {
    environment("TEAMS_WEBHOOK_URL", providers.environmentVariable("TEAMS_WEBHOOK_URL").getOrElse(""))
}
