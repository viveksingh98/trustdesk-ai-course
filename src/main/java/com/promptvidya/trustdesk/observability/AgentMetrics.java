package com.promptvidya.trustdesk.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * The agent's own meters, all low cardinality by construction: reasons,
 * tool names, outcomes, and model names are bounded sets; actors and
 * ids never become tags — they belong in the trace and the audit
 * trail, looked up by id.
 */
public final class AgentMetrics {

    static final String REFUSALS = "trustdesk.agent.refusals";
    static final String TOOL_RUNS = "trustdesk.agent.tool.runs";
    static final String TOKENS = "trustdesk.agent.tokens";

    private final MeterRegistry meters;

    public AgentMetrics(MeterRegistry meters) {
        this.meters = Objects.requireNonNull(meters);
    }

    /** A refusal by any gate — the decision layer, a guard, a bound, a budget. */
    public void refused(String reason) {
        Counter.builder(REFUSALS).tag("reason", bounded(reason)).register(meters).increment();
    }

    public void toolRan(String tool, String outcome) {
        Counter.builder(TOOL_RUNS).tag("tool", bounded(tool)).tag("outcome", bounded(outcome)).register(meters).increment();
    }

    /** Tokens are money: recorded per model and direction, summed by whoever reads the registry. */
    public void tokens(String model, long input, long output) {
        DistributionSummary.builder(TOKENS).tag("model", bounded(model)).tag("direction", "input").register(meters).record(input);
        DistributionSummary.builder(TOKENS).tag("model", bounded(model)).tag("direction", "output").register(meters).record(output);
    }

    /** A tag value is a name, never free text: short, or it is replaced. */
    static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 40 ? value : "other";
    }
}
