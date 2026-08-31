package smoke.boot;

import java.util.Optional;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.SpringVersion;

import io.github.teams4j.cards.Colors;
import io.github.teams4j.cards.FontWeight;
import io.github.teams4j.cards.dsl.Cards;
import io.github.teams4j.webhook.WorkflowsWebhookClient;

/**
 * The starter's consumption path, on both Boot lines.
 *
 * <pre>
 *   ./gradlew :boot-app:run                                        # no url -> no bean, app still starts
 *   ./gradlew :boot-app:run --args="--spring.profiles.active=send" # url -> bean, card sent
 *   ./gradlew :boot-app:run -PbootVersion=4.1.1 --args="--spring.profiles.active=send"
 * </pre>
 *
 */
@SpringBootApplication
public class BootSmokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootSmokeApplication.class, args);
    }

    @Bean
    CommandLineRunner smoke(Optional<WorkflowsWebhookClient> teams) {
        return args -> {
            System.out.println("Spring Framework " + SpringVersion.getVersion());
            if (teams.isEmpty()) {
                // No url, no bean, and the application still starts.
                System.out.println("no WorkflowsWebhookClient bean - teams4j.webhook.url is unset");
                return;
            }
            var response = teams.get().send(Cards.webhookCard()
                    .text("Card sent through the Spring Boot starter",
                            t -> t.weight(FontWeight.BOLDER).color(Colors.ACCENT))
                    .facts(f -> f.add("Spring", String.valueOf(SpringVersion.getVersion()))
                            .add("Path", "teams4j-webhook-spring-boot-starter"))
                    .openUrl("teams4j", "https://github.com/teams4j/teams4j"));
            System.out.println("HTTP " + response.statusCode() + " in " + response.attempts() + " attempt(s)");
        };
    }
}
