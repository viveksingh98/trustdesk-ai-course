package com.promptvidya.trustdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.domain.KnowledgeBase;
import com.promptvidya.trustdesk.domain.Ticket;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.security.access.AccessDeniedException;

class DomainReadToolsTest {

    private final AuditTrail audit = new AuditTrail(Clock.systemUTC());
    private final DomainReadTools tools =
            new DomainReadTools(TrustDeskDomain.seeded(), KnowledgeBase.seeded(), audit);

    private static ToolContext actor(String subject) {
        return new ToolContext(Map.of("actor", new ActorContext(subject, Set.of("tickets:read"))));
    }

    @Test
    void everyReadLeavesExactlyOneAuditEvent() {
        tools.myTickets(actor("alice"));
        tools.policyArticle("laptop-refresh", actor("alice"));

        assertThat(audit.eventsFor("alice")).extracting(AuditEvent::action, AuditEvent::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("list_tickets", "ALLOWED"),
                        org.assertj.core.groups.Tuple.tuple("read_article", "ALLOWED"));
    }

    @Test
    void refusalsAreEvidenceToo() {
        assertThatThrownBy(() -> tools.ticketById("T-2", actor("alice")))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(audit.eventsWithOutcome("REFUSED"))
                .extracting(AuditEvent::actor, AuditEvent::action, AuditEvent::target)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("alice", "read_ticket", "T-2"));
    }

    @Test
    void scopeComesFromTheActorAndTheDomain() {
        assertThat(tools.myTickets(actor("bob"))).extracting(Ticket::id).containsExactly("T-2");
        assertThat(tools.policyArticle("payroll-export-access", actor("bob")).owner())
                .isEqualTo("finance-ops");
    }

    @Test
    void theBeltIsReadOnlyAndTheSchemasHideTheRail() {
        var callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).hasSize(3);
        for (var callback : callbacks) {
            var definition = callback.getToolDefinition();
            assertThat(definition.name()).doesNotContain("close", "decide", "approve", "delete");
            assertThat(definition.inputSchema()).doesNotContain("actor", "scopes", "ToolContext");
        }
    }
}
