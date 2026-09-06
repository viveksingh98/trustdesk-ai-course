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

    private final int maximumRounds;
    private final AtomicInteger observedRounds = new AtomicInteger();

    public BoundedToolLoop(int maximumRounds) {
        if (maximumRounds < 1) {
            throw new IllegalArgumentException("maximumRounds must be at least 1");
        }
        this.maximumRounds = maximumRounds;
    }

    @Override
    public Boolean apply(ChatResponse response) {
        if (response == null || !response.hasToolCalls()) {
            return false;
        }
        return observedRounds.incrementAndGet() <= maximumRounds;
    }

    public int observedRounds() {
        return Math.min(observedRounds.get(), maximumRounds);
    }

    public int maximumRounds() {
        return maximumRounds;
    }

    public boolean boundWasHit() {
        return observedRounds.get() > maximumRounds;
    }
}
