# teams4j-smoke

Manual smoke tests for [teams4j](https://github.com/teams4j/teams4j) against a real Microsoft Teams
tenant. It lives outside the library repository on purpose: it consumes the **published artifacts**
through `mavenLocal`, so it also exercises what only an artifact can get wrong — the POM, dependency
leaks, the nullness contract.

What it has established so far is written up at
<https://teams4j.github.io/teams4j/reference/measurements>.

## Setup

```bash
cd ../teams4j && ./gradlew publishToMavenLocal && cd ../teams4j-smoke
export TEAMS_WEBHOOK_URL='https://...'   # never commit this: the URL is write access to the channel
```

Create the URL from the Teams channel via **⋯ → Workflows → "Post to a channel when a webhook
request is received"**. A `webhook.office.com` URL is a retired Microsoft 365 connector; create a new
one.

The version under test is `teams4jVersion` in `gradle.properties`.

## Modules

| Module | What it checks | JSON binding |
|---|---|---|
| `java-cli` | The probe catalogue and the endpoint measurements | `teams4j-cards-jackson` |
| `kotlin-cli` | The Kotlin DSL and `sendAwait`; the nullness contract without jspecify; no Jackson on the classpath | `teams4j-cards-kotlinx` |
| `boot-app` | The starter's auto-configuration on Boot 3.5 and 4.1 | brought in by the starter |

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

## What the channel shows that the status code does not

- The endpoint answers `202` to everything tried, including messages it then drops (oversized
  payloads, part of a concurrent burst). Count cards in the channel; do not trust the status.
- Burst cards name themselves (`burst <run> — #n of N`) so survivors can be counted per run.
- The `--raw` burst builds a fresh `HttpRequest` per send. A shared one shares its `BodyPublisher`,
  and a run that reused one lost 11 of 12 under a `202`, indistinguishable from a server-side drop.
