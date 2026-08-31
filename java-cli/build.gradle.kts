plugins {
    application
}

dependencies {
    implementation("io.github.teams4j:teams4j-webhook")
    // teams4j-webhook brings no JSON binding; without this line the client fails at construction.
    // implementation rather than runtimeOnly, so javac can resolve the model's Jackson annotations.
    implementation("io.github.teams4j:teams4j-cards-jackson")
}

application {
    mainClass.set("smoke.Smoke")
}

tasks.named<JavaExec>("run") {
    // ./gradlew :java-cli:run --args="send cookbook"
    standardInput = System.`in`
    environment("TEAMS_WEBHOOK_URL", providers.environmentVariable("TEAMS_WEBHOOK_URL").getOrElse(""))
}
