package com.promptvidya.trustdesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.authz.DecisionLayer.Outcome;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.access.AccessDeniedException;

/**
 * Deny by default, decided before anything runs: unknown tools, missing
 * grants, and missing intent on sensitive tools all refuse; every
 * decision leaves one row naming the tool, never the intent text.
 */
class DecisionLayerTest {

    private final AuditTrail audit = new AuditTrail(Clock.systemUTC());
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), audit);
    private final ActorContext reader = new ActorContext("alice", Set.of("tickets:read", "policies:it-hardware"));
    private final ActorContext requester = new ActorContext("bob", Set.of("access:request"));

    @Test
    void grantsAllowAndPrefixGrantsMatchShelves() {
        assertThat(decisions.decide(reader, "ticketById", null).allowed()).isTrue();
        assertThat(decisions.decide(reader, "policyArticle", null).allowed()).isTrue();
        assertThat(decisions.decide(reader, "requestAccess", "laptop for the new hire").outcome())
                .isEqualTo(Outcome.DENIED_NO_GRANT);
    }

    @Test
    void unknownToolsAreDeniedEvenForTheMostPrivilegedActor() {
        var root = new ActorContext("root", Set.of("tickets:read", "access:request", "policies:*", "audit:read"));

        assertThat(decisions.decide(root, "delete_everything", "cleanup").outcome())
                .isEqualTo(Outcome.DENIED_UNKNOWN_TOOL);
    }

    @Test
    void sensitiveToolsNeedAStatedBoundedIntent() {
        assertThat(decisions.decide(requester, "requestAccess", null).outcome()).isEqualTo(Outcome.DENIED_NO_INTENT);
        assertThat(decisions.decide(requester, "requestAccess", "   ").outcome()).isEqualTo(Outcome.DENIED_NO_INTENT);
        assertThat(decisions.decide(requester, "requestAccess", "x".repeat(501)).outcome()).isEqualTo(Outcome.DENIED_NO_INTENT);
        assertThat(decisions.decide(requester, "requestAccess", "report viewer for month-end close").allowed()).isTrue();
    }

    @Test
    void everyDecisionIsRecordedBeforeAnyRunAndNeverStoresTheIntent() {
        decisions.decide(requester, "requestAccess", "SECRET-INTENT-TEXT");
        decisions.decide(requester, "ticketById", null);

        assertThat(audit.eventsFor("bob"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .containsExactly(
                        tuple(DecisionLayer.ACTION, "requestAccess", "ALLOWED"),
                        tuple(DecisionLayer.ACTION, "ticketById", "DENIED_NO_GRANT"));
        assertThat(audit.all()).noneMatch(event -> event.target().contains("SECRET-INTENT-TEXT"));
    }

    @Test
    void theDecoratorRefusesWithoutAnActorAndNeverInvokesADeniedDelegate() {
        var invocations = new AtomicInteger();
        var delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("requestAccess").description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                invocations.incrementAndGet();
                return "ran";
            }
        };
        var guarded = AuthorizedToolCallback.guard(decisions, delegate)[0];

        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guarded.call("{}", new ToolContext(Map.of("actor", reader, "intent", "why"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("DENIED_NO_GRANT");
        assertThat(invocations.get()).isZero();

        assertThat(guarded.call("{}", new ToolContext(Map.of("actor", requester, "intent", "month-end close"))))
                .isEqualTo("ran");
        assertThat(invocations.get()).isEqualTo(1);
        assertThat(List.of(guarded.getToolDefinition().name())).containsExactly("requestAccess");
    }
}
