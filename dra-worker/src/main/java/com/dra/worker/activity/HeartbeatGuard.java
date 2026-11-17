package com.dra.worker.activity;

import io.temporal.activity.Activity;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a blocking outbound call while heartbeating on the activity thread.
 *
 * <p>Without heartbeats Temporal only notices a dead worker when {@code startToCloseTimeout}
 * expires: in the S4 drill a killed worker-io added ~28s to in-flight tasks because search/fetch
 * time out at 30s. With a 5s heartbeat timeout the retry is dispatched within seconds.
 *
 * <p>The call runs on a helper thread and the activity thread polls it every {@code interval},
 * sending a heartbeat on each poll. This keeps the business code synchronous and keeps every
 * heartbeat on the activity thread (the execution context is thread-local by contract).
 */
public final class HeartbeatGuard {

    /** 2s between beats vs a 5s heartbeat timeout leaves room for scheduling jitter. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);

    private static final ExecutorService CALLS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "activity-outbound-call");
        thread.setDaemon(true);
        return thread;
    });

    private HeartbeatGuard() {
    }

    public static <T> T await(Callable<T> call) {
        return await(call, DEFAULT_INTERVAL);
    }

    public static <T> T await(Callable<T> call, Duration interval) {
        Future<T> future = CALLS.submit(call);
        boolean inActivity = inActivity();
        try {
            // Beat once up front so even a short call leaves a fresh liveness timestamp.
            heartbeat(inActivity);
            while (true) {
                try {
                    return future.get(interval.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    heartbeat(inActivity);
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for outbound call", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("outbound call failed: " + cause.getMessage(), cause);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void heartbeat(boolean inActivity) {
        if (inActivity) {
            Activity.getExecutionContext().heartbeat("pulse");
        }
    }

    /** Plain unit tests call activities directly; there is no activity context then. */
    private static boolean inActivity() {
        try {
            Activity.getExecutionContext();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
