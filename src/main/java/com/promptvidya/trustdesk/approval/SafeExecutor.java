package com.promptvidya.trustdesk.approval;

import com.promptvidya.trustdesk.domain.AuditTrail;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.access.AccessDeniedException;

/**
 * Proceed, safely. An approved action runs as a list of steps, each of
 * which knows how to undo itself. The action id is the idempotency key:
 * a second execution returns the recorded outcome and runs nothing.
 * Limits are checked before the first step. If a step fails, every
 * step that already ran is undone in reverse, and the outcome says so.
 */
public final class SafeExecutor {

    public enum Outcome { COMPLETED, ROLLED_BACK, REFUSED_OVER_LIMIT }

    /** A side effect that can be taken back. */
    public interface Step {
        String name();

        void run() throws Exception;

        void undo();
    }

    /** What one execution may cost: how many side effects, and how large an amount. */
    public record Limits(int maximumSteps, long maximumAmount) {
        public Limits {
            if (maximumSteps < 1 || maximumAmount < 0) {
                throw new IllegalArgumentException("limits must allow at least one step and a non-negative amount");
            }
        }
    }

    public record Execution(String actionId, Outcome outcome, List<String> completedSteps, String failure) {}

    static final String EXECUTE_ACTION = "safe_execute";

    private final AuditTrail audit;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public SafeExecutor(AuditTrail audit) {
        this.audit = Objects.requireNonNull(audit);
    }

    /** Idempotent by action id: the first call runs, every later call replays the recorded outcome. */
    public Execution execute(String actionId, String subject, long amount, Limits limits, List<Step> steps) {
        Objects.requireNonNull(actionId);
        var previous = executions.get(actionId);
        if (previous != null) {
            audit.record(subject, EXECUTE_ACTION, actionId, "REPLAYED_" + previous.outcome());
            return previous;
        }
        if (steps.size() > limits.maximumSteps() || amount > limits.maximumAmount()) {
            var refused = new Execution(actionId, Outcome.REFUSED_OVER_LIMIT, List.of(), "over limit");
            executions.put(actionId, refused);
            audit.record(subject, EXECUTE_ACTION, actionId, Outcome.REFUSED_OVER_LIMIT.name());
            throw new AccessDeniedException("execution exceeds the approved limits: " + actionId);
        }
        var execution = executions.computeIfAbsent(actionId, key -> run(actionId, subject, steps));
        return execution;
    }

    public Optional<Execution> outcomeOf(String actionId) {
        return Optional.ofNullable(executions.get(actionId));
    }

    /** Run forward; on the first failure, undo what ran, in reverse, and record it. */
    private Execution run(String actionId, String subject, List<Step> steps) {
        var completed = new ArrayList<Step>();
        try {
            for (var step : steps) {
                step.run();
                completed.add(step);
            }
            audit.record(subject, EXECUTE_ACTION, actionId, Outcome.COMPLETED.name());
            return new Execution(actionId, Outcome.COMPLETED, completed.stream().map(Step::name).toList(), null);
        } catch (Exception failure) {
            for (var step : completed.reversed()) {
                step.undo();
            }
            audit.record(subject, EXECUTE_ACTION, actionId, Outcome.ROLLED_BACK.name());
            return new Execution(actionId, Outcome.ROLLED_BACK, List.of(), failure.getMessage());
        }
    }
}
