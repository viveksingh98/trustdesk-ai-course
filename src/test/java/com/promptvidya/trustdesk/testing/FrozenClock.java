package com.promptvidya.trustdesk.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Time that moves only when the test says so. Every component in the
 * demo takes a Clock through its constructor, so a test can hold the
 * moment still, jump past an expiry, or replay two calls at the same
 * tick — the conditions that expose replay and expiry bugs a running
 * clock hides.
 */
public final class FrozenClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    private FrozenClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public static FrozenClock at(String isoInstant) {
        return at(Instant.parse(isoInstant));
    }

    public static FrozenClock at(Instant start) {
        return new FrozenClock(new AtomicReference<>(Objects.requireNonNull(start)), ZoneOffset.UTC);
    }

    public FrozenClock advance(Duration by) {
        now.updateAndGet(current -> current.plus(by));
        return this;
    }

    public FrozenClock set(Instant to) {
        now.set(Objects.requireNonNull(to));
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new FrozenClock(now, other);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
