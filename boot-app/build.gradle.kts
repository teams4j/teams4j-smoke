plugins {
    application
}

val bootVersion = property("bootVersion") as String

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    implementation("io.github.teams4j:teams4j-webhook-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter")
}

// No Boot Gradle plugin: the starter is what is under test, and SpringApplication.run needs none.
application {
    mainClass.set("smoke.boot.BootSmokeApplication")
}

tasks.named<JavaExec>("run") {
    environment("TEAMS_WEBHOOK_URL", providers.environmentVariable("TEAMS_WEBHOOK_URL").getOrElse(""))
}

tasks.register("printBootVersion") {
    val v = bootVersion
    doLast { println("boot-app compiled against Spring Boot $v") }
}
