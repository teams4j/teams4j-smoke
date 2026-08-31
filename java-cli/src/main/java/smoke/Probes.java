package smoke;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.teams4j.cards.ActionOpenUrl;
import io.github.teams4j.cards.ActionStyle;
import io.github.teams4j.cards.ActionSubmit;
import io.github.teams4j.cards.AdaptiveCard;
import io.github.teams4j.cards.Colors;
import io.github.teams4j.cards.FontSize;
import io.github.teams4j.cards.FontWeight;
import io.github.teams4j.cards.Image;
import io.github.teams4j.cards.Media;
import io.github.teams4j.cards.MediaSource;
import io.github.teams4j.cards.dsl.Cards;

/**
 * The probe catalogue. Each expectation is what was observed on a real tenant on 2026-09-01, so a
 * run is a regression check; a card that behaves differently is the finding.
 *
 * <p>Images point at Wikimedia because the format rule reads the extension off the URL. Override
 * with the matching {@code SMOKE_IMAGE_*} / {@code SMOKE_MEDIA_*} variable if a host is unreachable.
 */
final class Probes {

    private Probes() {}

    private static final String PNG = env("SMOKE_IMAGE_PNG",
            "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png");
    private static final String GIF = env("SMOKE_IMAGE_GIF",
            "https://upload.wikimedia.org/wikipedia/commons/2/2c/Rotating_earth_%28large%29.gif");
    private static final String SVG = env("SMOKE_IMAGE_SVG",
            "https://upload.wikimedia.org/wikipedia/commons/0/02/SVG_logo.svg");
    private static final String WEBM = env("SMOKE_MEDIA_WEBM",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/9/9d/Big_Buck_Bunny_first_23_seconds.ogv/Big_Buck_Bunny_first_23_seconds.ogv.360p.webm");
    private static final String YOUTUBE = env("SMOKE_MEDIA_YOUTUBE",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    private static final String LINK = "https://github.com/teams4j/teams4j";

    static List<Probe> all() {
        List<Probe> probes = new ArrayList<>();

        // --- rendering ---

        probes.add(new Probe("cookbook", "-",
                "renders as the cookbook describes: bold attention-coloured heading, three facts, one button;"
                        + " Workflows appends its attribution line and the ISO timestamp is shown in the viewer's locale",
                Cards.webhookCard()
                        .text("Deploy failed: api", t -> t.weight(FontWeight.BOLDER)
                                .size(FontSize.LARGE)
                                .color(Colors.ATTENTION))
                        .facts(f -> f.add("Commit", "9f2c1ab")
                                .add("Cause", "health check timed out")
                                .add("Time", Instant.now().toString()))
                        .openUrl("View logs", LINK)
                        .build()));

        probes.add(new Probe("non-latin-emoji", "-",
                "Korean, emoji and a long line render intact and fold under wrap=true",
                Cards.webhookCard()
                        .text("Rendering probe, non-Latin text: 한글 테스트 문자열 · emoji 🚨🇰🇷 · and a line long"
                                + " enough to need wrapping. If this shows as a single cut-off line, the DSL's"
                                + " wrap=true default did not apply.")
                        .build()));

        // --- validator rules, sent with validation OFF so Teams answers rather than teams4j ---

        probes.add(new Probe("action-style", "(deleted rule)",
                "positive renders as a blue button and destructive as red; the rule that said Teams ignores them was deleted",
                Cards.card()
                        .text("action-style: default / positive / destructive")
                        .action(styled("default", null))
                        .action(styled("positive", ActionStyle.POSITIVE))
                        .action(styled("destructive", ActionStyle.DESTRUCTIVE))
                        .build()));

        probes.add(new Probe("webhook-submit", "webhook-submit",
                "the button is clickable and answers \"Unable to reach app. Please try again.\"",
                Cards.card()
                        .text("webhook-submit: press the button")
                        .action(ActionSubmit.builder().title("Submit").build())
                        .build()));

        probes.add(new Probe("submit-is-enabled", "(deleted rule)",
                "isEnabled=false renders greyed out and cannot be pressed; the rule that said it is ignored was deleted",
                Cards.card()
                        .text("submit-is-enabled: the button below is disabled")
                        .action(ActionSubmit.builder().title("Disabled").isEnabled(false).build())
                        .build()));

        probes.add(new Probe("speak", "speak",
                "no visible change; speak only reaches the immersive reader",
                Cards.webhookCard()
                        .text("speak: this card carries a speak string")
                        .customize(c -> c.speak("This card carries a speak string"))
                        .build()));

        probes.add(new Probe("schema-1-6", "schema-version",
                "1.5 is the ceiling: the card is refused and only fallbackText shows",
                Cards.webhookCard()
                        .version("1.6")
                        .text("schema-1-6: declared version 1.6")
                        .customize(c -> c.fallbackText("fallbackText for 1.6 - seeing this means 1.6 did not render"))
                        .build()));

        probes.add(new Probe("schema-1-7", "schema-version",
                "refused; only fallbackText shows",
                Cards.webhookCard()
                        .version("1.7")
                        .text("schema-1-7: declared version 1.7")
                        .customize(c -> c.fallbackText("fallbackText for 1.7 - this is the expected outcome"))
                        .build()));

        probes.add(new Probe("column-count", "column-count",
                "five columns render cleanly on desktop as a borderless table; the rule is guidance for mobile",
                Cards.webhookCard()
                        .text("column-count: five columns")
                        .columns(c -> {
                            for (int i = 1; i <= 5; i++) {
                                String label = "col " + i;
                                c.column(e -> e.text(label));
                            }
                        })
                        .build()));

        probes.add(new Probe("column-width", "column-width-too-wide + column-explicit-width",
                "two 300px columns do not break the layout: the left takes 300px, the right absorbs the rest",
                Cards.webhookCard()
                        .text("column-width: two 300px columns")
                        .columns(c -> c.column("300px", e -> e.text("left, 300px"))
                                .column("300px", e -> e.text("right, 300px")))
                        .build()));

        probes.add(new Probe("image-size", "image-size",
                "a 2000px image is scaled down to the card width",
                Cards.webhookCard()
                        .text("image-size: width declared 2000px")
                        .image(PNG, i -> i.width("2000px").altText("2000px wide"))
                        .build()));

        probes.add(new Probe("image-gif", "image-format",
                "gif renders and animates",
                Cards.webhookCard()
                        .text("image-gif: animated gif")
                        .body(Image.builder().url(GIF).altText("animated gif").width("200px").build())
                        .build()));

        probes.add(new Probe("image-svg", "image-format",
                "svg shows a broken-image icon with the alt text; a visible failure, not a blank",
                Cards.webhookCard()
                        .text("image-svg: svg below")
                        .body(Image.builder().url(SVG).altText("svg").width("200px").build())
                        .build()));

        // --- media: mimeType decides delivery, the host decides only playback ---

        probes.add(new Probe("media-direct", "media-host + media-mime-type",
                "message lost: 202, and the card never appears in the channel",
                Cards.webhookCard()
                        .text("media-direct: direct video link, no mimeType")
                        .body(Media.builder()
                                .addSource(MediaSource.builder().url(WEBM).build())
                                .altText("direct video")
                                .build())
                        .build()));

        probes.add(new Probe("media-youtube", "media-host",
                "embedded and playable",
                Cards.webhookCard()
                        .text("media-youtube: youtube source with mimeType")
                        .body(Media.builder()
                                .addSource(MediaSource.builder().url(YOUTUBE).mimeType("video/mp4").build())
                                .altText("youtube video")
                                .build())
                        .build()));

        probes.add(new Probe("media-youtube-nomime", "media-mime-type",
                "message lost despite the supported host: 202, no card",
                Cards.webhookCard()
                        .text("media-youtube-nomime: youtube source, no mimeType")
                        .body(Media.builder()
                                .addSource(MediaSource.builder().url(YOUTUBE).build())
                                .altText("youtube video, no mimeType")
                                .build())
                        .build()));

        probes.add(new Probe("media-direct-mime", "media-host",
                "arrives; renders \"This content is currently unavailable\" with an \"Open in browser\" link",
                Cards.webhookCard()
                        .text("media-direct-mime: direct video link with mimeType")
                        .body(Media.builder()
                                .addSource(MediaSource.builder().url(WEBM).mimeType("video/webm").build())
                                .altText("direct video with mimeType")
                                .build())
                        .build()));

        return List.copyOf(probes);
    }

    /** A burst card that names itself, so the channel shows which requests of which run survived. */
    static AdaptiveCard burst(int index, int total, String tag) {
        return Cards.webhookCard()
                .text("burst " + tag + " — #" + index + " of " + total)
                .build();
    }

    /** A card whose serialised envelope is at least {@code targetBytes} long. */
    static AdaptiveCard padded(int targetBytes) {
        return Cards.webhookCard()
                .text("oversize probe")
                .text("x".repeat(Math.max(0, targetBytes)))
                .build();
    }

    private static ActionOpenUrl styled(String title, ActionStyle style) {
        return ActionOpenUrl.builder().title(title).url(LINK).style(style).build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
