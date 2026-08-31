package smoke;

import io.github.teams4j.cards.AdaptiveCard;

/**
 * One card sent to a real channel to find out what Teams does with it.
 *
 * <p>Every probe exists because something in teams4j is currently a claim rather than a
 * measurement. {@code rule} names the validator rule the card is meant to trip (or {@code "-"} when
 * the probe is about rendering rather than a rule), and {@code expectation} is what the library
 * currently believes will happen. The probe passes or fails by whether the channel agrees.
 *
 * @param id short name used on the command line
 * @param rule the {@code TeamsProfileValidator} rule constant under test, or {@code "-"}
 * @param expectation what teams4j predicts, in the words the finding would use
 * @param card the card to send, built with the public DSL
 */
record Probe(String id, String rule, String expectation, AdaptiveCard card) {}
