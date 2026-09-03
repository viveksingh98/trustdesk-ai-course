package com.promptvidya.trustdesk.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Failure-path guard for TrustDesk's model calls.
 *
 * <p>Every call gets a hard deadline, transient failures earn a bounded
 * number of retries, and everything else fails closed to a refusal the
 * caller can show safely. The model never gets an unbounded slice of the
 * request thread, and no raw provider error ever reaches the user.
 */
public final class GuardedModelCall {

    /** Raised by callers to mark a failure as retry-worthy. */
    public static final class TransientModelException extends RuntimeException {
        public TransientModelException(String message) {
            super(message);
        }
    }

    public sealed interface Outcome {
        record Answer(String text) implements Outcome {}
        record Refused(String reason) implements Outcome {}
    }

    private final int maximumAttempts;
    private final Duration perAttemptTimeout;

    public GuardedModelCall(int maximumAttempts, Duration perAttemptTimeout) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        this.maximumAttempts = maximumAttempts;
        this.perAttemptTimeout = Objects.requireNonNull(perAttemptTimeout);
    }

    /** Runs the model call under deadline and retry policy, failing closed. */
    public Outcome call(Supplier<String> modelCall) {
        Objects.requireNonNull(modelCall, "modelCall must not be null");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int attempt = 1; attempt <= maximumAttempts; attempt += 1) {
                Future<String> future = executor.submit(modelCall::get);
                try {
                    return new Outcome.Answer(
                            future.get(perAttemptTimeout.toMillis(), TimeUnit.MILLISECONDS));
                } catch (TimeoutException timeout) {
                    future.cancel(true);
                    if (attempt == maximumAttempts) {
                        return new Outcome.Refused("model timed out");
                    }
                } catch (ExecutionException failure) {
                    if (!(failure.getCause() instanceof TransientModelException)
                            || attempt == maximumAttempts) {
                        return new Outcome.Refused("model call failed");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Outcome.Refused("call interrupted");
                }
            }
            return new Outcome.Refused("model call failed");
        } finally {
            executor.shutdownNow();
        }
    }
}
