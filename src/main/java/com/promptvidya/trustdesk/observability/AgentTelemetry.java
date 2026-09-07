package com.promptvidya.trustdesk.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.Set;

/**
 * One registry for every observation Spring AI emits — chat client,
 * model, tool, retrieval — wired to metrics, with a filter that keeps
 * content out even if someone flips the content switches on: tool
 * arguments, results, schemas, and descriptions are dropped, and any
 * remaining value that looks like a payload rather than a reference is
 * replaced. Telemetry carries names, counts, durations, and outcomes.
 */
public final class AgentTelemetry {

    static final int MAXIMUM_VALUE_CHARACTERS = 120;
    static final String REDACTED = "[redacted]";
    static final Set<String> CONTENT_KEYS = Set.of(
            "spring.ai.tool.call.arguments",
            "spring.ai.tool.call.result",
            "spring.ai.tool.definition.schema",
            "spring.ai.tool.definition.description");

    private final ObservationRegistry registry;

    private AgentTelemetry(ObservationRegistry registry) {
        this.registry = registry;
    }

    public static AgentTelemetry create(MeterRegistry meters) {
        var registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationFilter(new ContentRedactingFilter())
                .observationHandler(new DefaultMeterObservationHandler(Objects.requireNonNull(meters)));
        return new AgentTelemetry(registry);
    }

    public ObservationRegistry registry() {
        return registry;
    }

    /** Runs on every observation before handlers see it. */
    static final class ContentRedactingFilter implements ObservationFilter {

        @Override
        public Observation.Context map(Observation.Context context) {
            for (var key : CONTENT_KEYS) {
                context.removeHighCardinalityKeyValue(key);
                context.removeLowCardinalityKeyValue(key);
            }
            for (var keyValue : context.getHighCardinalityKeyValues()) {
                if (keyValue.getValue().length() > MAXIMUM_VALUE_CHARACTERS) {
                    context.addHighCardinalityKeyValue(KeyValue.of(keyValue.getKey(), REDACTED));
                }
            }
            return context;
        }
    }
}
