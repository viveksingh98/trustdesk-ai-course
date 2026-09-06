package com.promptvidya.trustdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.agent.TicketTools.Ticket;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.security.access.AccessDeniedException;

class TicketToolsTest {

    private final TicketTools tools = new TicketTools(Map.of(
            "T-1", new Ticket("T-1", "alice", "VPN drops every hour"),
            "T-2", new Ticket("T-2", "bob", "Payroll export access")));

    private static ToolContext actor(String subject) {
        return new ToolContext(Map.of("actor", new ActorContext(subject, Set.of("tickets:read"))));
    }

    @Test
    void employeesSeeOnlyTheirOwnTickets() {
        assertThat(tools.myOpenTickets(actor("alice")))
                .extracting(Ticket::id)
                .containsExactly("T-1");
    }

    @Test
    void theConfusedDeputyScenarioIsRefusedAtTheGate() {
        assertThatThrownBy(() -> tools.ticketById("T-2", actor("alice")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("ticket is not visible to this employee");
    }

    @Test
    void missingAndForeignTicketsRefuseWithTheSameWords() {
        assertThatThrownBy(() -> tools.ticketById("T-404", actor("alice")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("ticket is not visible to this employee");
    }

    @Test
    void theSubjectIsNeverAToolArgument() {
        for (var callback : ToolCallbacks.from(tools)) {
            var schema = callback.getToolDefinition().inputSchema();
            assertThat(schema).doesNotContain("subject", "owner", "actor", "ToolContext");
        }
    }
}
