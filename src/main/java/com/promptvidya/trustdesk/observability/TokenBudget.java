package com.promptvidya.trustdesk.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tokens are money, and money is a security control. Each actor gets a
 * daily token budget and a per-minute request rate; a request reserves
 * its estimate before the model is called and settles the difference
 * afterwards, so a runaway loop, a forty-page document, or a flood of
 * requests becomes a refusal — an event that is counted and recorded —
 * instead of a bill.
 */
public final class TokenBudget {

    public enum Refusal { RATE_LIMITED, BUDGET_EXHAUSTED }

    public record Limits(long tokensPerDay, int requestsPerMinute) {
        public Limits {
            if (tokensPerDay <= 0 || requestsPerMinute <= 0) {
                throw new IllegalArgumentException("limits are positive");
            }
        }
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final Clock clock;
    private final Limits limits;
    private final Map<String, Long> spent = new HashMap<>();
    private final Map<String, Deque<Instant>> recent = new HashMap<>();
    private LocalDate day;

    public TokenBudget(Clock clock, Limits limits) {
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Before the model call: refuse, or reserve the estimate and record the request. */
    public synchronized Optional<Refusal> reserve(String actor, long estimatedTokens) {
        var now = clock.instant();
        rollOverIfNewDay(now);
        var requests = recent.computeIfAbsent(actor, ignored -> new ArrayDeque<>());
        while (!requests.isEmpty() && requests.peekFirst().isBefore(now.minus(MINUTE))) {
            requests.pollFirst();
        }
        if (requests.size() >= limits.requestsPerMinute()) {
            return Optional.of(Refusal.RATE_LIMITED);
        }
        if (spent.getOrDefault(actor, 0L) + estimatedTokens > limits.tokensPerDay()) {
            return Optional.of(Refusal.BUDGET_EXHAUSTED);
        }
        requests.addLast(now);
        spent.merge(actor, estimatedTokens, Long::sum);
        return Optional.empty();
    }

    /** After the model call: replace the estimate with what the model actually reported. */
    public synchronized void settle(String actor, long estimatedTokens, long actualTokens) {
        spent.merge(actor, actualTokens - estimatedTokens, Long::sum);
    }

    public synchronized long spentToday(String actor) {
        rollOverIfNewDay(clock.instant());
        return spent.getOrDefault(actor, 0L);
    }

    private void rollOverIfNewDay(Instant now) {
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(day)) {
            spent.clear();
            day = today;
        }
    }
}
