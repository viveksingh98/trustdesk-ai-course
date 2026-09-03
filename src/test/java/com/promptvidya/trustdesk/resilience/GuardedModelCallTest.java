package com.promptvidya.trustdesk.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptvidya.trustdesk.resilience.GuardedModelCall.Outcome;
import com.promptvidya.trustdesk.resilience.GuardedModelCall.TransientModelException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GuardedModelCallTest {

    @Test
    void answersPassThroughUntouched() {
        var guard = new GuardedModelCall(3, Duration.ofSeconds(2));
        assertThat(guard.call(() -> "grounded answer"))
                .isEqualTo(new Outcome.Answer("grounded answer"));
    }

    @Test
    void transientFailuresRetryUpToTheBound() {
        var attempts = new AtomicInteger();
        var guard = new GuardedModelCall(3, Duration.ofSeconds(2));
        var outcome = guard.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new TransientModelException("blip");
            }
            return "recovered";
        });
        assertThat(outcome).isEqualTo(new Outcome.Answer("recovered"));
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void permanentFailuresFailClosedWithoutRetry() {
        var attempts = new AtomicInteger();
        var guard = new GuardedModelCall(3, Duration.ofSeconds(2));
        var outcome = guard.call(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider rejected the key");
        });
        assertThat(outcome).isEqualTo(new Outcome.Refused("model call failed"));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void deadlinesTurnHangsIntoRefusals() {
        var guard = new GuardedModelCall(2, Duration.ofMillis(80));
        var outcome = guard.call(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        });
        assertThat(outcome).isEqualTo(new Outcome.Refused("model timed out"));
    }
}
