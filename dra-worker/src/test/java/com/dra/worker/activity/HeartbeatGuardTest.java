package com.dra.worker.activity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeartbeatGuardTest {

    private static final Duration TINY = Duration.ofMillis(5);

    @Test
    void returnsCallResult() {
        assertEquals("ok", HeartbeatGuard.await(() -> "ok", TINY));
    }

    @Test
    void pollsWithoutActivityContext() {
        // No Temporal activity context here; the guard must degrade to a plain wait.
        String result = HeartbeatGuard.await(() -> {
            Thread.sleep(60);
            return "slow";
        }, TINY);

        assertEquals("slow", result);
    }

    @Test
    void propagatesRuntimeExceptionFromCall() {
        RuntimeException failure = assertThrows(IllegalStateException.class,
                () -> HeartbeatGuard.await(() -> {
                    throw new IllegalStateException("boom");
                }, TINY));

        assertEquals("boom", failure.getMessage());
    }
}
