package com.promptvidya.trustdesk.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.approval.SafeExecutor.Limits;
import com.promptvidya.trustdesk.approval.SafeExecutor.Outcome;
import com.promptvidya.trustdesk.approval.SafeExecutor.Step;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Safe execution, one promise at a time: the same approval never runs
 * twice, limits refuse before anything runs, and a failure halfway
 * leaves the world as it was.
 */
class SafeExecutorTest {

    private final AuditTrail audit = new AuditTrail(Clock.systemUTC());
    private final SafeExecutor executor = new SafeExecutor(audit);
    private final List<String> ledger = new ArrayList<>();

    private Step step(String name, boolean fails) {
        return new Step() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void run() throws Exception {
                if (fails) {
                    throw new IllegalStateException(name + " failed");
                }
                ledger.add("+" + name);
            }

            @Override
            public void undo() {
                ledger.add("-" + name);
            }
        };
    }

    @Test
    void theSameApprovalNeverRunsTwice() {
        var limits = new Limits(3, 1_000);

        var first = executor.execute("A-1", "alice", 500, limits, List.of(step("debit", false), step("notify", false)));
        var second = executor.execute("A-1", "alice", 500, limits, List.of(step("debit", false), step("notify", false)));

        assertThat(first.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(second).isSameAs(first);
        assertThat(ledger).containsExactly("+debit", "+notify");
        assertThat(audit.eventsFor("alice")).extracting(AuditEvent::outcome).containsExactly("COMPLETED", "REPLAYED_COMPLETED");
    }

    @Test
    void limitsRefuseBeforeAnythingRuns() {
        assertThatThrownBy(() -> executor.execute("A-2", "alice", 5_000, new Limits(3, 1_000), List.of(step("debit", false))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> executor.execute("A-3", "alice", 10, new Limits(1, 1_000), List.of(step("a", false), step("b", false))))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(ledger).isEmpty();
        assertThat(executor.outcomeOf("A-2")).get().extracting(SafeExecutor.Execution::outcome).isEqualTo(Outcome.REFUSED_OVER_LIMIT);
    }

    @Test
    void aFailureHalfwayUndoesWhatRanInReverse() {
        var execution = executor.execute("A-4", "alice", 100, new Limits(5, 1_000),
                List.of(step("reserve", false), step("debit", false), step("ship", true)));

        assertThat(execution.outcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(execution.failure()).isEqualTo("ship failed");
        assertThat(ledger).containsExactly("+reserve", "+debit", "-debit", "-reserve");
        assertThat(executor.outcomeOf("A-4")).get().extracting(SafeExecutor.Execution::outcome).isEqualTo(Outcome.ROLLED_BACK);
    }

    @Test
    void limitsMustBeSane() {
        assertThatThrownBy(() -> new Limits(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
