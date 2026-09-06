package smoke.bot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.teams4j.bot.Activity;
import io.github.teams4j.bot.ActivityReceiver;
import io.github.teams4j.bot.BotCredentials;
import io.github.teams4j.bot.BotException;
import io.github.teams4j.bot.BotNotInConversationException;
import io.github.teams4j.bot.BotTokenVerifier;
import io.github.teams4j.bot.ChannelAccount;
import io.github.teams4j.bot.ConnectorClient;
import io.github.teams4j.bot.ConversationParameters;
import io.github.teams4j.bot.ConversationReference;
import io.github.teams4j.bot.ConversationResourceResponse;
import io.github.teams4j.bot.ResourceResponse;
import io.github.teams4j.bot.TokenVerificationException;
import io.github.teams4j.cards.JsonCodec;
import io.github.teams4j.cards.dsl.Actions;
import io.github.teams4j.cards.dsl.Cards;

/**
 * The bot smoke run: a real Azure Bot registration, a tunnel to this process, and the Connector.
 *
 * <pre>
 *   export TEAMS_BOT_APP_ID=... TEAMS_BOT_APP_SECRET=... [TEAMS_BOT_TENANT_ID=...]
 *   ./gradlew :bot-cli:run --args="serve 3978"      # then add the bot to a chat or channel in Teams
 *   ./gradlew :bot-cli:run --args="send"            # a card to the stored conversation
 *   ./gradlew :bot-cli:run --args="update"          # send, then replace it
 *   ./gradlew :bot-cli:run --args="delete"          # send, then delete it
 *   ./gradlew :bot-cli:run --args="targeted"        # only the last user who wrote to the bot sees it
 * </pre>
 *
 * <p>{@code serve} stores where to post ({@code bot-conversation.properties}) the moment Teams says
 * the bot was added, and replies to every message and every card submit, so the inbound path is
 * checked by talking to the bot. The other commands check the outbound path without the tunnel.
 */
public final class BotSmoke {

    private static final Path STORE = Path.of("bot-conversation.properties");
    private static final JsonCodec JSON = JsonCodec.discover();

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "serve" : args[0];
        BotCredentials credentials = credentials();
        ConnectorClient connector = ConnectorClient.builder(credentials).build();
        switch (command) {
            case "serve" -> serve(args.length > 1 ? Integer.parseInt(args[1]) : 3978, credentials, connector);
            case "send" -> {
                ResourceResponse sent = connector.sendActivity(where(), connector.cardActivity(card("send", "Sent by `send`")));
                System.out.println("sent, id=" + sent.id());
            }
            case "update" -> {
                ResourceResponse sent = connector.sendActivity(where(), connector.cardActivity(card("update", "Working on it...")));
                System.out.println("sent, id=" + sent.id() + "; replacing in 3s");
                Thread.sleep(3000);
                connector.updateActivity(where(), sent.id(), connector.cardActivity(card("update", "Done. This card replaced the first one.")));
                System.out.println("updated");
            }
            case "delete" -> {
                ResourceResponse sent = connector.sendActivity(where(), connector.cardActivity(card("delete", "This card disappears in 3s")));
                Thread.sleep(3000);
                connector.deleteActivity(where(), sent.id());
                System.out.println("deleted " + sent.id());
            }
            case "targeted" -> {
                String user = stored().getProperty("lastUserId");
                if (user == null) {
                    System.err.println("no lastUserId stored yet: write to the bot once while `serve` is running");
                    System.exit(2);
                }
                Activity ephemeral = connector.cardActivity(card("targeted", "Only you see this"))
                        .toBuilder()
                        .recipient(ChannelAccount.of(user))
                        .build();
                System.out.println("targeted, id=" + connector.sendTargetedActivity(where(), ephemeral).id());
            }
            case "converse" -> {
                // Proactive: open (or find) the one-to-one chat with the last user who wrote, then post
                // into it. Needs the tenant, which `serve` stores alongside the user.
                Properties p = stored();
                String user = p.getProperty("lastUserId");
                String tenant = p.getProperty("tenantId");
                if (user == null || tenant == null) {
                    System.err.println("no lastUserId/tenantId stored yet: write to the bot once while `serve` is running");
                    System.exit(2);
                }
                ConversationResourceResponse chat = connector.createConversation(
                        where().serviceUrl(), ConversationParameters.personal(user, tenant));
                System.out.println("conversation " + chat.id() + " at " + chat.reference().serviceUrl());
                ResourceResponse sent = connector.sendActivity(chat.reference(),
                        connector.cardActivity(card("converse", "Proactive: the bot opened this chat itself")));
                System.out.println("sent, id=" + sent.id());
            }
            default -> {
                System.err.println("usage: serve [port] | send | update | delete | targeted | converse");
                System.exit(2);
            }
        }
    }

    // ---- inbound -----------------------------------------------------------------------------

    private static void serve(int port, BotCredentials credentials, ConnectorClient connector) throws IOException {
        ActivityReceiver receiver = new ActivityReceiver(BotTokenVerifier.builder(credentials.appId()).build());
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/messages", exchange -> handle(exchange, receiver, credentials, connector));
        server.start();
        System.out.println("listening on http://localhost:" + port + "/api/messages -- point the tunnel and the"
                + " Azure Bot messaging endpoint here, then add the bot in Teams");
    }

    private static void handle(HttpExchange exchange, ActivityReceiver receiver, BotCredentials credentials,
            ConnectorClient connector) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405);
            return;
        }
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Activity activity;
        try {
            activity = receiver.receive(exchange.getRequestHeaders().getFirst("Authorization"), body);
        } catch (TokenVerificationException e) {
            // The reason goes to the console only; the caller gets a bare 401.
            System.out.println(Instant.now() + " 401 " + e.getMessage());
            respond(exchange, 401);
            return;
        } catch (IllegalArgumentException e) {
            System.out.println(Instant.now() + " 400 " + e.getMessage());
            respond(exchange, 400);
            return;
        }
        // Teams retries on anything but a prompt 200, so answer first and act after.
        respond(exchange, 200);
        try {
            act(activity, credentials, connector);
        } catch (BotException e) {
            System.out.println("  !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void act(Activity activity, BotCredentials credentials, ConnectorClient connector) throws IOException {
        System.out.printf("%s %s from=%s conv=%s tenant=%s%n",
                Instant.now(), activity.type(),
                activity.from() == null ? null : activity.from().id(),
                activity.conversation() == null ? null : activity.conversation().id(),
                activity.tenantId());
        ConversationReference where = activity.conversationReference();
        if (where == null) {
            System.out.println("  no conversation reference; ignored");
            return;
        }
        // A channel message's id points into its thread; `send` and friends should post to the channel.
        ConversationReference channel = where.withoutMessageId();
        String user = activity.from() == null ? null : activity.from().id();
        if (activity.isBotAdded(credentials.botId())) {
            store(channel, null, activity.tenantId());
            System.out.println("  bot added: stored " + where);
            connector.sendActivity(where, connector.cardActivity(card("welcome",
                    "teams4j bot smoke is here. Say something, or press the button.")));
            return;
        }
        if (activity.value() != null) {
            String json = JSON.write(activity.value());
            System.out.println("  submit value=" + json);
            store(channel, user, activity.tenantId());
            connector.replyToActivity(where, activity.id(), Activity.message("Got your submit: `" + json + "`"));
            return;
        }
        if (activity.isMessage()) {
            String text = activity.textWithoutMentions();
            System.out.println("  text=\"" + text + "\" mentions=" + activity.mentions().size());
            store(channel, user, activity.tenantId());
            connector.replyToActivity(where, activity.id(),
                    connector.cardActivity(card("echo", "You said: " + text)));
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        try (OutputStream out = exchange.getResponseBody()) {
            // empty body
        }
    }

    // ---- cards and storage -------------------------------------------------------------------

    private static io.github.teams4j.cards.AdaptiveCard card(String probe, String text) {
        return Cards.card()
                .text("teams4j bot smoke: " + probe)
                .text(text)
                .facts(f -> f.add("Sent at", Instant.now().toString()))
                .action(Actions.submit("Press me", Map.of("probe", probe)))
                .openUrl("teams4j", "https://github.com/teams4j/teams4j")
                .build();
    }

    private static BotCredentials credentials() {
        String appId = env("TEAMS_BOT_APP_ID");
        String secret = env("TEAMS_BOT_APP_SECRET");
        String tenant = System.getenv("TEAMS_BOT_TENANT_ID");
        return tenant == null || tenant.isBlank()
                ? BotCredentials.of(appId, secret)
                : BotCredentials.singleTenant(appId, secret, tenant);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set. Never pass credentials on the command line.");
        }
        return value;
    }

    private static Properties stored() throws IOException {
        Properties p = new Properties();
        if (Files.exists(STORE)) {
            try (var in = Files.newInputStream(STORE)) {
                p.load(in);
            }
        }
        return p;
    }

    private static void store(ConversationReference where, String lastUserId, String tenantId) throws IOException {
        Properties p = stored();
        p.setProperty("serviceUrl", where.serviceUrl().toString());
        p.setProperty("conversationId", where.conversationId());
        if (lastUserId != null) {
            p.setProperty("lastUserId", lastUserId);
        }
        if (tenantId != null) {
            p.setProperty("tenantId", tenantId);
        }
        try (var out = Files.newOutputStream(STORE)) {
            p.store(out, "written by bot-cli serve; git-ignored");
        }
    }

    private static ConversationReference where() throws IOException {
        Properties p = stored();
        String serviceUrl = p.getProperty("serviceUrl");
        String conversationId = p.getProperty("conversationId");
        if (serviceUrl == null || conversationId == null) {
            System.err.println("no conversation stored yet. With `serve` and the tunnel running, either add the bot"
                    + " in Teams or just send it a message: both store the conversation. Then retry.");
            System.exit(2);
        }
        return ConversationReference.of(serviceUrl, conversationId);
    }

    @SuppressWarnings("unused")
    private static final List<Map.Entry<String, String>> WHAT_TO_LOOK_FOR = List.of(
            Map.entry("welcome", "a card appears when the bot is added"),
            Map.entry("echo", "a reply appears under your message, in the thread in a channel"),
            Map.entry("submit", "pressing the button yields a 'Got your submit' reply"),
            Map.entry("update", "the first card is replaced in place, not followed by a second"),
            Map.entry("targeted", "the card shows for one user only, marked 'Only visible to you'"),
            Map.entry("roster", "remove the bot, run `send`: BotNotInConversationException"));
}
