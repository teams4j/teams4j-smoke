package smoke;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import io.github.teams4j.cards.AdaptiveCard;
import io.github.teams4j.webhook.RateLimitMode;
import io.github.teams4j.webhook.ValidationMode;
import io.github.teams4j.webhook.WebhookException;
import io.github.teams4j.webhook.WebhookMessage;
import io.github.teams4j.webhook.WebhookResponse;
import io.github.teams4j.webhook.WebhookResponseException;
import io.github.teams4j.webhook.WorkflowsWebhookClient;

/**
 * The manual smoke run against a real tenant: whether Teams renders what the library predicts, and
 * whether the endpoint answers the way the client assumes. This runner sends the cards; the eyes are
 * yours.
 *
 * <pre>
 *   export TEAMS_WEBHOOK_URL='https://...'
 *   ./gradlew :java-cli:run --args="list"
 *   ./gradlew :java-cli:run --args="send all"
 *   ./gradlew :java-cli:run --args="send cookbook action-style"
 *   ./gradlew :java-cli:run --args="oversize"
 *   ./gradlew :java-cli:run --args="burst 12"
 * </pre>
 *
 * <p>Every run appends to {@code smoke-results.md}; the settled observations live on the docs site
 * at https://teams4j.github.io/teams4j/reference/measurements.
 */
public final class Smoke {

    private static final Path RESULTS = Path.of("smoke-results.md");

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "list" : args[0];
        switch (command) {
            case "list" -> list();
            case "send" -> send(List.of(args).subList(1, args.length));
            case "oversize" -> oversize();
            case "burst" -> {
                List<String> rest = List.of(args).subList(1, args.length);
                int n = rest.stream().filter(a -> !a.startsWith("--")).findFirst()
                        .map(Integer::parseInt).orElse(12);
                if (rest.contains("--raw")) {
                    burstRaw(n);
                } else if (rest.contains("--concurrent")) {
                    burstConcurrent(n);
                } else {
                    burst(n);
                }
            }
            default -> {
                System.err.println("unknown command: " + command);
                System.err.println("usage: list | send <id|all> ... | oversize"
                        + " | burst [n] [--concurrent|--raw]");
                System.exit(2);
            }
        }
    }

    private static void list() {
        System.out.printf("%-16s %-42s %s%n", "ID", "RULE", "EXPECTATION");
        for (Probe probe : Probes.all()) {
            System.out.printf("%-16s %-42s %s%n", probe.id(), probe.rule(), probe.expectation());
        }
        System.out.println();
        System.out.println("plus: oversize (28KB limit), burst [n] (4 req/s limit)");
        System.out.println("      burst [n] --concurrent   fires all n at once through sendAsync");
        System.out.println("      burst [n] --raw          bypasses teams4j to read Retry-After verbatim");
    }

    private static void send(List<String> ids) throws IOException {
        List<Probe> selected = select(ids);
        // Validation off: the question is what Teams does, not what teams4j predicts.
        WorkflowsWebhookClient client = WorkflowsWebhookClient.builder(webhookUrl())
                .validation(ValidationMode.OFF)
                .rateLimit(RateLimitMode.BLOCK)
                .build();

        List<String> rows = new ArrayList<>();
        for (Probe probe : selected) {
            String outcome;
            try {
                WebhookResponse response = client.send(probe.card());
                outcome = "HTTP " + response.statusCode() + ", " + response.attempts() + " attempt(s)";
            } catch (WebhookException e) {
                outcome = describe(e);
            }
            System.out.printf("%-16s %-42s %s%n", probe.id(), probe.rule(), outcome);
            rows.add("| `" + probe.id() + "` | `" + probe.rule() + "` | " + probe.expectation()
                    + " | " + outcome + " | _(write what you saw here)_ |");
        }

        record(header("send") + """
                | probe | rule | expected | send result | observed (what you actually saw) |
                |---|---|---|---|---|
                """ + String.join("\n", rows) + "\n");
        System.out.println();
        System.out.println("Now look at the channel and fill in the last column of " + RESULTS.toAbsolutePath());
    }

    /** What the endpoint does with a payload over 28 KB, with teams4j's own check lifted. */
    private static void oversize() throws IOException {
        WorkflowsWebhookClient client = WorkflowsWebhookClient.builder(webhookUrl())
                .validation(ValidationMode.OFF)
                .rateLimit(RateLimitMode.BLOCK)
                .maxAttempts(1)
                .maxPayloadBytes(Integer.MAX_VALUE)
                .build();

        List<String> rows = new ArrayList<>();
        for (int padding : new int[] {20_000, 28_000, 40_000, 100_000}) {
            AdaptiveCard card = Probes.padded(padding);
            int bytes = client.serialise(WebhookMessage.of(card)).getBytes(StandardCharsets.UTF_8).length;
            String outcome;
            try {
                WebhookResponse response = client.send(card);
                outcome = "HTTP " + response.statusCode();
            } catch (WebhookException e) {
                outcome = describe(e);
            }
            System.out.printf("%,10d bytes  %s%n", bytes, outcome);
            rows.add("| " + String.format(Locale.ROOT, "%,d", bytes) + " | " + outcome + " |");
        }

        record(header("oversize") + """
                teams4j refuses anything over 28KB (=28672 bytes) before it sends. This run lifts that
                cap and lets the endpoint answer for itself - the real boundary and status code
                get settled here.

                | serialized size | response |
                |---|---|
                """ + String.join("\n", rows) + "\n");
    }

    /**
     * Sequential sends. Round-trip latency paces the loop to about 4 req/s, so this cannot reach the
     * throttle; {@link #burstConcurrent} can.
     */
    private static void burst(int count) throws IOException {
        WorkflowsWebhookClient client = WorkflowsWebhookClient.builder(webhookUrl())
                .validation(ValidationMode.OFF)
                .rateLimit(RateLimitMode.OFF)
                .maxAttempts(1)
                .build();

        List<String> rows = new ArrayList<>();
        Instant start = Instant.now();
        for (int i = 1; i <= count; i++) {
            Duration elapsed = Duration.between(start, Instant.now());
            String outcome;
            try {
                WebhookResponse response = client.send(Probes.padded(0));
                outcome = "HTTP " + response.statusCode();
            } catch (WebhookException e) {
                outcome = describe(e);
            }
            System.out.printf("#%-3d +%-8s %s%n", i, elapsed.toMillis() + "ms", outcome);
            rows.add("| " + i + " | +" + elapsed.toMillis() + "ms | " + outcome + " |");
        }

        record(header("burst " + count) + """
                Sequential sends. Each one waits for its own response, so **round-trip latency paces the
                loop** and it never exceeds 4 req/s - an instrument that cannot reach the throttle.
                Use `--concurrent` for that.

                | # | elapsed | response |
                |---|---|---|
                """.formatted(count) + String.join("\n", rows) + "\n");
    }

    /**
     * All sends start before any completes, so the rate is bounded by the endpoint. One attempt each:
     * a retry that absorbed a 429 would hide the measurement.
     */
    private static void burstConcurrent(int count) throws IOException {
        WorkflowsWebhookClient client = WorkflowsWebhookClient.builder(webhookUrl())
                .validation(ValidationMode.OFF)
                .rateLimit(RateLimitMode.OFF)
                .maxAttempts(1)
                .build();

        String tag = runTag();
        Instant start = Instant.now();
        List<CompletableFuture<String>> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pending.add(client.sendAsync(Probes.burst(i + 1, count, tag))
                    .handle((response, failure) -> {
                        long at = Duration.between(start, Instant.now()).toMillis();
                        if (failure != null) {
                            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                    ? failure.getCause()
                                    : failure;
                            return at + "|" + (cause instanceof WebhookException e
                                    ? describe(e)
                                    : cause.getClass().getSimpleName() + ": " + cause.getMessage());
                        }
                        return at + "|HTTP " + response.statusCode();
                    }));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();

        List<String> rows = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            String[] parts = pending.get(i).join().split("\\|", 2);
            System.out.printf("#%-3d done@%-8s %s%n", i + 1, parts[0] + "ms", parts[1]);
            rows.add("| " + (i + 1) + " | +" + parts[0] + "ms | " + parts[1] + " |");
        }

        record(header("burst " + count + " --concurrent") + """
                %d requests fired **concurrently** via `sendAsync` (run `%s`). All of them start before any
                completes, so the request rate is set by the endpoint rather than by round-trip
                latency. Retries are pinned to one attempt - a retry that absorbs a 429 hides the
                very thing being measured.

                Each card identifies itself as `burst %s — #n of %d`. **Count how many actually
                arrived in the channel** - the status codes do not report loss.

                | # | completed at | response |
                |---|---|---|
                """.formatted(count, tag, tag, count) + String.join("\n", rows) + "\n");
    }

    /**
     * The same burst with the JDK client alone, reading {@code Retry-After} verbatim: the parsed
     * {@link Duration} cannot tell seconds from an HTTP-date, or a parse failure from an absent header.
     */
    private static void burstRaw(int count) throws IOException, InterruptedException {
        URI url = webhookUrl();
        WorkflowsWebhookClient writer = WorkflowsWebhookClient.builder(url)
                .validation(ValidationMode.OFF)
                .build();
        HttpClient http = HttpClient.newHttpClient();
        String tag = runTag();

        Instant start = Instant.now();
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // A fresh request per send: a shared HttpRequest shares its BodyPublisher, and a run
            // that reused one lost 11 of 12 under a 202.
            String body = writer.serialise(WebhookMessage.of(Probes.burst(i + 1, count, tag)));
            pending.add(http.sendAsync(
                    HttpRequest.newBuilder(url)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();

        List<String> rows = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            HttpResponse<String> response = pending.get(i).join();
            String retryAfter = response.headers().firstValue("Retry-After").orElse("(absent)");
            String throttle = response.headers().firstValue("x-ms-ratelimit-remaining-subscription-writes")
                    .orElse("(absent)");
            System.out.printf("#%-3d %d  Retry-After: %-34s  ratelimit-remaining: %s%n",
                    i + 1, response.statusCode(), retryAfter, throttle);
            rows.add("| " + (i + 1) + " | " + response.statusCode() + " | `" + retryAfter + "` | `"
                    + throttle + "` |");
        }
        System.out.println("elapsed " + Duration.between(start, Instant.now()).toMillis() + "ms");

        record(header("burst " + count + " --raw") + """
                %d requests fired concurrently with the JDK `HttpClient`, with teams4j out of the way
                (run `%s`). `WebhookResponseException.retryAfter()` is a `Duration`, so it cannot
                distinguish `"12"` from an HTTP-date, nor a parse failure from an absent header -
                hence reading the raw value here.

                A fresh `HttpRequest` is built per request. Reusing one shares its `BodyPublisher`,
                and a run done that way lost 11 of 12 beneath a `202` - indistinguishable from
                server-side loss. Each card identifies itself as `burst %s — #n of %d`.

                | # | status | `Retry-After` (raw) | `x-ms-ratelimit-remaining-…-writes` |
                |---|---|---|---|
                """.formatted(count, tag, tag, count) + String.join("\n", rows) + "\n");
    }

    /** Different every run, so two runs never blur together in a channel. */
    private static String runTag() {
        return Long.toString(Instant.now().getEpochSecond() % 100_000, 36);
    }

    private static String describe(WebhookException e) {
        if (e instanceof WebhookResponseException response) {
            String retryAfter = response.retryAfter() == null ? "none" : response.retryAfter().toString();
            String body = response.body().replaceAll("\\s+", " ").trim();
            if (body.length() > 300) {
                body = body.substring(0, 300) + "...";
            }
            return "HTTP " + response.statusCode()
                    + ", Retry-After: " + retryAfter
                    + ", attempts: " + response.attempts()
                    + ", body: " + (body.isEmpty() ? "(empty)" : body);
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static List<Probe> select(List<String> ids) {
        List<Probe> all = Probes.all();
        if (ids.isEmpty() || ids.contains("all")) {
            return all;
        }
        List<Probe> selected = new ArrayList<>();
        for (String id : ids) {
            all.stream()
                    .filter(probe -> probe.id().equals(id))
                    .findFirst()
                    .ifPresentOrElse(selected::add, () -> {
                        throw new IllegalArgumentException("no such probe: " + id);
                    });
        }
        return selected;
    }

    private static URI webhookUrl() {
        String url = System.getenv("TEAMS_WEBHOOK_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("TEAMS_WEBHOOK_URL is not set. "
                    + "Never pass the URL on the command line - it is a channel write credential.");
        }
        return URI.create(url.trim());
    }

    private static String header(String command) {
        return "\n\n## " + Instant.now() + " — `" + command + "`\n\n";
    }

    private static void record(String markdown) throws IOException {
        Files.writeString(RESULTS, markdown, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Smoke() {}
}
