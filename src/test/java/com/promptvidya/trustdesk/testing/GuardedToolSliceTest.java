package com.promptvidya.trustdesk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.agent.TicketTools;
import com.promptvidya.trustdesk.agent.TicketTools.Ticket;
import com.promptvidya.trustdesk.authz.AuthorizedToolCallback;
import com.promptvidya.trustdesk.authz.DecisionLayer;
import com.promptvidya.trustdesk.authz.DecisionLayer.Outcome;
import com.promptvidya.trustdesk.authz.GrantLedger;
import com.promptvidya.trustdesk.authz.ToolPolicy;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.access.AccessDeniedException;

/**
 * The inner ring, in code. Policies and guards get unit tests with
 * exact assertions and no model in the room; a tool gets a slice test
 * through the real tool loop with the model as a scripted fixture; the
 * grant ledger gets a frozen clock; and the script itself fails loudly
 * when a loop runs past it.
 */
class GuardedToolSliceTest {

    private static final String TICKET_BY_ID = "ticketById";
    private static final String READ_T1 = "{\"ticketId\":\"T-1\"}";

    private final FrozenClock clock = FrozenClock.at("2026-09-07T09:00:00Z");
    private final AuditTrail audit = new AuditTrail(clock);
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), audit);
    private final ActorContext alice = new ActorContext("alice", Set.of("tickets:read"));
    private final ActorContext bob = new ActorContext("bob", Set.of("policies:it-hardware"));
    private final TicketTools tickets = new TicketTools(Map.of("T-1", new Ticket("T-1", "alice", "VPN access")));
    private final ToolCallback[] guarded = AuthorizedToolCallback.guard(decisions, ToolCallbacks.from(tickets));

    private ToolCallback guardedTicketById() {
        return Arrays.stream(guarded)
                .filter(callback -> callback.getToolDefinition().name().equals(TICKET_BY_ID))
                .findFirst()
                .orElseThrow();
    }

    private static ToolCallingAdvisor toolLoop() {
        return ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    @Test
    void unitThePolicyAndTheDecisionLayerAreExactWithNoModelInTheRoom() {
        assertThat(ToolPolicy.trustDesk().requirementFor(TICKET_BY_ID))
                .get().extracting(ToolPolicy.Requirement::scope).isEqualTo("tickets:read");
        assertThat(decisions.decide(alice, TICKET_BY_ID, "checking my ticket").outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(decisions.decide(bob, TICKET_BY_ID, "checking a ticket").outcome()).isEqualTo(Outcome.DENIED_NO_GRANT);
        assertThat(decisions.decide(alice, "dropTable", "cleanup").outcome()).isEqualTo(Outcome.DENIED_UNKNOWN_TOOL);
    }

    @Test
    void unitTheGuardStopsAMissingGrantBeforeTheToolRuns() {
        var context = new ToolContext(Map.of(AuthorizedToolCallback.ACTOR_KEY, bob));

        assertThatThrownBy(() -> guardedTicketById().call(READ_T1, context))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageStartingWith(Outcome.DENIED_NO_GRANT.name());
        assertThat(audit.eventsFor("bob"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .containsExactly(tuple("tool_run", TICKET_BY_ID, Outcome.DENIED_NO_GRANT.name()));
    }

    @Test
    void sliceATicketReadRunsThroughTheRealLoopWithTheModelAsAFixture() {
        var model = ScriptedChatModel.script()
                .toolCall(TICKET_BY_ID, READ_T1)
                .answer("Your VPN ticket is open.");

        var answer = ChatClient.create(model)
                .prompt()
                .user("What is the state of my ticket?")
                .toolCallbacks(guarded)
                .toolContext(Map.of(AuthorizedToolCallback.ACTOR_KEY, alice, AuthorizedToolCallback.INTENT_KEY, "my ticket"))
                .advisors(toolLoop())
                .call()
                .content();

        assertThat(answer).isEqualTo("Your VPN ticket is open.");
        assertThat(model.invocations()).isEqualTo(2);
        assertThat(model.lastToolResponse()).get()
                .extracting(response -> response.getResponses().getFirst().responseData())
                .asString()
                .contains("VPN access");
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .containsExactly(tuple("tool_run", TICKET_BY_ID, Outcome.ALLOWED.name()));
    }

    @Test
    void sliceATaskGrantExpiresOnTheFrozenClock() {
        var ledger = new GrantLedger(clock);
        ledger.forTask("bob", "tickets:read", "hardware-lead", Duration.ofHours(1));
        var bobWithToken = new ActorContext("bob", Set.of("tickets:read"));

        assertThat(decisions.decide(ledger.narrow(bobWithToken), TICKET_BY_ID, "triage").outcome()).isEqualTo(Outcome.ALLOWED);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(decisions.decide(ledger.narrow(bobWithToken), TICKET_BY_ID, "triage").outcome()).isEqualTo(Outcome.DENIED_NO_GRANT);
    }

    @Test
    void unitTheScriptFailsLoudlyWhenTheLoopRunsPastIt() {
        var model = ScriptedChatModel.script().toolCall(TICKET_BY_ID, READ_T1);

        assertThatThrownBy(() -> ChatClient.create(model)
                        .prompt()
                        .user("What is the state of my ticket?")
                        .toolCallbacks(guarded)
                        .toolContext(Map.of(AuthorizedToolCallback.ACTOR_KEY, alice))
                        .advisors(toolLoop())
                        .call()
                        .content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("script exhausted after 2 calls");
    }
}
