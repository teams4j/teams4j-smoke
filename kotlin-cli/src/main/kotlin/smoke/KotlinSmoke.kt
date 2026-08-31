package smoke

import io.github.teams4j.cards.Colors
import io.github.teams4j.cards.FontSize
import io.github.teams4j.cards.FontWeight
import io.github.teams4j.cards.kotlin.adaptiveCard
import io.github.teams4j.webhook.WorkflowsWebhookClient
import io.github.teams4j.webhook.kotlin.sendAwait
import kotlinx.coroutines.runBlocking

/**
 * The Kotlin consumption path: the generated type-safe DSL plus `sendAwait`.
 *
 * ```
 * export TEAMS_WEBHOOK_URL='https://...'
 * ./gradlew :kotlin-cli:run
 * ```
 *
 * No jspecify on this classpath, on purpose: Kotlin reads the nullness contract from the annotation
 * name in the class file, so the guard below must still be required.
 */
fun main(): Unit = runBlocking {
    val webhookUrl = System.getenv("TEAMS_WEBHOOK_URL")
        ?: error("TEAMS_WEBHOOK_URL is not set. Never pass the URL on the command line.")

    val card = adaptiveCard {
        body {
            textBlock {
                text = "Kotlin: card sent with sendAwait"
                weight = FontWeight.BOLDER
                size = FontSize.LARGE
                color = Colors.GOOD
                wrap = true
            }
            factSet {
                facts {
                    fact { title = "Path"; value = "teams4j-cards-kotlin + teams4j-webhook-kotlin" }
                    fact { title = "Thread"; value = Thread.currentThread().name }
                }
            }
        }
        webhookActions {
            actionOpenUrl { title = "teams4j"; url = "https://github.com/teams4j/teams4j" }
        }
    }

    // Every generated component is @Nullable; dropping `?.` here must fail to compile.
    println("body elements: ${card.body()?.size}")

    val client = WorkflowsWebhookClient.create(webhookUrl)
    val response = client.sendAwait(card)
    println("HTTP ${response.statusCode()} in ${response.attempts()} attempt(s)")
}
