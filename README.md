# teams4j-smoke

Manual smoke tests for [teams4j](https://github.com/teams4j/teams4j) against a real Microsoft Teams
tenant. It lives outside the library repository on purpose: it consumes the **published artifacts**
(from Central, or from `mavenLocal` with `-Pteams4jVersion`), so it also exercises what only an
artifact can get wrong — the POM, dependency leaks, the nullness contract.

What it has established so far is written up at
<https://teams4j.github.io/teams4j/reference/measurements>.

## Setup

```bash
# only for an unreleased teams4j; otherwise the version in gradle.properties comes from Central
cd ../teams4j && ./gradlew publishToMavenLocal && cd ../teams4j-smoke   # then -Pteams4jVersion=<its printVersion>
export TEAMS_WEBHOOK_URL='https://...'   # never commit this: the URL is write access to the channel
```

Create the URL from the Teams channel via **⋯ → Workflows → "Post to a channel when a webhook
request is received"**. A `webhook.office.com` URL is a retired Microsoft 365 connector; create a new
one.

The version under test is `teams4jVersion` in `gradle.properties`. `kotlinVersion` there is the
compiler for `kotlin-cli`; pass `-PkotlinVersion=<kotlinBaseline from teams4j's catalog>` to run it on
the lowest Kotlin the modules support.

## Modules

| Module | What it checks | JSON binding |
|---|---|---|
| `java-cli` | The probe catalogue and the endpoint measurements | `teams4j-cards-jackson` |
| `kotlin-cli` | The Kotlin DSL and `sendAwait`; the nullness contract without jspecify; no Jackson on the classpath | `teams4j-cards-kotlinx` |
| `boot-app` | The starter's auto-configuration on Boot 3.5 and 4.1 | brought in by the starter |
| `bot-cli` | `teams4j-bot` against a real bot registration: inbound verification, replies, update, delete, targeted | `teams4j-cards-jackson` |

## Running

```bash
./gradlew :java-cli:run --args="list"                    # the probes and what each expects
./gradlew :java-cli:run --args="send all"                # send every probe card
./gradlew :java-cli:run --args="send cookbook image-svg" # or a selection
./gradlew :java-cli:run --args="oversize"                # payloads around the 28 KB limit
./gradlew :java-cli:run --args="burst 12"                # sequential; latency paces this below 4 req/s
./gradlew :java-cli:run --args="burst 12 --concurrent"   # sendAsync, all at once
./gradlew :java-cli:run --args="burst 12 --raw"          # JDK HttpClient alone, raw Retry-After header

./gradlew :kotlin-cli:run

./gradlew :boot-app:run                                                      # no URL: no bean, still starts
./gradlew :boot-app:run --args="--spring.profiles.active=send"               # Boot 3.5.16
./gradlew :boot-app:run -PbootVersion=4.1.1 --args="--spring.profiles.active=send"
```

Probe cards are sent with validation **off**, so that Teams answers rather than the validator. Each
probe carries the outcome the library currently predicts; the run is a regression check against the
tenant, and a card that renders differently from its prediction is the finding.

`send`, `oversize` and `burst` append a table to `smoke-results.md` (git-ignored). Look at the
channel, fill in the last column, and carry anything new into the measurements page.

## The bot

`bot-cli` needs a bot registration and a tunnel; nothing else in this repository does.

1. **Register.** In the Azure portal create an *Azure Bot* resource (or use the Teams Developer Portal).
   Note the Microsoft App ID, create a client secret, and note the tenant id if the registration is
   single-tenant. Enable the **Microsoft Teams** channel on the bot.
2. **Tunnel.** `cloudflared tunnel --url http://localhost:3978` (a Cloudflare quick tunnel: no account,
   a fresh `trycloudflare.com` address per run) or `devtunnel host -p 3978 --allow-anonymous`. Set the
   bot's **messaging endpoint** to `https://<tunnel>/api/messages`, and again whenever the address
   changes.
3. **Install.** Sideload an app package whose manifest lists the bot id, or add the bot from the
   Developer Portal, into a personal chat, a group chat, or a channel.

```bash
export TEAMS_BOT_APP_ID='...' TEAMS_BOT_APP_SECRET='...'   # TEAMS_BOT_TENANT_ID='...' for single-tenant
./gradlew :bot-cli:run --args="serve 3978"    # keep running; add the bot in Teams, then talk to it
./gradlew :bot-cli:run --args="send"          # in another shell: a card to the stored conversation
./gradlew :bot-cli:run --args="update"        # a card that is replaced in place three seconds later
./gradlew :bot-cli:run --args="delete"        # a card that is removed three seconds later
./gradlew :bot-cli:run --args="targeted"      # visible only to the last user who wrote to (or pressed a button on) the bot
```

`serve` writes `bot-conversation.properties` (git-ignored) when Teams reports the bot was added, and
again on every message the bot receives; that is the `ConversationReference` the other commands post
to, and the same thing a real bot would store. If the bot was installed before `serve` was up, sending
it one message is enough.

What to look for, and what each proves:

| Step | In Teams | Proves |
|---|---|---|
| Add the bot | A welcome card appears | Token verification passed; `isBotAdded` fired; `sendActivity` works |
| Say something (in a channel, `@mention` the bot: Teams delivers nothing else to it) | A reply card under your message (in a channel: in the thread) | `textWithoutMentions`, `replyToActivity` |
| Press the button | "Got your submit: `{"probe":"…"}`" | `Action.Submit` round trip through `Activity.value()` |
| `update` | One card, whose text changes | `updateActivity` replaces rather than appends |
| `delete` | The card vanishes | `deleteActivity` |
| `targeted` | A card marked "Only visible to you" | `?isTargetedActivity=true` with `recipient` |
| Remove the bot, run `send` | Console: `BotNotInConversationException` | The 403 is recognised and not retried |
| Any request without a Bot Framework token (`curl -XPOST localhost:3978/api/messages`) | `401`, console names the check | Fail-closed verification |

Run on 2026-09-05: every row passed except the channel `@mention`, which needs the app's bot scope to
include Team. The findings are on the docs' measurements page.

## What the channel shows that the status code does not

- The endpoint answers `202` to everything tried, including messages it then drops (oversized
  payloads, part of a concurrent burst). Count cards in the channel; do not trust the status.
- Burst cards name themselves (`burst <run> — #n of N`) so survivors can be counted per run.
- The `--raw` burst builds a fresh `HttpRequest` per send. A shared one shares its `BodyPublisher`,
  and a run that reused one lost 11 of 12 under a `202`, indistinguishable from a server-side drop.
