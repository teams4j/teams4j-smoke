plugins {
    application
}

dependencies {
    implementation("io.github.teams4j:teams4j-bot")
    // The bot module brings no JSON binding; both the CardWriter and the JsonCodec come from here.
    implementation("io.github.teams4j:teams4j-cards-jackson")
}

application {
    mainClass.set("smoke.bot.BotSmoke")
}

tasks.named<JavaExec>("run") {
    // ./gradlew :bot-cli:run --args="serve 3978"
    standardInput = System.`in`
    for (name in listOf("TEAMS_BOT_APP_ID", "TEAMS_BOT_APP_SECRET", "TEAMS_BOT_TENANT_ID")) {
        environment(name, providers.environmentVariable(name).getOrElse(""))
    }
}
