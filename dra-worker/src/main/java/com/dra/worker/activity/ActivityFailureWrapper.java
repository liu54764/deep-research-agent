package com.dra.worker.activity;

import com.dra.common.error.DraException;
import io.temporal.failure.ApplicationFailure;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Wraps activity implementations at the Temporal boundary.
 *
 * <p>A {@link DraException} flagged {@code retryable=false} is just a RuntimeException to
 * Temporal, so {@code RetryOptions} at the workflow level retries it three times. That turned
 * permanent failures into request amplification: the Tavily quota exhaustion (HTTP 432)
 * re-issued every search call threefold during the C=8 load test. Mapping it to
 * {@code ApplicationFailure.newNonRetryableFailureWithCause} stops the retries and carries the
 * business error code as the failure type, so the workflow can persist a meaningful code
 * instead of WORKFLOW_ERROR.
 */
public final class ActivityFailureWrapper {

    private ActivityFailureWrapper() {
    }

    /** Returns a proxy that translates non-retryable business errors before Temporal sees them. */
    public static <T> T wrap(Class<T> activityInterface, T implementation) {
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(
                activityInterface.getClassLoader(),
                new Class<?>[]{activityInterface},
                (target, method, args) -> {
                    try {
                        return method.invoke(implementation, args);
                    } catch (InvocationTargetException e) {
                        throw translate(e.getCause());
                    }
                });
        return proxy;
    }

    static Throwable translate(Throwable error) {
        if (error instanceof DraException dra && !dra.retryable()) {
            return ApplicationFailure.newNonRetryableFailureWithCause(
                    dra.getMessage(), dra.code().name(), dra);
        }
        return error;
    }
}
