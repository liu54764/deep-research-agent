package com.dra.worker.activity;

import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityFailureWrapperTest {

    interface Sample {
        String call(String mode);
    }

    @Test
    void returnsValueOnSuccess() {
        Sample sample = ActivityFailureWrapper.wrap(Sample.class, mode -> "ok:" + mode);
        assertEquals("ok:fast", sample.call("fast"));
    }

    @Test
    void nonRetryableBusinessFailureBecomesNonRetryableApplicationFailure() {
        Sample sample = ActivityFailureWrapper.wrap(Sample.class, mode -> {
            throw new DraException(ErrorCode.SEARCH_UNAVAILABLE,
                    "Tavily HTTP 432: exceeds plan limit", false);
        });

        ApplicationFailure failure = assertThrows(ApplicationFailure.class, () -> sample.call("x"));

        assertEquals("SEARCH_UNAVAILABLE", failure.getType());
        assertTrue(failure.isNonRetryable());
        assertEquals("Tavily HTTP 432: exceeds plan limit", failure.getOriginalMessage());
    }

    @Test
    void retryableBusinessFailurePassesThrough() {
        Sample sample = ActivityFailureWrapper.wrap(Sample.class, mode -> {
            throw new DraException(ErrorCode.SEARCH_UNAVAILABLE, "Tavily HTTP 503", true);
        });

        DraException failure = assertThrows(DraException.class, () -> sample.call("x"));

        assertTrue(failure.retryable());
    }
}
