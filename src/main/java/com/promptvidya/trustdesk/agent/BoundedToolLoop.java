package com.promptvidya.trustdesk.agent;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

/**
 * Deterministic stop for the tool-calling loop. The framework's default
 * checker keeps the loop running for as long as the model asks for
 * tools; this one additionally refuses after a fixed number of rounds.
 * One instance guards one call — build it per request, like the actor.
 */
public final class BoundedToolLoop implements ToolExecutionEligibilityChecker {

    /**
     * Every exit from the loop has a name a dashboard can count: the model
     * stopped asking on its own, or the bound cut it off.
     */
    public enum StopReason {
        STILL_RUNNING,
        NATURAL_STOP,
        BOUND_HIT
    }

    private final int maximumRounds;
    private final AtomicInteger observedRounds = new AtomicInteger();
    private volatile StopReason stopReason = StopReason.STILL_RUNNING;

    public BoundedToolLoop(int maximumRounds) {
        if (maximumRounds < 1) {
            throw new IllegalArgumentException("maximumRounds must be at least 1");
        }
        this.maximumRounds = maximumRounds;
    }

    @Override
    public Boolean apply(ChatResponse response) {
        if (response == null || !response.hasToolCalls()) {
            stopReason = StopReason.NATURAL_STOP;
            return false;
        }
        if (observedRounds.incrementAndGet() > maximumRounds) {
            stopReason = StopReason.BOUND_HIT;
            return false;
        }
        return true;
    }

    public StopReason stopReason() {
        return stopReason;
    }

    public int observedRounds() {
        return Math.min(observedRounds.get(), maximumRounds);
    }

    public int maximumRounds() {
        return maximumRounds;
    }

    public boolean boundWasHit() {
        return stopReason == StopReason.BOUND_HIT;
    }
}
