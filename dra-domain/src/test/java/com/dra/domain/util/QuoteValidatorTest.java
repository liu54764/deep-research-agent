package com.dra.domain.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteValidatorTest {

    @Test
    void acceptsQuoteAfterWhitespaceNormalization() {
        assertTrue(QuoteValidator.isSupportedBy(
                "Temporal provides  durable\nexecution",
                "Temporal provides durable execution for long-running workflows."));
    }

    @Test
    void rejectsHallucinatedQuote() {
        assertFalse(QuoteValidator.isSupportedBy(
                "Temporal guarantees exactly-once activities.",
                "Temporal executes activities at least once."));
    }

    @Test
    void rejectsBlankInput() {
        assertFalse(QuoteValidator.isSupportedBy("", "some content"));
        assertFalse(QuoteValidator.isSupportedBy("quote", null));
    }

    @Test
    void foldsTypographicQuotesDashesAndNbsp() {
        String source = "Temporal\u2019s docs call it \u201Cdurable execution\u201D \u2014 "
                + "a workflow can\u00A0resume after a crash.";

        assertTrue(QuoteValidator.isSupportedBy(
                "Temporal's docs call it \"durable execution\" - a workflow can resume after a crash.",
                source));
    }

    @Test
    void unwrapsQuotesTheModelAddedItself() {
        String source = "Durable execution replays the event history to rebuild state.";
        Optional<QuoteValidator.Resolved> resolved = QuoteValidator.resolve(
                "\u201CDurable execution replays the event history to rebuild state.\u201D", source);

        assertTrue(resolved.isPresent());
        assertTrue(resolved.get().rewritten());
        assertEquals(source, resolved.get().quote());
    }

    @Test
    void resolvesExactCharacterOffsets() {
        String source = "Intro line.\n\nDurable execution replays events.\n\nOutro line.";
        Optional<QuoteValidator.Resolved> resolved =
                QuoteValidator.resolve("Durable execution replays events.", source);

        assertTrue(resolved.isPresent());
        assertEquals(source.indexOf("Durable execution"), resolved.get().start());
        assertEquals("Durable execution replays events.",
                source.substring(resolved.get().start(), resolved.get().end()));
    }

    @Test
    void acceptsElidedMiddleWhenEveryPartExistsInOrder() {
        String source = "Durable execution persists the event history, and a restarted worker "
                + "replays that history to rebuild in-memory workflow state.";

        Optional<QuoteValidator.Resolved> resolved = QuoteValidator.resolve(
                "Durable execution persists the event history ... replays that history to rebuild "
                        + "in-memory workflow state.", source);

        assertTrue(resolved.isPresent());
        assertTrue(resolved.get().rewritten());
        assertTrue(source.contains("Durable execution persists the event history"));
    }

    @Test
    void rejectsElisionWhenOnePartIsMissing() {
        String source = "Durable execution persists the event history, and a restarted worker "
                + "replays that history to rebuild in-memory workflow state.";

        assertFalse(QuoteValidator.isSupportedBy(
                "Durable execution persists the event history ... the model writes its own summary.",
                source));
    }

    @Test
    void rejectsQuoteThatOnlyStartsLikeTheSource() {
        String source = "Temporal executes activities at least once, so handlers must be idempotent.";

        assertFalse(QuoteValidator.isSupportedBy(
                "Temporal executes activities exactly once, so handlers must be idempotent.", source));
    }
}
