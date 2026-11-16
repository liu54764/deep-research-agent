package com.dra.worker.activity.stub;

import com.dra.common.hash.Hashing;

import java.util.Locale;

final class StubSupport {

    private StubSupport() {
    }

    /**
     * Deterministic 26-char id: activity retries must reuse the same row instead of
     * creating orphaned duplicates (Temporal guarantees at-least-once execution).
     */
    static String stableId(String value) {
        return Hashing.sha256Hex(value).substring(0, 26).toUpperCase(Locale.ROOT);
    }

    static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("activity interrupted", e);
        }
    }
}
