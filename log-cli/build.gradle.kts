plugins {
    application
}

dependencies {
    // Both appenders on one classpath; each is driven through its backend's own API, no bridge.
    implementation("io.github.teams4j:teams4j-logback")
    implementation("io.github.teams4j:teams4j-log4j2")
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")
}

application {
    mainClass.set("smoke.LogSmoke")
}

tasks.named<JavaExec>("run") {
    // ./gradlew :log-cli:run --args="logback"   (or log4j2)
    environment("TEAMS_WEBHOOK_URL", providers.environmentVariable("TEAMS_WEBHOOK_URL").getOrElse(""))
}
