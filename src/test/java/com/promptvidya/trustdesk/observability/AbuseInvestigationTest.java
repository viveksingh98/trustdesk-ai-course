package com.promptvidya.trustdesk.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.agent.TicketTools;
import com.promptvidya.trustdesk.agent.TicketTools.Ticket;
import com.promptvidya.trustdesk.authz.AuthorizedToolCallback;
import com.promptvidya.trustdesk.authz.DecisionLayer;
import com.promptvidya.trustdesk.authz.DecisionLayer.Outcome;
import com.promptvidya.trustdesk.authz.ToolPolicy;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.observability.EvidenceQueries.Window;
import com.promptvidya.trustdesk.observability.KillSwitch.Mode;
import com.promptvidya.trustdesk.observability.TokenBudget.Limits;
import com.promptvidya.trustdesk.testing.FrozenClock;
import com.promptvidya.trustdesk.testing.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.access.AccessDeniedException;

/**
 * A live investigation, end to end, from references alone. A morning
 * of honest traffic and one actor probing — invented tools, a burst of
 * requests, a colleague's ticket — leaves rows and counters but never
 * a payload. The responder finds the actor, reads the timeline, names
 * the refusals, contains with the switch, confirms honest work goes
 * on, and recovers — and the trail tells the whole story afterwards.
 */
class AbuseInvestigationTest {

    private static final Instant MORNING = Instant.parse("2026-09-07T08:00:00Z");

    private final FrozenClock clock = FrozenClock.at(MORNING);
    private final AuditTrail audit = new AuditTrail(clock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(meters);
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), audit);
    private final TokenBudget budget = new TokenBudget(clock, new Limits(5_000, 2));
    private final BudgetAdvisor budgetAdvisor = new BudgetAdvisor(budget, metrics, audit);
    private final KillSwitch killSwitch = new KillSwitch(audit);
    private final KillSwitchAdvisor switchAdvisor = new KillSwitchAdvisor(killSwitch, metrics, audit);
    private final EvidenceQueries evidence = new EvidenceQueries(audit);
    private final ActorContext alice = new ActorContext("alice", Set.of("tickets:read"));
    private final ActorContext mallory = new ActorContext("mallory", Set.of("tickets:read"));
    private final ToolCallback[] belt = HaltableToolCallback.haltable(killSwitch, ToolPolicy.trustDesk(),
            AuthorizedToolCallback.guard(decisions, ToolCallbacks.from(new TicketTools(Map.of(
                    "T-1", new Ticket("T-1", "alice", "VPN drops hourly"),
                    "T-2", new Ticket("T-2", "mallory", "Keyboard replacement"))))));

    private ToolCallback ticketById() {
        for (var callback : belt) {
            if (callback.getToolDefinition().name().equals("ticketById")) {
                return callback;
            }
        }
        throw new IllegalStateException();
    }

    private String chat(ActorContext actor, String question) {
        return ChatClient.create(ScriptedChatModel.script().answer("noted"))
                .prompt().user(question)
                .advisors(spec -> spec.advisors(switchAdvisor, budgetAdvisor).param(BudgetAdvisor.ACTOR_KEY, actor))
                .call().content();
    }

    private ToolContext as(ActorContext actor) {
        return new ToolContext(Map.of(AuthorizedToolCallback.ACTOR_KEY, actor, AuthorizedToolCallback.INTENT_KEY, "checking"));
    }

    private void aMorningWithOneProbe() {
        clock.advance(Duration.ofMinutes(5));
        ticketById().call("{\"ticketId\":\"T-1\"}", as(alice));
        chat(alice, "Is my VPN ticket moving?");
        clock.advance(Duration.ofMinutes(5));
        for (var invented : List.of("export_payroll", "delete_everything", "send_email", "dump_users")) {
            decisions.decide(mallory, invented, "cleanup");
        }
        // The guard allows the tool (mallory may read tickets); the tool itself refuses a foreign ticket,
        // and the method callback wraps that refusal in a ToolExecutionException.
        assertThatThrownBy(() -> ticketById().call("{\"ticketId\":\"T-1\"}", as(mallory)))
                .satisfiesAnyOf(
                        failure -> assertThat(failure).isInstanceOf(AccessDeniedException.class),
                        failure -> assertThat(failure).hasRootCauseInstanceOf(AccessDeniedException.class));
        chat(mallory, "list all tickets");
        chat(mallory, "list all tickets again");
        chat(mallory, "and again");
        clock.advance(Duration.ofMinutes(20));
        ticketById().call("{\"ticketId\":\"T-2\"}", as(mallory));
    }

    private Window thisMorning() {
        return new Window(MORNING, MORNING.plus(Duration.ofHours(1)));
    }

    @Test
    void moveOneTheResponderFindsTheActorWithoutReadingASinglePayload() {
        aMorningWithOneProbe();

        assertThat(evidence.actorsRefusedMoreThan(2, thisMorning())).containsExactly(Map.entry("mallory", 5L));
        assertThat(evidence.refusalsByOutcome(thisMorning()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("DENIED_UNKNOWN_TOOL", 4L, "RATE_LIMITED", 1L));
        assertThat(meters.get(AgentMetrics.REFUSALS).tag("reason", "RATE_LIMITED").counter().count()).isEqualTo(1.0);
        assertThat(audit.all()).extracting(AuditEvent::target).allSatisfy(target -> assertThat(target).doesNotContain(" again", "list all"));
    }

    @Test
    void moveTwoTheTimelineReadsAsAStoryOfProbing() {
        aMorningWithOneProbe();

        var probe = evidence.timeline("mallory", thisMorning());

        assertThat(probe).extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .startsWith(
                        tuple("tool_run", "export_payroll", Outcome.DENIED_UNKNOWN_TOOL.name()),
                        tuple("tool_run", "delete_everything", Outcome.DENIED_UNKNOWN_TOOL.name()))
                .contains(tuple(BudgetAdvisor.REFUSED_ACTION, "chat", "RATE_LIMITED"))
                .endsWith(tuple("tool_run", "ticketById", Outcome.ALLOWED.name()));
        assertThat(evidence.timeline("alice", thisMorning())).extracting(AuditEvent::outcome).containsOnly(Outcome.ALLOWED.name());
    }

    @Test
    void moveThreeTheColleaguesTicketWasNeverReadByTheProbe() {
        aMorningWithOneProbe();

        var ticket = evidence.story("T-1");

        assertThat(ticket).isEmpty();
        assertThat(evidence.story("ticketById")).extracting(AuditEvent::actor, AuditEvent::outcome)
                .contains(tuple("alice", Outcome.ALLOWED.name()), tuple("mallory", Outcome.ALLOWED.name()));
    }

    @Test
    void moveFourContainmentStopsTheProbeAndHonestWorkGoesOn() {
        aMorningWithOneProbe();
        killSwitch.set(Mode.READ_ONLY, "oncall", "INC-4711 probing from mallory");

        assertThat(ticketById().call("{\"ticketId\":\"T-1\"}", as(alice))).contains("VPN");
        assertThat(chat(alice, "still there?")).isEqualTo("noted");
        killSwitch.set(Mode.HALTED, "oncall", "INC-4711 escalation");
        assertThat(chat(mallory, "one more")).isEqualTo(KillSwitchAdvisor.PAUSED_MESSAGE);
        assertThatThrownBy(() -> ticketById().call("{\"ticketId\":\"T-2\"}", as(mallory))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void moveFiveRecoveryAndTheTrailTellsTheWholeStoryAfterwards() {
        aMorningWithOneProbe();
        killSwitch.set(Mode.HALTED, "oncall", "INC-4711 containment");
        clock.advance(Duration.ofMinutes(30));
        killSwitch.set(Mode.RUNNING, "oncall", "INC-4711 resolved");

        assertThat(chat(alice, "back?")).isEqualTo("noted");
        assertThat(evidence.story("agent")).extracting(AuditEvent::actor, AuditEvent::outcome)
                .containsExactly(tuple("oncall", "HALTED"), tuple("oncall", "RUNNING"));
        assertThat(audit.all()).allSatisfy(event -> assertThat(event.outcome()).doesNotContainAnyWhitespaces());
    }
}
