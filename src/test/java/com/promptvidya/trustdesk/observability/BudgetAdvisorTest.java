package com.promptvidya.trustdesk.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.observability.TokenBudget.Limits;
import com.promptvidya.trustdesk.observability.TokenBudget.Refusal;
import com.promptvidya.trustdesk.testing.FrozenClock;
import com.promptvidya.trustdesk.testing.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.access.AccessDeniedException;

/**
 * Refusing before the spend, on a frozen clock: a request inside the
 * budget reaches the model and is settled; a burst is rate limited
 * without a model call; a large prompt exhausts the day; the next day
 * starts clean; and no actor means no budget and no call.
 */
class BudgetAdvisorTest {

    private final FrozenClock clock = FrozenClock.at("2026-09-07T09:00:00Z");
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AuditTrail audit = new AuditTrail(clock);
    private final TokenBudget budget = new TokenBudget(clock, new Limits(100, 2));
    private final BudgetAdvisor advisor = new BudgetAdvisor(budget, new AgentMetrics(meters), audit);
    private final ActorContext alice = new ActorContext("alice", Set.of("tickets:read"));

    private String ask(ScriptedChatModel model, String question) {
        return ChatClient.create(model)
                .prompt()
                .user(question)
                .advisors(spec -> spec.advisors(advisor).param(BudgetAdvisor.ACTOR_KEY, alice))
                .call()
                .content();
    }

    @Test
    void aRequestInsideTheBudgetReachesTheModelAndIsSettled() {
        var model = ScriptedChatModel.script().answer("Laptops are replaced every three years.");

        assertThat(ask(model, "How often are laptops replaced?")).isEqualTo("Laptops are replaced every three years.");

        assertThat(model.invocations()).isEqualTo(1);
        assertThat(budget.spentToday("alice")).isBetween(10L, 30L);
        assertThat(audit.eventsFor("alice")).isEmpty();
    }

    @Test
    void aBurstIsRateLimitedWithoutAModelCall() {
        var model = ScriptedChatModel.script().answer("one").answer("two");
        ask(model, "first");
        ask(model, "second");

        var refused = ask(ScriptedChatModel.script().answer("never"), "third within the minute");

        assertThat(refused).isEqualTo(BudgetAdvisor.REFUSED_MESSAGE);
        assertThat(model.invocations()).isEqualTo(2);
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .containsExactly(tuple(BudgetAdvisor.REFUSED_ACTION, "chat", Refusal.RATE_LIMITED.name()));
        assertThat(meters.get(AgentMetrics.REFUSALS).tag("reason", "RATE_LIMITED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aLargePromptExhaustsTheDayAndTheNextDayStartsClean() {
        var refusalModel = ScriptedChatModel.script().answer("never");

        var refused = ask(refusalModel, "x".repeat(800));

        assertThat(refused).isEqualTo(BudgetAdvisor.REFUSED_MESSAGE);
        assertThat(refusalModel.invocations()).isZero();
        assertThat(audit.eventsWithOutcome(Refusal.BUDGET_EXHAUSTED.name())).hasSize(1);

        clock.advance(Duration.ofDays(1));
        var tomorrow = ScriptedChatModel.script().answer("fresh budget");
        assertThat(ask(tomorrow, "small question")).isEqualTo("fresh budget");
        assertThat(tomorrow.invocations()).isEqualTo(1);
    }

    @Test
    void noActorMeansNoBudgetAndNoCall() {
        var model = ScriptedChatModel.script().answer("never");

        assertThatThrownBy(() -> ChatClient.create(model)
                        .prompt()
                        .user("anonymous")
                        .advisors(advisor)
                        .call()
                        .content())
                .isInstanceOf(AccessDeniedException.class);
        assertThat(model.invocations()).isZero();
    }
}
