package com.dra.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchOptionsTest {

    @Test
    void defaultsAreUsable() {
        BenchOptions options = BenchOptions.parse(new String[]{});

        assertEquals("http://localhost:8080", options.getApiUrl());
        assertEquals(10, options.getTasks());
        assertEquals(4, options.getConcurrency());
        assertEquals("QUICK", options.getDepth());
        assertEquals(0.98, options.getMinSuccessRate(), 1e-9);
        assertEquals(300, options.getMaxP95Seconds(), 1e-9);
        assertFalse(options.isHelp());
    }

    @Test
    void parsesBothFlagStyles() {
        BenchOptions options = BenchOptions.parse(new String[]{
                "--api-url=http://example:8080", "--tasks", "20", "--concurrency=8",
                "--depth=standard", "--ramp-seconds=10", "--max-p95-seconds=0"
        });

        assertEquals("http://example:8080", options.getApiUrl());
        assertEquals(20, options.getTasks());
        assertEquals(8, options.getConcurrency());
        assertEquals("STANDARD", options.getDepth());
        assertEquals(10, options.getRampSeconds());
        assertEquals(0, options.getMaxP95Seconds(), 1e-9);
    }

    @Test
    void helpShortCircuitsValidation() {
        BenchOptions options = BenchOptions.parse(new String[]{"--help", "--tasks=-1"});

        assertTrue(options.isHelp());
    }

    @Test
    void rejectsUnknownOption() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchOptions.parse(new String[]{"--nope=1"}));
    }

    @Test
    void rejectsInvalidNumbersAndDepth() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchOptions.parse(new String[]{"--tasks=abc"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchOptions.parse(new String[]{"--tasks=0"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchOptions.parse(new String[]{"--depth=ULTRA"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchOptions.parse(new String[]{"--min-success-rate=2"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchOptions.parse(new String[]{"--poll-interval-ms=10"}));
    }
}
